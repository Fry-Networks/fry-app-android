package com.frynetworks.fryapp.auth

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/** Hand-written fakes (no MockK): a wallet bridge that signs whatever it is given, and an
 *  in-memory cookie/session store. */
private class FakeWalletBridge(private val address: String) : WalletBridge {
    var lastPayment: Map<String, Any?> = emptyMap()
    var signCalls = 0
    override val events: Flow<BridgeEvent> = emptyFlow()
    override suspend fun connect(vendor: WalletVendor) = WalletAccount(address, vendor)
    override suspend fun reconnect(vendor: WalletVendor): WalletAccount? = null
    override suspend fun disconnect() {}
    override suspend fun buildPayment(sender: String, receiver: String, amountMicro: Long, noteUtf8: String?): String {
        lastPayment = mapOf("sender" to sender, "receiver" to receiver, "amountMicro" to amountMicro, "note" to noteUtf8)
        return "dW5zaWduZWQ="
    }
    override suspend fun buildAssetTransfer(sender: String, receiver: String, assetId: Long, amountMicro: Long, noteUtf8: String?) = "axfer"
    override suspend fun buildOptIn(sender: String, assetId: Long) = "optin"
    override suspend fun signTxns(groups: List<List<TxnToSign>>): List<List<String?>> { signCalls++; return groups.map { g -> g.map { if (it.sign) "c2lnbmVk" else null } } }
    override suspend fun submit(signedB64: List<String>, waitRounds: Int): List<String> = signedB64.map { "TX$it" }
    override suspend fun decodeTxn(txnB64: String) = TxnSummary("pay", address, address, 0, null, null, "id")
}

private class MemoryCookieStore : CookieStore {
    var saved: List<String> = emptyList()
    override fun load() = saved
    override fun save(serialized: List<String>) { saved = serialized }
}

private class MemorySessionStore : SessionStore {
    var profile: SessionProfile? = null
    override fun load(): SessionProfile? = profile
    override fun save(profile: SessionProfile?) { this.profile = profile }
}

class SignInUseCaseTest {

    private val server = MockWebServer()
    private val address = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val recorded = mutableListOf<RecordedRequest>()
    private var isNew = false
    private var callbackUrl = "https://dashboard.frynetworks.com/"
    private val cookieStore = MemoryCookieStore()
    private val sessionStore = MemorySessionStore()

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                return when (request.path) {
                    "/api/check-user" -> MockResponse().setBody("""{"isNew":$isNew}""")
                    "/api/auth/csrf" -> MockResponse().setBody("""{"csrfToken":"csrf-${recorded.size}"}""")
                        .addHeader("Set-Cookie", "__Host-next-auth.csrf-token=csrfcookie; Path=/; HttpOnly; SameSite=Lax")
                    "/api/auth/callback/wallet" -> MockResponse().setBody("""{"url":"$callbackUrl"}""")
                        .addHeader("Set-Cookie", "__Secure-next-auth.session-token=jwt-123; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax")
                    "/api/auth/capture-fingerprint" -> MockResponse().setBody("""{"success":true,"fingerprint":"fp-abc","userAgent":"${DashboardConfig.USER_AGENT}"}""")
                    "/api/auth/session" -> MockResponse().setBody("""{"user":{"id":"u1","address":"$address","email":"a@b.c","first_name":"A","last_name":"B","admin":false},"deviceFingerprint":"fp-abc","userAgent":"${DashboardConfig.USER_AGENT}"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun useCase(bridge: FakeWalletBridge): Pair<SignInUseCase, SessionRepository> {
        val jar = PersistentCookieJar(cookieStore, host = server.hostName)
        val client = OkHttpClient.Builder().cookieJar(jar).addInterceptor(HeaderPinInterceptor()).build()
        val api = NextAuthApi.create(client, server.url("/").toString())
        val session = SessionRepository(api, jar, sessionStore)
        val binder = FingerprintBinder(api)
        return SignInUseCase(bridge, api, binder, session, NonceGenerator { "424242" }) to session
    }

    private fun form(r: RecordedRequest): Map<String, String> =
        r.body.readUtf8().split("&").associate { kv -> kv.substringBefore("=") to URLDecoder.decode(kv.substringAfter("="), "UTF-8") }

    @Test
    fun `existing user signs in with a nonce txn and ends up SignedIn with a bound fingerprint`() = runTest {
        val bridge = FakeWalletBridge(address)
        val (useCase, session) = useCase(bridge)

        val result = useCase.signIn(WalletVendor.PERA, profile = null)

        assertTrue("expected Success, got $result", result is SignInResult.Success)
        val state = session.state.value
        assertTrue("expected SignedIn, got $state", state is SessionState.SignedIn)
        assertEquals(address, (state as SessionState.SignedIn).profile.address)
        assertTrue(state.fingerprintBound)

        assertEquals(listOf("/api/check-user", "/api/auth/csrf", "/api/auth/callback/wallet", "/api/auth/capture-fingerprint", "/api/auth/csrf", "/api/auth/session", "/api/auth/session"), recorded.map { it.path })

        // the proof txn is a 0-amount self-payment carrying the dashboard nonce message
        assertEquals(address, bridge.lastPayment["sender"]); assertEquals(address, bridge.lastPayment["receiver"]); assertEquals(0L, bridge.lastPayment["amountMicro"])
        assertEquals("Sign this message to prove you own the wallet: 424242", bridge.lastPayment["note"])
        assertEquals(1, bridge.signCalls)

        val cb = recorded[2]
        assertEquals("1", cb.getHeader("X-Auth-Return-Redirect"))
        assertTrue(cb.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        val f = form(cb)
        assertEquals("csrf-2", f["csrfToken"]); assertEquals(address, f["address"]); assertEquals("c2lnbmVk", f["signedTxn"]); assertEquals("424242", f["nonce"]); assertEquals("true", f["json"])
        assertEquals("https://dashboard.frynetworks.com/", f["callbackUrl"])
        assertTrue(cb.getHeader("Cookie")!!.contains("__Host-next-auth.csrf-token=csrfcookie"))

        // fingerprint capture carries the session cookie; the update posts csrf + fingerprint data
        assertTrue(recorded[3].getHeader("Cookie")!!.contains("__Secure-next-auth.session-token=jwt-123"))
        val update = JsonParser.parseString(recorded[5].body.readUtf8()).asJsonObject
        assertEquals("POST", recorded[5].method)
        assertEquals("csrf-5", update["csrfToken"].asString)
        assertEquals("fp-abc", update["data"].asJsonObject["deviceFingerprint"].asString)
        assertEquals(DashboardConfig.USER_AGENT, update["data"].asJsonObject["userAgent"].asString)
        assertEquals(address, sessionStore.profile?.address)
    }

    @Test
    fun `a rejected signature is reported as an error and leaves the session signed out`() = runTest {
        callbackUrl = "https://dashboard.frynetworks.com/signin?error=CredentialsSignin"
        val (useCase, session) = useCase(FakeWalletBridge(address))
        val result = useCase.signIn(WalletVendor.PERA, profile = null)
        assertTrue("expected Failure, got $result", result is SignInResult.Failure)
        assertEquals("CredentialsSignin", (result as SignInResult.Failure).code)
        assertTrue(session.state.value is SessionState.SignedOut)
    }

    @Test
    fun `a new wallet needs a profile before the callback and then sends it`() = runTest {
        isNew = true
        val (useCase, _) = useCase(FakeWalletBridge(address))
        val first = useCase.signIn(WalletVendor.PERA, profile = null)
        assertTrue("expected NeedsProfile, got $first", first is SignInResult.NeedsProfile)
        assertEquals(listOf("/api/check-user"), recorded.map { it.path })

        val second = useCase.signIn(WalletVendor.PERA, profile = NewUserProfile("new@fry.test", "New", "User"))
        assertTrue("expected Success, got $second", second is SignInResult.Success)
        val f = form(recorded.first { it.path == "/api/auth/callback/wallet" })
        assertEquals("new@fry.test", f["email"]); assertEquals("New", f["first_name"]); assertEquals("User", f["last_name"])
    }

    @Test
    fun `sign-out clears cookies, profile and the wallet session`() = runTest {
        val bridge = FakeWalletBridge(address)
        val (useCase, session) = useCase(bridge)
        useCase.signIn(WalletVendor.PERA, profile = null)
        assertTrue(cookieStore.saved.isNotEmpty())
        SignOutUseCase(NextAuthApi.create(OkHttpClient.Builder().cookieJar(PersistentCookieJar(cookieStore, host = server.hostName)).build(), server.url("/").toString()), bridge, session).signOut()
        assertTrue(session.state.value is SessionState.SignedOut)
        assertTrue(cookieStore.saved.isEmpty())
        assertEquals(null, sessionStore.profile)
        assertEquals("/api/auth/signout", recorded.last().path)
    }
}
