package com.frynetworks.fryqa

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONObject
import java.io.File

/**
 * Shared plumbing for the QA suites. Everything is addressed by resource-id (the app exposes its
 * Compose test tags as bare resource-ids) or by visible text; never by screen coordinates. Every
 * selector is confined to one package: the app under test, the permission controller, the package
 * installer, or Android Settings for the one Bluetooth pairing consent button, so a dialog from
 * any other app sharing the phone is never touched.
 *
 * Instrumentation arguments (`am instrument -e name value`) carry only non-secret switches:
 * `targetPackage` (a Fry app package only, default com.frynetworks.fryapp), `board`, `iterations`,
 * `keyMode`, `expectChannel`, `ackActiveElsewhere`. Wi-Fi credentials, wallet and miner key come
 * from `<qa external files dir>/provision.json`, pushed by the operator's harness, so they never
 * appear in a process list. Results are appended to `<qa files dir>/results.jsonl` with keys
 * masked to 6 characters and no passwords.
 */
object Qa {
    /** The packages that may serve the runtime-permission dialog (AOSP, Mainline, One UI). */
    val PERMISSION_CONTROLLERS = listOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.samsung.android.permissioncontroller",
    )

    /** The packages that may serve the install prompt (AOSP, Google, One UI). */
    val INSTALLERS = listOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
    )
    private const val SETTINGS = "com.android.settings"

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    val args: Bundle get() = InstrumentationRegistry.getArguments()
    private val self: Context get() = instrumentation.context

    /** Refuses anything but the Fry app packages before a single window is touched. */
    val targetPackage: String get() = QaTarget.checked(args.getString("targetPackage"))

    fun arg(name: String, default: String): String = args.getString(name) ?: default

    /** `provision.json`: {"ssid","pass","wallet","minerKey","setupCode"}; missing fields are null. */
    fun provisionConfig(): JSONObject {
        val file = File(self.getExternalFilesDir(null), "provision.json")
        check(file.isFile) { "push provision.json to ${file.absolutePath} first" }
        return JSONObject(file.readText())
    }

    fun JSONObject.optStringOrNull(name: String): String? = if (has(name) && !isNull(name)) getString(name).takeIf { it.isNotBlank() } else null

    fun mask(key: String?): String? = key?.let { it.take(6) + "…" }

    fun record(test: String, fields: Map<String, Any?>) {
        val line = JSONObject().put("test", test).put("t", System.currentTimeMillis()).put("targetPackage", targetPackage)
        fields.forEach { (k, v) -> line.put(k, v ?: JSONObject.NULL) }
        File(self.filesDir, "results.jsonl").appendText(line.toString() + "\n")
    }

    fun installedVersionCode(): Long? = runCatching {
        val info = self.packageManager.getPackageInfo(targetPackage, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrNull()

    fun launchApp(timeoutMs: Long = 15_000) {
        val intent = self.packageManager.getLaunchIntentForPackage(targetPackage)
            ?: error("$targetPackage is not installed")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        self.startActivity(intent)
        check(device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), timeoutMs)) { "$targetPackage did not come to the foreground" }
    }

    /** A resource-id inside the app under test only. */
    fun res(id: String): BySelector = By.res(id).pkg(targetPackage)

    /** A resource-id pattern inside the app under test only. */
    fun res(pattern: java.util.regex.Pattern): BySelector = By.res(pattern).pkg(targetPackage)

    /** Visible text inside the app under test only. */
    fun text(value: String): BySelector = By.text(value).pkg(targetPackage)

    fun find(selector: BySelector, timeoutMs: Long = 10_000): UiObject2? = device.wait(Until.findObject(selector), timeoutMs)

    fun need(selector: BySelector, what: String, timeoutMs: Long = 10_000): UiObject2 =
        find(selector, timeoutMs) ?: error("not found within ${timeoutMs}ms: $what")

    fun tap(id: String, timeoutMs: Long = 10_000) = need(res(id), id, timeoutMs).click()

    /** Scrolls the app's first scrollable container until [selector] shows (Compose lists are lazy). */
    fun scrollTo(selector: BySelector, maxSwipes: Int = 8): UiObject2? {
        repeat(maxSwipes) {
            device.findObject(selector)?.let { return it }
            device.findObject(By.scrollable(true).pkg(targetPackage))?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f) ?: return null
        }
        return device.findObject(selector)
    }

    fun type(id: String, text: String) {
        val field = need(res(id), id)
        field.click()
        field.text = text
    }

    /**
     * Closes the soft keyboard only when one is showing: a blind Back press with no keyboard
     * open would leave the screen under test instead.
     */
    fun hideKeyboard() {
        val imeShowing = runCatching {
            instrumentation.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }.getOrDefault(false)
        if (imeShowing) {
            device.pressBack()
            device.waitForIdle()
        }
    }

    /** The first of [selectors] on screen within [timeoutMs], polling, or null; never blocks per selector. */
    private fun waitForAny(selectors: List<BySelector>, timeoutMs: Long): UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            selectors.firstNotNullOfOrNull { device.findObject(it) }?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(250)
        }
    }

    /**
     * Answers Android's runtime permission dialogs positively, by the permission controller's
     * resource ids (with the visible button text as fallback), inside one of the
     * [PERMISSION_CONTROLLERS] only. Returns how many were answered.
     */
    fun allowPermissionDialogs(maxDialogs: Int = 6, timeoutMs: Long = 4_000): Int {
        val allow = PERMISSION_CONTROLLERS.flatMap { p ->
            listOf(
                By.pkg(p).res("$p:id/permission_allow_foreground_only_button"),
                By.pkg(p).res("$p:id/permission_allow_button"),
                By.pkg(p).text("While using the app"),
                By.pkg(p).text("Allow"),
            )
        }
        val fineLocation = PERMISSION_CONTROLLERS.map { p -> By.pkg(p).res("$p:id/permission_location_accuracy_radio_fine") }
        var answered = 0
        repeat(maxDialogs) {
            // Android 12+ asks precise vs approximate first: BLE scanning needs precise.
            fineLocation.firstNotNullOfOrNull { device.findObject(it) }?.click()
            val button = waitForAny(allow, if (answered == 0) timeoutMs else 1_500) ?: return answered
            button.click()
            answered++
            device.waitForIdle()
        }
        return answered
    }

    /**
     * The one Settings interaction allowed: the "Pair" button of Android's Bluetooth pairing
     * consent, which the first encrypted `09` write raises on many phones - and only when that
     * consent names a Fry board ("FRY-..."), never a pairing request for any other device.
     * Returns true if pressed.
     */
    fun acceptPairingConsent(): Boolean {
        if (!device.hasObject(By.pkg(SETTINGS).textContains("FRY-"))) return false
        val pair = device.findObject(By.pkg(SETTINGS).text("Pair")) ?: return false
        pair.click()
        device.waitForIdle()
        return true
    }

    fun hasPermission(permission: String): Boolean =
        self.packageManager.checkPermission(permission, targetPackage) == PackageManager.PERMISSION_GRANTED
}
