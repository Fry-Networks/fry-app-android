package com.frynetworks.fryapp.auth

import com.frynetworks.fryapp.network.dashboard.DashboardConfig
import com.frynetworks.fryapp.network.dashboard.JsJson
import com.frynetworks.fryapp.network.dashboard.jsonBody
import com.frynetworks.fryapp.wallet.BridgeException
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletBridge
import com.frynetworks.fryapp.wallet.WalletVendor
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom

/** Profile fields the dashboard requires for a wallet it has never seen. */
data class NewUserProfile(val email: String, val firstName: String, val lastName: String)

sealed interface SignInResult {
    data class Success(val profile: SessionProfile) : SignInResult
    data class NeedsProfile(val address: String) : SignInResult
    data class Failure(val code: String, val message: String) : SignInResult
}

fun interface NonceGenerator {
    fun next(): String
}

/** Same shape as the dashboard: `Math.floor(Math.random() * 1000000).toString()`. */
class SecureNonceGenerator : NonceGenerator {
    private val random = SecureRandom()
    override fun next(): String = random.nextInt(1_000_000).toString()
}

/**
 * Native replica of `pages/signin.tsx`: connect the wallet, ask the dashboard whether the address
 * is new, sign a 0-amount self-payment whose note is the nonce message, post it to the NextAuth
 * credentials callback, bind the device fingerprint, then verify the session.
 */
class SignInUseCase(
    private val bridge: WalletBridge,
    private val api: NextAuthApi,
    private val binder: FingerprintBinder,
    private val session: SessionRepository,
    private val nonceGenerator: NonceGenerator = SecureNonceGenerator(),
    private val callbackUrl: String = DashboardConfig.BASE_URL + "/",
) {

    suspend fun signIn(vendor: WalletVendor, profile: NewUserProfile?): SignInResult {
        return try {
            run(vendor, profile)
        } catch (e: CancellationException) {
            session.markSignedOut()
            throw e
        } catch (e: BridgeException) {
            session.markSignedOut()
            SignInResult.Failure(e.code.name, e.message ?: e.code.name)
        } catch (e: IOException) {
            session.markSignedOut()
            SignInResult.Failure("NETWORK", e.message ?: "Network error")
        } catch (e: Exception) {
            session.markSignedOut()
            SignInResult.Failure("UNKNOWN", e.message ?: "Sign-in failed")
        }
    }

    private suspend fun run(vendor: WalletVendor, profile: NewUserProfile?): SignInResult {
        session.setStep(SignInStep.ConnectingWallet)
        val account = bridge.reconnect(vendor) ?: bridge.connect(vendor)
        val address = account.address

        session.setStep(SignInStep.CheckingUser)
        val isNew = api.checkUser(JsJson.stringify(jsonBody { "address" to address }).toRequestBody(JSON)).isNew == true
        if (isNew && profile == null) {
            session.markSignedOut()
            return SignInResult.NeedsProfile(address)
        }

        session.setStep(SignInStep.BuildingProof)
        val nonce = nonceGenerator.next()
        val unsigned = bridge.buildPayment(address, address, 0L, NONCE_MESSAGE_PREFIX + nonce)

        session.setStep(SignInStep.AwaitingSignature)
        val signed = bridge.signTxns(listOf(listOf(TxnToSign(unsigned, sign = true)))).firstOrNull()?.firstOrNull()
            ?: throw BridgeException(com.frynetworks.fryapp.wallet.BridgeErrorCode.USER_REJECTED, "Wallet returned no signature")

        session.setStep(SignInStep.Authenticating)
        val csrf = api.csrf().csrfToken ?: return failure("CSRF", "Could not obtain a CSRF token")
        val callback = api.walletCallback(
            csrfToken = csrf,
            callbackUrl = callbackUrl,
            json = "true",
            address = address,
            signedTxn = signed,
            nonce = nonce,
            email = profile?.email,
            firstName = profile?.firstName,
            lastName = profile?.lastName,
        )
        val error = callback.url?.let { queryParam(it, "error") }
        if (!error.isNullOrBlank()) return failure(error, describe(error))

        session.setStep(SignInStep.BindingDevice)
        val bound = binder.rebind()

        session.setStep(SignInStep.Verifying)
        val serverSession = api.session()
        val serverAddress = serverSession.getAsJsonObject("user")?.get("address")?.takeIf { !it.isJsonNull }?.asString
        if (serverAddress != address) return failure("SESSION_MISMATCH", "The dashboard session does not belong to the connected wallet")
        val user = serverSession.getAsJsonObject("user")
        val result = SessionProfile(
            address = address,
            email = user.str("email") ?: profile?.email,
            firstName = user.str("first_name") ?: profile?.firstName,
            lastName = user.str("last_name") ?: profile?.lastName,
            vendor = vendor,
            fingerprint = binder.lastFingerprint,
        )
        session.markSignedIn(result, bound)
        return SignInResult.Success(result)
    }

    private fun failure(code: String, message: String): SignInResult {
        session.markSignedOut()
        return SignInResult.Failure(code, message)
    }

    private fun describe(error: String): String = when (error) {
        "CredentialsSignin" -> "The dashboard did not accept the wallet signature."
        else -> error
    }

    /** Pure query-string lookup (android.net.Uri is a stub on the JVM). */
    private fun queryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        return query.split('&').firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }

    private fun com.google.gson.JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

    companion object {
        const val NONCE_MESSAGE_PREFIX = "Sign this message to prove you own the wallet: "
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
