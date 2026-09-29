package com.frynetworks.fryapp.update

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app is on screen (an activity is resumed). An unattended update pass must not
 * replace the process then: the user may be in the middle of a claim, a stake or a board setup.
 * Registered by [com.frynetworks.fryapp.FryApp]; no dependency on lifecycle-process.
 */
@Singleton
class ForegroundTracker @Inject constructor() : Application.ActivityLifecycleCallbacks {
    private val resumedActivities = AtomicInteger(0)

    val resumed: Boolean get() = resumedActivities.get() > 0

    override fun onActivityResumed(activity: Activity) {
        resumedActivities.incrementAndGet()
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
