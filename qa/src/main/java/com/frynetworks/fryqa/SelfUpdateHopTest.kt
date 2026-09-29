package com.frynetworks.fryqa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One self-update hop (vc6 -> vc7 -> vc8 per D-7): trigger a manual check, confirm Android's
 * package-installer prompt by its button id / text - inside the installer package only, and only
 * when that same window names this app ([QaTarget.APP_LABEL]), never a prompt raised for another
 * app on the shared phone - and wait until the installed versionCode rises. Self-instrumenting,
 * so this test survives the app being replaced. -e expectVersionCode N makes the target explicit.
 *
 * No Settings are changed here: the harness grants REQUEST_INSTALL_PACKAGES with
 * `appops set <package> REQUEST_INSTALL_PACKAGES allow` before the hop.
 */
@RunWith(AndroidJUnit4::class)
class SelfUpdateHopTest {

    private fun confirmButtons(installer: String): List<BySelector> = listOf(
        By.pkg(installer).res("android:id/button1"),
        By.pkg(installer).text("Update"),
        By.pkg(installer).text("Install"),
        By.pkg(installer).text("UPDATE"),
        By.pkg(installer).text("INSTALL"),
    )

    /** The enabled confirm button of an installer window that names this app, if one is showing. */
    private fun appInstallConfirm(): Pair<String, UiObject2>? {
        for (installer in Qa.INSTALLERS) {
            if (!Qa.device.hasObject(By.pkg(installer).textContains(QaTarget.APP_LABEL))) continue
            val button = confirmButtons(installer).firstNotNullOfOrNull { Qa.device.findObject(it) }?.takeIf { it.isEnabled } ?: continue
            return installer to button
        }
        return null
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
            appInstallConfirm()?.let { (installer, button) ->
                button.click()
                steps += "confirm:$installer:${button.text ?: button.resourceName}"
            }
            Thread.sleep(2_000)
            after = Qa.installedVersionCode() ?: after
        }
        Qa.record("SelfUpdateHopTest", mapOf("from" to before, "to" to after, "expect" to expect, "steps" to steps.joinToString("|")))
        assertTrue("versionCode $before -> $after, expected $expect", after >= expect)
    }
}
