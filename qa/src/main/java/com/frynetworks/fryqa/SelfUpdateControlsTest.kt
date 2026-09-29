package com.frynetworks.fryqa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Settings "App updates" section shows the channel the adb-written file selects and a manual
 * check reaches a terminal status (C-sha / C-cert / C-down controls are prepared by the lead's
 * manifests; this suite records what the app said).
 *   -e expectChannel stable|test
 */
@RunWith(AndroidJUnit4::class)
class SelfUpdateControlsTest {

    @Test
    fun channelAndManualCheck() {
        val expect = Qa.arg("expectChannel", "stable")
        Qa.launchApp()
        Qa.tap("nav_settings")
        val channel = (Qa.scrollTo(Qa.res("settings_update_channel")) ?: error("no App updates section")).text
        Qa.tap("settings_update_check")
        val terminal = listOf("latest version", "does not update itself", "failed", "Installing", "ready")
        val reached = Qa.device.wait(Until.hasObject(By.res("settings_update_status")), 5_000)
        var status = ""
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            status = Qa.device.findObject(By.res("settings_update_status"))?.text.orEmpty()
            if (terminal.any { status.contains(it, ignoreCase = true) }) break
            Thread.sleep(1_000)
        }
        Qa.record(
            "SelfUpdateControlsTest",
            mapOf("channelText" to channel, "expectChannel" to expect, "status" to status, "statusShown" to reached,
                "lastInstall" to Qa.device.findObject(By.res("settings_update_last_install"))?.text, "versionCode" to Qa.installedVersionCode()),
        )
        assertTrue("channel text $channel", channel.contains("Channel: $expect"))
        assertTrue("no terminal status: $status", terminal.any { status.contains(it, ignoreCase = true) })
    }
}
