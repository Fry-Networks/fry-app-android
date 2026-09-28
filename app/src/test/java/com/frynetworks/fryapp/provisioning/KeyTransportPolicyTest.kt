package com.frynetworks.fryapp.provisioning

import com.frynetworks.fryapp.ble.DeviceStatusJson
import com.frynetworks.fryapp.wifi.SoftApInfo
import com.frynetworks.fryapp.wifi.capabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyTransportPolicyTest {

    private val v11 = DeviceStatusJson.parse("""{"v":1,"proto":2,"caps":["key_write","error_reset","errs_v2"],"s":0,"e":0,"d":0,"k":0,"kc":0,"reg":0,"hb":-1,"fw":"0.4.0","ota":"valid"}""")

    @Test
    fun `0A is parsed into capabilities`() {
        assertEquals(2, v11.proto)
        assertTrue(v11.keyWrite)
        assertTrue(v11.errorReset)
        assertEquals(false, v11.keyPresent)
        assertEquals(false, v11.keyConfirmed)
        assertEquals("0.4.0", v11.fw)
        assertEquals("valid", v11.ota)
    }

    @Test
    fun `no 0A, empty or garbage means protocol 1`() {
        assertEquals(DeviceCapabilities.PROTO_1, DeviceStatusJson.parse(null as ByteArray?))
        assertEquals(DeviceCapabilities.PROTO_1, DeviceStatusJson.parse(ByteArray(0)))
        assertEquals(DeviceCapabilities.PROTO_1, DeviceStatusJson.parse("not json"))
        assertEquals(DeviceCapabilities.PROTO_1, DeviceStatusJson.parse("""{"v":1}"""))
        assertFalse(DeviceCapabilities.PROTO_1.keyWrite)
    }

    @Test
    fun `BLE sends the key only to a board that advertises key_write`() {
        assertEquals(KeyTransport.Send, KeyTransportPolicy.forBle(v11))
        assertEquals(KeyTransport.DeviceKeeps, KeyTransportPolicy.forBle(DeviceCapabilities.PROTO_1))
        assertEquals(KeyTransport.DeviceKeeps, KeyTransportPolicy.forBle(DeviceCapabilities(proto = 2, caps = setOf("error_reset"))))
    }

    @Test
    fun `SoftAP never sends a key over the open AP`() {
        assertEquals(KeyTransport.Send, KeyTransportPolicy.forSoftAp(v11, joinedWithSetupCode = true))
        val open = KeyTransportPolicy.forSoftAp(v11, joinedWithSetupCode = false)
        assertTrue(open is KeyTransport.Refuse)
        assertEquals(KeyTransportPolicy.OPEN_AP_REFUSAL, (open as KeyTransport.Refuse).reason)
        assertEquals(KeyTransport.DeviceKeeps, KeyTransportPolicy.forSoftAp(DeviceCapabilities.PROTO_1, joinedWithSetupCode = false))
    }

    @Test
    fun `SoftAP info capabilities follow proto, caps and keySet`() {
        val info = SoftApInfo("FRY-ESP8266-ABC123", "FEM-AB…", "0.4.0", "ESP8266", proto = 2, caps = listOf("key_write"), keySet = true)
        assertEquals(DeviceCapabilities(2, setOf("key_write"), keyPresent = true, fw = "0.4.0"), info.capabilities())
        assertEquals(DeviceCapabilities.PROTO_1, SoftApInfo("x", "FEM-" + "A".repeat(32), "0.3.3", "ESP8266").capabilities())
    }
}
