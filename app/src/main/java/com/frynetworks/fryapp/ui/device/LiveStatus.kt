package com.frynetworks.fryapp.ui.device

import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.data.dashboard.api.DashboardErrorCodes
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.ui.common.TimeFormat
import com.frynetworks.fryapp.ui.miners.detail.MinerStateTerms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/** The dashboard's live status for a board, as the device screen shows it (AP5, OD-43). */
sealed interface LiveStatus {
    /** Signed in, no answer yet. */
    data object Checking : LiveStatus

    /** No dashboard session: nothing is requested. */
    data object SignedOut : LiveStatus

    /** The dashboard's own word for `is_active` (via [MinerStateTerms]), answered at [checkedAtMillis]. */
    data class Known(val term: String, val checkedAtMillis: Long) : LiveStatus

    /** No current answer; [lastCheckedMillis] is when the dashboard last answered, if ever. */
    data class Unavailable(val lastCheckedMillis: Long?) : LiveStatus
}

/**
 * Activity itself is decided by the dashboard (lib/deviceActivity.ts); the app never re-derives
 * it. [STALE_MS] only stops an old answer from being shown as current.
 */
object LiveStatusRules {
    const val POLL_MS = 60_000L
    const val STALE_MS = 15 * 60_000L

    /** How often a visible screen re-reads the clock (ages, staleness); never a request. */
    const val TICK_MS = 30_000L
    private const val MAX_BACKOFF_MS = 5 * 60_000L

    fun term(isActive: Boolean?): String = MinerStateTerms.rows(DeviceDetail(isActive = isActive), null, null).active

    fun answerTerm(detail: DeviceDetail): String = term(detail.isActive)

    /** A missing dashboard record is an answer (nothing reported), not a failure; null otherwise. */
    fun notFoundTerm(error: Throwable): String? =
        if (error is DashboardException && (error.code == DashboardErrorCodes.DEVICE_NOT_FOUND || error.httpStatus == 404)) term(null) else null

    /** Wait after [consecutiveFailures] failed requests in a row: 60 s, 2 min, 4 min, then 5 min. */
    fun backoffMillis(consecutiveFailures: Int): Long =
        (POLL_MS shl (consecutiveFailures - 1).coerceIn(0, 3)).coerceAtMost(MAX_BACKOFF_MS)

    /** [status] as it may be shown at [nowMillis]: an answer older than [STALE_MS] is unavailable. */
    fun current(status: LiveStatus, nowMillis: Long): LiveStatus =
        if (status is LiveStatus.Known && nowMillis - status.checkedAtMillis > STALE_MS) LiveStatus.Unavailable(status.checkedAtMillis) else status
}

/** The clock, read at once and then every [LiveStatusRules.TICK_MS] for as long as it is collected. */
fun liveTicker(nowMillis: () -> Long): Flow<Long> = flow {
    while (true) {
        emit(nowMillis())
        delay(LiveStatusRules.TICK_MS)
    }
}

/** Every word the device screen shows for its live status. */
object LiveStatusText {
    const val LABEL = "Live status"

    fun status(status: LiveStatus, nowMillis: Long): String = when (val s = LiveStatusRules.current(status, nowMillis)) {
        LiveStatus.Checking -> "Checking the dashboard…"
        LiveStatus.SignedOut -> "Sign in to see live status"
        is LiveStatus.Known -> s.term
        is LiveStatus.Unavailable ->
            s.lastCheckedMillis?.let { "Status unavailable · last checked " + TimeFormat.relative(it, nowMillis) } ?: "Status unavailable"
    }

    fun checked(status: LiveStatus, nowMillis: Long): String? =
        (LiveStatusRules.current(status, nowMillis) as? LiveStatus.Known)?.let {
            "Checked " + TimeFormat.relative(it.checkedAtMillis, nowMillis)
        }
}

/**
 * Polls the dashboard for one board while collected: at once, then every [LiveStatusRules.POLL_MS],
 * backing off on failures. Requests are sequential and only made with a signed-in session; a sign-in
 * or wallet change restarts with nothing carried over. Keeps the last status across collections so
 * a returning screen shows it (aged by [LiveStatusRules.current]) until the next answer.
 */
class LiveStatusPoller(
    private val session: Flow<SessionState>,
    private val fetch: suspend () -> DeviceDetail,
    private val nowMillis: () -> Long,
) {
    private var address: String? = null
    private var shown: LiveStatus = LiveStatus.Checking
    private var lastAnswerMillis: Long? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun updates(): Flow<LiveStatus> = session
        .map { (it as? SessionState.SignedIn)?.profile?.address }
        .distinctUntilChanged()
        .transformLatest { signedIn ->
            if (signedIn == null) {
                forget(null)
                emit(LiveStatus.SignedOut)
                return@transformLatest
            }
            if (signedIn != address) forget(signedIn)
            emit(LiveStatusRules.current(shown, nowMillis()))
            var failures = 0
            while (true) {
                val term = try {
                    LiveStatusRules.answerTerm(fetch())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LiveStatusRules.notFoundTerm(e)
                }
                if (term != null) {
                    failures = 0
                    val at = nowMillis()
                    lastAnswerMillis = at
                    shown = LiveStatus.Known(term, at)
                } else {
                    failures++
                    shown = LiveStatus.Unavailable(lastAnswerMillis)
                }
                emit(shown)
                delay(if (failures == 0) LiveStatusRules.POLL_MS else LiveStatusRules.backoffMillis(failures))
            }
        }

    private fun forget(newAddress: String?) {
        address = newAddress
        shown = LiveStatus.Checking
        lastAnswerMillis = null
    }
}
