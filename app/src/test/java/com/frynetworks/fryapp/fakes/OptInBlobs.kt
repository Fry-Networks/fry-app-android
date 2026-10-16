package com.frynetworks.fryapp.fakes

import com.frynetworks.fryapp.util.SignedTxnReader
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A signed ASA opt-in blob (`{sig, txn}` msgpack) for tests: an asset transfer of 0 from [sender] to itself, with real msgpack framing so that
 * [SignedTxnReader] reads its id, first and last valid round. The signature is 64 zero bytes; nothing here is ever sent anywhere.
 */
object OptInBlobs {

    fun signed(assetId: Long, firstValid: Long = 1_000L, lastValid: Long = 1_500L, senderByte: Int = 7): String {
        val sender = ByteArray(32) { senderByte.toByte() }
        val txn = ByteArrayOutputStream().apply {
            write(0x80 or 6)
            str("type"); str("axfer")
            str("snd"); bin(sender)
            str("arcv"); bin(sender)
            str("xaid"); uint(assetId)
            str("fv"); uint(firstValid)
            str("lv"); uint(lastValid)
        }.toByteArray()
        val out = ByteArrayOutputStream().apply {
            write(0x80 or 2)
            str("sig"); bin(ByteArray(64))
            str("txn"); write(txn)
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun txId(blob: String): String = checkNotNull(SignedTxnReader.parse(blob)).txId

    private fun ByteArrayOutputStream.str(s: String) {
        write(0xa0 or s.length)
        write(s.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.bin(b: ByteArray) {
        write(0xc4)
        write(b.size)
        write(b)
    }

    private fun ByteArrayOutputStream.uint(v: Long) {
        when {
            v < 128 -> write(v.toInt())
            v <= 0xff -> { write(0xcc); write(v.toInt()) }
            v <= 0xffff -> { write(0xcd); write((v shr 8).toInt()); write(v.toInt()) }
            v <= 0xffffffffL -> { write(0xce); for (s in intArrayOf(24, 16, 8, 0)) write((v shr s).toInt()) }
            else -> { write(0xcf); for (s in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) write((v shr s).toInt()) }
        }
    }
}
