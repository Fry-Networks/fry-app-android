package com.frynetworks.fryapp.util

/**
 * FIPS 180-4 SHA-512/256 (section 5.3.6.2 initial hash values, section 6.4 SHA-512 rounds,
 * output truncated to the first 256 bits) in pure Kotlin.
 *
 * Android ships no "SHA-512/256" MessageDigest provider: Conscrypt registers only
 * SHA-1/224/256/384/512 and the platform BouncyCastle strips the truncated SHA-512/t variants,
 * so `MessageDigest.getInstance("SHA-512/256")` throws NoSuchAlgorithmException on every device
 * while passing on a desktop JDK. Algorand address checksums are the last 4 bytes of the
 * SHA-512/256 of the public key, so the app has to compute the digest itself.
 */
object Sha512_256 {

    private const val BLOCK_BYTES = 128
    private const val OUTPUT_BYTES = 32

    /** First 64 bits of the fractional parts of the cube roots of the first 80 primes. */
    private val K = longArrayOf(
        0x428A2F98D728AE22uL.toLong(), 0x7137449123EF65CDuL.toLong(), 0xB5C0FBCFEC4D3B2FuL.toLong(), 0xE9B5DBA58189DBBCuL.toLong(),
        0x3956C25BF348B538uL.toLong(), 0x59F111F1B605D019uL.toLong(), 0x923F82A4AF194F9BuL.toLong(), 0xAB1C5ED5DA6D8118uL.toLong(),
        0xD807AA98A3030242uL.toLong(), 0x12835B0145706FBEuL.toLong(), 0x243185BE4EE4B28CuL.toLong(), 0x550C7DC3D5FFB4E2uL.toLong(),
        0x72BE5D74F27B896FuL.toLong(), 0x80DEB1FE3B1696B1uL.toLong(), 0x9BDC06A725C71235uL.toLong(), 0xC19BF174CF692694uL.toLong(),
        0xE49B69C19EF14AD2uL.toLong(), 0xEFBE4786384F25E3uL.toLong(), 0x0FC19DC68B8CD5B5uL.toLong(), 0x240CA1CC77AC9C65uL.toLong(),
        0x2DE92C6F592B0275uL.toLong(), 0x4A7484AA6EA6E483uL.toLong(), 0x5CB0A9DCBD41FBD4uL.toLong(), 0x76F988DA831153B5uL.toLong(),
        0x983E5152EE66DFABuL.toLong(), 0xA831C66D2DB43210uL.toLong(), 0xB00327C898FB213FuL.toLong(), 0xBF597FC7BEEF0EE4uL.toLong(),
        0xC6E00BF33DA88FC2uL.toLong(), 0xD5A79147930AA725uL.toLong(), 0x06CA6351E003826FuL.toLong(), 0x142929670A0E6E70uL.toLong(),
        0x27B70A8546D22FFCuL.toLong(), 0x2E1B21385C26C926uL.toLong(), 0x4D2C6DFC5AC42AEDuL.toLong(), 0x53380D139D95B3DFuL.toLong(),
        0x650A73548BAF63DEuL.toLong(), 0x766A0ABB3C77B2A8uL.toLong(), 0x81C2C92E47EDAEE6uL.toLong(), 0x92722C851482353BuL.toLong(),
        0xA2BFE8A14CF10364uL.toLong(), 0xA81A664BBC423001uL.toLong(), 0xC24B8B70D0F89791uL.toLong(), 0xC76C51A30654BE30uL.toLong(),
        0xD192E819D6EF5218uL.toLong(), 0xD69906245565A910uL.toLong(), 0xF40E35855771202AuL.toLong(), 0x106AA07032BBD1B8uL.toLong(),
        0x19A4C116B8D2D0C8uL.toLong(), 0x1E376C085141AB53uL.toLong(), 0x2748774CDF8EEB99uL.toLong(), 0x34B0BCB5E19B48A8uL.toLong(),
        0x391C0CB3C5C95A63uL.toLong(), 0x4ED8AA4AE3418ACBuL.toLong(), 0x5B9CCA4F7763E373uL.toLong(), 0x682E6FF3D6B2B8A3uL.toLong(),
        0x748F82EE5DEFB2FCuL.toLong(), 0x78A5636F43172F60uL.toLong(), 0x84C87814A1F0AB72uL.toLong(), 0x8CC702081A6439ECuL.toLong(),
        0x90BEFFFA23631E28uL.toLong(), 0xA4506CEBDE82BDE9uL.toLong(), 0xBEF9A3F7B2C67915uL.toLong(), 0xC67178F2E372532BuL.toLong(),
        0xCA273ECEEA26619CuL.toLong(), 0xD186B8C721C0C207uL.toLong(), 0xEADA7DD6CDE0EB1EuL.toLong(), 0xF57D4F7FEE6ED178uL.toLong(),
        0x06F067AA72176FBAuL.toLong(), 0x0A637DC5A2C898A6uL.toLong(), 0x113F9804BEF90DAEuL.toLong(), 0x1B710B35131C471BuL.toLong(),
        0x28DB77F523047D84uL.toLong(), 0x32CAAB7B40C72493uL.toLong(), 0x3C9EBE0A15C9BEBCuL.toLong(), 0x431D67C49C100D4CuL.toLong(),
        0x4CC5D4BECB3E42B6uL.toLong(), 0x597F299CFC657E2AuL.toLong(), 0x5FCB6FAB3AD6FAECuL.toLong(), 0x6C44198C4A475817uL.toLong(),
    )

    /** SHA-512/256 initial hash values (FIPS 180-4 section 5.3.6.2). */
    private val IV = longArrayOf(
        0x22312194FC2BF72CuL.toLong(), 0x9F555FA3C84C64C2uL.toLong(), 0x2393B86B6F53B151uL.toLong(), 0x963877195940EABDuL.toLong(),
        0x96283EE2A88EFFE3uL.toLong(), 0xBE5E1E2553863992uL.toLong(), 0x2B0199FC2C85B8AAuL.toLong(), 0x0EB72DDC81C52CA2uL.toLong(),
    )

    fun digest(input: ByteArray): ByteArray {
        val h = IV.copyOf()
        val padded = pad(input)
        val w = LongArray(80)
        var offset = 0
        while (offset < padded.size) {
            compress(h, padded, offset, w)
            offset += BLOCK_BYTES
        }
        val out = ByteArray(OUTPUT_BYTES)
        for (i in 0 until OUTPUT_BYTES / 8) writeLongBigEndian(out, i * 8, h[i])
        return out
    }

    private fun pad(input: ByteArray): ByteArray {
        // 0x80, zeros, then a 128-bit big-endian bit length; total length a multiple of 128.
        val zeros = (BLOCK_BYTES - ((input.size + 1 + 16) % BLOCK_BYTES)) % BLOCK_BYTES
        val total = input.size + 1 + zeros + 16
        val msg = ByteArray(total)
        System.arraycopy(input, 0, msg, 0, input.size)
        msg[input.size] = 0x80.toByte()
        val bitLength = input.size.toLong() * 8L
        for (i in 0 until 8) msg[total - 1 - i] = (bitLength ushr (8 * i)).toByte()
        return msg
    }

    private fun compress(h: LongArray, msg: ByteArray, offset: Int, w: LongArray) {
        for (t in 0 until 16) w[t] = readLongBigEndian(msg, offset + t * 8)
        for (t in 16 until 80) {
            val x = w[t - 15]
            val y = w[t - 2]
            val s0 = x.rotateRight(1) xor x.rotateRight(8) xor (x ushr 7)
            val s1 = y.rotateRight(19) xor y.rotateRight(61) xor (y ushr 6)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 80) {
            val bigS1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + bigS1 + ch + K[t] + w[t]
            val bigS0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = bigS0 + maj
            hh = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }

    private fun readLongBigEndian(src: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (src[offset + i].toLong() and 0xFF)
        return value
    }

    private fun writeLongBigEndian(dst: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) dst[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }
}
