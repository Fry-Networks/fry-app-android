package com.frynetworks.fryapp.network.dashboard

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Produces the two dashboard anti-automation headers exactly as `lib/clientToken.ts` and
 * `lib/requestSignature.client.ts` do in the browser:
 *
 * - `x-client-token`   = sha256hex("fry-rewards-client-" + User-Agent)
 * - `x-request-signature` = HMAC-SHA256hex(secret, "METHOD|path|canonicalBody|timestampSeconds")
 *
 * The server recomputes both from the request it received, so the User-Agent must be the one the
 * request carries and the canonical body must be the exact bytes sent (see [JsJson]).
 */
class SecurityHeaderSigner(
    private val signatureSecret: String,
    private val clientTokenSecret: String = CLIENT_TOKEN_SECRET,
) {

    fun clientToken(userAgent: String): String =
        MessageDigest.getInstance("SHA-256").digest((clientTokenSecret + userAgent).toByteArray(Charsets.UTF_8)).toHex()

    fun signature(method: String, path: String, canonicalBody: String, timestampSeconds: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signatureSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val message = "$method|$path|$canonicalBody|$timestampSeconds"
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val CLIENT_TOKEN_SECRET = "fry-rewards-client-"
        const val HEADER_CLIENT_TOKEN = "x-client-token"
        const val HEADER_SIGNATURE = "x-request-signature"
        const val HEADER_TIMESTAMP = "x-request-timestamp"
    }
}
