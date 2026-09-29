package com.frynetworks.fryqa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U5/I6 on a freshly installed app: the Scan tab asks for permissions, the scan ends within its
 * window (the button returns to "Start scan"), and either boards, the empty-state help or the
 * preflight guidance is shown - never an endless "Scanning...".
 * adb shell am instrument -w -e class com.frynetworks.fryqa.FreshInstallDiscoveryTest
 *   [-e targetPackage com.frynetworks.fryapp.debug] com.frynetworks.fryqa/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class FreshInstallDiscoveryTest {

    @Test
    fun scanFinishesAndExplainsTheOutcome() {
        Qa.launchApp()
        Qa.tap("nav_scan")
        Qa.tap("scan_start")
        val answered = Qa.allowPermissionDialogs()
        // A grant restarts the scan from the permission callback; tap again if it did not.
        if (Qa.find(Qa.text("Scanning..."), 3_000) == null) Qa.find(Qa.res("scan_start"))?.click()
        val started = System.currentTimeMillis()
        val finished = Qa.device.wait(Until.hasObject(Qa.res("scan_start").hasDescendant(Qa.text("Start scan"))), 60_000)
        val seconds = (System.currentTimeMillis() - started) / 1000
        val results = (0 until 20).count { Qa.device.hasObject(Qa.res("scan_result_$it")) }
        val preflight = Qa.device.findObjects(Qa.res(java.util.regex.Pattern.compile("preflight_issue_.*"))).map { it.resourceName }
        Qa.record(
            "FreshInstallDiscoveryTest",
            mapOf(
                "permissionDialogsAnswered" to answered,
                "scanFinished" to finished,
                "scanSeconds" to seconds,
                "results" to results,
                "resultLabels" to (0 until results).mapNotNull { Qa.device.findObject(Qa.res("scan_result_name_$it"))?.text }.joinToString("|"),
                "emptyHelp" to Qa.device.hasObject(Qa.res("scan_empty")),
                "error" to Qa.device.findObject(Qa.res("scan_error"))?.text,
                "preflight" to preflight.joinToString("|"),
            ),
        )
        assertTrue("scan still running after 60 s", finished)
        assertTrue("no outcome shown", results > 0 || Qa.device.hasObject(Qa.res("scan_empty")) || preflight.isNotEmpty() || Qa.device.hasObject(Qa.res("scan_error")))
    }
}
