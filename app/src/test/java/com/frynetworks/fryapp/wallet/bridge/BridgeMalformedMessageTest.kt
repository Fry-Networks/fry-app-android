package com.frynetworks.fryapp.wallet.bridge

import android.util.Log
import com.frynetworks.fryapp.wallet.WalletVendor
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A message the page sends that cannot be decoded must be ignored: an exception out of a `@JavascriptInterface` method kills the app. */
class BridgeMalformedMessageTest {
    private val h = BridgePageHarness()

    @Before
    fun setUp() {
        h.setUp()
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
    }

    @After fun tearDown() = h.tearDown()

    private fun assertIgnored(json: String) = runTest {
        h.native.onMessage(json)

        verify(exactly = 1) { Log.w(any<String>(), "ignoring unrecognised bridge message") }
        // The bridge is still usable afterwards.
        assertEquals(BridgePageHarness.ADDRESS, h.bridge.reconnect(WalletVendor.PERA)?.address)
    }

    @Test
    fun `a reply whose id is not a string is ignored`() = assertIgnored("""{"id":{"x":1},"ok":true}""")

    @Test
    fun `a reply whose ok flag is not a boolean is ignored`() = assertIgnored("""{"id":"a","ok":{"x":1}}""")

    @Test
    fun `an error reply whose code is not a string is ignored`() = assertIgnored("""{"id":"a","ok":false,"error":{"code":{"x":1}}}""")

    @Test
    fun `an event whose name is not a string is ignored`() = assertIgnored("""{"event":{"x":1}}""")

    @Test
    fun `an openUri event whose uri is not a string is ignored`() = assertIgnored("""{"event":"openUri","uri":{"x":1}}""")

    @Test
    fun `an openUri event whose wallet is not a string is ignored`() = assertIgnored("""{"event":"openUri","uri":"wc:x@1","wallet":{"x":1}}""")
}
