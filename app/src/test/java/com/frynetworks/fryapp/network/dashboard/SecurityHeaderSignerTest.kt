package com.frynetworks.fryapp.network.dashboard

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** Vectors computed by Node crypto (tools/bridge/vectors/gen.mjs): sha256 client token and
 *  HMAC-SHA256 request signature exactly as the dashboard verifies them. */
class SecurityHeaderSignerTest {

    private val signer = SecurityHeaderSigner(signatureSecret = "fry-rewards-signature-v1-")

    private fun vectors() = JsonParser.parseString(
        javaClass.classLoader!!.getResource("fixtures/canonical/vectors.json")!!.readText(),
    ).asJsonArray

    @Test
    fun `client token is sha256 of the client secret plus the exact user agent`() {
        for (v in vectors()) {
            val o = v.asJsonObject
            assertEquals(o["clientToken"].asString, signer.clientToken(o["userAgent"].asString))
        }
    }

    @Test
    fun `request signature is HMAC-SHA256 over method, path, canonical body and timestamp`() {
        for (v in vectors()) {
            val o = v.asJsonObject
            assertEquals(
                o["signature"].asString,
                signer.signature(o["method"].asString, o["path"].asString, o["canonical"].asString, o["timestamp"].asLong),
            )
        }
    }

    @Test
    fun `a different secret changes the signature`() {
        val other = SecurityHeaderSigner(signatureSecret = "rotated")
        val v = vectors()[0].asJsonObject
        val a = signer.signature("POST", "/api/rewards/claim", v["canonical"].asString, 1700000000L)
        val b = other.signature("POST", "/api/rewards/claim", v["canonical"].asString, 1700000000L)
        assert(a != b)
    }
}
