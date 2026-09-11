package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.data.Device
import com.frynetworks.fryapp.data.DeviceDao
import com.frynetworks.fryapp.data.dashboard.api.AlgodApi
import com.frynetworks.fryapp.data.dashboard.api.DashboardApi
import com.frynetworks.fryapp.data.dashboard.db.AssetTotalsCacheDao
import com.frynetworks.fryapp.data.dashboard.db.AssetTotalsCacheEntity
import com.frynetworks.fryapp.data.dashboard.db.MinerDetailCacheDao
import com.frynetworks.fryapp.data.dashboard.db.MinerDetailCacheEntity
import com.frynetworks.fryapp.data.dashboard.db.RemoteMinerDao
import com.frynetworks.fryapp.data.dashboard.db.RemoteMinerEntity
import com.frynetworks.fryapp.data.dashboard.db.RewardSummaryCacheDao
import com.frynetworks.fryapp.data.dashboard.db.RewardSummaryCacheEntity
import com.frynetworks.fryapp.network.dashboard.DashboardConfig
import com.frynetworks.fryapp.network.dashboard.FingerprintRetryInterceptor
import com.frynetworks.fryapp.network.dashboard.HeaderPinInterceptor
import com.frynetworks.fryapp.network.dashboard.SecurityHeaderInterceptor
import com.frynetworks.fryapp.network.dashboard.SecurityHeaderSigner
import com.frynetworks.fryapp.network.dashboard.ServerClock
import com.frynetworks.fryapp.network.dashboard.SessionEventBus
import com.frynetworks.fryapp.network.dashboard.cookies.CookieStore
import com.frynetworks.fryapp.network.dashboard.cookies.PersistentCookieJar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Shared fixtures/fakes for the repository tests (hand-written, no MockK). */
internal object Fixtures {
    const val ADDRESS = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    const val FEM = "FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    const val RDN = "RDN-BCDEFGHIJKLMNOPQRSTUVWXYZ0123456"

    fun load(name: String): String =
        Fixtures::class.java.classLoader!!.getResource("fixtures/dashboard/$name.json")!!.readText()

    fun json(name: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(load(name))

    fun body(json: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(json)
}

internal class MemoryCookieStore : CookieStore {
    var saved: List<String> = emptyList()
    override fun load() = saved
    override fun save(serialized: List<String>) { saved = serialized }
}

/**
 * The production OkHttp stack — cookie jar + header pin + security-header signing + fingerprint
 * retry (rebind always fails, so 409/403 pass through) — pointed at a [MockWebServer].
 */
internal class DashboardStack(server: MockWebServer) {
    val userAgent: String = DashboardConfig.USER_AGENT
    val signer = SecurityHeaderSigner(DashboardConfig.SIGNATURE_SECRET)
    val clock = ServerClock()
    val bus = SessionEventBus()
    val cookieStore = MemoryCookieStore()
    val jar = PersistentCookieJar(cookieStore, host = server.hostName)
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .addInterceptor(HeaderPinInterceptor())
        .addInterceptor(SecurityHeaderInterceptor(signer, clock))
        .addInterceptor(FingerprintRetryInterceptor(rebind = { false }, onSessionEvent = { bus.emit(it) }))
        .build()
    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    val api: DashboardApi = retrofit.create(DashboardApi::class.java)
    val algod: AlgodApi = retrofit.create(AlgodApi::class.java)

    /** Asserts a POST to [path] carried exactly [expectedBody] and the three security headers computed over it. */
    fun assertSignedPost(r: RecordedRequest, path: String, expectedBody: String) {
        assertEquals("POST", r.method)
        assertEquals(path, r.path)
        val sent = r.body.readUtf8()
        assertEquals(expectedBody, sent)
        assertEquals("application/json", r.getHeader("Content-Type"))
        assertEquals(signer.clientToken(userAgent), r.getHeader("x-client-token"))
        val tsHeader = r.getHeader("x-request-timestamp")
        assertNotNull("x-request-timestamp missing on $path", tsHeader)
        val ts = tsHeader!!.toLong()
        assertTrue("timestamp $ts not within 5 s of now", kotlin.math.abs(ts - System.currentTimeMillis() / 1000) < 5)
        assertEquals(signer.signature("POST", path, sent, ts), r.getHeader("x-request-signature"))
    }

    /** Asserts a POST to [path] carried exactly [expectedBody] and NO security headers. */
    fun assertUnsignedPost(r: RecordedRequest, path: String, expectedBody: String) {
        assertEquals("POST", r.method)
        assertEquals(path, r.path)
        assertEquals(expectedBody, r.body.readUtf8())
        assertEquals("application/json", r.getHeader("Content-Type"))
        assertNoSecurityHeaders(r)
    }

    fun assertNoSecurityHeaders(r: RecordedRequest) {
        assertNull(r.getHeader("x-client-token"))
        assertNull(r.getHeader("x-request-signature"))
        assertNull(r.getHeader("x-request-timestamp"))
    }
}

// --- in-memory DAO fakes (the Room interfaces are plain Kotlin interfaces) ---

internal class FakeRemoteMinerDao : RemoteMinerDao {
    val rows = MutableStateFlow<Map<String, RemoteMinerEntity>>(emptyMap())
    override fun observeAll(): Flow<List<RemoteMinerEntity>> = rows.map { it.values.sortedBy { r -> r.minerKey } }
    override suspend fun getAllOnce(): List<RemoteMinerEntity> = rows.value.values.sortedBy { it.minerKey }
    override suspend fun upsertAll(rows: List<RemoteMinerEntity>) { this.rows.value = this.rows.value + rows.associateBy { it.minerKey } }
    override suspend fun deleteNotIn(keep: List<String>) { rows.value = rows.value.filterKeys { it in keep } }
    override suspend fun updateNickname(minerKey: String, nickname: String) {
        rows.value = rows.value.mapValues { if (it.key == minerKey) it.value.copy(nickname = nickname) else it.value }
    }
    override suspend fun deleteAll() { rows.value = emptyMap() }
}

internal class FakeMinerDetailCacheDao : MinerDetailCacheDao {
    val rows = MutableStateFlow<Map<String, MinerDetailCacheEntity>>(emptyMap())
    override fun observe(minerKey: String): Flow<MinerDetailCacheEntity?> = rows.map { it[minerKey] }
    override suspend fun upsert(row: MinerDetailCacheEntity) { rows.value = rows.value + (row.minerKey to row) }
    override suspend fun upsertAll(rows: List<MinerDetailCacheEntity>) { this.rows.value = this.rows.value + rows.associateBy { it.minerKey } }
    override suspend fun deleteAll() { rows.value = emptyMap() }
}

internal class FakeRewardSummaryCacheDao : RewardSummaryCacheDao {
    val rows = MutableStateFlow<Map<String, RewardSummaryCacheEntity>>(emptyMap())
    override fun observe(minerKey: String): Flow<RewardSummaryCacheEntity?> = rows.map { it[minerKey] }
    override fun observeAll(): Flow<List<RewardSummaryCacheEntity>> = rows.map { it.values.sortedBy { r -> r.minerKey } }
    override suspend fun upsertAll(rows: List<RewardSummaryCacheEntity>) { this.rows.value = this.rows.value + rows.associateBy { it.minerKey } }
    override suspend fun deleteAll() { rows.value = emptyMap() }
}

internal class FakeAssetTotalsCacheDao : AssetTotalsCacheDao {
    val row = MutableStateFlow<AssetTotalsCacheEntity?>(null)
    override fun observe(): Flow<AssetTotalsCacheEntity?> = row
    override suspend fun upsert(row: AssetTotalsCacheEntity) { this.row.value = row }
    override suspend fun deleteAll() { row.value = null }
}

internal class FakeDeviceDao : DeviceDao {
    val rows = MutableStateFlow<Map<String, Device>>(emptyMap())
    override fun observeAll(): Flow<List<Device>> = rows.map { it.values.sortedByDescending { d -> d.lastSeen } }
    override suspend fun getAllOnce(): List<Device> = rows.value.values.sortedByDescending { it.lastSeen }
    override suspend fun upsert(device: Device) { rows.value = rows.value + (device.minerKey to device) }
    override suspend fun delete(device: Device) { rows.value = rows.value - device.minerKey }
    override suspend fun deleteByMinerKey(minerKey: String) { rows.value = rows.value - minerKey }
}
