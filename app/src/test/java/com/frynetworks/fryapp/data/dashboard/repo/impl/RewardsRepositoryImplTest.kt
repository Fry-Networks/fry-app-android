package com.frynetworks.fryapp.data.dashboard.repo.impl

import app.cash.turbine.test
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.network.dashboard.SessionEvent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/** [RewardsRepositoryImpl]: exact bodies + signing on every rewards route, chunking, caches, error mapping. */
class RewardsRepositoryImplTest {

    private val server = MockWebServer()
    private lateinit var stack: DashboardStack
    private val summaryDao = FakeRewardSummaryCacheDao()
    private val totalsDao = FakeAssetTotalsCacheDao()
    private var address: String? = Fixtures.ADDRESS
    private val serverTimes = mutableListOf<Long>()
    private lateinit var repo: RewardsRepositoryImpl

    @Before
    fun start() {
        server.start()
        stack = DashboardStack(server)
        repo = RewardsRepositoryImpl(stack.api, summaryDao, totalsDao, { address }, stack.bus, now = { 42L }, onServerTime = { serverTimes += it })
    }

    @After fun stop() = server.shutdown()

    @Test
    fun `refreshAssetTotals signs the canonical empty object, caches the answer and reports serverTime`() = runTest {
        server.enqueue(Fixtures.json("asset-totals"))
        repo.observeAssetTotals().test {
            assertNull(awaitItem())
            val totals = repo.refreshAssetTotals()
            assertEquals(0, BigDecimal("9.99").compareTo(totals.totals?.tfry?.claimable))
            stack.assertSignedPost(server.takeRequest(), "/api/rewards/get-asset-totals", "{}")
            val cached = awaitItem()
            assertEquals(0, BigDecimal("12.5").compareTo(cached?.totals?.fnode?.pending))
            assertEquals(42L, totalsDao.row.value?.fetchedAt)
            assertEquals(listOf(1757548800000L), serverTimes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshSummaries chunks at 50 keys in order, merges the answers and caches each summary verbatim`() = runTest {
        val keys = (1..120).map { "FEM-" + it.toString().padStart(6, '0') }
        repeat(3) { i ->
            val chunk = keys.drop(i * 50).take(50)
            server.enqueue(Fixtures.body("""{"success":true,"summaries":{${chunk.joinToString(",") { """"$it":{"pending":$i,"claimable":0.5,"extra_field":"kept"}""" }}}}"""))
        }

        val merged = repo.refreshSummaries(keys)

        assertEquals(120, merged.size)
        assertEquals(0, BigDecimal("2").compareTo(merged["FEM-000120"]?.pending))
        assertEquals(3, server.requestCount)
        for (i in 0 until 3) {
            stack.assertSignedPost(server.takeRequest(), "/api/rewards/get-reward-summary-batch", """{"miner_keys":[${keys.drop(i * 50).take(50).joinToString(",") { "\"$it\"" }}]}""")
        }
        assertEquals(120, summaryDao.rows.value.size)
        assertTrue(summaryDao.rows.value["FEM-000001"]!!.json.contains("\"extra_field\":\"kept\""))
        repo.observeSummary("FEM-000077").test {
            assertEquals(0, BigDecimal("0.5").compareTo(awaitItem()?.claimable))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshSummaries with no keys makes no request`() = runTest {
        assertTrue(repo.refreshSummaries(emptyList()).isEmpty())
        assertTrue(repo.refreshSummaries(listOf("", "  ")).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refreshSummary posts miner_key signed and null when the dashboard has no summary`() = runTest {
        server.enqueue(Fixtures.json("reward-summary"))
        val summary = repo.refreshSummary(Fixtures.FEM)
        assertEquals(0, BigDecimal("4.25").compareTo(summary?.claimable))
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/get-reward-summary", """{"miner_key":"${Fixtures.FEM}"}""")
        repo.observeSummary(Fixtures.FEM).test {
            assertEquals("2026-04-20T00:05:00.000Z", awaitItem()?.firstRewardAt)
            cancelAndIgnoreRemainingEvents()
        }

        server.enqueue(Fixtures.body("""{"success":true,"summary":null}"""))
        assertNull(repo.refreshSummary(Fixtures.RDN))
    }

    @Test
    fun `historyPage posts miner_key then page, signed`() = runTest {
        server.enqueue(Fixtures.json("rewards-page"))
        val page = repo.historyPage(Fixtures.FEM, 3)
        assertEquals(3, page.items.size)
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/get-rewards-page", """{"miner_key":"${Fixtures.FEM}","page":3}""")
    }

    @Test
    fun `activity posts the session address unsigned and returns the events`() = runTest {
        server.enqueue(Fixtures.json("activity"))
        val events = repo.activity()
        assertEquals(listOf("reward_unlocked", "registered"), events.map { it.type })
        stack.assertUnsignedPost(server.takeRequest(), "/api/activity/recent", """{"address":"${Fixtures.ADDRESS}"}""")
    }

    @Test
    fun `prices sends asset ids as strings and maps the answer back to Long keys`() = runTest {
        server.enqueue(Fixtures.json("price"))
        val prices = repo.prices(listOf(2681521901L, 2485202024L, 2681521901L))
        assertEquals(0, BigDecimal("0.05").compareTo(prices[2681521901L]))
        assertEquals(0, BigDecimal("0.12").compareTo(prices[2485202024L]))
        stack.assertUnsignedPost(server.takeRequest(), "/api/price/get", """{"asset_ids":["2681521901","2485202024"]}""")
        assertTrue(repo.prices(emptyList()).isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `claimPreview body is miner_key, optional no, then preview true - exactly as Claim tsx`() = runTest {
        server.enqueue(Fixtures.json("claim-preview"))
        server.enqueue(Fixtures.json("claim-preview"))
        server.enqueue(Fixtures.json("claim-preview"))

        val preview = repo.claimPreview(Fixtures.FEM)
        assertTrue(preview.preview)
        assertEquals(0, BigDecimal("4.25").compareTo(preview.totals[0].amount))
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/claim", """{"miner_key":"${Fixtures.FEM}","preview":true}""")

        repo.claimPreview(Fixtures.FEM, no = 12)
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/claim", """{"miner_key":"${Fixtures.FEM}","no":12,"preview":true}""")

        repo.claimPreview(Fixtures.FEM, no = 0) // `no ? {...} : {...}` — 0 is falsy in JS
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/claim", """{"miner_key":"${Fixtures.FEM}","preview":true}""")
    }

    @Test
    fun `claimEnvelope body omits preview and parses both the user_pays and custodial answers`() = runTest {
        server.enqueue(Fixtures.json("claim-envelope-user-pays"))
        val userPays = repo.claimEnvelope(Fixtures.FEM, no = 7)
        assertEquals("user_pays", userPays.mode)
        assertEquals("GROUP-1700000000000-abc123", userPays.groupId)
        assertNull(userPays.txId)
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/claim", """{"miner_key":"${Fixtures.FEM}","no":7}""")

        server.enqueue(Fixtures.json("claim-custodial"))
        val custodial = repo.claimEnvelope(Fixtures.FEM)
        assertEquals("CUSTODIALTXID000000000000000000000000000000000000000000", custodial.txId)
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/claim", """{"miner_key":"${Fixtures.FEM}"}""")
    }

    @Test
    fun `claimConfirm posts groupId then signedUserLegB64, signed`() = runTest {
        server.enqueue(Fixtures.json("confirm-result"))
        val result = repo.claimConfirm("GROUP-1", "c2lnbmVk")
        assertEquals(true, result.ok)
        assertEquals("2026-09-10T14:22:00.000Z", result.claimedAt)
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/confirm", """{"groupId":"GROUP-1","signedUserLegB64":"c2lnbmVk"}""")
    }

    @Test
    fun `claimConfirmCustodial posts txId and returns the not-yet-confirmed answer for polling instead of throwing`() = runTest {
        server.enqueue(Fixtures.body("""{"success":false,"code":"NETWORK_ERROR","message":"Not yet confirmed"}"""))
        val pending = repo.claimConfirmCustodial("TX1")
        assertEquals(false, pending.success)
        stack.assertSignedPost(server.takeRequest(), "/api/rewards/confirm", """{"txId":"TX1"}""")

        server.enqueue(Fixtures.body("""{"success":true,"claimedAt":"2026-09-10T14:22:00.000Z"}"""))
        assertEquals(true, repo.claimConfirmCustodial("TX1").success)
    }

    @Test
    fun `a claim error envelope becomes a DashboardException with code, message, action and status`() = runTest {
        server.enqueue(Fixtures.json("error").setResponseCode(409))
        val e = runCatching { repo.claimEnvelope(Fixtures.FEM) }.exceptionOrNull() as DashboardException
        assertEquals("REWARD_ON_HOLD", e.code)
        assertEquals("This reward is currently on hold pending review.", e.message)
        assertEquals("CONTACT_SUPPORT", e.action)
        assertEquals(409, e.httpStatus)
    }

    @Test
    fun `a 401 with no envelope is UNAUTHORIZED and expires the session`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        stack.bus.events.test {
            val e = runCatching { repo.refreshAssetTotals() }.exceptionOrNull() as DashboardException
            assertEquals("UNAUTHORIZED", e.code)
            assertEquals(401, e.httpStatus)
            assertEquals(SessionEvent.Expired, awaitItem())
        }
    }

    @Test
    fun `a 401 WALLET_MISMATCH does not expire the session`() = runTest {
        server.enqueue(Fixtures.body("""{"success":false,"code":"WALLET_MISMATCH","message":"The wallet address does not match your session"}""", 401))
        stack.bus.events.test {
            val e = runCatching { repo.refreshSummary(Fixtures.FEM) }.exceptionOrNull() as DashboardException
            assertEquals("WALLET_MISMATCH", e.code)
            expectNoEvents()
        }
    }

    @Test
    fun `a dropped connection is NETWORK_ERROR and a non-JSON success body is BAD_RESPONSE`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals("NETWORK_ERROR", (runCatching { repo.activity() }.exceptionOrNull() as DashboardException).code)

        server.enqueue(MockResponse().setBody("<html>bad gateway</html>"))
        assertEquals("BAD_RESPONSE", (runCatching { repo.activity() }.exceptionOrNull() as DashboardException).code)
    }

    @Test
    fun `every rewards call requires a signed-in address`() = runTest {
        address = null
        for (call in listOf<suspend () -> Any?>(
            { repo.refreshAssetTotals() },
            { repo.refreshSummaries(listOf(Fixtures.FEM)) },
            { repo.refreshSummary(Fixtures.FEM) },
            { repo.historyPage(Fixtures.FEM, 1) },
            { repo.activity() },
            { repo.claimPreview(Fixtures.FEM) },
            { repo.claimEnvelope(Fixtures.FEM) },
            { repo.claimConfirm("g", "s") },
            { repo.claimConfirmCustodial("t") },
        )) {
            val e = runCatching { call() }.exceptionOrNull()
            assertNotNull(e)
            assertEquals("UNAUTHORIZED", (e as DashboardException).code)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `clearCache empties both reward caches`() = runTest {
        server.enqueue(Fixtures.json("asset-totals"))
        server.enqueue(Fixtures.json("reward-summary-batch"))
        repo.refreshAssetTotals()
        repo.refreshSummaries(listOf(Fixtures.FEM, Fixtures.RDN))
        assertNotNull(totalsDao.row.value)
        assertEquals(2, summaryDao.rows.value.size)
        repo.clearCache()
        assertNull(totalsDao.row.value)
        assertTrue(summaryDao.rows.value.isEmpty())
    }
}
