package com.frynetworks.fryapp.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A scripted v1.1 board behind mocked Bluetooth classes, for driving the real
 * [BleProvisioner] session on the JVM. Every GATT call answers through the
 * [BluetoothGattCallback] the provisioner registered, on [scope] (the test scheduler), so the
 * session's timeouts and settles run in virtual time. Reads answer at once; a write completes
 * after [writeDelayMs] for its characteristic (and a prepared write's execute after
 * [executeDelayMs]); the wallet write is followed by the [statusAfterWallet] notification.
 * Android's bond with the board is [bondState]; createBond() moves it to BONDED after
 * [pairDelayMs] (back to NONE when [pairingDeclined]).
 */
@Suppress("DEPRECATION")
class FakeGattBoard(
    private val scope: CoroutineScope,
    boardKey: String,
    statusJson: String?,
    private val mtuGranted: Boolean = true,
) {
    val values = HashMap<UUID, ByteArray?>().apply {
        put(FryGattContract.CHAR_DEVICE_NAME, "FRY-ESP32-ABC123".toByteArray())
        put(FryGattContract.CHAR_CHIP_TYPE, "ESP32".toByteArray())
        put(FryGattContract.CHAR_FW_VERSION, "0.4.0".toByteArray())
        put(FryGattContract.CHAR_MINER_KEY, boardKey.toByteArray())
        put(FryGattContract.CHAR_DEVICE_STATUS, statusJson?.toByteArray())
    }
    var writeDelayMs: Map<UUID, Long> = emptyMap()

    /** Delay before a prepared write's execute completes, per characteristic. */
    var executeDelayMs: Map<UUID, Long> = emptyMap()
    var statusAfterWallet: ByteArray = byteArrayOf(3)

    /** Android's bond with the board, as BluetoothDevice.getBondState() reports it. */
    @Volatile var bondState = BluetoothDevice.BOND_NONE
    var pairDelayMs = 0L
    var pairingDeclined = false
    var createBondCalls = 0

    /**
     * Android as seen on a Galaxy S22 (Android 16): a `09` write on an unpaired link starts
     * pairing, pairing succeeds, and the write is never sent to the board nor reported back.
     */
    var losesWriteThatPairs = false

    /**
     * Android holds a bond the board does not keep: on the next connect Android encrypts, the board
     * has no key, and Android drops the link (status 22) and forgets the bond.
     */
    var staleBond = false
    var connects = 0
    private var connected = false

    /** Every value the provisioner wrote, in order, simple and prepared writes alike. */
    val writes = mutableListOf<Pair<UUID, ByteArray>>()

    /** The characteristics that went through the prepared (reliable) write procedure. */
    val preparedWrites = mutableListOf<UUID>()

    lateinit var callback: BluetoothGattCallback
    val gatt: BluetoothGatt = mockk(relaxed = true)
    val context: Context = mockk()

    private val staged = HashMap<UUID, ByteArray>()
    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()
    private var inReliableWrite = false

    fun written(uuid: UUID): ByteArray? = writes.lastOrNull { it.first == uuid }?.second

    init {
        val all = listOf(
            FryGattContract.CHAR_DEVICE_NAME, FryGattContract.CHAR_CHIP_TYPE, FryGattContract.CHAR_FW_VERSION,
            FryGattContract.CHAR_MINER_KEY, FryGattContract.CHAR_DEVICE_STATUS, FryGattContract.CHAR_STATUS,
            FryGattContract.CHAR_WIFI_SSID, FryGattContract.CHAR_WIFI_PASS, FryGattContract.CHAR_WALLET,
            FryGattContract.CHAR_MINER_KEY_WRITE,
        )
        for (u in all) {
            val c = mockk<BluetoothGattCharacteristic>(relaxed = true)
            every { c.uuid } returns u
            every { c.value } answers { values[u] }
            every { c.setValue(any<ByteArray>()) } answers { staged[u] = firstArg(); true }
            chars[u] = c
        }
        val status = chars.getValue(FryGattContract.CHAR_STATUS)
        val cccd = mockk<BluetoothGattDescriptor>(relaxed = true)
        every { cccd.characteristic } returns status
        every { status.getDescriptor(FryGattContract.CCCD) } returns cccd
        val service = mockk<BluetoothGattService>()
        every { service.getCharacteristic(any()) } answers { chars[firstArg()] }

        every { gatt.getService(FryGattContract.SERVICE_FRY) } returns service
        every { gatt.discoverServices() } answers {
            if (connected) callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
            connected
        }
        every { gatt.requestMtu(any()) } answers {
            if (mtuGranted) callback.onMtuChanged(gatt, firstArg(), BluetoothGatt.GATT_SUCCESS)
            else callback.onMtuChanged(gatt, FryGattContract.DEFAULT_ATT_MTU, BluetoothGatt.GATT_FAILURE)
            true
        }
        every { gatt.readCharacteristic(any()) } answers {
            callback.onCharacteristicRead(gatt, firstArg(), BluetoothGatt.GATT_SUCCESS)
            true
        }
        every { gatt.setCharacteristicNotification(any(), any()) } returns true
        every { gatt.writeDescriptor(any<BluetoothGattDescriptor>()) } answers {
            callback.onDescriptorWrite(gatt, firstArg(), BluetoothGatt.GATT_SUCCESS)
            true
        }
        every { gatt.beginReliableWrite() } answers { inReliableWrite = true; true }
        every { gatt.abortReliableWrite() } answers { inReliableWrite = false }
        every { gatt.writeCharacteristic(any<BluetoothGattCharacteristic>()) } answers {
            val c = firstArg<BluetoothGattCharacteristic>()
            val u = c.uuid
            if (losesWriteThatPairs && u == FryGattContract.CHAR_MINER_KEY_WRITE && bondState != BluetoothDevice.BOND_BONDED) {
                startPairing()
                return@answers true // never reaches the board, never reported
            }
            writes += u to staged.getValue(u)
            val prepared = inReliableWrite
            scope.launch {
                delay(writeDelayMs[u] ?: 0L)
                callback.onCharacteristicWrite(gatt, c, BluetoothGatt.GATT_SUCCESS)
                if (!prepared && u == FryGattContract.CHAR_WALLET) notifyStatus()
            }
            true
        }
        every { gatt.executeReliableWrite() } answers {
            val u = writes.last().first
            preparedWrites += u
            inReliableWrite = false
            scope.launch {
                delay(executeDelayMs[u] ?: 0L)
                callback.onReliableWriteCompleted(gatt, BluetoothGatt.GATT_SUCCESS)
                if (u == FryGattContract.CHAR_WALLET) notifyStatus()
            }
            true
        }

        val device = mockk<BluetoothDevice>()
        every { device.connectGatt(any(), any(), any(), any()) } answers {
            connects++
            val cb: BluetoothGattCallback = thirdArg()
            callback = cb
            connected = true
            cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            if (staleBond && bondState == BluetoothDevice.BOND_BONDED) {
                staleBond = false
                scope.launch {
                    delay(200)
                    connected = false
                    bondState = BluetoothDevice.BOND_NONE
                    cb.onConnectionStateChange(gatt, 22, BluetoothProfile.STATE_DISCONNECTED)
                }
            }
            gatt
        }
        every { device.bondState } answers { bondState }
        every { device.createBond() } answers {
            createBondCalls++
            startPairing()
            true
        }
        val adapter = mockk<BluetoothAdapter>()
        every { adapter.getRemoteDevice(any<String>()) } returns device
        val manager = mockk<BluetoothManager>()
        every { manager.adapter } returns adapter
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
    }

    private fun startPairing() {
        bondState = BluetoothDevice.BOND_BONDING
        scope.launch {
            delay(pairDelayMs)
            bondState = if (pairingDeclined) BluetoothDevice.BOND_NONE else BluetoothDevice.BOND_BONDED
        }
    }

    private fun notifyStatus() {
        values[FryGattContract.CHAR_STATUS] = statusAfterWallet
        callback.onCharacteristicChanged(gatt, chars.getValue(FryGattContract.CHAR_STATUS))
    }
}
