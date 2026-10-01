package com.frynetworks.fryapp.ui.provision

import com.frynetworks.fryapp.ble.KEY_WRITE_NEEDS_USB
import com.frynetworks.fryapp.ble.ProvError
import com.frynetworks.fryapp.provisioning.KeyTransportPolicy
import com.frynetworks.fryapp.ui.scan.DiscoveryInputs
import com.frynetworks.fryapp.ui.scan.DiscoveryPreflight
import com.frynetworks.fryapp.ui.scan.PermissionState
import com.frynetworks.fryapp.wifi.SOFTAP_NEEDS_ANDROID_10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O-2: ESP32, C3 and S3 boards are set up in this app over Bluetooth and an ESP8266 through its
 * own Wi-Fi setup page; the USB / browser web setup is retired. No user-facing setup string may
 * still send people there, and a guide link may only point at an anchor the ESP guide has.
 */
class EspSetupCopyTest {

    private val banned = listOf(
        "USB", "web setup", "browser", "Improv", "setup-miner", "dashboard setup",
        "Web Serial", "Web Bluetooth", "No Android needed",
    )

    private fun bannedIn(text: String): List<String> = banned.filter { text.contains(it, ignoreCase = true) }

    /** Anchors present on https://docs.frynetworks.com/docs/esp-miners.html (sha16 99a3018546d8ea53, 2026-10-01). */
    private val guideAnchors = setOf(
        "esp8266", "flash", "get-a-key", "install-app", "monitor", "no-android", "not-earning",
        "privacy", "provision", "reflash-v031", "register", "s3", "troubleshooting", "what-you-need",
    )

    private fun inputs(sdk: Int, ble: Boolean) = DiscoveryInputs(
        sdkInt = sdk,
        permissions = emptyMap<String, PermissionState>().withDefault { PermissionState.GRANTED },
        bleSupported = ble,
        bluetoothOn = true,
        locationOn = true,
        wifiOn = true,
    )

    /** Every string AP2 covers, read from the code that shows it. */
    private fun setupStrings(): Map<String, String> = mapOf(
        "ble_unsupported" to DiscoveryPreflight.evaluate(inputs(36, ble = false)).ble.issues.single { it.code == "ble_unsupported" }.message,
        "softap_unsupported" to DiscoveryPreflight.evaluate(inputs(28, ble = true)).softAp.issues.single { it.code == "softap_unsupported" }.message,
        "OPEN_AP_REFUSAL" to KeyTransportPolicy.OPEN_AP_REFUSAL,
        "SOFTAP_NEEDS_ANDROID_10" to ProvisionErrorCopy.forFailure(SOFTAP_NEEDS_ANDROID_10),
        "KEY_LOCKED" to ProvisionErrorCopy.forError(ProvError.KEY_LOCKED, errorResetSupported = true),
        "KEY_LOCKED latched" to ProvisionErrorCopy.forError(ProvError.KEY_LOCKED),
        "key_locked refusal" to ProvisionErrorCopy.forRefusal(403, "key_locked"),
        "KEY_WRITE_NEEDS_USB" to ProvisionErrorCopy.forFailure(KEY_WRITE_NEEDS_USB),
        "key_needs_secure_ap" to ProvisionErrorCopy.forRefusal(403, "key_needs_secure_ap"),
    )

    @Test
    fun `the matcher catches the retired strings (positive control)`() {
        val retired = listOf(
            "This phone has no Bluetooth LE, so it cannot find ESP32 boards. Use the USB web setup instead.",
            "Setting up an ESP8266 over its Wi-Fi setup network needs Android 10 or newer. Use the USB web setup instead.",
            "This board's setup network is open, so Fry will not send a miner key over it. It already has a key; to change it, use the USB web setup.",
            "8 characters, shown on the USB web setup page. Leave empty for a board that already has a key.",
            "This board already has a confirmed miner key and will not replace it over the air. To change the key, use the USB web setup.",
            "This board's firmware is too old to take a miner key over Bluetooth. Set the key with the USB web setup page, or install the latest firmware from the web flasher first.",
            "This board only takes a miner key over its protected setup network. Restart it without a key set, or use the USB web setup.",
            "Open the browser", "improv serial", "/setup-miner", "Dashboard setup", "web serial", "web bluetooth", "No Android needed.",
        )
        for (s in retired) assertTrue("matcher misses: $s", bannedIn(s).isNotEmpty())
        assertTrue(bannedIn("Set it up in the Fry app over Bluetooth.").isEmpty())
    }

    @Test
    fun `no ESP setup string sends users to the retired USB or browser setup`() {
        val strings = setupStrings()
        assertEquals(9, strings.size)
        for ((site, text) in strings) {
            assertTrue("$site is blank", text.isNotBlank())
            assertEquals("$site still says ${bannedIn(text)}: $text", emptyList<String>(), bannedIn(text))
        }
    }

    @Test
    fun `guide links point only at existing anchors of the ESP miners guide`() {
        val link = Regex("""https?://[^\s)]+""")
        for ((site, text) in setupStrings()) {
            for (m in link.findAll(text)) {
                val url = m.value.trimEnd('.', ',')
                assertTrue("$site links elsewhere: $url", url.startsWith("https://docs.frynetworks.com/docs/esp-miners.html"))
                val anchor = url.substringAfter('#', "")
                assertTrue("$site links to a missing anchor: $url", anchor.isEmpty() || anchor in guideAnchors)
            }
        }
    }

    @Test
    fun `no string literal in the app sources uses the retired setup wording`() {
        val root = File("src/main/java")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val literals = files.flatMap { f -> stringLiterals(f.readText()).map { f.name to it } }
        // Positive controls: the scanner sees real literals (this one sits in ProvisionErrorCopy.kt)…
        assertTrue(literals.any { it.second == "Setup failed" })
        assertTrue("too few literals: ${literals.size}", literals.size > 500)
        // …and reads code, not comments.
        assertEquals(listOf("a", "b // c", "d"), stringLiterals("val x = \"a\" // \"nope\"\n/* \"nope\" */ f(\"b // c\", \"\"\"d\"\"\")"))
        val hits = literals.filter { bannedIn(it.second).isNotEmpty() }
        assertEquals("retired wording in: $hits", emptyList<Pair<String, String>>(), hits)
    }

    /** String-literal contents of Kotlin [src], skipping comments; templates are kept as written. */
    private fun stringLiterals(src: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it }
                    out += src.substring(i + 3, end)
                    i = end + 3
                }
                src[i] == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < src.length && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2 } else sb.append(src[i++])
                    }
                    out += sb.toString()
                    i++
                }
                src[i] == '\'' -> i = src.indexOf('\'', i + if (src.startsWith("'\\", i)) 3 else 2).let { if (it < 0) src.length else it + 1 }
                else -> i++
            }
        }
        return out
    }
}
