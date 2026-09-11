package com.frynetworks.fryapp.data.dashboard.repo

import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.model.HardwareStatusEntry
import com.frynetworks.fryapp.data.dashboard.model.MyKeysResponse
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.StatusSummaryResponse
import kotlinx.coroutines.flow.Flow

/** Fleet online/total from `/api/devices/status-summary`; null when the dashboard answers 503 `ACTIVITY_UNAVAILABLE`. */
data class FleetSummary(val online: Int, val total: Int) {
    companion object {
        fun from(response: StatusSummaryResponse?): FleetSummary? =
            response?.let { FleetSummary(online = it.online ?: 0, total = it.total ?: 0) }
    }
}

/**
 * Miners owned by the signed-in wallet, merged with this phone's provisioned `devices` table.
 *
 * Contract (dashboard.frynetworks.com, session cookie + pinned headers via the dashboard OkHttp
 * client; the address in request bodies is always the signed-in session address):
 *  - `POST /api/devices/list {address}`            -> list rows (cached in `remote_miners`)
 *  - `POST /api/devices/batch {miner_keys}` <=200  -> hydrated details (cached in `miner_detail_cache`)
 *  - `POST /api/devices/{key} {address}`           -> one hydrated detail
 *  - `POST /api/devices/status-summary {address}`  -> fleet counts (503 tolerated -> null)
 *  - `POST /api/hardware/status {miner_keys}`      -> hardware verification, hardware families only
 *  - `GET  /api/my-keys`                           -> keys + IoT credentials + BYOD licenses
 *  - `POST /api/products/get-product {miner_key}`  -> product catalog entry (stake tiers, tokens)
 *  - `POST /api/change-name {address,name,miner_key}`
 *  - `POST /api/devices/save-wallet-info {miner_key,reward_wallet,address}`
 *
 * Every failure surfaces as [com.frynetworks.fryapp.network.dashboard.DashboardException]
 * (code/message/action/httpStatus); 401 additionally emits `SessionEvent.Expired`.
 * Refresh calls are sequential (the dashboard rate-limits at ~5 req/s) — never fan out per miner.
 */
interface MinerRepository {
    /** Cached rows joined with local devices; emits immediately from cache, updates after [refresh]. */
    fun observeMiners(): Flow<List<MinerListItem>>

    /** list -> batch -> reward-summary-batch -> asset-totals -> status-summary -> hardware/status. */
    suspend fun refresh()

    /** Cached hydrated detail (from batch or [refreshDetail]); null until fetched. */
    fun observeDetail(minerKey: String): Flow<DeviceDetail?>

    suspend fun refreshDetail(minerKey: String): DeviceDetail

    /** Last fleet summary seen by [refresh]; null before the first refresh or when unavailable. */
    fun observeFleetSummary(): Flow<FleetSummary?>

    suspend fun hardwareStatus(minerKeys: List<String>): Map<String, HardwareStatusEntry>

    suspend fun myKeys(): MyKeysResponse

    /** Product catalog entry for the miner's family; null when the dashboard has none. */
    suspend fun product(minerKey: String): Product?

    suspend fun rename(minerKey: String, nickname: String)

    /** [wallet] must already pass `AlgorandAddress.isValid`. */
    suspend fun setRewardWallet(minerKey: String, wallet: String)

    /** Drops every cached dashboard row (sign-out). The local `devices` table is untouched. */
    suspend fun clearCache()
}
