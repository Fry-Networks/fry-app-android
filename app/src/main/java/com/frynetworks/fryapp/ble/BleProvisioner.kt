package com.frynetworks.fryapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
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
}

/** Result of one raw GATT operation: the status code Android reported, plus a payload. */
private data class GattOpResult<T>(val status: Int, val value: T?)

private const val GATT_ERROR_133 = 133
private const val GATT_TIMEOUT = -1
private const val GATT_CALL_REJECTED = -2
private const val OP_TIMEOUT_MS = 5_000L
private const val OVERALL_TIMEOUT_MS = 60_000L
private const val LINK_SETTLE_MS = 300L

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

    @SuppressLint("MissingPermission")
    fun provision(address: String, ssid: String, pass: String, wallet: String): Flow<ProvisionEvent> =
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

            val session = GattSession(device) { event -> trySend(event) }
            // try/finally, not awaitClose alone. If the collector is cancelled while suspended
            // inside run() -- the user backing out of the provisioning screen, any time within a
            // 60 s window -- the CancellationException propagates straight past awaitClose, which
            // then never registers, and the GATT client handle leaks. Android has a small fixed
            // table of concurrent GATT clients; a few leaks exhaust it and every later connect
            // fails with status 133 until Bluetooth is power-cycled.
            try {
                val outcome = withTimeoutOrNull(OVERALL_TIMEOUT_MS) { session.run(steps) }
                if (outcome != true) {
                    trySend(ProvisionEvent.Failed("Provisioning timed out"))
                }
            } finally {
                session.close()
            }

            awaitClose { session.close() }
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
                        emit(ProvisionEvent.Failed("GATT disconnected: status=$status"))
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
                val decoded = ProvisioningReducer.fromStatusBytesOrNull(value)
                if (decoded == null) {
                    Log.w(TAG, "ignoring unrecognised status payload (${value.size} bytes)")
                    return
                }
                emit(ProvisionEvent.StatusUpdate(decoded))
                if (decoded.state == ProvState.CONNECTED ||
                    decoded.state == ProvState.ERROR
                ) {
                    if (!terminal.isCompleted) terminal.complete(Unit)
                }
            }
        }

        @SuppressLint("MissingPermission")
        suspend fun run(steps: List<WriteStep>): Boolean {
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
            val minerKey = readChar(FryGattContract.CHAR_MINER_KEY)?.toString(Charsets.UTF_8)
            if (name != null && chip != null && fw != null && minerKey != null) {
                emit(ProvisionEvent.DeviceInfo(BleDeviceInfo(name, chip, fw, minerKey)))
            }

            enableStatusNotifications()

            for (step in steps) {
                val ok = writeChar(step.characteristic, step.value)
                if (!ok) {
                    emit(ProvisionEvent.Failed("Write failed for characteristic ${step.characteristic}"))
                    return false
                }
            }

            terminal.await()
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
                        ) == BluetoothGatt.GATT_SUCCESS
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
                    return withTimeoutOrNull(OP_TIMEOUT_MS) { deferred.await() } ?: GattOpResult(GATT_TIMEOUT, null)
                }
                var result = attempt()
                if (result.status == GATT_ERROR_133) {
                    Log.w(TAG, "write status 133, retrying once")
                    result = attempt()
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
                ) == BluetoothGatt.GATT_SUCCESS
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
            val result = withTimeoutOrNull(OP_TIMEOUT_MS) { deferred.await() }
                ?: GattOpResult(GATT_TIMEOUT, null)
            if (result.status != BluetoothGatt.GATT_SUCCESS) {
                gatt.abortReliableWrite()
                return@withLock false
            }
            // executeReliableWrite completes via onReliableWriteCompleted; a false return means
            // the stack would not even start it.
            if (!gatt.executeReliableWrite()) {
                gatt.abortReliableWrite()
                return@withLock false
            }
            true
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
