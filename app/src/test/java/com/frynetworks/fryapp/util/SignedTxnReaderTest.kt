package com.frynetworks.fryapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Vectors come from algosdk 3.7.0 (the version pinned in tools/bridge/package-lock.json, integrity sha512-6mr5w+A+...Qi3g==),
 * installed outside the repo; the transactions are only signed, never broadcast. Reproduce with
 * `npm install algosdk@3.7.0 --ignore-scripts` and a script that signs, with a throwaway account (seed of 32 bytes of 0x07),
 * makeAssetTransferTxnWithSuggestedParamsFromObject (asset 2681521901, amount 5000000, fv 66029053, lv 66030053, mainnet genesis,
 * 90-character json note) and makePaymentTxnWithSuggestedParamsFromObject (amount 1000), then prints txn.signTxn(sk) as base64 and txn.txID().
 */
class SignedTxnReaderTest {

    private val axferSigned = "gqNzaWfEQOVQ4a1yhbpYw/M3dbBmTh1Z0OLVpNTgbPVY5/W1d0KvuQ+03G0dkC+DtjLBG1J2UHHlj/D1AAboMwvOCf9vIwOjdHhui6RhYW10zgBMS0CkYXJjdsQg/RckOFqgx1tk+3jNYC+h2ZH96/drE8WO1wLqyDXp9hijZmVlzQPoomZ2zgPvhf2jZ2VurG1haW5uZXQtdjEuMKJnaMQgwGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit+ibHbOA++J5aRub3RlxG57ImEiOiJzdGFrZSIsIm4iOiJ4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHgifaNzbmTEIOpKbGPinFIKvvVQexMuxfmVR3auvr57kkIe6mkURtIspHR5cGWlYXhmZXKkeGFpZM6f1Mbt"
    private val axferTxId = "GCT7RIV64JK5FV3UGVB6Q4EKMV6KMKINCNCG6JHAQ4PICRRYIRZQ"
    private val paySigned = "gqNzaWfEQJ3tQu0YfcH7nHqxFeCRswiuwanJNK7WsUFubIbmoqnNo0JpuyJ9kOllpU0pLNaTKBfHdKP5Qqae3qFZ6QKStAOjdHhuiaNhbXTNA+ijZmVlzQPoomZ2zgPvhf2jZ2VurG1haW5uZXQtdjEuMKJnaMQgwGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit+ibHbOA++J5aNyY3bEIP0XJDhaoMdbZPt4zWAvodmR/ev3axPFjtcC6sg16fYYo3NuZMQg6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iykdHlwZaNwYXk="
    private val payTxId = "3TMZOQPUKWBLEVC2CQZCDUFS2O2KTDD3MHNC5JGZ4UWCF442SFWA"

    @Test
    fun `the transaction id of a signed asset transfer equals the id algosdk computed`() {
        assertEquals(axferTxId, SignedTxnReader.parse(axferSigned)?.txId)
    }

    @Test
    fun `the transaction id of a signed payment equals the id algosdk computed`() {
        assertEquals(payTxId, SignedTxnReader.parse(paySigned)?.txId)
    }

    @Test
    fun `first and last valid round are read from the signed bytes`() {
        val parsed = SignedTxnReader.parse(axferSigned)
        assertNotNull(parsed)
        assertEquals(66_029_053L, parsed!!.firstValid)
        assertEquals(66_030_053L, parsed.lastValid)
    }

    @Test
    fun `a last valid round later than a build round estimate is honoured as decoded`() {
        // 66030053 is 1000 rounds after fv: any estimate of built round + 1001 would be smaller than a lv set 5000 rounds out.
        assertEquals(66_030_053L, SignedTxnReader.parse(paySigned)?.lastValid)
    }

    @Test
    fun `receiver asset and amount of the asset transfer are read`() {
        val parsed = SignedTxnReader.parse(axferSigned)!!
        assertEquals(2_681_521_901L, parsed.assetId)
        assertEquals(5_000_000L, parsed.amount)
        assertEquals(32, parsed.receiverKey?.size)
        assertEquals(32, parsed.senderKey?.size)
    }

    @Test
    fun `text that is not base64 gives null`() {
        assertNull(SignedTxnReader.parse("signed(axfer|A|B|1|2|#1)"))
    }

    @Test
    fun `base64 that is not a signed transaction gives null`() {
        assertNull(SignedTxnReader.parse(Base64.getEncoder().encodeToString("not msgpack at all".toByteArray())))
        assertNull(SignedTxnReader.parse(""))
    }

    @Test
    fun `every truncation of a valid blob gives null or a parse without throwing`() {
        val bytes = Base64.getDecoder().decode(axferSigned)
        for (n in 0 until bytes.size) {
            SignedTxnReader.parse(Base64.getEncoder().encodeToString(bytes.copyOf(n)))
        }
        assertNull(SignedTxnReader.parse(Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size - 1))))
    }

    @Test
    fun `a signed blob without a txn map gives null`() {
        // fixmap(1) {"sig": bin(1) 0x00}
        val blob = byteArrayOf(0x81.toByte(), 0xa3.toByte(), 's'.code.toByte(), 'i'.code.toByte(), 'g'.code.toByte(), 0xc4.toByte(), 1, 0)
        assertNull(SignedTxnReader.parse(Base64.getEncoder().encodeToString(blob)))
    }

    @Test
    fun `a transaction with no readable last valid round yields a txid but a null last valid round`() {
        // fixmap(1) {"txn": fixmap(1) {"fv": 5}}  (no lv)
        val blob = byteArrayOf(
            0x81.toByte(), 0xa3.toByte(), 't'.code.toByte(), 'x'.code.toByte(), 'n'.code.toByte(),
            0x81.toByte(), 0xa2.toByte(), 'f'.code.toByte(), 'v'.code.toByte(), 5,
        )
        val parsed = SignedTxnReader.parse(Base64.getEncoder().encodeToString(blob))
        assertNotNull(parsed)
        assertNull(parsed!!.lastValid)
        assertEquals(5L, parsed.firstValid)
    }

    @Test
    fun `deeply nested input is refused without a stack overflow`() {
        val nested = ByteArray(10_000) { 0x91.toByte() } + byteArrayOf(0)
        val blob = byteArrayOf(0x81.toByte(), 0xa3.toByte(), 't'.code.toByte(), 'x'.code.toByte(), 'n'.code.toByte()) + nested
        assertNull(SignedTxnReader.parse(Base64.getEncoder().encodeToString(blob)))
    }

    // --- hand-built msgpack: every width the reader accepts, and what it must refuse --------------------------------------

    private fun b64(vararg parts: ByteArray) = Base64.getEncoder().encodeToString(parts.fold(ByteArray(0)) { acc, p -> acc + p })
    private fun fixstr(text: String) = byteArrayOf((0xa0 or text.length).toByte()) + text.toByteArray()
    private fun str8(text: String) = byteArrayOf(0xd9.toByte(), text.length.toByte()) + text.toByteArray()
    private fun str16(text: String) = byteArrayOf(0xda.toByte(), 0, text.length.toByte()) + text.toByteArray()
    private fun bin16(size: Int) = byteArrayOf(0xc5.toByte(), (size shr 8).toByte(), size.toByte()) + ByteArray(size)
    private fun bin8(size: Int) = byteArrayOf(0xc4.toByte(), size.toByte()) + ByteArray(size)
    private fun fixmap(n: Int) = byteArrayOf((0x80 or n).toByte())
    private fun map16(n: Int) = byteArrayOf(0xde.toByte(), 0, n.toByte())
    private fun uint(v: Long): ByteArray = when {
        v < 128 -> byteArrayOf(v.toByte())
        v < 65_536 -> byteArrayOf(0xcd.toByte(), (v shr 8).toByte(), v.toByte())
        else -> byteArrayOf(0xce.toByte(), (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }
    private val uint64Max = byteArrayOf(0xcf.toByte()) + ByteArray(8) { 0xff.toByte() }

    private fun txnMap(vararg entries: ByteArray) = fixmap(entries.size / 2) + entries.fold(ByteArray(0)) { a, e -> a + e }

    @Test
    fun `map16 headers and str8 and str16 keys are read`() {
        val txn = map16(2) + str8("fv") + uint(70_000) + str16("lv") + uint(71_000)
        val blob = map16(2) + fixstr("sig") + bin8(64) + str8("txn") + txn
        val parsed = SignedTxnReader.parse(b64(blob))
        assertNotNull(parsed)
        assertEquals(70_000L, parsed!!.firstValid)
        assertEquals(71_000L, parsed.lastValid)
    }

    @Test
    fun `a bin16 signature is skipped`() {
        val blob = fixmap(2) + fixstr("sig") + bin16(300) + fixstr("txn") + txnMap(fixstr("lv"), uint(9))
        assertEquals(9L, SignedTxnReader.parse(b64(blob))?.lastValid)
    }

    @Test
    fun `a uint64 above the Long range gives a null last valid round and still parses`() {
        val blob = fixmap(1) + fixstr("txn") + txnMap(fixstr("fv"), uint(5), fixstr("lv"), uint64Max)
        val parsed = SignedTxnReader.parse(b64(blob))
        assertNotNull(parsed)
        assertNull(parsed!!.lastValid)
        assertEquals(5L, parsed.firstValid)
    }

    @Test
    fun `a rekeyed signature with sgnr is parsed with the same txid`() {
        val plain = fixmap(2) + fixstr("sig") + bin8(64) + fixstr("txn") + txnMap(fixstr("fv"), uint(5), fixstr("lv"), uint(9))
        val rekeyed = fixmap(3) + fixstr("sgnr") + bin8(32) + fixstr("sig") + bin8(64) + fixstr("txn") + txnMap(fixstr("fv"), uint(5), fixstr("lv"), uint(9))
        val a = SignedTxnReader.parse(b64(plain))
        val b = SignedTxnReader.parse(b64(rekeyed))
        assertNotNull(b)
        assertEquals(a!!.txId, b!!.txId)
        assertEquals(9L, b.lastValid)
    }

    @Test
    fun `a duplicate txn key is refused`() {
        val blob = fixmap(2) + fixstr("txn") + txnMap(fixstr("lv"), uint(9)) + fixstr("txn") + txnMap(fixstr("lv"), uint(99))
        assertNull(SignedTxnReader.parse(b64(blob)))
    }

    @Test
    fun `a duplicate top-level key other than txn is refused`() {
        val blob = fixmap(3) + fixstr("sig") + bin8(64) + fixstr("sig") + bin8(64) + fixstr("txn") + txnMap(fixstr("lv"), uint(9))
        assertNull(SignedTxnReader.parse(b64(blob)))
    }

    @Test
    fun `a duplicate key inside txn is refused`() {
        val blob = fixmap(1) + fixstr("txn") + txnMap(fixstr("lv"), uint(9), fixstr("lv"), uint(10))
        assertNull(SignedTxnReader.parse(b64(blob)))
    }

    @Test
    fun `one trailing byte after the signed blob is refused`() {
        val bytes = Base64.getDecoder().decode(axferSigned)
        assertNotNull(SignedTxnReader.parse(Base64.getEncoder().encodeToString(bytes)))
        assertNull(SignedTxnReader.parse(Base64.getEncoder().encodeToString(bytes + byteArrayOf(0))))
    }

    @Test
    fun `the raw transaction is exactly the txn span of the blob`() {
        val bytes = Base64.getDecoder().decode(axferSigned)
        // fixmap(2) + "sig" (fixstr 4 bytes) + bin8(64) (66 bytes) + "txn" (fixstr 4 bytes): the txn map starts at 1 + 4 + 66 + 4
        val expected = bytes.copyOfRange(75, bytes.size)
        assertEquals(0x8b, expected[0].toInt() and 0xff) // fixmap(11): a transfer with a note
        assertTrue(expected.contentEquals(SignedTxnReader.parse(axferSigned)!!.rawTxn))
    }

    @Test
    fun `every shorter prefix of a valid blob gives null`() {
        val bytes = Base64.getDecoder().decode(paySigned)
        for (n in 0 until bytes.size) {
            assertNull("prefix of $n bytes", SignedTxnReader.parse(Base64.getEncoder().encodeToString(bytes.copyOf(n))))
        }
    }
}
