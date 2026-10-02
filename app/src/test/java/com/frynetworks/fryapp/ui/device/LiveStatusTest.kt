package com.frynetworks.fryapp.ui.device

import com.frynetworks.fryapp.data.dashboard.api.DashboardErrorCodes
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.ui.miners.detail.MinerStateTerms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AP5 (OD-43): the device screen's live status, in the dashboard's words (MinerStateTerms, A3),
 * never shown as current once older than 15 minutes.
 */
class LiveStatusTest {

    private val t0 = 1_759_400_000_000L
    private val minute = 60_000L

    /** A3: the status word is the text before ':' when MinerStateTerms appends an explanation. */
    private fun statusWord(term: String) = term.substringBefore(':').trim()

    @Test
    fun `is_active maps to the exact MinerStateTerms words`() {
        for (v in listOf(true, false, null)) {
            assertEquals(MinerStateTerms.rows(DeviceDetail(isActive = v), null, null).active, LiveStatusRules.term(v))
        }
        assertEquals("Active", LiveStatusRules.term(true))
        assertEquals("Inactive: no recent heartbeat on the dashboard", LiveStatusRules.term(false))
        assertEquals("Not reported by the dashboard", LiveStatusRules.term(null))
    }

    @Test
    fun `the status word before the colon is the dashboard's Active or Inactive (A3)`() {
        assertEquals("Active", statusWord(LiveStatusRules.term(true)))
        assertEquals("Inactive", statusWord(LiveStatusRules.term(false)))
        // Positive control: the matcher does reject a different word.
        assertFalse(statusWord("Online: seen recently").equals("Active", ignoreCase = true))
        assertFalse(statusWord("Live status: Inactive").equals("Inactive", ignoreCase = true))
    }

    @Test
    fun `a dashboard answer maps through is_active, a missing record is not reported`() {
        assertEquals(LiveStatusRules.term(true), LiveStatusRules.answerTerm(DeviceDetail(isActive = true)))
        assertEquals(LiveStatusRules.term(false), LiveStatusRules.answerTerm(DeviceDetail(isActive = false)))
        assertEquals(LiveStatusRules.term(null), LiveStatusRules.answerTerm(DeviceDetail(isActive = null)))
        val notFound404 = DashboardException(DashboardErrorCodes.DEVICE_NOT_FOUND, "Device not found", httpStatus = 404)
        val notFoundEmpty = DashboardException(DashboardErrorCodes.DEVICE_NOT_FOUND, "Device not found", httpStatus = 200)
        assertEquals(LiveStatusRules.term(null), LiveStatusRules.notFoundTerm(notFound404))
        assertEquals(LiveStatusRules.term(null), LiveStatusRules.notFoundTerm(notFoundEmpty))
        assertNull(LiveStatusRules.notFoundTerm(DashboardException("WALLET_MISMATCH", "mismatch", httpStatus = 401)))
        assertNull(LiveStatusRules.notFoundTerm(DashboardException("INTERNAL_ERROR", "boom", httpStatus = 500)))
        assertNull(LiveStatusRules.notFoundTerm(java.io.IOException("offline")))
    }

    @Test
    fun `error backoff is 60 s then 2 then 4 then capped at 5 min`() {
        assertEquals(
            listOf(60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L),
            (1..6).map { LiveStatusRules.backoffMillis(it) },
        )
        assertEquals(60_000L, LiveStatusRules.POLL_MS)
    }

    @Test
    fun `a result is current for 15 minutes and unavailable one millisecond later`() {
        val known = LiveStatus.Known(LiveStatusRules.term(true), checkedAtMillis = t0)
        assertEquals(known, LiveStatusRules.current(known, t0 + 15 * minute))
        assertEquals(LiveStatus.Unavailable(t0), LiveStatusRules.current(known, t0 + 15 * minute + 1))
        assertEquals(LiveStatus.Checking, LiveStatusRules.current(LiveStatus.Checking, t0 + 99 * minute))
        assertEquals(LiveStatus.SignedOut, LiveStatusRules.current(LiveStatus.SignedOut, t0 + 99 * minute))
    }

    @Test
    fun `every state has its own words`() {
        val now = t0 + 2 * minute
        assertEquals("Live status", LiveStatusText.LABEL)
        assertEquals("Checking the dashboard…", LiveStatusText.status(LiveStatus.Checking, now))
        assertEquals("Sign in to see live status", LiveStatusText.status(LiveStatus.SignedOut, now))
        assertEquals("Active", LiveStatusText.status(LiveStatus.Known("Active", t0), now))
        assertEquals("Checked 2m ago", LiveStatusText.checked(LiveStatus.Known("Active", t0), now))
        assertEquals("Checked just now", LiveStatusText.checked(LiveStatus.Known("Active", now), now))
        assertEquals("Status unavailable · last checked 2m ago", LiveStatusText.status(LiveStatus.Unavailable(t0), now))
        assertEquals("Status unavailable", LiveStatusText.status(LiveStatus.Unavailable(null), now))
        assertNull(LiveStatusText.checked(LiveStatus.Unavailable(t0), now))
        assertNull(LiveStatusText.checked(LiveStatus.SignedOut, now))
        assertNull(LiveStatusText.checked(LiveStatus.Checking, now))
    }

    @Test
    fun `a stale result is never rendered as current`() {
        val stale = LiveStatus.Known(LiveStatusRules.term(true), checkedAtMillis = t0)
        val now = t0 + 20 * minute
        assertEquals("Status unavailable · last checked 20m ago", LiveStatusText.status(stale, now))
        assertNull(LiveStatusText.checked(stale, now))
    }

    @Test
    fun `no state renders two statuses at once`() {
        val now = t0 + minute
        val states = listOf(
            LiveStatus.Checking, LiveStatus.SignedOut,
            LiveStatus.Known(LiveStatusRules.term(true), t0), LiveStatus.Known(LiveStatusRules.term(false), t0),
            LiveStatus.Known(LiveStatusRules.term(null), t0), LiveStatus.Unavailable(t0), LiveStatus.Unavailable(null),
        )
        val words = listOf("Active", "Inactive", "Not reported", "Status unavailable", "Sign in", "Checking")
        for (s in states) {
            val shown = listOfNotNull(LiveStatusText.status(s, now), LiveStatusText.checked(s, now)).joinToString(" | ")
            val hits = words.filter { w -> Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(shown) }
            assertEquals("$s shows $shown", 1, hits.size)
        }
        // Positive control: a contradictory rendering is caught.
        val bad = "Active | Status unavailable"
        assertTrue(words.count { w -> Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(bad) } > 1)
    }
}
