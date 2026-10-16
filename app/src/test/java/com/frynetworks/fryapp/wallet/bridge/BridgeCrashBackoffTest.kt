package com.frynetworks.fryapp.wallet.bridge

import android.os.Handler
import android.webkit.RenderProcessGoneDetail
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A renderer that keeps crashing must not be re-attached in a tight loop: after a few quick crashes the re-attach backs off. */
class BridgeCrashBackoffTest {
    private val h = BridgePageHarness()
    private val delayed = mutableListOf<Pair<Long, Runnable>>()
    private var now = 1_000_000L

    @Before
    fun setUp() {
        h.setUp()
        every { anyConstructed<Handler>().postDelayed(any<Runnable>(), any<Long>()) } answers {
            delayed += secondArg<Long>() to firstArg<Runnable>()
            true
        }
        h.bridge.clock = { now }
    }

    @After fun tearDown() = h.tearDown()

    private fun crash() {
        val detail = mockk<RenderProcessGoneDetail>()
        every { detail.didCrash() } returns true
        assertTrue(h.client.onRenderProcessGone(h.webView, detail))
    }

    private fun runNextDelayed() = delayed.removeAt(0).second.run()

    @Test
    fun `the first three crashes in a minute re-attach at once`() {
        repeat(3) { crash() }
        assertEquals(3, h.bridge.generation.value)
        assertTrue(delayed.isEmpty())
    }

    @Test
    fun `from the fourth crash the re-attach is delayed and the delay doubles`() {
        repeat(3) { crash() }

        crash()
        assertEquals(3, h.bridge.generation.value)
        assertFalse("the dead view is detached meanwhile", h.bridge.isAttached)
        assertEquals(listOf(1_000L), delayed.map { it.first })
        runNextDelayed()
        assertEquals(4, h.bridge.generation.value)

        crash()
        assertEquals(4, h.bridge.generation.value)
        assertEquals(listOf(2_000L), delayed.map { it.first })
        runNextDelayed()
        assertEquals(5, h.bridge.generation.value)
    }

    @Test
    fun `the delay is capped`() {
        repeat(3) { crash() }
        val seen = mutableListOf<Long>()
        repeat(8) {
            crash()
            seen += delayed.single().first
            runNextDelayed()
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), seen)
    }

    @Test
    fun `a crash while a delayed re-attach is already queued queues nothing more`() {
        repeat(3) { crash() }
        crash()
        crash()
        assertEquals(1, delayed.size)
        runNextDelayed()
        assertEquals(4, h.bridge.generation.value)
    }

    @Test
    fun `a quiet minute starts the count over`() {
        repeat(3) { crash() }
        now += 61_000L

        crash()

        assertEquals(4, h.bridge.generation.value)
        assertTrue(delayed.isEmpty())
    }
}
