package com.frynetworks.fryapp.wallet.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frynetworks.fryapp.util.AlgorandAddress
import com.frynetworks.fryapp.wallet.TxnToSign
import com.frynetworks.fryapp.wallet.WalletVendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Boots the real bridge page (assets/bridge/bridge.html + the esbuild bundle) inside a WebView on
 * an Android runtime and drives it through the JSON-RPC surface with the built-in stub wallet:
 * connect, build a payment, sign a two-entry group (one signable, one display-only), decode.
 * No Pera/Defly app and no network signing are involved; suggested params come from algod.
 */
@RunWith(AndroidJUnit4::class)
class BridgeStubWalletTest {

    @Test
    fun stubWalletRoundTripThroughTheRealBridgePage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = WalletBridgeWebView(context, ExternalUriLauncher(context))
        bridge.stubMode = true
        withContext(Dispatchers.Main) { bridge.attach() }

        withTimeout(30_000) { assertTrue("ping", bridge.ping()) }

        val account = bridge.connect(WalletVendor.PERA)
        assertTrue("stub address must be a valid Algorand address: ${account.address}", AlgorandAddress.isValid(account.address))

        val unsigned = bridge.buildPayment(account.address, account.address, 0L, "Sign this message to prove you own the wallet: 1")
        assertTrue(unsigned.isNotBlank())

        val summary = bridge.decodeTxn(unsigned)
        assertEquals("pay", summary.type)
        assertEquals(account.address, summary.sender)
        assertEquals(0L, summary.amount)

        val signed = bridge.signTxns(listOf(listOf(TxnToSign(unsigned, sign = true), TxnToSign(unsigned, sign = false))))
        assertEquals(1, signed.size)
        assertEquals(2, signed[0].size)
        assertNotNull("signable entry must be signed", signed[0][0])
        assertNull("display-only entry must not be signed", signed[0][1])

        bridge.disconnect()
    }
}
