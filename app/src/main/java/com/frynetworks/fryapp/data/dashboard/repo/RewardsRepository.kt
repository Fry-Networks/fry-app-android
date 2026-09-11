package com.frynetworks.fryapp.data.dashboard.repo

import com.frynetworks.fryapp.data.dashboard.model.ActivityEvent
import com.frynetworks.fryapp.data.dashboard.model.AssetTotalsResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.data.dashboard.model.RewardsPageResponse
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow

/**
 * Rewards on the dashboard API (signed routes carry the `x-request-*` headers automatically).
 *
 *  - `POST /api/rewards/get-asset-totals {address}`                 (signed) -> cached `asset_totals_cache`
 *  - `POST /api/rewards/get-reward-summary-batch {miner_keys}` <=50 (signed) -> cached `reward_summary_cache`
 *  - `POST /api/rewards/get-reward-summary {miner_key}`             (signed)
 *  - `POST /api/rewards/get-rewards-page {miner_key,page}`          (signed) -> in-memory, 10/page
 *  - `POST /api/activity/recent {address}`                                   -> <=12 events, in-memory
 *  - `POST /api/price/get {asset_ids}`                                       -> USD per asset id
 *  - `POST /api/rewards/claim {miner_key[,no],preview:true}`        (signed) -> totals only
 *  - `POST /api/rewards/claim {miner_key[,no]}`                     (signed) -> user_pays envelope or custodial txId
 *  - `POST /api/rewards/confirm {groupId,signedUserLegB64}`         (signed)
 *  - `POST /api/rewards/confirm {txId}`                             (signed, custodial poll)
 *
 * Body key order is significant for the HMAC: `miner_key` before `no` before `preview`, exactly as
 * Claim.tsx sends them; optional fields are omitted, never null.
 */
interface RewardsRepository {
    fun observeAssetTotals(): Flow<AssetTotalsResponse?>

    suspend fun refreshAssetTotals(): AssetTotalsResponse

    fun observeSummary(minerKey: String): Flow<RewardSummary?>

    /** Chunked batch fetch; returns the merged map and caches it. */
    suspend fun refreshSummaries(minerKeys: List<String>): Map<String, RewardSummary>

    suspend fun refreshSummary(minerKey: String): RewardSummary?

    /** [page] is 1-based like the dashboard. */
    suspend fun historyPage(minerKey: String, page: Int): RewardsPageResponse

    suspend fun activity(): List<ActivityEvent>

    /** USD price per asset id; assets the dashboard has no price for are absent. */
    suspend fun prices(assetIds: List<Long>): Map<Long, BigDecimal>

    suspend fun claimPreview(minerKey: String, no: Int? = null): ClaimPreviewResponse

    suspend fun claimEnvelope(minerKey: String, no: Int? = null): ClaimEnvelopeResponse

    suspend fun claimConfirm(groupId: String, signedUserLegB64: String): ConfirmResultResponse

    suspend fun claimConfirmCustodial(txId: String): ConfirmResultResponse

    suspend fun clearCache()
}
