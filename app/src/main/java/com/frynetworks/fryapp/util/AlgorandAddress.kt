package com.frynetworks.fryapp.util

/**
 * Validates Algorand addresses: 58-character RFC 4648 base32 (no padding) encoding of a
 * 32-byte public key followed by a 4-byte checksum (the last 4 bytes of SHA-512/256 of the
 * public key). See PROTOCOL.md section 1 — the Wallet BLE characteristic requires exactly
 * 58 bytes and the firmware rejects anything that fails this checksum (error code 5).
 */
object AlgorandAddress {

    private const val ADDRESS_LENGTH = 58
    private const val PUBLIC_KEY_LENGTH = 32
    private const val CHECKSUM_LENGTH = 4
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun isValid(address: String): Boolean {
        if (address.length != ADDRESS_LENGTH) return false
        if (!address.all { it in BASE32_ALPHABET }) return false

        val decoded = try {
            base32Decode(address)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (decoded.size < PUBLIC_KEY_LENGTH + CHECKSUM_LENGTH) return false

        val publicKey = decoded.copyOfRange(0, PUBLIC_KEY_LENGTH)
        val checksum = decoded.copyOfRange(PUBLIC_KEY_LENGTH, PUBLIC_KEY_LENGTH + CHECKSUM_LENGTH)

        // Pure-Kotlin SHA-512/256: Android has no MessageDigest provider for it (see Sha512_256).
        val digest = Sha512_256.digest(publicKey)
        val expectedChecksum = digest.copyOfRange(digest.size - CHECKSUM_LENGTH, digest.size)

        return checksum.contentEquals(expectedChecksum)
    }

    private fun base32Decode(input: String): ByteArray {
        val output = ByteArray(input.length * 5 / 8)
        var buffer = 0L
        var bitsLeft = 0
        var outputIndex = 0
        for (c in input) {
            val value = BASE32_ALPHABET.indexOf(c)
            if (value < 0) throw IllegalArgumentException("Invalid base32 character: $c")
            buffer = (buffer shl 5) or value.toLong()
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output[outputIndex++] = ((buffer shr bitsLeft) and 0xFF).toByte()
            }
        }
        return output
    }
}
