package com.frynetworks.fryqa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One self-update hop (vc6 -> vc7 -> vc8 per D-7): trigger a manual check, confirm Android's
 * prompts by their button text / ids ("Install unknown apps" toggle, then Update/Install), and
 * wait until the installed versionCode rises. Self-instrumenting, so this test survives the app
 * being replaced. -e expectVersionCode N makes the target explicit.
 */
@RunWith(AndroidJUnit4::class)
class SelfUpdateHopTest {

    private val confirmButtons: List<BySelector> = listOf(
        By.res("android:id/button1"),
        By.text("Update"),
        By.text("Install"),
        By.text("UPDATE"),
        By.text("INSTALL"),
    )

    @Test
    fun hop() {
        val before = Qa.installedVersionCode() ?: error("${Qa.targetPackage} not installed")
        val expect = Qa.arg("expectVersionCode", (before + 1).toString()).toLong()
        Qa.launchApp()
        Qa.tap("nav_settings")
        (Qa.scrollTo(Qa.res("settings_update_check")) ?: error("no update button")).click()
        val steps = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + 300_000
        var after = before
        while (System.currentTimeMillis() < deadline && after < expect) {
            // First self-update from a sideloaded install: allow "Install unknown apps" for the app.
            Qa.device.findObject(By.text("Settings"))?.takeIf { Qa.device.hasObject(By.textContains("unknown apps")) }?.let { it.click(); steps += "open-unknown-apps" }
            Qa.device.findObject(By.res("android:id/switch_widget"))?.takeIf { !it.isChecked }?.let { it.click(); steps += "allow-source"; Qa.device.pressBack() }
            confirmButtons.firstNotNullOfOrNull { Qa.device.findObject(it) }?.takeIf { it.isEnabled }?.let { it.click(); steps += "confirm:${it.text ?: it.resourceName}" }
            Thread.sleep(2_000)
            after = Qa.installedVersionCode() ?: after
        }
        Qa.record("SelfUpdateHopTest", mapOf("from" to before, "to" to after, "expect" to expect, "steps" to steps.joinToString("|")))
        assertTrue("versionCode $before -> $after, expected $expect", after >= expect)
    }
}
