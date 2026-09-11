package com.frynetworks.fryapp.fakes

import com.frynetworks.fryapp.auth.CallbackResponse
import com.frynetworks.fryapp.auth.CaptureFingerprintResponse
import com.frynetworks.fryapp.auth.CheckUserResponse
import com.frynetworks.fryapp.auth.CsrfResponse
import com.frynetworks.fryapp.auth.NextAuthApi
import com.frynetworks.fryapp.auth.SessionProfile
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionStore
import com.frynetworks.fryapp.network.dashboard.ServerClock
import com.frynetworks.fryapp.network.dashboard.cookies.CookieStore
import com.frynetworks.fryapp.network.dashboard.cookies.PersistentCookieJar
import com.frynetworks.fryapp.wallet.WalletVendor
import com.google.gson.JsonObject
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

const val TEST_ADDRESS = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
const val OTHER_ADDRESS = "UKVAN7ORIUX7Y6QJFYQ4YGQAZD3RAC7QTDB73S2E5MSILUWAA7FJ6N7WLU"

/** In-memory NextAuth surface: the ViewModel tests never sign in through the network. */
class FakeNextAuthApi : NextAuthApi {
    override suspend fun csrf(): CsrfResponse = CsrfResponse("csrf")
    override suspend fun walletCallback(
        csrfToken: String, callbackUrl: String, json: String, address: String, signedTxn: String, nonce: String,
        email: String?, firstName: String?, lastName: String?,
    ): CallbackResponse = CallbackResponse("https://dashboard.frynetworks.com/")
    override suspend fun captureFingerprint(body: RequestBody): CaptureFingerprintResponse = CaptureFingerprintResponse(true, "fp", "ua")
    override suspend fun updateSession(body: RequestBody): JsonObject = JsonObject()
    override suspend fun session(): JsonObject = JsonObject()
    override suspend fun signOut(csrfToken: String, json: String): Response<ResponseBody> = Response.success("".toResponseBody())
    override suspend fun checkUser(body: RequestBody): CheckUserResponse = CheckUserResponse(false)
}

class MemorySessionStore : SessionStore {
    var profile: SessionProfile? = null
    override fun load(): SessionProfile? = profile
    override fun save(profile: SessionProfile?) {
        this.profile = profile
    }
}

class MemoryCookieStore : CookieStore {
    var serialized: List<String> = emptyList()
    override fun load(): List<String> = serialized
    override fun save(serialized: List<String>) {
        this.serialized = serialized
    }
}

object TestSession {
    fun signedOut(): SessionRepository = SessionRepository(FakeNextAuthApi(), PersistentCookieJar(MemoryCookieStore()), MemorySessionStore())

    fun signedIn(address: String = TEST_ADDRESS, vendor: WalletVendor = WalletVendor.PERA): SessionRepository =
        signedOut().also { it.markSignedIn(SessionProfile(address, vendor = vendor), fingerprintBound = true) }
}

/** A [ServerClock] pinned to a fixed instant (offset applied against the wall clock). */
fun fixedClock(nowMillis: Long): ServerClock = ServerClock().also { it.observeServerMillis(nowMillis) }
