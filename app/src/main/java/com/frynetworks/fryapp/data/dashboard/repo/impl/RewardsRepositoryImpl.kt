package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.data.dashboard.api.DashboardApi
import com.frynetworks.fryapp.data.dashboard.api.DashboardCalls
import com.frynetworks.fryapp.data.dashboard.api.DashboardErrorCodes
import com.frynetworks.fryapp.data.dashboard.api.toJsonRequestBody
import com.frynetworks.fryapp.data.dashboard.db.AssetTotalsCacheDao
import com.frynetworks.fryapp.data.dashboard.db.AssetTotalsCacheEntity
import com.frynetworks.fryapp.data.dashboard.db.RewardSummaryCacheDao
import com.frynetworks.fryapp.data.dashboard.db.RewardSummaryCacheEntity
import com.frynetworks.fryapp.data.dashboard.model.ActivityEvent
import com.frynetworks.fryapp.data.dashboard.model.ActivityResponse
import com.frynetworks.fryapp.data.dashboard.model.AssetTotalsResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.data.dashboard.model.DashboardGson
import com.frynetworks.fryapp.data.dashboard.model.PriceResponse
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.data.dashboard.model.RewardsPageResponse
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.network.dashboard.JsonBody
import com.frynetworks.fryapp.network.dashboard.SessionEventBus
import com.frynetworks.fryapp.network.dashboard.jsonBody
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal

/**
 * Retrofit + Room implementation of [RewardsRepository]. Wire notes (dashboard snapshot):
 *  - `get-asset-totals` reads nothing from the body; the browser signs `{}` and sends no body
 *    (pages/devices.tsx:1018-1027), so the app sends the canonical `{}` — identical signature input.
 *  - `get-reward-summary-batch` caps at 200 keys server-side (get-reward-summary-batch.ts:28); the
 *    app chunks at [SUMMARY_CHUNK] = 50 as the contract requires.
 *  - `price/get` takes `asset_ids` as STRINGS (price/get.ts:15-17, useTokenPrices.ts:24).
 *  - `claim` bodies mirror Claim.tsx:377 — `{miner_key}` or `{miner_key,no}` (a `no` of 0 is falsy
 *    in JS and therefore omitted), plus `preview:true` last for the preview call.
 *  - `confirm {txId}` answers 200 `{success:false, code:"NETWORK_ERROR"}` while the custodial txn
 *    is still pending (confirm.ts:206) — returned as-is for the caller to poll, never thrown.
 * Cached rows keep the dashboard's JSON verbatim so unmodelled fields survive.
 */
class RewardsRepositoryImpl(
    private val api: DashboardApi,
    private val summaryDao: RewardSummaryCacheDao,
    private val totalsDao: AssetTotalsCacheDao,
    private val sessionAddress: () -> String?,
    bus: SessionEventBus? = null,
    private val gson: Gson = DashboardGson.instance,
    private val now: () -> Long = System::currentTimeMillis,
    /** Receives `serverTime` (epoch millis) from responses that carry it, e.g. to feed `ServerClock`. */
    private val onServerTime: (Long) -> Unit = {},
) : RewardsRepository {

    private val calls = DashboardCalls(bus, gson)

    private fun requireAddress(): String =
        sessionAddress()?.takeIf { it.isNotBlank() }
            ?: throw DashboardException(DashboardErrorCodes.UNAUTHORIZED, "Not signed in to the dashboard")

    override fun observeAssetTotals(): Flow<AssetTotalsResponse?> =
        totalsDao.observe().map { row ->
            row?.let { runCatching { gson.fromJson(it.json, AssetTotalsResponse::class.java) }.getOrNull() }
        }

    override suspend fun refreshAssetTotals(): AssetTotalsResponse {
        requireAddress()
        val element = calls.execute { api.assetTotals(jsonBody { }.toJsonRequestBody()) }
        val totals = calls.parse(element, AssetTotalsResponse::class.java)
        totals.serverTime?.let(onServerTime)
        totalsDao.upsert(AssetTotalsCacheEntity(id = AssetTotalsCacheEntity.SINGLETON_ID, json = element.toString(), fetchedAt = now()))
        return totals
    }

    override fun observeSummary(minerKey: String): Flow<RewardSummary?> =
        summaryDao.observe(minerKey).map { row ->
            row?.let { runCatching { gson.fromJson(it.json, RewardSummary::class.java) }.getOrNull() }
        }

    override suspend fun refreshSummaries(minerKeys: List<String>): Map<String, RewardSummary> {
        val keys = minerKeys.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return emptyMap()
        requireAddress()
        val merged = LinkedHashMap<String, RewardSummary>()
        for (chunk in keys.chunked(SUMMARY_CHUNK)) {
            val element = calls.execute { api.rewardSummaryBatch(jsonBody { "miner_keys" to chunk }.toJsonRequestBody()) }
            val summaries = DashboardCalls.objectOrNull(DashboardCalls.objectOrNull(element)?.get("summaries")) ?: continue
            val stamp = now()
            val rows = ArrayList<RewardSummaryCacheEntity>(summaries.size())
            for ((key, value) in summaries.entrySet()) {
                if (!value.isJsonObject) continue
                val summary = calls.parse(value, RewardSummary::class.java)
                summary.serverTime?.let(onServerTime)
                merged[key] = summary
                rows += RewardSummaryCacheEntity(minerKey = key, json = value.toString(), fetchedAt = stamp)
            }
            if (rows.isNotEmpty()) summaryDao.upsertAll(rows)
        }
        return merged
    }

    override suspend fun refreshSummary(minerKey: String): RewardSummary? {
        requireAddress()
        val element = calls.execute { api.rewardSummary(jsonBody { "miner_key" to minerKey }.toJsonRequestBody()) }
        val summaryElement = DashboardCalls.objectOrNull(element)?.get("summary")?.takeIf { it.isJsonObject } ?: return null
        val summary = calls.parse(summaryElement, RewardSummary::class.java)
        summary.serverTime?.let(onServerTime)
        summaryDao.upsertAll(listOf(RewardSummaryCacheEntity(minerKey = minerKey, json = summaryElement.toString(), fetchedAt = now())))
        return summary
    }

    override suspend fun historyPage(minerKey: String, page: Int): RewardsPageResponse {
        requireAddress()
        val response = calls.call(RewardsPageResponse::class.java) {
            api.rewardsPage(jsonBody { "miner_key" to minerKey; "page" to page }.toJsonRequestBody())
        }
        response.serverTime?.let(onServerTime)
        return response
    }

    override suspend fun activity(): List<ActivityEvent> {
        val address = requireAddress()
        return calls.call(ActivityResponse::class.java) { api.recentActivity(jsonBody { "address" to address }.toJsonRequestBody()) }.events
    }

    override suspend fun prices(assetIds: List<Long>): Map<Long, BigDecimal> {
        val ids = assetIds.distinct().map { it.toString() }
        if (ids.isEmpty()) return emptyMap()
        val response = calls.call(PriceResponse::class.java) { api.prices(jsonBody { "asset_ids" to ids }.toJsonRequestBody()) }
        val result = LinkedHashMap<Long, BigDecimal>()
        for ((key, value) in response.prices) {
            val id = key.toLongOrNull() ?: continue
            result[id] = value
        }
        return result
    }

    override suspend fun claimPreview(minerKey: String, no: Int?): ClaimPreviewResponse {
        requireAddress()
        return calls.call(ClaimPreviewResponse::class.java) {
            api.claim(claimBody(minerKey, no).apply { "preview" to true }.toJsonRequestBody())
        }
    }

    override suspend fun claimEnvelope(minerKey: String, no: Int?): ClaimEnvelopeResponse {
        requireAddress()
        return calls.call(ClaimEnvelopeResponse::class.java) { api.claim(claimBody(minerKey, no).toJsonRequestBody()) }
    }

    override suspend fun claimConfirm(groupId: String, signedUserLegB64: String): ConfirmResultResponse {
        requireAddress()
        return calls.call(ConfirmResultResponse::class.java) {
            api.confirm(jsonBody { "groupId" to groupId; "signedUserLegB64" to signedUserLegB64 }.toJsonRequestBody())
        }
    }

    override suspend fun claimConfirmCustodial(txId: String): ConfirmResultResponse {
        requireAddress()
        return calls.call(ConfirmResultResponse::class.java) { api.confirm(jsonBody { "txId" to txId }.toJsonRequestBody()) }
    }

    override suspend fun clearCache() {
        summaryDao.deleteAll()
        totalsDao.deleteAll()
    }

    /** `no ? { miner_key, no } : { miner_key }` (Claim.tsx:377) — JS truthiness, so 0 is omitted too. */
    private fun claimBody(minerKey: String, no: Int?): JsonBody = jsonBody {
        "miner_key" to minerKey
        if (no != null && no != 0) "no" to no
    }

    companion object {
        /** Contract chunk size for `get-reward-summary-batch` (server cap is 200). */
        const val SUMMARY_CHUNK = 50
    }
}
