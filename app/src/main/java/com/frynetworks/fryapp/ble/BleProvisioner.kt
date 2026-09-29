package com.frynetworks.fryapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.frynetworks.fryapp.provisioning.DeviceCapabilities
import com.frynetworks.fryapp.provisioning.KeyPlan
import com.frynetworks.fryapp.provisioning.KeyTransportPolicy
import com.frynetworks.fryapp.provisioning.ProvStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import com.frynetworks.fryapp.provisioning.ProvisioningReducer
import com.frynetworks.fryapp.provisioning.WriteStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Static device identity, read once at the start of a provisioning session. */
data class BleDeviceInfo(
    val name: String,
    val chip: String,
    val fwVersion: String,
    val minerKey: String,
)

/** Events emitted while [BleProvisioner.provision] runs. */
sealed class ProvisionEvent {
    data class DeviceInfo(val info: BleDeviceInfo) : ProvisionEvent()
    data class StatusUpdate(val status: ProvStatus) : ProvisionEvent()
    data class Failed(val reason: String) : ProvisionEvent()

    /**
     * The link dropped after the wallet write (the commit) succeeded: the board is leaving
     * Bluetooth to join Wi-Fi. Not a failure; only the backend can now confirm it arrived.
     */
    data object Handoff : ProvisionEvent()

    /** What the board advertised in `0A` ([DeviceCapabilities.PROTO_1] when it has no `0A`). */
    data class Capabilities(val caps: DeviceCapabilities) : ProvisionEvent()

    /** Characteristic `05` read after Connected, when an owner key was written; null if unreadable. */
    data class KeyReadBack(val minerKey: String?) : ProvisionEvent()
}

/** Result of one raw GATT operation: the status code Android reported, plus a payload. */
private data class GattOpResult<T>(val status: Int, val value: T?)

private const val GATT_ERROR_133 = 133
private const val GATT_TIMEOUT = -1
internal const val GATT_CALL_REJECTED = -2
internal const val OP_TIMEOUT_MS = 5_000L
// The `09` write is the first write on a freshly encrypted link (ECDH on the board), so it gets
// its own window.
internal const val KEY_WRITE_TIMEOUT_MS = 30_000L
// PROTOCOL.md 11.3: `09` needs an encrypted link, so the session pairs before writing it. When the
// write itself started pairing, Android (Galaxy S22, Android 16) paired and then never sent or
// reported the write. Pairing may wait on the system consent dialog.
internal const val PAIRING_TIMEOUT_MS = 30_000L
private const val BOND_POLL_MS = 250L
// Firmware worst case: Wi-Fi join (≤30 s) + registration with one quick retry; 60 s cut it off.
internal const val OVERALL_TIMEOUT_MS = 120_000L
private const val LINK_SETTLE_MS = 300L
// PROTOCOL.md 11.3: the client keeps the link until 5 s after it saw Connected.
private const val LINK_HOLD_AFTER_CONNECTED_MS = 5_000L
// A refused `09` write (Error 7/8) is notified right after the write; an SSID write would reset it.
private const val KEY_VERDICT_SETTLE_MS = 500L
internal const val BUSY_RETRIES = 3
internal const val BUSY_BACKOFF_MS = 150L

/**
 * Android refuses a GATT call outright while the previous one is still in flight ("busy":
 * `false`, or ERROR_GATT_WRITE_REQUEST_BUSY on API 33+). Retry such refusals [BUSY_RETRIES]
 * times with growing pauses; any other outcome is returned as is.
 */
internal suspend fun <R> retryWhileBusy(statusOf: (R) -> Int, attempt: suspend () -> R): R {
    var result = attempt()
    var retry = 0
    while (statusOf(result) == GATT_CALL_REJECTED && retry < BUSY_RETRIES) {
        retry++
        delay(BUSY_BACKOFF_MS * retry)
        result = attempt()
    }
    return result
}

/**
 * Pairs [device] for the encrypted `09` write and waits until Android reports it bonded. A bonded
 * device needs nothing: Android encrypts a bonded link on connect. False when pairing was refused
 * or declined, or did not finish within [timeoutMs].
 */
@SuppressLint("MissingPermission")
internal suspend fun pairForKeyWrite(device: BluetoothDevice, timeoutMs: Long = PAIRING_TIMEOUT_MS): Boolean {
    if (device.bondState == BluetoothDevice.BOND_BONDED) return true
    if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) return false
    return withTimeoutOrNull(timeoutMs) {
        var sawBonding = false
        var state = device.bondState
        while (state != BluetoothDevice.BOND_BONDED) {
            if (state == BluetoothDevice.BOND_BONDING) sawBonding = true
            else if (sawBonding) return@withTimeoutOrNull false // declined, or pairing failed
            delay(BOND_POLL_MS)
            state = device.bondState
        }
        true
    } ?: false
}

/** How long one write to [uuid] may take before it counts as failed. */
internal fun writeTimeoutMs(uuid: UUID): Long =
    if (uuid == FryGattContract.CHAR_MINER_KEY_WRITE) KEY_WRITE_TIMEOUT_MS else OP_TIMEOUT_MS

/**
 * Drives one BLE provisioning session end to end per PROTOCOL.md section 1: connect,
 * discover services, negotiate MTU (best-effort), read identity characteristics, subscribe
 * to status notifications, then write SSID, PASS and WALLET in that order and keep emitting
 * status notifications until the peripheral reaches Connected or Error.
 *
 * BluetoothGatt permits exactly one outstanding operation at a time, so every GATT call
 * below is serialized behind [opMutex] with its own 5s timeout. Android's well-known
 * transient GATT_ERROR (status 133) is retried once, both on the initial connect and on any
 * subsequent operation.
 */
@Singleton
class BleProvisioner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val opMutex = Mutex()

    fun provision(address: String, ssid: String, pass: String, wallet: String): Flow<ProvisionEvent> =
        provision(address, ssid, pass, wallet, minerKey = null)

    /**
     * [minerKey] (already C-1 valid) is written to `09` before the Wi-Fi settings when the board
     * advertises `key_write`; a protocol-1 board keeps its own key and nothing is written.
     */
    @SuppressLint("MissingPermission")
    fun provision(address: String, ssid: String, pass: String, wallet: String, minerKey: String?): Flow<ProvisionEvent> =
        callbackFlow {
            val steps = try {
                ProvisioningReducer.plan(ssid, pass, wallet)
            } catch (e: IllegalArgumentException) {
                trySend(ProvisionEvent.Failed(e.message ?: "Invalid provisioning input"))
                close()
                return@callbackFlow
            }

            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            val device = adapter?.getRemoteDevice(address)
            if (adapter == null || device == null) {
                trySend(ProvisionEvent.Failed("Bluetooth adapter unavailable"))
                close()
                return@callbackFlow
            }

            // Android may hold a bond the board does not (the board pairs without bonding, or was
            // erased): Android encrypts on connect, the board has no key, and Android drops the
            // link and forgets the bond. The first session's failure is then held back and one
            // fresh session pairs anew.
            val bondedAtStart = device.bondState == BluetoothDevice.BOND_BONDED
            var retried = false
            var held: ProvisionEvent.Failed? = null
            val first = GattSession(device) { event ->
                if (bondedAtStart && event is ProvisionEvent.Failed) {
                    if (!retried) held = event
                } else {
                    trySend(event)
                }
            }
            var session = first
            // try/finally, not awaitClose alone. If the collector is cancelled while suspended
            // inside run() -- the user backing out of the provisioning screen, any time within a
            // 60 s window -- the CancellationException propagates straight past awaitClose, which
            // then never registers, and the GATT client handle leaks. Android has a small fixed
            // table of concurrent GATT clients; a few leaks exhaust it and every later connect
            // fails with status 133 until Bluetooth is power-cycled.
            try {
                val outcome = withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
                    var ok = first.run(steps, minerKey)
                    if (!ok && bondedAtStart && device.bondState == BluetoothDevice.BOND_NONE) {
                        Log.w(TAG, "the board refused Android's stored bond; pairing again in a new session")
                        retried = true
                        held = null
                        first.close()
                        delay(LINK_SETTLE_MS)
                        session = GattSession(device) { event -> trySend(event) }
                        ok = session.run(steps, minerKey)
                    }
                    ok
                }
                held?.let { trySend(it) }
                // A session that returned false already reported why; only a real timeout is one.
                if (outcome == null) {
                    trySend(ProvisionEvent.Failed("Provisioning timed out"))
                }
            } finally {
                first.close()
                session.close()
            }

            awaitClose {
                first.close()
                session.close()
            }
        }

    /** One connect-to-terminal-status session. Not reused across calls. */
    private inner class GattSession(
        private val device: BluetoothDevice,
        private val emit: (ProvisionEvent) -> Unit,
    ) {
        private lateinit var gatt: BluetoothGatt
        private var connectResult = CompletableDeferred<GattOpResult<Unit>>()
        private var servicesResult: CompletableDeferred<GattOpResult<Unit>>? = null
        private var mtuResult: CompletableDeferred<GattOpResult<Unit>>? = null
        private var reliableWriteResult: CompletableDeferred<GattOpResult<Unit>>? = null

        /** Set once the wallet write (the commit) succeeded; a disconnect after it is a handoff. */
        @Volatile private var committed = false
        @Volatile private var lastState: ProvState? = null

        /**
         * ATT MTU actually in force. 23 is the BLE default every connection starts at, and it is
         * what we are still on if the peripheral refuses our request. Usable payload per single
         * ATT write is MTU minus the 3-byte opcode+handle header.
         */
        private var negotiatedMtu: Int = FryGattContract.DEFAULT_ATT_MTU
        private val readResults = HashMap<UUID, CompletableDeferred<GattOpResult<ByteArray>>>()
        private val writeResults = HashMap<UUID, CompletableDeferred<GattOpResult<Unit>>>()
        private val descriptorResults = HashMap<UUID, CompletableDeferred<GattOpResult<Unit>>>()
        private val terminal = CompletableDeferred<Unit>()
        private var connectCompleted = false

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectCompleted = true
                    if (!connectResult.isCompleted) connectResult.complete(GattOpResult(status, Unit))
                } else {
                    if (!connectResult.isCompleted) {
                        connectResult.complete(GattOpResult(status, null))
                    } else if (connectCompleted) {
                        emit(if (committed) ProvisionEvent.Handoff else ProvisionEvent.Failed("GATT disconnected: status=$status"))
                        if (!terminal.isCompleted) terminal.complete(Unit)
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                servicesResult?.let { if (!it.isCompleted) it.complete(GattOpResult(status, Unit)) }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
                mtuResult?.let { if (!it.isCompleted) it.complete(GattOpResult(status, Unit)) }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                readResults.remove(characteristic.uuid)?.complete(GattOpResult(status, characteristic.value))
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                readResults.remove(characteristic.uuid)?.complete(GattOpResult(status, value))
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                writeResults.remove(characteristic.uuid)?.complete(GattOpResult(status, Unit))
            }

            override fun onReliableWriteCompleted(g: BluetoothGatt, status: Int) {
                reliableWriteResult?.let { if (!it.isCompleted) it.complete(GattOpResult(status, Unit)) }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                descriptorResults.remove(descriptor.characteristic.uuid)?.complete(GattOpResult(status, Unit))
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                handleNotification(characteristic.uuid, characteristic.value)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                handleNotification(characteristic.uuid, value)
            }

            private fun handleNotification(uuid: UUID, value: ByteArray) {
                if (uuid != FryGattContract.CHAR_STATUS || value.isEmpty()) return
                // Runs inside a binder callback: an exception here would kill the process.
                val decoded = ProvisioningReducer.fromStatusBytesDetailedOrNull(value)
                if (decoded == null) {
                    Log.w(TAG, "ignoring unrecognised status payload (${value.size} bytes)")
                    return
                }
                lastState = decoded.state
                emit(ProvisionEvent.StatusUpdate(decoded))
                if (decoded.state == ProvState.CONNECTED ||
                    decoded.state == ProvState.ERROR
                ) {
                    if (!terminal.isCompleted) terminal.complete(Unit)
                }
            }
        }

        /** [ownerKey] is what the user entered (C-1 valid) or null; the board's own key is read below as `boardKey`. */
        @SuppressLint("MissingPermission")
        suspend fun run(steps: List<WriteStep>, ownerKey: String?): Boolean {
            var connectOutcome = connectOnce()
            if (connectOutcome.status == GATT_ERROR_133) {
                Log.w(TAG, "connectGatt status 133, retrying once")
                runCatching { gatt.close() }
                delay(LINK_SETTLE_MS)
                connectResult = CompletableDeferred()
                connectOutcome = connectOnce()
            }
            if (connectOutcome.value == null) {
                emit(ProvisionEvent.Failed("GATT connect failed: status=${connectOutcome.status}"))
                return false
            }

            // Let the link settle before service discovery — some ESP32 peripherals refuse
            // the very first discoverServices() call otherwise.
            delay(LINK_SETTLE_MS)

            val discovered = withRetry133 {
                val deferred = CompletableDeferred<GattOpResult<Unit>>()
                servicesResult = deferred
                gatt.discoverServices()
                awaitOp(deferred)
            }
            if (discovered.status != BluetoothGatt.GATT_SUCCESS) {
                emit(ProvisionEvent.Failed("Service discovery failed: status=${discovered.status}"))
                return false
            }

            // MTU failure is tolerated — the firmware falls back to a long/prepared write.
            withRetry133 {
                val deferred = CompletableDeferred<GattOpResult<Unit>>()
                mtuResult = deferred
                if (!gatt.requestMtu(FryGattContract.REQUESTED_MTU)) {
                    deferred.complete(GattOpResult(GATT_CALL_REJECTED, null))
                }
                awaitOp(deferred)
            }

            val name = readChar(FryGattContract.CHAR_DEVICE_NAME)?.toString(Charsets.UTF_8)
            val chip = readChar(FryGattContract.CHAR_CHIP_TYPE)?.toString(Charsets.UTF_8)
            val fw = readChar(FryGattContract.CHAR_FW_VERSION)?.toString(Charsets.UTF_8)
            val boardKey = readChar(FryGattContract.CHAR_MINER_KEY)?.toString(Charsets.UTF_8)
            if (name != null && chip != null && fw != null && boardKey != null) {
                emit(ProvisionEvent.DeviceInfo(BleDeviceInfo(name, chip, fw, boardKey)))
            }

            val caps = readChar(FryGattContract.CHAR_DEVICE_STATUS)?.let { DeviceStatusJson.parse(it) } ?: DeviceCapabilities.PROTO_1
            emit(ProvisionEvent.Capabilities(caps))

            // PROTOCOL.md 11.1/11.8: only the owner's key is ever written to `09`, never the
            // board's own; and a running board in an API-side error ignores 01/02/03 over an
            // unencrypted link (only a `09` write, which pairs, encrypts it), so with no key to
            // write the session stops here and says why instead of writing into the void.
            val keySteps = when (val plan = KeyTransportPolicy.planKeySteps(ownerKey, caps)) {
                is KeyPlan.Stop -> {
                    emit(ProvisionEvent.StatusUpdate(ProvStatus(ProvState.ERROR, plan.error)))
                    return true
                }
                is KeyPlan.Write -> listOf(WriteStep(FryGattContract.CHAR_MINER_KEY_WRITE, plan.key.toByteArray(Charsets.US_ASCII)))
                KeyPlan.NoKeyStep -> emptyList()
            }
            // Pair first: a `09` write that itself starts pairing can be lost (PAIRING_TIMEOUT_MS).
            if (keySteps.isNotEmpty() && !pairForKeyWrite(device)) {
                emit(ProvisionEvent.Failed("Pairing failed"))
                return false
            }

            enableStatusNotifications()

            for (step in keySteps + steps) {
                val ok = writeChar(step.characteristic, step.value)
                if (!ok) {
                    emit(ProvisionEvent.Failed("Write failed for characteristic ${step.characteristic}"))
                    return false
                }
                if (step.characteristic == FryGattContract.CHAR_MINER_KEY_WRITE) {
                    delay(KEY_VERDICT_SETTLE_MS)
                    // The board refused the key (Error 7/8): stop here; writing the SSID would reset that error.
                    if (terminal.isCompleted) return true
                }
                if (step.characteristic == FryGattContract.CHAR_WALLET) committed = true
            }

            terminal.await()
            if (lastState == ProvState.CONNECTED) {
                if (keySteps.isNotEmpty()) {
                    emit(ProvisionEvent.KeyReadBack(readChar(FryGattContract.CHAR_MINER_KEY)?.toString(Charsets.UTF_8)))
                }
                delay(LINK_HOLD_AFTER_CONNECTED_MS)
            }
            return true
        }

        @SuppressLint("MissingPermission")
        private suspend fun connectOnce(): GattOpResult<Unit> {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            return withTimeoutOrNull(OP_TIMEOUT_MS) { connectResult.await() } ?: GattOpResult(GATT_TIMEOUT, null)
        }

        private suspend fun awaitOp(deferred: CompletableDeferred<GattOpResult<Unit>>): GattOpResult<Unit> =
            withTimeoutOrNull(OP_TIMEOUT_MS) { deferred.await() } ?: GattOpResult(GATT_TIMEOUT, null)

        private suspend fun withRetry133(block: suspend () -> GattOpResult<Unit>): GattOpResult<Unit> =
            opMutex.withLock {
                val first = block()
                if (first.status == GATT_ERROR_133) {
                    Log.w(TAG, "GATT status 133, retrying once")
                    block()
                } else {
                    first
                }
            }

        @SuppressLint("MissingPermission")
        private suspend fun readChar(uuid: UUID): ByteArray? {
            val characteristic = gatt.getService(FryGattContract.SERVICE_FRY)?.getCharacteristic(uuid) ?: return null
            return opMutex.withLock {
                suspend fun attempt(): GattOpResult<ByteArray> {
                    val deferred = CompletableDeferred<GattOpResult<ByteArray>>()
                    readResults[uuid] = deferred
                    if (!gatt.readCharacteristic(characteristic)) {
                        readResults.remove(uuid)
                        return GattOpResult(GATT_CALL_REJECTED, null)
                    }
                    return withTimeoutOrNull(OP_TIMEOUT_MS) { deferred.await() } ?: GattOpResult(GATT_TIMEOUT, null)
                }
                var result = attempt()
                if (result.status == GATT_ERROR_133) {
                    Log.w(TAG, "read status 133, retrying once")
                    result = attempt()
                }
                result.value
            }
        }

        @SuppressLint("MissingPermission")
        private suspend fun writeChar(uuid: UUID, value: ByteArray): Boolean {
            val characteristic = gatt.getService(FryGattContract.SERVICE_FRY)?.getCharacteristic(uuid) ?: return false
            // A single ATT Write Request carries at most MTU-3 bytes. Android does not promise to
            // fragment an oversized value for us, so if the peripheral refused our MTU bump the
            // 58-byte wallet would go out truncated -- silently writing a different address than
            // the user typed. Drive the prepared-write procedure ourselves in that case.
            val needsLongWrite = value.size > (negotiatedMtu - FryGattContract.ATT_WRITE_HEADER_BYTES)
            if (needsLongWrite) {
                Log.w(
                    TAG,
                    "payload ${value.size}B exceeds MTU $negotiatedMtu; using prepared write",
                )
                return writeCharLong(characteristic, uuid, value)
            }
            return opMutex.withLock {
                suspend fun attempt(): GattOpResult<Unit> {
                    val deferred = CompletableDeferred<GattOpResult<Unit>>()
                    writeResults[uuid] = deferred
                    val issued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(
                            characteristic,
                            value,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        ) == BluetoothStatusCodes.SUCCESS // anything else, e.g. ERROR_GATT_WRITE_REQUEST_BUSY, is a refusal
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            characteristic.value = value
                            gatt.writeCharacteristic(characteristic)
                        }
                    }
                    if (!issued) {
                        writeResults.remove(uuid)
                        return GattOpResult(GATT_CALL_REJECTED, null)
                    }
                    return withTimeoutOrNull(writeTimeoutMs(uuid)) { deferred.await() } ?: GattOpResult(GATT_TIMEOUT, null)
                }
                var result = retryWhileBusy({ it.status }) { attempt() }
                if (result.status == GATT_ERROR_133) {
                    Log.w(TAG, "write status 133, retrying once")
                    result = retryWhileBusy({ it.status }) { attempt() }
                }
                result.status == BluetoothGatt.GATT_SUCCESS
            }
        }

        /**
         * Prepared ("long") write: begin a reliable-write transaction, issue the value, then
         * execute it. The stack fragments the payload across ATT Prepare Write packets and the
         * peripheral reassembles on Execute. Bails out and aborts the transaction on any failure
         * so a half-written value is never committed.
         */
        @SuppressLint("MissingPermission")
        private suspend fun writeCharLong(
            characteristic: BluetoothGattCharacteristic,
            uuid: UUID,
            value: ByteArray,
        ): Boolean = opMutex.withLock {
            if (!gatt.beginReliableWrite()) {
                Log.w(TAG, "beginReliableWrite rejected")
                return@withLock false
            }
            val deferred = CompletableDeferred<GattOpResult<Unit>>()
            writeResults[uuid] = deferred
            val issued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    characteristic.value = value
                    gatt.writeCharacteristic(characteristic)
                }
            }
            if (!issued) {
                writeResults.remove(uuid)
                gatt.abortReliableWrite()
                return@withLock false
            }
            val result = withTimeoutOrNull(writeTimeoutMs(uuid)) { deferred.await() }
                ?: GattOpResult(GATT_TIMEOUT, null)
            if (result.status != BluetoothGatt.GATT_SUCCESS) {
                gatt.abortReliableWrite()
                return@withLock false
            }
            // executeReliableWrite completes via onReliableWriteCompleted; a false return means
            // the stack would not even start it. Wait for that completion: returning early let
            // the next write start while the execute was still in flight, and Android refused it.
            val executed = CompletableDeferred<GattOpResult<Unit>>()
            reliableWriteResult = executed
            if (!gatt.executeReliableWrite()) {
                reliableWriteResult = null
                gatt.abortReliableWrite()
                return@withLock false
            }
            val completion = withTimeoutOrNull(writeTimeoutMs(uuid)) { executed.await() } ?: GattOpResult(GATT_TIMEOUT, null)
            reliableWriteResult = null
            completion.status == BluetoothGatt.GATT_SUCCESS
        }

        @SuppressLint("MissingPermission")
        private suspend fun enableStatusNotifications() {
            val characteristic = gatt.getService(FryGattContract.SERVICE_FRY)
                ?.getCharacteristic(FryGattContract.CHAR_STATUS) ?: return
            val cccd = characteristic.getDescriptor(FryGattContract.CCCD) ?: return
            opMutex.withLock {
                gatt.setCharacteristicNotification(characteristic, true)
                val deferred = CompletableDeferred<GattOpResult<Unit>>()
                descriptorResults[characteristic.uuid] = deferred
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(cccd)
                    }
                }
                withTimeoutOrNull(OP_TIMEOUT_MS) { deferred.await() }
            }
        }

        @SuppressLint("MissingPermission")
        fun close() {
            runCatching { gatt.close() }
        }
    }

    companion object {
        private const val TAG = "BleProvisioner"
    }
}
