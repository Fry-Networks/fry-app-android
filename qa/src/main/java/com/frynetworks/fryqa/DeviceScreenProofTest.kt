package com.frynetworks.fryqa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.frynetworks.fryqa.Qa.optStringOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I13/IMG5 on a real miner (signed in): the device screen shows the dashboard's state words and
 * every action button is at least 48 dp tall with its label present. The proof is the recorded
 * geometry and text; no screenshot is written, since the screen shows the full miner key and
 * wallet. The miner key comes from provision.json ("minerKey").
 */
@RunWith(AndroidJUnit4::class)
class DeviceScreenProofTest {

    private val actions = listOf(
        "miner_action_claim", "miner_action_stake_registration", "miner_action_stake_verification",
        "miner_action_withdraw", "miner_action_rename", "miner_action_reward_wallet",
    )

    @Test
    fun deviceScreen() {
        val key = Qa.provisionConfig().optStringOrNull("minerKey") ?: error("provision.json has no minerKey")
        Qa.launchApp()
        Qa.tap("nav_miners")
        (Qa.scrollTo(Qa.res("miners_item_$key")) ?: error("miner ${Qa.mask(key)} not in the list")).click()
        Qa.need(Qa.res("miner_identity"), "miner_identity", 20_000)
        val rows = listOf("miner_registered", "miner_is_active", "miner_reward_eligible", "miner_reward_block_reason", "miner_verified")
            .associateWith { Qa.device.findObject(Qa.res(it))?.text }
        Qa.scrollTo(Qa.res("miner_action_reward_wallet"))
        val minPx = (48 * Qa.instrumentation.context.resources.displayMetrics.density).toInt()
        val buttons = actions.associateWith { id -> Qa.device.findObject(Qa.res(id))?.let { it.visibleBounds.height() to (it.findObject(By.textContains(""))?.text ?: it.contentDescription) } }
        Qa.record(
            "DeviceScreenProofTest",
            mapOf("key" to Qa.mask(key), "rows" to rows.toString(), "buttons" to buttons.toString(), "minPx" to minPx),
        )
        assertTrue("state rows missing: $rows", rows["miner_registered"] != null && rows["miner_is_active"] != null)
        buttons.forEach { (id, v) -> assertTrue("$id missing", v != null); assertTrue("$id is ${v!!.first}px < 48dp", v.first >= minPx) }
    }
}
