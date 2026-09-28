package com.frynetworks.fryapp.provisioning

import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.network.dashboard.DashboardException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class HandoffOutcome { ONLINE, NOT_SEEN, SIGNED_OUT }

/** After a handoff the phone can no longer see the board; only the backend can say it arrived. */
fun interface HandoffWatcher {
    suspend fun awaitOnline(minerKey: String): HandoffOutcome
}

/**
 * Polls `POST /api/devices/{key}` every [POLL_MS] for up to [WINDOW_MS] while signed in. Online
 * means the dashboard reports the board active (C-4 `is_active`), or the device appearing at all
 * after being missing: a board's record only exists once the board itself has registered.
 */
class DashboardHandoffWatcher @Inject constructor(
    private val miners: MinerRepository,
    private val session: SessionRepository,
) : HandoffWatcher {

    override suspend fun awaitOnline(minerKey: String): HandoffOutcome {
        if (session.state.value !is SessionState.SignedIn) return HandoffOutcome.SIGNED_OUT
        var sawMissing = false
        val online = withTimeoutOrNull(WINDOW_MS) {
            var found = false
            while (!found) {
                val detail = try {
                    miners.refreshDetail(minerKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DashboardException) {
                    if (e.httpStatus == 404) sawMissing = true
                    null
                } catch (e: Exception) {
                    null
                }
                found = detail != null && (detail.isActive == true || sawMissing)
                if (!found) delay(POLL_MS)
            }
            true
        }
        return if (online == true) HandoffOutcome.ONLINE else HandoffOutcome.NOT_SEEN
    }

    companion object {
        const val WINDOW_MS = 180_000L
        const val POLL_MS = 10_000L
    }
}
