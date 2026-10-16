package com.frynetworks.fryapp.util

import java.util.Base64

/** What a signed transaction states about itself, read from its own bytes (not from anything the bridge reports). */
class SignedTxn(
    /** The msgpack bytes of the `txn` map exactly as signed. */
    val rawTxn: ByteArray,
    /** base32 (no padding) of SHA-512/256("TX" + [rawTxn]): the id the network knows the transaction by. */
    val txId: String,
    val firstValid: Long?,
    /** Last round the transaction can be included in; null when it cannot be read, which never allows a rebuild. */
    val lastValid: Long?,
    val assetId: Long?,
    /** `aamt` of an asset transfer, `amt` of a payment, in micro units. */
    val amount: Long?,
    val senderKey: ByteArray?,
    /** `arcv` of an asset transfer, `rcv` of a payment: the 32-byte public key. */
    val receiverKey: ByteArray?,
)

/**
 * A minimal msgpack reader for the `{sig, txn}` blob a wallet returns: it finds the raw `txn` sub-map and reads a few of
 * its fields. Anything unexpected (truncation, trailing bytes, duplicate keys, deep nesting) gives null, never an exception.
 */
object SignedTxnReader {

    private const val MAX_DEPTH = 24
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun parse(signedB64: String): SignedTxn? {
        val bytes = try {
            Base64.getDecoder().decode(signedB64)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            Reader(bytes).signedTxn()
        } catch (e: RuntimeException) {
            null
        }
    }

    fun base32NoPad(input: ByteArray): String {
        val out = StringBuilder((input.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in input) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(BASE32_ALPHABET[(buffer shr bits) and 31])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    private class Malformed : RuntimeException()

    private class Reader(private val b: ByteArray) {
        private var pos = 0

        fun signedTxn(): SignedTxn? {
            var txnStart = -1
            var txnEnd = -1
            val seen = HashSet<String>()
            val entries = mapHeader()
            repeat(entries) {
                val key = str()
                if (!seen.add(key)) throw Malformed()
                if (key == "txn") {
                    txnStart = pos
                    skip(1)
                    txnEnd = pos
                } else {
                    skip(1)
                }
            }
            if (pos != b.size || txnStart < 0) return null
            val raw = b.copyOfRange(txnStart, txnEnd)
            return Reader(raw).txnFields(raw)
        }

        fun txnFields(raw: ByteArray): SignedTxn? {
            var fv: Long? = null
            var lv: Long? = null
            var xaid: Long? = null
            var amount: Long? = null
            var snd: ByteArray? = null
            var rcv: ByteArray? = null
            val seen = HashSet<String>()
            val entries = mapHeader()
            repeat(entries) {
                val key = str()
                if (!seen.add(key)) throw Malformed()
                when (key) {
                    "fv" -> fv = uintOrNull()
                    "lv" -> lv = uintOrNull()
                    "xaid" -> xaid = uintOrNull()
                    "aamt", "amt" -> amount = uintOrNull()
                    "snd" -> snd = bin32OrNull()
                    "rcv", "arcv" -> rcv = bin32OrNull()
                    else -> skip(1)
                }
            }
            if (pos != b.size) return null
            val id = base32NoPad(Sha512_256.digest("TX".toByteArray(Charsets.US_ASCII) + raw))
            return SignedTxn(raw, id, fv, lv, xaid, amount, snd, rcv)
        }

        private fun u8(): Int {
            if (pos >= b.size) throw Malformed()
            return b[pos++].toInt() and 0xFF
        }

        private fun uN(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 8) or u8().toLong() }
            return v
        }

        private fun need(n: Long) {
            if (n < 0 || n > b.size - pos) throw Malformed()
        }

        fun mapHeader(): Int {
            val t = u8()
            val n = when {
                t in 0x80..0x8f -> (t and 0x0f).toLong()
                t == 0xde -> uN(2)
                t == 0xdf -> uN(4)
                else -> throw Malformed()
            }
            if (n > Int.MAX_VALUE) throw Malformed()
            return n.toInt()
        }

        fun str(): String {
            val t = u8()
            val n = when {
                t in 0xa0..0xbf -> (t and 0x1f).toLong()
                t == 0xd9 -> uN(1)
                t == 0xda -> uN(2)
                t == 0xdb -> uN(4)
                else -> throw Malformed()
            }
            need(n)
            val s = String(b, pos, n.toInt(), Charsets.UTF_8)
            pos += n.toInt()
            return s
        }

        /** An unsigned integer value, or null (value consumed) when it is any other type or does not fit a Long. */
        fun uintOrNull(): Long? {
            val t = b.getOrNull(pos)?.toInt()?.and(0xFF) ?: throw Malformed()
            return when {
                t <= 0x7f -> { pos++; t.toLong() }
                t == 0xcc -> { pos++; uN(1) }
                t == 0xcd -> { pos++; uN(2) }
                t == 0xce -> { pos++; uN(4) }
                t == 0xcf -> { pos++; uN(8).takeIf { it >= 0 } }
                else -> { skip(1); null }
            }
        }

        fun bin32OrNull(): ByteArray? {
            val t = b.getOrNull(pos)?.toInt()?.and(0xFF) ?: throw Malformed()
            if (t != 0xc4) {
                skip(1)
                return null
            }
            pos++
            val n = uN(1)
            need(n)
            val out = b.copyOfRange(pos, pos + n.toInt())
            pos += n.toInt()
            return out.takeIf { it.size == 32 }
        }

        /** Skips [count] values. */
        fun skip(count: Long, depth: Int = 0) {
            if (depth > MAX_DEPTH) throw Malformed()
            var left = count
            while (left > 0) {
                left--
                val t = u8()
                when {
                    t <= 0x7f || t >= 0xe0 || t == 0xc0 || t == 0xc2 || t == 0xc3 -> Unit
                    t in 0x80..0x8f -> skip(2L * (t and 0x0f), depth + 1)
                    t in 0x90..0x9f -> skip((t and 0x0f).toLong(), depth + 1)
                    t in 0xa0..0xbf -> advance((t and 0x1f).toLong())
                    t == 0xc4 || t == 0xd9 -> advance(uN(1))
                    t == 0xc5 || t == 0xda -> advance(uN(2))
                    t == 0xc6 || t == 0xdb -> advance(uN(4))
                    t == 0xcc || t == 0xd0 -> advance(1)
                    t == 0xcd || t == 0xd1 -> advance(2)
                    t == 0xce || t == 0xd2 || t == 0xca -> advance(4)
                    t == 0xcf || t == 0xd3 || t == 0xcb -> advance(8)
                    t == 0xdc -> skip(uN(2), depth + 1)
                    t == 0xdd -> skip(uN(4), depth + 1)
                    t == 0xde -> skip(2 * uN(2), depth + 1)
                    t == 0xdf -> skip(2 * uN(4), depth + 1)
                    t == 0xd4 -> advance(2)
                    t == 0xd5 -> advance(3)
                    t == 0xd6 -> advance(5)
                    t == 0xd7 -> advance(9)
                    t == 0xd8 -> advance(17)
                    t == 0xc7 -> advance(uN(1) + 1)
                    t == 0xc8 -> advance(uN(2) + 1)
                    t == 0xc9 -> advance(uN(4) + 1)
                    else -> throw Malformed()
                }
            }
        }

        private fun advance(n: Long) {
            need(n)
            pos += n.toInt()
        }
    }
}
