package com.frynetworks.fryapp.data.dashboard.repo

import com.frynetworks.fryapp.data.Device
import com.frynetworks.fryapp.data.dashboard.model.MinerSummary
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.util.isDeviceOnline

/**
 * Pure join of the dashboard's miner list with this phone's provisioned `devices` table.
 *
 *  - A key on both sides is [MinerSource.BOTH]; the dashboard's `status` wins (the phone only
 *    knows it provisioned the device, not whether the wallet still owns it).
 *  - A key only on the phone is [MinerSource.LOCAL] with [MinerStatus.NOT_ON_DASHBOARD].
 *  - A key only on the dashboard is [MinerSource.REMOTE] with `localOnline == null`.
 *  - `displayName` = dashboard `nickname` ?: local `Device.name` ?: `MinerListItem.shortKey`
 *    (blank strings count as absent).
 *  - Order: [MinerStatus.rank] ascending (needs-attention first), then [MinerFamily] in
 *    declaration order (the catalog grouping), then display name (case-insensitive), then key.
 *
 * No I/O; `nowMillis` feeds the local online heuristic (`util/DeviceStatus.kt`) so tests are
 * deterministic.
 */
object MinerMerge {

    fun mergeMiners(
        remote: List<MinerSummary>,
        local: List<Device>,
        summaries: Map<String, RewardSummary>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<MinerListItem> {
        val localByKey = local.associateBy { it.minerKey }
        val seen = HashSet<String>()
        val items = ArrayList<MinerListItem>(remote.size + local.size)

        for (summary in remote) {
            val key = summary.minerKey?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (!seen.add(key)) continue
            val device = localByKey[key]
            items += MinerListItem(
                minerKey = key,
                family = MinerFamily.fromMinerKey(key),
                displayName = displayName(key, summary.nickname, device?.name),
                status = MinerStatus.fromWire(summary.status),
                source = if (device != null) MinerSource.BOTH else MinerSource.REMOTE,
                localOnline = device?.let { isDeviceOnline(it, nowMillis) },
                remote = summary,
                local = device,
                rewards = summaries[key],
            )
        }

        for (device in local) {
            val key = device.minerKey
            if (key.isBlank() || !seen.add(key)) continue
            items += MinerListItem(
                minerKey = key,
                family = MinerFamily.fromMinerKey(key),
                displayName = displayName(key, null, device.name),
                status = MinerStatus.NOT_ON_DASHBOARD,
                source = MinerSource.LOCAL,
                localOnline = isDeviceOnline(device, nowMillis),
                remote = null,
                local = device,
                rewards = summaries[key],
            )
        }

        return items.sortedWith(ORDER)
    }

    private val ORDER: Comparator<MinerListItem> =
        compareBy<MinerListItem> { it.status.rank }
            .thenBy { it.family.ordinal }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            .thenBy { it.minerKey }

    private fun displayName(key: String, nickname: String?, localName: String?): String =
        nickname?.trim()?.takeIf { it.isNotEmpty() }
            ?: localName?.trim()?.takeIf { it.isNotEmpty() }
            ?: MinerListItem.shortKey(key)
}
