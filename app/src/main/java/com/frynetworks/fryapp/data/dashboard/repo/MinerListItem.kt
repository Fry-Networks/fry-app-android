package com.frynetworks.fryapp.data.dashboard.repo

import com.frynetworks.fryapp.data.Device
import com.frynetworks.fryapp.data.dashboard.model.MinerSummary
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.domain.MinerFamily

/** Where a row of the Miners tab comes from: the dashboard, this phone's provisioned list, or both. */
enum class MinerSource { LOCAL, REMOTE, BOTH }

/**
 * Dashboard `status` of a miner (`/api/devices/list` `status`), plus [NOT_ON_DASHBOARD] for a
 * device this phone provisioned that the signed-in wallet does not own on the dashboard.
 * [rank] is the default sort order (needs attention first).
 */
enum class MinerStatus(val wireValue: String?, val rank: Int) {
    ACTIVE("active", 3),
    PENDING("pending", 1),
    UNREGISTERED("unregistered", 0),
    MIGRATED("migrated", 4),
    NOT_ON_DASHBOARD(null, 2),
    UNKNOWN(null, 5),
    ;

    companion object {
        fun fromWire(value: String?): MinerStatus =
            entries.firstOrNull { it.wireValue != null && it.wireValue.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * One row of the Miners tab: the dashboard's summary merged with the local `devices` row (if this
 * phone provisioned it) and the cached reward summary. Produced by `MinerMerge` in the data layer;
 * rendered by `MinersScreen`. Never carries credentials.
 */
data class MinerListItem(
    val minerKey: String,
    val family: MinerFamily,
    /** `nickname` ?: local `Device.name` ?: the key's short form (`PREFIX-XXXXXX`). */
    val displayName: String,
    val status: MinerStatus,
    val source: MinerSource,
    /** Local BLE/Wi-Fi status when [source] includes the phone's list; null for remote-only rows. */
    val localOnline: Boolean?,
    val remote: MinerSummary?,
    val local: Device?,
    val rewards: RewardSummary?,
) {
    val isNode: Boolean get() = family.isNode || (remote?.node ?: false)

    companion object {
        /** `PREFIX-XXXXXX` — the first six characters after the prefix, matching the dashboard's short form. */
        fun shortKey(minerKey: String): String {
            val prefix = minerKey.substringBefore('-', "")
            val rest = minerKey.substringAfter('-', "")
            if (prefix.isEmpty() || rest.isEmpty()) return minerKey
            return "$prefix-${rest.take(6)}"
        }
    }
}
