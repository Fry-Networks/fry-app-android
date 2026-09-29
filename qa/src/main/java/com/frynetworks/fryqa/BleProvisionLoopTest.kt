package com.frynetworks.fryqa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Until
import com.frynetworks.fryqa.Qa.optStringOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * U4/U8/I5: provision one board N times over BLE and record each outcome.
 *   -e board FRY-ESP32-ABC123   (substring of the scan label)
 *   -e iterations 5
 *   -e keyMode owner|none        (owner: type provision.json's minerKey into the key step)
 *   -e ackActiveElsewhere true   (tick the "active on another install" box if it appears)
 * provision.json supplies ssid, pass, wallet, minerKey. A pass is Connected, or a handoff the
 * dashboard confirmed; the JSONL row has the final status text and duration per iteration.
 * The first encrypted key write may raise Android's Bluetooth pairing consent; its "Pair" button
 * (in Settings) is the only thing outside the app this test presses.
 */
@RunWith(AndroidJUnit4::class)
class BleProvisionLoopTest {

    @Test
    fun provisionRepeatedly() {
        val board = Qa.arg("board", "FRY-")
        val iterations = Qa.arg("iterations", "1").toInt()
        val keyMode = Qa.arg("keyMode", "owner")
        val ack = Qa.arg("ackActiveElsewhere", "false").toBoolean()
        val cfg = Qa.provisionConfig()
        var passes = 0
        for (i in 1..iterations) {
            val started = System.currentTimeMillis()
            val outcome = runCatching { once(board, keyMode, ack, cfg) }.getOrElse { "exception: ${it.message}" }
            val ok = outcome.startsWith("Connected") || outcome.startsWith("handoff-online")
            if (ok) passes++
            Qa.record(
                "BleProvisionLoopTest",
                mapOf("iteration" to i, "of" to iterations, "board" to board, "keyMode" to keyMode, "key" to Qa.mask(cfg.optStringOrNull("minerKey")),
                    "outcome" to outcome, "pass" to ok, "seconds" to (System.currentTimeMillis() - started) / 1000),
            )
        }
        Qa.record("BleProvisionLoopTest", mapOf("summary" to "$passes/$iterations"))
        assertEquals("passes", iterations, passes)
    }

    private fun once(board: String, keyMode: String, ack: Boolean, cfg: org.json.JSONObject): String {
        Qa.launchApp()
        Qa.tap("nav_scan")
        Qa.tap("scan_start")
        Qa.allowPermissionDialogs(timeoutMs = 2_000)
        val row = Qa.device.wait(Until.findObject(Qa.res(Pattern.compile("scan_result_name_\\d+")).textContains(board)), 30_000)
            ?: return "board not found"
        row.click()
        Qa.type("prov_ssid", cfg.getString("ssid"))
        Qa.type("prov_pass", cfg.optStringOrNull("pass").orEmpty())
        cfg.optStringOrNull("wallet")?.let { Qa.type("prov_wallet", it) }
        if (keyMode == "owner") {
            Qa.type("prov_key", cfg.getString("minerKey"))
            Qa.find(Qa.res("prov_key_check"), 15_000)
            Qa.device.findObject(Qa.res("prov_key_ack"))?.let { box -> if (ack) box.click() else return "key active elsewhere (not acknowledged)" }
        }
        Qa.hideKeyboard() // so the submit button is on screen
        (Qa.scrollTo(Qa.res("prov_submit")) ?: return "no submit button").click()
        // Terminal: Success navigates to the device screen; Error / handoff stay on prov_status.
        val deadline = System.currentTimeMillis() + 200_000
        var pairingAccepted = false
        while (System.currentTimeMillis() < deadline) {
            if (!pairingAccepted && Qa.acceptPairingConsent()) pairingAccepted = true
            if (Qa.device.hasObject(Qa.res("prov_continue"))) return "Connected (board kept its own key)"
            if (!Qa.device.hasObject(Qa.res("prov_status"))) return "Connected (left provisioning screen)"
            val status = Qa.device.findObject(Qa.res("prov_status"))?.text.orEmpty()
            when {
                status.startsWith("Error:") -> return status
                Qa.device.hasObject(Qa.res("prov_open_device")) -> return "handoff-unconfirmed: $status"
            }
            Thread.sleep(1_000)
        }
        return "timeout: " + Qa.device.findObject(Qa.res("prov_status"))?.text
    }
}
