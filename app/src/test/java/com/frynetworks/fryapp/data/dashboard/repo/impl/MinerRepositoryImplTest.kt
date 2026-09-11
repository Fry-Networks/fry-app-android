package com.frynetworks.fryapp.data.dashboard.repo.impl

import app.cash.turbine.test
import com.frynetworks.fryapp.data.Device
import com.frynetworks.fryapp.data.Transport
import com.frynetworks.fryapp.data.dashboard.repo.FleetSummary
import com.frynetworks.fryapp.data.dashboard.repo.MinerSource
import com.frynetworks.fryapp.data.dashboard.repo.MinerStatus
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.network.dashboard.SessionEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * [MinerRepositoryImpl] against the real OkHttp/Retrofit stack and a MockWebServer: every route,
 * its exact body bytes (key order), the signed/unsigned header rule, the refresh sequence, and the
 * cache/merge behaviour observed through the in-memory DAO fakes.
 */
class MinerRepositoryImplTest {

    private val server = MockWebServer()
    private lateinit var stack: DashboardStack
    private val recorded = mutableListOf<RecordedRequest>()
    private val responses = HashMap<String, () -> MockResponse>()
    private var address: String? = Fixtures.ADDRESS

    private val remoteDao = FakeRemoteMinerDao()
    private val detailDao = FakeMinerDetailCacheDao()
    private val summaryDao = FakeRewardSummaryCacheDao()
    private val totalsDao = FakeAssetTotalsCacheDao()
    private val deviceDao = FakeDeviceDao()
    private var now = 1_757_548_800_000L

    private lateinit var rewards: RewardsRepositoryImpl
    private lateinit var repo: MinerRepositoryImpl

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                return responses[request.path]?.invoke() ?: MockResponse().setResponseCode(404).setBody("""{"success":false,"code":"NOT_FOUND"}""")
            }
        }
        server.start()
        stack = DashboardStack(server)
        responses["/api/devices/list"] = { Fixtures.json("miner-list") }
        responses["/api/devices/batch"] = { Fixtures.json("batch-devices") }
        responses["/api/rewards/get-reward-summary-batch"] = { Fixtures.json("reward-summary-batch") }
        responses["/api/rewards/get-asset-totals"] = { Fixtures.json("asset-totals") }
        responses["/api/devices/status-summary"] = { Fixtures.json("status-summary") }
        responses["/api/hardware/status"] = { Fixtures.json("hardware-status") }
        rewards = RewardsRepositoryImpl(stack.api, summaryDao, totalsDao, { address }, stack.bus, now = { now })
        repo = MinerRepositoryImpl(stack.api, remoteDao, detailDao, summaryDao, deviceDao, rewards, { address }, stack.bus, now = { now })
    }

    @After fun stop() = server.shutdown()

    private fun localDevice(key: String, name: String = "Porch") =
        Device(minerKey = key, name = name, chip = "ESP32", fwVersion = "0.2.0", wallet = Fixtures.ADDRESS, transport = Transport.BLE, lastSeen = now - 1_000L, status = 1)

    // --- refresh ---

    @Test
    fun `refresh runs list, batch, summary-batch, asset-totals, status-summary, hardware-status in order with exact bodies`() = runTest {
        repo.refresh()

        assertEquals(
            listOf("/api/devices/list", "/api/devices/batch", "/api/rewards/get-reward-summary-batch", "/api/rewards/get-asset-totals", "/api/devices/status-summary", "/api/hardware/status"),
            recorded.map { it.path },
        )
        stack.assertUnsignedPost(recorded[0], "/api/devices/list", """{"address":"${Fixtures.ADDRESS}"}""")
        stack.assertUnsignedPost(recorded[1], "/api/devices/batch", """{"miner_keys":["${Fixtures.FEM}","${Fixtures.RDN}"]}""")
        stack.assertSignedPost(recorded[2], "/api/rewards/get-reward-summary-batch", """{"miner_keys":["${Fixtures.FEM}","${Fixtures.RDN}"]}""")
        stack.assertSignedPost(recorded[3], "/api/rewards/get-asset-totals", "{}")
        stack.assertUnsignedPost(recorded[4], "/api/devices/status-summary", """{"address":"${Fixtures.ADDRESS}"}""")
        stack.assertUnsignedPost(recorded[5], "/api/hardware/status", """{"miner_keys":["${Fixtures.FEM}","${Fixtures.RDN}"]}""")
    }

    @Test
    fun `refresh fills every cache and the fleet summary`() = runTest {
        repo.refresh()

        assertEquals(setOf(Fixtures.FEM, Fixtures.RDN), remoteDao.rows.value.keys)
        assertEquals("Backyard FEM", remoteDao.rows.value[Fixtures.FEM]?.nickname)
        assertEquals("pending", remoteDao.rows.value[Fixtures.RDN]?.status)
        assertEquals(now, remoteDao.rows.value[Fixtures.FEM]?.fetchedAt)
        assertEquals(setOf(Fixtures.FEM, Fixtures.RDN), detailDao.rows.value.keys)
        assertEquals(setOf(Fixtures.FEM, Fixtures.RDN), summaryDao.rows.value.keys)
        assertNotNull(totalsDao.row.value)
        assertEquals(FleetSummary(online = 1, total = 2), repo.observeFleetSummary().first())
        assertEquals(true, repo.lastHardwareStatus.value[Fixtures.FEM]?.linked)
        assertEquals("NOT_LINKED", repo.lastHardwareStatus.value[Fixtures.RDN]?.reason)
    }

    @Test
    fun `refresh trims dashboard rows the wallet no longer owns but never touches the local devices table`() = runTest {
        remoteDao.upsertAll(listOf(com.frynetworks.fryapp.data.dashboard.db.RemoteMinerEntity("FEM-GONE", null, null, "active", true, false, true, true, true, false, false, false, 1L)))
        deviceDao.upsert(localDevice("IOT-LOCAL"))

        repo.refresh()

        assertEquals(setOf(Fixtures.FEM, Fixtures.RDN), remoteDao.rows.value.keys)
        assertEquals(setOf("IOT-LOCAL"), deviceDao.rows.value.keys)
    }

    @Test
    fun `refresh only asks hardware-status for hardware families`() = runTest {
        responses["/api/devices/list"] = {
            Fixtures.body("""{"success":true,"miner_keys":[{"miner_key":"IHAQM-AAAA","status":"active"},{"miner_key":"IOT-BBBB","status":"active"},{"miner_key":"BM-CCCC","status":"active"}]}""")
        }
        responses["/api/devices/batch"] = { Fixtures.body("""{"success":true,"devices":{}}""") }
        responses["/api/hardware/status"] = { Fixtures.body("""{"BM-CCCC":{"linked":true,"valid":true}}""") }

        repo.refresh()

        val hw = recorded.last()
        stack.assertUnsignedPost(hw, "/api/hardware/status", """{"miner_keys":["BM-CCCC"]}""")
        assertEquals(mapOf("BM-CCCC" to true), repo.lastHardwareStatus.value.mapValues { it.value.linked })
    }

    @Test
    fun `refresh skips hardware-status and summaries entirely for a wallet with no hardware miners`() = runTest {
        responses["/api/devices/list"] = { Fixtures.body("""{"success":true,"miner_keys":[]}""") }
        repo.refresh()
        assertEquals(listOf("/api/devices/list", "/api/rewards/get-asset-totals", "/api/devices/status-summary"), recorded.map { it.path })
        assertTrue(remoteDao.rows.value.isEmpty())
    }

    @Test
    fun `refresh chunks batch at 200 keys and summaries at 50 keys, sequentially`() = runTest {
        val keys = (1..201).map { "FEM-" + it.toString().padStart(6, '0') }
        responses["/api/devices/list"] = {
            Fixtures.body("""{"success":true,"miner_keys":[${keys.joinToString(",") { """{"miner_key":"$it","status":"active"}""" }}]}""")
        }
        responses["/api/devices/batch"] = { Fixtures.body("""{"success":true,"devices":{}}""") }
        responses["/api/rewards/get-reward-summary-batch"] = { Fixtures.body("""{"success":true,"summaries":{}}""") }
        responses["/api/hardware/status"] = { Fixtures.body("{}") }

        repo.refresh()

        val batches = recorded.filter { it.path == "/api/devices/batch" }
        assertEquals(2, batches.size)
        val summaries = recorded.filter { it.path == "/api/rewards/get-reward-summary-batch" }
        assertEquals(5, summaries.size)
        // every batch call finished before the first summary call — no interleaving / fan-out
        assertTrue(recorded.indexOfLast { it.path == "/api/devices/batch" } < recorded.indexOfFirst { it.path == "/api/rewards/get-reward-summary-batch" })
        assertEquals(200, com.google.gson.JsonParser.parseString(batches[0].body.readUtf8()).asJsonObject["miner_keys"].asJsonArray.size())
        assertEquals(1, com.google.gson.JsonParser.parseString(batches[1].body.readUtf8()).asJsonObject["miner_keys"].asJsonArray.size())
        assertEquals(listOf(50, 50, 50, 50, 1), summaries.map { com.google.gson.JsonParser.parseString(it.body.readUtf8()).asJsonObject["miner_keys"].asJsonArray.size() })
    }

    @Test
    fun `a 503 ACTIVITY_UNAVAILABLE status-summary yields a null fleet summary, not an error`() = runTest {
        responses["/api/devices/status-summary"] = {
            Fixtures.body("""{"success":false,"code":"ACTIVITY_UNAVAILABLE","message":"Device status is temporarily unavailable","total":2}""", 503)
        }
        repo.refresh()
        assertNull(repo.observeFleetSummary().first())
        assertEquals("/api/hardware/status", recorded.last().path) // refresh carried on
    }

    @Test
    fun `refresh throws UNAUTHORIZED without a network call when signed out`() = runTest {
        address = null
        val e = runCatching { repo.refresh() }.exceptionOrNull() as DashboardException
        assertEquals("UNAUTHORIZED", e.code)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a 401 from the dashboard surfaces the envelope and emits SessionEvent Expired`() = runTest {
        responses["/api/devices/list"] = { Fixtures.body("""{"success":false,"code":"SESSION_REQUIRED","message":"Your session has expired","action":"Please sign in again to continue"}""", 401) }
        stack.bus.events.test {
            val e = runCatching { repo.refresh() }.exceptionOrNull() as DashboardException
            assertEquals("SESSION_REQUIRED", e.code)
            assertEquals("Your session has expired", e.message)
            assertEquals("Please sign in again to continue", e.action)
            assertEquals(401, e.httpStatus)
            assertEquals(SessionEvent.Expired, awaitItem())
        }
    }

    @Test
    fun `a dropped connection maps to NETWORK_ERROR`() = runTest {
        // Dispatcher-served responses only honour socket policies applied after the request is read.
        responses["/api/devices/list"] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val e = runCatching { repo.refresh() }.exceptionOrNull() as DashboardException
        assertEquals("NETWORK_ERROR", e.code)
        assertEquals(0, e.httpStatus)
    }

    // --- observeMiners ---

    @Test
    fun `observeMiners emits the merged view immediately from cache and again after refresh`() = runTest {
        deviceDao.upsert(localDevice("IOT-LOCAL", "Porch"))
        repo.observeMiners().test {
            val initial = awaitItem()
            assertEquals(listOf("IOT-LOCAL"), initial.map { it.minerKey })
            assertEquals(MinerStatus.NOT_ON_DASHBOARD, initial[0].status)
            assertEquals(MinerSource.LOCAL, initial[0].source)

            repo.refresh()

            var latest = awaitItem()
            while (latest.size < 3 || latest.any { it.minerKey == Fixtures.FEM && it.rewards == null }) latest = awaitItem()
            assertEquals(listOf(Fixtures.RDN, "IOT-LOCAL", Fixtures.FEM), latest.map { it.minerKey })
            val fem = latest.first { it.minerKey == Fixtures.FEM }
            assertEquals("Backyard FEM", fem.displayName)
            assertEquals(MinerSource.REMOTE, fem.source)
            assertEquals(0, BigDecimal("4.25").compareTo(fem.rewards?.claimable))
            assertEquals("Porch", latest.first { it.minerKey == "IOT-LOCAL" }.displayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- detail ---

    @Test
    fun `refreshDetail posts the session address to devices slash key and caches the raw document`() = runTest {
        responses["/api/devices/${Fixtures.FEM}"] = { Fixtures.json("device-detail") }
        repo.observeDetail(Fixtures.FEM).test {
            assertNull(awaitItem())
            val detail = repo.refreshDetail(Fixtures.FEM)
            assertEquals("BYOD-LICENSE-0042", detail.byod)
            stack.assertUnsignedPost(recorded.single(), "/api/devices/${Fixtures.FEM}", """{"address":"${Fixtures.ADDRESS}"}""")
            val cached = awaitItem()
            assertEquals("Backyard FEM", cached?.nickname)
            assertEquals("tolerated", cached?.raw?.get("some_future_field_not_yet_modelled")?.asString)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshDetail without a device in the answer is DEVICE_NOT_FOUND`() = runTest {
        responses["/api/devices/${Fixtures.FEM}"] = { Fixtures.body("""{"success":true}""") }
        val e = runCatching { repo.refreshDetail(Fixtures.FEM) }.exceptionOrNull() as DashboardException
        assertEquals("DEVICE_NOT_FOUND", e.code)
    }

    // --- other routes ---

    @Test
    fun `hardwareStatus accepts both the real flat map and the statuses envelope`() = runTest {
        responses["/api/hardware/status"] = { Fixtures.body("""{"${Fixtures.FEM}":{"linked":true,"valid":false,"reason":"missing_mac"},"${Fixtures.RDN}":{"linked":false,"valid":false}}""") }
        val flat = repo.hardwareStatus(listOf(Fixtures.FEM, Fixtures.RDN))
        assertEquals("missing_mac", flat[Fixtures.FEM]?.reason)
        assertEquals(false, flat[Fixtures.RDN]?.linked)

        responses["/api/hardware/status"] = { Fixtures.json("hardware-status") }
        val enveloped = repo.hardwareStatus(listOf(Fixtures.FEM, Fixtures.RDN))
        assertEquals(true, enveloped[Fixtures.FEM]?.macMatch)
        assertEquals(2, enveloped.size)

        assertTrue(repo.hardwareStatus(emptyList()).isEmpty())
        assertEquals(2, recorded.size)
    }

    @Test
    fun `myKeys is a plain GET`() = runTest {
        responses["/api/my-keys"] = { Fixtures.json("my-keys") }
        val keys = repo.myKeys()
        assertEquals(listOf("BYOD-LICENSE-0042"), keys.byodLicenses)
        assertEquals("GET", recorded.single().method)
        assertEquals("/api/my-keys", recorded.single().path)
        stack.assertNoSecurityHeaders(recorded.single())
    }

    @Test
    fun `myKeys maps the real nested iotCredentials object into the modelled credentials list`() = runTest {
        responses["/api/my-keys"] = {
            Fixtures.body(
                """{"success":true,"devices":[{"miner_key":"IHAQM-CDEF","nickname":"Air","is_registered":true,"iotCredentials":{"air":{"miner_type":"IHAQM","api_type":"purpleair","credentials":{"api_key":"REDACTED-TOKEN-VALUE","sensor_index":"12345"},"credentials_saved_at":"2026-05-20T08:00:00.000Z","position":null,"position_saved_at":null}}},{"miner_key":"${Fixtures.FEM}","nickname":"Backyard FEM","is_registered":true}],"byodLicenses":[]}""",
            )
        }
        val keys = repo.myKeys()
        val air = keys.devices.first { it.minerKey == "IHAQM-CDEF" }
        assertEquals(1, air.credentials.size)
        assertEquals("air", air.credentials[0].portal)
        assertEquals("REDACTED-TOKEN-VALUE", air.credentials[0].credentials["api_key"])
        assertEquals("2026-05-20T08:00:00.000Z", air.credentials[0].credentialsSavedAt)
        assertTrue(keys.devices.first { it.minerKey == Fixtures.FEM }.credentials.isEmpty())
    }

    @Test
    fun `product posts miner_key and unwraps the first data entry, null when the catalog is empty`() = runTest {
        responses["/api/products/get-product"] = { Fixtures.json("product") }
        val product = repo.product(Fixtures.FEM)
        assertEquals("FEM", product?.key)
        assertEquals(0, BigDecimal("40.0").compareTo(product?.reward?.stake?.register))
        stack.assertUnsignedPost(recorded.single(), "/api/products/get-product", """{"miner_key":"${Fixtures.FEM}"}""")

        responses["/api/products/get-product"] = { Fixtures.body("""{"success":true,"data":[]}""") }
        assertNull(repo.product(Fixtures.FEM))
    }

    @Test
    fun `rename POSTs address, name, miner_key in that order and updates the cached nickname`() = runTest {
        repo.refresh()
        recorded.clear()
        responses["/api/change-name"] = { Fixtures.body("""{"message":"ok"}""") }

        repo.rename(Fixtures.FEM, "Roof FEM")

        stack.assertUnsignedPost(recorded.single(), "/api/change-name", """{"address":"${Fixtures.ADDRESS}","name":"Roof FEM","miner_key":"${Fixtures.FEM}"}""")
        assertEquals("Roof FEM", remoteDao.rows.value[Fixtures.FEM]?.nickname)
    }

    @Test
    fun `setRewardWallet POSTs miner_key, reward_wallet, address in that order`() = runTest {
        responses["/api/devices/save-wallet-info"] = { Fixtures.body("""{"success":true}""") }
        repo.setRewardWallet(Fixtures.FEM, Fixtures.ADDRESS)
        stack.assertUnsignedPost(recorded.single(), "/api/devices/save-wallet-info", """{"miner_key":"${Fixtures.FEM}","reward_wallet":"${Fixtures.ADDRESS}","address":"${Fixtures.ADDRESS}"}""")
    }

    @Test
    fun `the session cookie set by one answer is sent on the next request`() = runTest {
        responses["/api/devices/list"] = {
            Fixtures.json("miner-list").addHeader("Set-Cookie", "__Secure-next-auth.session-token=jwt-123; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax")
        }
        repo.refresh()
        assertNull(recorded[0].getHeader("Cookie"))
        assertTrue(recorded[1].getHeader("Cookie")!!.contains("__Secure-next-auth.session-token=jwt-123"))
    }

    @Test
    fun `clearCache drops the dashboard caches and fleet summary but keeps local devices`() = runTest {
        deviceDao.upsert(localDevice("IOT-LOCAL"))
        repo.refresh()
        rewards.clearCache()
        repo.clearCache()
        assertTrue(remoteDao.rows.value.isEmpty())
        assertTrue(detailDao.rows.value.isEmpty())
        assertTrue(summaryDao.rows.value.isEmpty())
        assertNull(totalsDao.row.value)
        assertNull(repo.observeFleetSummary().first())
        assertEquals(setOf("IOT-LOCAL"), deviceDao.rows.value.keys)
    }
}
