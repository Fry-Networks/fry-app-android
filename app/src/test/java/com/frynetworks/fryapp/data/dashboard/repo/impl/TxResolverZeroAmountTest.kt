package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.data.dashboard.api.IndexerApi
import com.frynetworks.fryapp.data.dashboard.repo.TxResolution
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/** An ASA opt-in is an asset transfer of 0 to oneself; an indexer may leave the zero `amount` out, which must still match. */
class TxResolverZeroAmountTest {

    private val me = "5JFGYY7CTRJAVPXVKB5RGLWF7GKUO5VOX27HXESCD3VGSFCG2IWAKDM5YU"
    private val someone = "7ULSIOC2UDDVWZH3PDGWAL5B3GI7327XNMJ4LDWXALVMQNPJ6YMB7QOADI"

    private fun axfer(id: String, asset: Long, rcv: String, amountField: String) =
        """{"id":"$id","tx-type":"axfer","sender":"$me","asset-transfer-transaction":{$amountField"asset-id":$asset,"receiver":"$rcv"}}"""

    private fun resolver(vararg tx: String): TxResolverImpl {
        val body = """{"current-round":1,"transactions":[${tx.joinToString(",")}]}"""
        val indexer = object : IndexerApi {
            override suspend fun get(url: String): Response<ResponseBody> = Response.success(body.toResponseBody("application/json".toMediaType()))
        }
        return TxResolverImpl(FakeAlgodRepository(), indexer, listOf("https://a.invalid", "https://b.invalid"))
    }

    private suspend fun TxResolverImpl.optIn(asset: Long = 77) = findTransfer(me, me, asset, 0L, 500L)

    @Test
    fun `an opt-in with an explicit zero amount is found`() = runTest {
        assertEquals(TxResolution.Confirmed("OPT", null), resolver(axfer("OPT", 77, me, "\"amount\":0,")).optIn())
    }

    @Test
    fun `an opt-in whose zero amount the indexer left out is found`() = runTest {
        assertEquals(TxResolution.Confirmed("OPT", null), resolver(axfer("OPT", 77, me, "")).optIn())
    }

    @Test
    fun `a missing amount never matches another asset, another receiver or a non-zero search`() = runTest {
        assertEquals(TxResolution.Unknown, resolver(axfer("OPT", 78, me, "")).optIn())
        assertEquals(TxResolution.Unknown, resolver(axfer("OPT", 77, someone, "")).optIn())
        assertEquals(TxResolution.Unknown, resolver(axfer("OPT", 77, me, "")).findTransfer(me, me, 77, 1_000L, 500L))
    }

    @Test
    fun `a non-zero amount is not an opt-in`() = runTest {
        assertEquals(TxResolution.Unknown, resolver(axfer("OPT", 77, me, "\"amount\":5,")).optIn())
    }
}
