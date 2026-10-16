package com.frynetworks.fryapp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** The dashboard sends the claim's user leg as a bare unsigned transaction: its id is the same hash as for the signed form. */
class SignedTxnReaderBareTest {

    // Same payment as SignedTxnReaderTest.paySigned; its `txn` map re-encoded as the bare form algosdk's encodeUnsignedTransaction gives.
    private val paySigned = "gqNzaWfEQJ3tQu0YfcH7nHqxFeCRswiuwanJNK7WsUFubIbmoqnNo0JpuyJ9kOllpU0pLNaTKBfHdKP5Qqae3qFZ6QKStAOjdHhuiaNhbXTNA+ijZmVlzQPoomZ2zgPvhf2jZ2VurG1haW5uZXQtdjEuMKJnaMQgwGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit+ibHbOA++J5aNyY3bEIP0XJDhaoMdbZPt4zWAvodmR/ev3axPFjtcC6sg16fYYo3NuZMQg6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iykdHlwZaNwYXk="
    private val payTxId = "3TMZOQPUKWBLEVC2CQZCDUFS2O2KTDD3MHNC5JGZ4UWCF442SFWA"

    private fun bare(): String = Base64.getEncoder().encodeToString(checkNotNull(SignedTxnReader.parse(paySigned)).rawTxn)

    @Test
    fun `a bare payment gives the id algosdk computed and its rounds`() {
        val parsed = SignedTxnReader.parseBare(bare())
        assertNotNull(parsed)
        assertEquals(payTxId, parsed!!.txId)
        assertEquals(66_029_053L, parsed.firstValid)
        assertEquals(66_030_053L, parsed.lastValid)
        assertEquals(1000L, parsed.amount)
    }

    @Test
    fun `a signed blob is not a bare transaction`() {
        assertNull(SignedTxnReader.parseBare(paySigned))
    }

    @Test
    fun `a bare transaction is not a signed blob`() {
        assertNull(SignedTxnReader.parse(bare()))
    }

    @Test
    fun `garbage and trailing bytes give null`() {
        assertNull(SignedTxnReader.parseBare("not base64 !"))
        assertNull(SignedTxnReader.parseBare(Base64.getEncoder().encodeToString(Base64.getDecoder().decode(bare()) + byteArrayOf(0))))
    }
}
