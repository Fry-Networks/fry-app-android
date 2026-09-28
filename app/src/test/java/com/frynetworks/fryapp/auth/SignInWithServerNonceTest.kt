package com.frynetworks.fryapp.auth

import com.frynetworks.fryapp.di.DashboardModule
import com.frynetworks.fryapp.network.dashboard.DashboardConfig
import com.frynetworks.fryapp.network.dashboard.HeaderPinInterceptor
import com.frynetworks.fryapp.network.dashboard.cookies.CookieStore
import com.frynetworks.fryapp.network.dashboard.cookies.PersistentCookieJar
import com.frynetworks.fryapp.wallet.BridgeEvent
import com.frynetworks.fryapp.wallet.TxnSummary
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletAccount
import com.frynetworks.fryapp.wallet.WalletBridge
import com.frynetworks.fryapp.wallet.WalletVendor
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

/**
 * The production dashboard (registration_portal lib/auth.ts, D8) only accepts a login nonce it
 * issued itself via `POST /api/auth/nonce`, once, for the same address. The app is built exactly
 * as production wires it ([DashboardModule.provideSignInUseCase]) and signs in against a server
 * that enforces that rule.
 */
class SignInWithServerNonceTest {

    /** Signs by base64-encoding the unsigned txn so the server can read the note back. */
    private class NoteEchoBridge(private val address: String) : WalletBridge {
        override val events: Flow<BridgeEvent> = emptyFlow()
        override suspend fun connect(vendor: WalletVendor) = WalletAccount(address, vendor)
        override suspend fun reconnect(vendor: WalletVendor): WalletAccount? = null
        override suspend fun disconnect() {}
        override suspend fun buildPayment(sender: String, receiver: String, amountMicro: Long, noteUtf8: String?): String =
            "pay|$sender|$receiver|$amountMicro|${noteUtf8 ?: ""}"
        override suspend fun buildAssetTransfer(sender: String, receiver: String, assetId: Long, amountMicro: Long, noteUtf8: String?) = "axfer"
        override suspend fun buildOptIn(sender: String, assetId: Long) = "optin"
        override suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>> =
            groups.map { g -> g.map { if (it.sign) Base64.getEncoder().encodeToString(it.txnB64.toByteArray()) else null } }
        override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> = signedB64
        override suspend fun decodeTxn(txnB64: String) = TxnSummary("pay", address, address, 0, null, null, "id")
    }

    private class Cookies : CookieStore {
        var saved: List<String> = emptyList()
        override fun load() = saved
        override fun save(serialized: List<String>) { saved = serialized }
    }

    private class Profiles : SessionStore {
        var profile: SessionProfile? = null
        override fun load(): SessionProfile? = profile
        override fun save(profile: SessionProfile?) { this.profile = profile }
    }

    private enum class NonceRoute { SERVER, MISSING, BROKEN }

    private val server = MockWebServer()
    private val address = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val recorded = mutableListOf<RecordedRequest>()
    private val issued = mutableMapOf<String, String>() // nonce -> address
    private val used = mutableSetOf<String>()
    private var route = NonceRoute.SERVER
    private var issuedCount = 0

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                return when (request.path) {
                    "/api/auth/nonce" -> when (route) {
                        NonceRoute.MISSING -> MockResponse().setResponseCode(404)
                        NonceRoute.BROKEN -> MockResponse().setResponseCode(503).setBody("""{"success":false,"code":"UNAVAILABLE"}""")
                        NonceRoute.SERVER -> {
                            val body = JsonParser.parseString(request.body.clone().readUtf8()).asJsonObject
                            val nonce = "srvNonce${++issuedCount}x"
                            issued[nonce] = body["address"].asString
                            MockResponse().setBody("""{"nonce":"$nonce","expiresAt":"2026-09-28T20:00:00.000Z"}""")
                        }
                    }
                    "/api/check-user" -> MockResponse().setBody("""{"isNew":false}""")
                    "/api/auth/csrf" -> MockResponse().setBody("""{"csrfToken":"csrf-${recorded.size}"}""")
                        .addHeader("Set-Cookie", "__Host-next-auth.csrf-token=csrfcookie; Path=/; HttpOnly; SameSite=Lax")
                    "/api/auth/callback/wallet" -> callback(form(request))
                    "/api/auth/capture-fingerprint" -> MockResponse().setBody("""{"success":true,"fingerprint":"fp-abc","userAgent":"${DashboardConfig.USER_AGENT}"}""")
                    "/api/auth/session" -> MockResponse().setBody("""{"user":{"id":"u1","address":"$address"},"deviceFingerprint":"fp-abc","userAgent":"${DashboardConfig.USER_AGENT}"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    /** lib/auth.ts verifySignature: note must carry the nonce; the nonce must be server-issued, unused, same address. */
    private fun callback(f: Map<String, String>): MockResponse {
        val nonce = f["nonce"].orEmpty()
        val signed = String(Base64.getDecoder().decode(f["signedTxn"].orEmpty()))
        val noteOk = signed.endsWith("|" + SignInUseCase.NONCE_MESSAGE_PREFIX + nonce)
        val acceptLegacy = route == NonceRoute.MISSING // an older dashboard without the nonce store
        val nonceOk = acceptLegacy || (issued[nonce] == f["address"] && used.add(nonce))
        return if (noteOk && nonceOk) {
            MockResponse().setBody("""{"url":"https://dashboard.frynetworks.com/"}""")
                .addHeader("Set-Cookie", "__Secure-next-auth.session-token=jwt-123; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax")
        } else {
            MockResponse().setBody("""{"url":"https://dashboard.frynetworks.com/signin?error=CredentialsSignin"}""")
        }
    }

    private fun form(r: RecordedRequest): Map<String, String> =
        r.body.clone().readUtf8().split("&").associate { kv -> kv.substringBefore("=") to URLDecoder.decode(kv.substringAfter("="), "UTF-8") }

    private fun productionUseCase(): SignInUseCase {
        val jar = PersistentCookieJar(Cookies(), host = server.hostName)
        val client = OkHttpClient.Builder().cookieJar(jar).addInterceptor(HeaderPinInterceptor()).build()
        val api = NextAuthApi.create(client, server.url("/").toString())
        val session = SessionRepository(api, jar, Profiles())
        return DashboardModule.provideSignInUseCase(NoteEchoBridge(address), api, FingerprintBinder(api), session)
    }

    private fun paths() = recorded.map { it.path }

    @Test
    fun `sign-in uses the nonce the dashboard issued and the dashboard accepts it`() = runTest {
        val result = productionUseCase().signIn(WalletVendor.PERA, profile = null)

        assertTrue("expected Success, got $result", result is SignInResult.Success)
        assertTrue("nonce must be requested before the callback: ${paths()}", paths().indexOf("/api/auth/nonce") in 0 until paths().indexOf("/api/auth/callback/wallet"))
        val nonceBody = JsonParser.parseString(recorded.first { it.path == "/api/auth/nonce" }.body.clone().readUtf8()).asJsonObject
        assertEquals(address, nonceBody["address"].asString)
        assertEquals("srvNonce1x", form(recorded.first { it.path == "/api/auth/callback/wallet" })["nonce"])
    }

    @Test
    fun `every sign-in fetches a fresh single-use nonce`() = runTest {
        val first = productionUseCase().signIn(WalletVendor.PERA, profile = null)
        val second = productionUseCase().signIn(WalletVendor.PERA, profile = null)

        assertTrue("expected Success, got $first", first is SignInResult.Success)
        assertTrue("expected Success, got $second", second is SignInResult.Success)
        assertEquals(setOf("srvNonce1x", "srvNonce2x"), used)
    }

    @Test
    fun `a dashboard that cannot issue a nonce fails the sign-in without falling back to a local nonce`() = runTest {
        route = NonceRoute.BROKEN
        val result = productionUseCase().signIn(WalletVendor.PERA, profile = null)

        assertTrue("expected Failure, got $result", result is SignInResult.Failure)
        assertEquals("NONCE_UNAVAILABLE", (result as SignInResult.Failure).code)
        assertFalse("no proof may be posted without a server nonce: ${paths()}", "/api/auth/callback/wallet" in paths())
    }

    @Test
    fun `an older dashboard without the nonce route still signs in with a local nonce`() = runTest {
        route = NonceRoute.MISSING
        val result = productionUseCase().signIn(WalletVendor.PERA, profile = null)

        assertTrue("expected Success, got $result", result is SignInResult.Success)
        val nonce = form(recorded.first { it.path == "/api/auth/callback/wallet" })["nonce"].orEmpty()
        assertTrue("legacy nonce is 0..999999, got $nonce", nonce.toIntOrNull() in 0..999_999)
    }
}
