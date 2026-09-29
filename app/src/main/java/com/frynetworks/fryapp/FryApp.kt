package com.frynetworks.fryapp

import android.app.Application
import com.frynetworks.fryapp.update.ForegroundTracker
import com.frynetworks.fryapp.update.UpdateCheckWorker
import com.frynetworks.fryapp.update.UpdateCoordinator
import com.frynetworks.fryapp.update.UpdateTrigger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FryApp : Application() {

    @Inject lateinit var updates: UpdateCoordinator
    @Inject lateinit var foreground: ForegroundTracker

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // An unattended update pass never installs while an activity is resumed.
        registerActivityLifecycleCallbacks(foreground)
        // Self-update (O-2): a throttled check at launch plus a daily WorkManager check. The
        // launch check waits for the first activity to resume, so a pass that may not install
        // while the app is on screen defers before downloading rather than after.
        runCatching { UpdateCheckWorker.schedule(this) }
        appScope.launch {
            delay(LAUNCH_CHECK_SETTLE_MS)
            runCatching { updates.check(UpdateTrigger.LAUNCH) }
        }
    }

    companion object {
        const val LAUNCH_CHECK_SETTLE_MS = 3_000L
    }
}
