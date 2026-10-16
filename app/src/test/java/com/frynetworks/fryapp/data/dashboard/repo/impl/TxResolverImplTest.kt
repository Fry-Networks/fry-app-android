package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.data.dashboard.api.IndexerApi
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.TxResolution
import com.frynetworks.fryapp.data.dashboard.repo.TxStatus
import com.frynetworks.fryapp.fakes.FakeAlgodRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * The only verdict that lets a stake or a claim fee be sent again is [TxResolution.Absent]: the pool and BOTH indexers have never
 * heard of the txid, and algod and both indexers have moved past its last valid round. Everything doubtful is Unknown.
 */
class TxResolverImplTest {

    private val txId = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABCDEFGHIJKLMNOPQRST"
    private val lv = 1_000L
    private val notFound = """{"message":"no transaction found for transaction id: $txId"}"""

    private class Idx {
        val server = MockWebServer()
        var txCode = 404
        var txBody: String? = null
        var txPolicy: SocketPolicy = SocketPolicy.KEEP_OPEN
        var txContentType = "application/json"
        var healthCode = 200
        var healthBody: String? = null
        var accountsCode = 200
        var accountsBody = """{"current-round":1,"transactions":[]}"""
        val requests = mutableListOf<RecordedRequest>()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.path.orEmpty()
                    return when {
                        path == "/health" -> MockResponse().setResponseCode(healthCode).setBody(healthBody ?: health(0)).setHeader("Content-Type", "application/json")
                        path.startsWith("/v2/transactions/") ->
                            MockResponse().setResponseCode(txCode).setBody(txBody.orEmpty()).setHeader("Content-Type", txContentType).also {
                                if (txPolicy != SocketPolicy.KEEP_OPEN) it.socketPolicy = txPolicy
                            }
                        path.startsWith("/v2/accounts/") -> MockResponse().setResponseCode(accountsCode).setBody(accountsBody).setHeader("Content-Type", "application/json")
                        else -> MockResponse().setResponseCode(500)
                    }
                }
            }
            server.start()
        }

        fun round(r: Long) = apply { healthBody = health(r) }
        fun url(): String = server.url("/").toString().trimEnd('/')
    }

    private class Algod(private val backing: FakeAlgodRepository = FakeAlgodRepository()) : AlgodRepository by backing {
        var status: TxStatus = TxStatus.Unknown
        var statusError: Throwable? = null
        var round: Long? = 0
        var notInPool = true
        var poolCalls = 0

        override suspend fun txStatus(txId: String): TxStatus {
            statusError?.let { throw it }
            return status
        }

        override suspend fun currentRound(): Long? = round
        override suspend fun poolHasNoRecord(txId: String): Boolean {
            poolCalls++
            return notInPool
        }
    }

    private val a = Idx()
    private val b = Idx()
    private val algod = Algod()
    private lateinit var resolver: TxResolverImpl

    @Before
    fun setUp() {
        val api = Retrofit.Builder().baseUrl(a.server.url("/")).client(OkHttpClient()).build().create(IndexerApi::class.java)
        resolver = TxResolverImpl(algod, api, listOf(a.url(), b.url()), callTimeoutMillis = 600)
        // the baseline: everything past lv and nothing known
        algod.round = lv + 1
        a.round(lv + 1).txBody = notFound
        b.round(lv + 1).txBody = notFound
    }

    @After
    fun tearDown() {
        a.server.shutdown()
        b.server.shutdown()
    }

    companion object {
        fun health(round: Long, db: Boolean = true, migrating: Boolean = false) =
            """{"data":{},"db-available":$db,"is-migrating":$migrating,"message":"$round","round":$round,"version":"3.10.0-ndly"}"""
    }

    private fun found(id: String = txId, round: Long = 900) = """{"current-round":${lv + 1},"transaction":{"id":"$id","confirmed-round":$round}}"""

    private suspend fun resolve(lastValid: Long? = lv) = resolver.resolve(txId, lastValid)

    // --- the one way to Absent ------------------------------------------------------------------------------------------

    @Test
    fun `pool 404 and both indexers 404 and every round past lv is Absent`() = runTest {
        assertEquals(TxResolution.Absent, resolve())
    }

    @Test
    fun `an unknown txid from algod's own status is treated like a missing pool entry`() = runTest {
        algod.status = TxStatus.Absent
        assertEquals(TxResolution.Absent, resolve())
    }

    // --- Unknown, never Absent ------------------------------------------------------------------------------------------

    @Test
    fun `one indexer 404 and the other 5xx is Unknown`() = runTest {
        b.txCode = 503
        b.txBody = """{"message":"down"}"""
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `one indexer 404 and the other lagging is Unknown`() = runTest {
        b.round(lv - 5)
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `both indexers lagging is Unknown`() = runTest {
        a.round(lv - 5)
        b.round(lv - 1)
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `a 429 from an indexer is Unknown`() = runTest {
        a.txCode = 429
        a.txBody = """{"message":"slow down"}"""
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `an indexer that never answers is Unknown`() = runTest {
        a.txPolicy = SocketPolicy.NO_RESPONSE
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `an indexer whose health never answers is Unknown`() = runTest {
        a.healthCode = 500
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `an unreachable indexer is Unknown`() = runTest {
        b.server.shutdown()
        assertEquals(TxResolution.Unknown, resolve())
    }

    // --- boundaries -----------------------------------------------------------------------------------------------------

    @Test
    fun `an indexer round equal to lv is not past it`() = runTest {
        a.round(lv)
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `algod round equal to lv is not past it`() = runTest {
        algod.round = lv
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `lv plus one with the algod source lagging is not Absent`() = runTest {
        algod.round = lv
        a.round(lv + 1)
        b.round(lv + 1)
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `lv plus one with every source caught up is Absent`() = runTest {
        algod.round = lv + 1
        a.round(lv + 1)
        b.round(lv + 1)
        assertEquals(TxResolution.Absent, resolve())
    }

    @Test
    fun `an unknown algod round is Unknown`() = runTest {
        algod.round = null
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `no last valid round is never Absent`() = runTest {
        assertEquals(TxResolution.Unknown, resolve(lastValid = null))
    }

    // --- anything known to a node ---------------------------------------------------------------------------------------

    @Test
    fun `either indexer finding it is Confirmed`() = runTest {
        a.txCode = 200
        a.txBody = found()
        assertEquals(TxResolution.Confirmed(txId, 900), resolve())
    }

    @Test
    fun `the second indexer finding it is Confirmed`() = runTest {
        b.txCode = 200
        b.txBody = found()
        assertEquals(TxResolution.Confirmed(txId, 900), resolve())
    }

    @Test
    fun `an indexer finding it settles Confirmed even when algod is failing and lagging`() = runTest {
        a.txCode = 200
        a.txBody = found()
        b.txCode = 500
        algod.statusError = java.io.IOException("algod down")
        algod.round = null
        assertEquals(TxResolution.Confirmed(txId, 900), resolve())
    }

    @Test
    fun `a pool entry confirmed by algod is Confirmed without asking the indexers`() = runTest {
        algod.status = TxStatus.Confirmed(777)
        assertEquals(TxResolution.Confirmed(txId, 777), resolve())
        assertTrue(a.requests.isEmpty() && b.requests.isEmpty())
    }

    @Test
    fun `a transaction still in the pool is Unknown`() = runTest {
        algod.status = TxStatus.InPool
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `a pool error is Unknown`() = runTest {
        algod.status = TxStatus.Rejected("overspend")
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `a pool lookup that cannot say the txid is absent is Unknown`() = runTest {
        algod.notInPool = false
        assertEquals(TxResolution.Unknown, resolve())
    }

    // --- bodies that are not what they must be --------------------------------------------------------------------------

    @Test
    fun `a 200 that names another transaction is Unknown`() = runTest {
        a.txCode = 200
        a.txBody = found(id = "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ")
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `a 200 with a malformed body is Unknown`() = runTest {
        a.txCode = 200
        a.txBody = "<html>proxy</html>"
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `an HTML 404 is not a definitive not-found`() = runTest {
        a.txBody = "<html><body>404 not found</body></html>"
        a.txContentType = "text/html"
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `a JSON 404 with another message is not a definitive not-found`() = runTest {
        a.txBody = """{"message":"endpoint not found"}"""
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `an indexer with its database unavailable is Unknown`() = runTest {
        a.healthBody = health(lv + 1, db = false)
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `an indexer that is migrating is Unknown`() = runTest {
        b.healthBody = health(lv + 1, migrating = true)
        assertEquals(TxResolution.Unknown, resolve())
    }

    @Test
    fun `a health reply without a numeric round is Unknown`() = runTest {
        a.healthBody = """{"db-available":true,"is-migrating":false,"message":"x"}"""
        assertEquals(TxResolution.Unknown, resolve())
        a.healthBody = """{"db-available":true,"is-migrating":false,"round":"soon"}"""
        assertEquals(TxResolution.Unknown, resolve())
    }

    // --- what leaves the phone ------------------------------------------------------------------------------------------

    @Test
    fun `only GET requests for the txid and the health route go out`() = runTest {
        resolve()
        for (idx in listOf(a, b)) {
            assertEquals(setOf("/v2/transactions/$txId", "/health"), idx.requests.map { it.path }.toSet())
            assertTrue(idx.requests.all { it.method == "GET" && it.bodySize == 0L })
        }
    }

    @Test
    fun `a txid that is not a transaction id is Unknown and sends nothing`() = runTest {
        assertEquals(TxResolution.Unknown, resolver.resolve("../../etc?x=1", lv))
        assertTrue(a.requests.isEmpty() && b.requests.isEmpty())
    }

    // --- finding a transfer whose id was never learned ---------------------------------------------------------------------

    private val sender = "5JFGYY7CTRJAVPXVKB5RGLWF7GKUO5VOX27HXESCD3VGSFCG2IWAKDM5YU"
    private val receiver = "7ULSIOC2UDDVWZH3PDGWAL5B3GI7327XNMJ4LDWXALVMQNPJ6YMB7QOADI"

    private fun axfer(id: String, amount: Long = 5_000_000, asset: Long = 2_681_521_901, rcv: String = receiver) =
        """{"id":"$id","tx-type":"axfer","sender":"$sender","asset-transfer-transaction":{"amount":$amount,"asset-id":$asset,"receiver":"$rcv"}}"""

    private fun pay(id: String, amount: Long = 1_000, rcv: String = receiver) =
        """{"id":"$id","tx-type":"pay","sender":"$sender","payment-transaction":{"amount":$amount,"receiver":"$rcv"}}"""

    private fun page(vararg tx: String) = """{"current-round":${lv + 1},"transactions":[${tx.joinToString(",")}]}"""

    private suspend fun find(asset: Long = 2_681_521_901, amount: Long = 5_000_000, minRound: Long? = 500) =
        resolver.findTransfer(sender, receiver, asset, amount, minRound)

    @Test
    fun `both indexers listing the same single matching transfer is Confirmed with its id`() = runTest {
        a.accountsBody = page(axfer("TXA"), axfer("OTHER", amount = 7))
        b.accountsBody = page(axfer("TXA"))
        assertEquals(TxResolution.Confirmed("TXA", null), find())
    }

    @Test
    fun `a match found by only one indexer is Unknown`() = runTest {
        a.accountsBody = page(axfer("TXA"))
        assertEquals(TxResolution.Unknown, find())
    }

    @Test
    fun `indexers naming different transfers is Unknown`() = runTest {
        a.accountsBody = page(axfer("TXA"))
        b.accountsBody = page(axfer("TXB"))
        assertEquals(TxResolution.Unknown, find())
    }

    @Test
    fun `two matching transfers in one listing is Unknown`() = runTest {
        a.accountsBody = page(axfer("TXA"), axfer("TXB"))
        b.accountsBody = page(axfer("TXA"), axfer("TXB"))
        assertEquals(TxResolution.Unknown, find())
    }

    @Test
    fun `a transfer of another amount or asset or receiver does not match`() = runTest {
        a.accountsBody = page(axfer("T1", amount = 4_999_999), axfer("T2", asset = 1), axfer("T3", rcv = sender))
        b.accountsBody = a.accountsBody
        assertEquals(TxResolution.Unknown, find())
    }

    @Test
    fun `a payment matches when the asset id is zero`() = runTest {
        a.accountsBody = page(pay("PAYA"))
        b.accountsBody = page(pay("PAYA"))
        assertEquals(TxResolution.Confirmed("PAYA", null), find(asset = 0, amount = 1_000))
    }

    @Test
    fun `an indexer error is Unknown`() = runTest {
        a.accountsBody = page(axfer("TXA"))
        b.accountsBody = page(axfer("TXA"))
        b.accountsCode = 502
        assertEquals(TxResolution.Unknown, find())
    }

    @Test
    fun `without a round to search from nothing is asked and the answer is Unknown`() = runTest {
        a.accountsBody = page(axfer("TXA"))
        b.accountsBody = page(axfer("TXA"))
        assertEquals(TxResolution.Unknown, find(minRound = null))
        assertTrue(a.requests.isEmpty() && b.requests.isEmpty())
    }

    @Test
    fun `the search is bounded by min-round and the sender only`() = runTest {
        a.accountsBody = page(axfer("TXA"))
        b.accountsBody = page(axfer("TXA"))
        find(minRound = 500)
        val path = a.requests.single { it.path!!.startsWith("/v2/accounts/") }.path!!
        assertTrue(path, path.startsWith("/v2/accounts/$sender/transactions?"))
        assertTrue(path, path.contains("min-round=500"))
    }
}
