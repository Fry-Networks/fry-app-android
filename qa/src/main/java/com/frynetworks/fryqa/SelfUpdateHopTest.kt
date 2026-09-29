package com.frynetworks.fryqa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One self-update hop (vc6 -> vc7 -> vc8 per D-7): trigger a manual check, confirm Android's
 * package-installer prompt by its button id / text (inside the installer package only), and wait
 * until the installed versionCode rises. Self-instrumenting, so this test survives the app being
 * replaced. -e expectVersionCode N makes the target explicit.
 *
 * No Settings are changed here: the harness grants REQUEST_INSTALL_PACKAGES with
 * `appops set <package> REQUEST_INSTALL_PACKAGES allow` before the hop.
 */
@RunWith(AndroidJUnit4::class)
class SelfUpdateHopTest {

    private val confirmButtons: List<BySelector> = Qa.INSTALLERS.flatMap { installer ->
        listOf(
            By.pkg(installer).res("android:id/button1"),
            By.pkg(installer).text("Update"),
            By.pkg(installer).text("Install"),
            By.pkg(installer).text("UPDATE"),
            By.pkg(installer).text("INSTALL"),
        )
    }

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
            confirmButtons.firstNotNullOfOrNull { Qa.device.findObject(it) }?.takeIf { it.isEnabled }?.let { it.click(); steps += "confirm:${it.text ?: it.resourceName}" }
            Thread.sleep(2_000)
            after = Qa.installedVersionCode() ?: after
        }
        Qa.record("SelfUpdateHopTest", mapOf("from" to before, "to" to after, "expect" to expect, "steps" to steps.joinToString("|")))
        assertTrue("versionCode $before -> $after, expected $expect", after >= expect)
    }
}
