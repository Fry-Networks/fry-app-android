package com.frynetworks.fryapp.fakes

import com.frynetworks.fryapp.data.dashboard.model.ActivityEvent
import com.frynetworks.fryapp.data.dashboard.model.AssetTotalsResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimEnvelopeResponse
import com.frynetworks.fryapp.data.dashboard.model.ClaimPreviewResponse
import com.frynetworks.fryapp.data.dashboard.model.ConfirmResultResponse
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.model.HardwareStatusEntry
import com.frynetworks.fryapp.data.dashboard.model.MyKeysResponse
import com.frynetworks.fryapp.data.dashboard.model.PrecheckResponse
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.data.dashboard.model.RewardsPageResponse
import com.frynetworks.fryapp.data.dashboard.model.StakeSubmitResponse
import com.frynetworks.fryapp.data.dashboard.model.WithdrawableResponse
import com.frynetworks.fryapp.data.dashboard.repo.AlgodRepository
import com.frynetworks.fryapp.data.dashboard.repo.FleetSummary
import com.frynetworks.fryapp.data.dashboard.repo.MinerListItem
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeRepository
import com.frynetworks.fryapp.data.dashboard.repo.StakeSubmitPayload
import com.frynetworks.fryapp.domain.StakeContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal

/**
 * Hand-written fakes for the repository interfaces (never MockK). Each call can be scripted to
 * throw via the `*Error` fields; every call is counted so tests can assert the pipeline shape.
 */
class FakeMinerRepository : MinerRepository {
    val miners = MutableStateFlow<List<MinerListItem>>(emptyList())
    val details = MutableStateFlow<Map<String, DeviceDetail>>(emptyMap())
    val fleet = MutableStateFlow<FleetSummary?>(null)
    var products: Map<String, Product?> = emptyMap()
    var hardware: Map<String, HardwareStatusEntry> = emptyMap()
    var keys: MyKeysResponse = MyKeysResponse()

    var refreshError: Throwable? = null
    var refreshDetailError: Throwable? = null
    var productError: Throwable? = null
    var myKeysError: Throwable? = null
    var renameError: Throwable? = null
    var rewardWalletError: Throwable? = null

    var refreshCalls = 0
    var refreshDetailCalls = 0
    var clearCacheCalls = 0
    val renames = mutableListOf<Pair<String, String>>()
    val rewardWallets = mutableListOf<Pair<String, String>>()

    /** What [refresh] publishes into [miners] once it succeeds (simulates cache-after-fetch). */
    var onRefresh: (() -> Unit)? = null

    /** When set, [refresh] suspends until the gate completes (lets tests observe the Loading phase). */
    var refreshGate: CompletableDeferred<Unit>? = null

    override fun observeMiners(): Flow<List<MinerListItem>> = miners

    override suspend fun refresh() {
        refreshCalls++
        refreshGate?.await()
        refreshError?.let { throw it }
        onRefresh?.invoke()
    }

    override fun observeDetail(minerKey: String): Flow<DeviceDetail?> = details.map { it[minerKey] }

    override suspend fun refreshDetail(minerKey: String): DeviceDetail {
        refreshDetailCalls++
        refreshDetailError?.let { throw it }
        return details.value[minerKey] ?: throw IllegalStateException("no detail scripted for $minerKey")
    }

    override fun observeFleetSummary(): Flow<FleetSummary?> = fleet

    override suspend fun hardwareStatus(minerKeys: List<String>): Map<String, HardwareStatusEntry> =
        hardware.filterKeys { it in minerKeys }

    override suspend fun myKeys(): MyKeysResponse {
        myKeysError?.let { throw it }
        return keys
    }

    override suspend fun product(minerKey: String): Product? {
        productError?.let { throw it }
        return products[minerKey]
    }

    override suspend fun rename(minerKey: String, nickname: String) {
        renameError?.let { throw it }
        renames += minerKey to nickname
        details.value[minerKey]?.let { d -> details.value = details.value + (minerKey to d.copy(nickname = nickname)) }
    }

    override suspend fun setRewardWallet(minerKey: String, wallet: String) {
        rewardWalletError?.let { throw it }
        rewardWallets += minerKey to wallet
    }

    override suspend fun clearCache() {
        clearCacheCalls++
        miners.value = emptyList()
        details.value = emptyMap()
    }
}

class FakeRewardsRepository : RewardsRepository {
    val totals = MutableStateFlow<AssetTotalsResponse?>(null)
    val summaries = MutableStateFlow<Map<String, RewardSummary>>(emptyMap())
    var pages: Map<Int, RewardsPageResponse> = emptyMap()
    var activityEvents: List<ActivityEvent> = emptyList()
    var priceTable: Map<Long, BigDecimal> = emptyMap()
    var preview: ClaimPreviewResponse = ClaimPreviewResponse(success = true, preview = true)
    var envelope: ClaimEnvelopeResponse = ClaimEnvelopeResponse(success = true)
    var confirmResult: ConfirmResultResponse = ConfirmResultResponse(ok = true, txId = "CONFIRMTX")
    /** Scripted custodial poll responses, consumed in order; the last one repeats. */
    var custodialResults: List<ConfirmResultResponse> = listOf(ConfirmResultResponse(ok = true, txId = "CUSTODIALTX"))

    var pageError: Throwable? = null
    var activityError: Throwable? = null
    var pricesError: Throwable? = null
    var previewError: Throwable? = null
    var envelopeError: Throwable? = null
    var confirmError: Throwable? = null
    var custodialError: Throwable? = null

    var refreshSummaryCalls = 0
    var clearCacheCalls = 0
    var custodialPolls = 0
    val previewCalls = mutableListOf<Pair<String, Int?>>()
    val envelopeCalls = mutableListOf<Pair<String, Int?>>()
    val confirmCalls = mutableListOf<Pair<String, String>>()
    val pageCalls = mutableListOf<Pair<String, Int>>()

    override fun observeAssetTotals(): Flow<AssetTotalsResponse?> = totals
    override suspend fun refreshAssetTotals(): AssetTotalsResponse = totals.value ?: AssetTotalsResponse()
    override fun observeSummary(minerKey: String): Flow<RewardSummary?> = summaries.map { it[minerKey] }
    override suspend fun refreshSummaries(minerKeys: List<String>): Map<String, RewardSummary> = summaries.value.filterKeys { it in minerKeys }

    override suspend fun refreshSummary(minerKey: String): RewardSummary? {
        refreshSummaryCalls++
        return summaries.value[minerKey]
    }

    override suspend fun historyPage(minerKey: String, page: Int): RewardsPageResponse {
        pageCalls += minerKey to page
        pageError?.let { throw it }
        return pages[page] ?: RewardsPageResponse(success = true, items = emptyList(), totalPages = pages.size.coerceAtLeast(1))
    }

    override suspend fun activity(): List<ActivityEvent> {
        activityError?.let { throw it }
        return activityEvents
    }

    override suspend fun prices(assetIds: List<Long>): Map<Long, BigDecimal> {
        pricesError?.let { throw it }
        return priceTable.filterKeys { it in assetIds }
    }

    override suspend fun claimPreview(minerKey: String, no: Int?): ClaimPreviewResponse {
        previewCalls += minerKey to no
        previewError?.let { throw it }
        return preview
    }

    override suspend fun claimEnvelope(minerKey: String, no: Int?): ClaimEnvelopeResponse {
        envelopeCalls += minerKey to no
        envelopeError?.let { throw it }
        return envelope
    }

    override suspend fun claimConfirm(groupId: String, signedUserLegB64: String): ConfirmResultResponse {
        confirmCalls += groupId to signedUserLegB64
        confirmError?.let { throw it }
        return confirmResult
    }

    override suspend fun claimConfirmCustodial(txId: String): ConfirmResultResponse {
        custodialPolls++
        custodialError?.let { throw it }
        return custodialResults.getOrNull(custodialPolls - 1) ?: custodialResults.last()
    }

    override suspend fun clearCache() {
        clearCacheCalls++
        totals.value = null
        summaries.value = emptyMap()
    }
}

class FakeStakeRepository : StakeRepository {
    var withdrawableTable: Map<String, WithdrawableResponse> = emptyMap()
    var precheckError: Throwable? = null
    var verifyError: Throwable? = null
    var submitError: Throwable? = null
    var withdrawError: Throwable? = null
    var submitResponse: StakeSubmitResponse = StakeSubmitResponse(success = true, txId = "RECORDEDTX")
    var withdrawResponse: StakeSubmitResponse = StakeSubmitResponse(success = true, message = "ok", txId = "WITHDRAWTX")

    val precheckCalls = mutableListOf<Triple<String, String, StakeContext>>()
    val verifyCalls = mutableListOf<Pair<String, String>>()
    val submitCalls = mutableListOf<Pair<StakeContext, StakeSubmitPayload>>()
    val withdrawCalls = mutableListOf<Triple<StakeContext, String, String>>()
    var withdrawableCalls = 0

    override suspend fun withdrawable(address: String, minerKey: String, context: StakeContext): WithdrawableResponse {
        withdrawableCalls++
        return withdrawableTable[contextKey(context)] ?: WithdrawableResponse(success = true, withdrawable = false)
    }

    override suspend fun precheck(minerKey: String, address: String, context: StakeContext): PrecheckResponse {
        precheckCalls += Triple(minerKey, address, context)
        precheckError?.let { throw it }
        return PrecheckResponse(success = true)
    }

    override suspend fun verifyTxn(address: String, txId: String) {
        verifyCalls += address to txId
        verifyError?.let { throw it }
    }

    override suspend fun submit(context: StakeContext, payload: StakeSubmitPayload): StakeSubmitResponse {
        submitCalls += context to payload
        submitError?.let { throw it }
        return submitResponse
    }

    override suspend fun withdraw(context: StakeContext, address: String, minerKey: String): StakeSubmitResponse {
        withdrawCalls += Triple(context, address, minerKey)
        withdrawError?.let { throw it }
        return withdrawResponse
    }

    companion object {
        fun contextKey(context: StakeContext): String = when (context) {
            StakeContext.Registration -> "registration"
            StakeContext.Node -> "node"
            is StakeContext.Verification -> "verification"
        }
    }
}

class FakeAlgodRepository : AlgodRepository {
    var algoBalance: Long = 10_000_000L
    /** assetId -> balance; absent = not opted in. */
    val assetBalances = mutableMapOf<Long, Long>()
    /** Opt-ins that become visible only after [optInVisibleAfterChecks] further checks (simulates ledger lag). */
    var optInVisibleAfterChecks = 0
    var balanceError: Throwable? = null
    var waitError: Throwable? = null
    var isOptedInCalls = 0
    val waited = mutableListOf<String>()

    fun markOptedIn(assetId: Long, balance: Long = 0L) {
        assetBalances[assetId] = balance
    }

    override suspend fun algoBalanceMicro(address: String): Long {
        balanceError?.let { throw it }
        return algoBalance
    }

    override suspend fun assetBalanceMicro(address: String, assetId: Long): Long? {
        balanceError?.let { throw it }
        isOptedInCalls++
        if (optInVisibleAfterChecks > 0) {
            optInVisibleAfterChecks--
            return null
        }
        return assetBalances[assetId]
    }

    override suspend fun authAddress(address: String): String? = null

    override suspend fun waitConfirmed(txId: String, rounds: Int) {
        waited += txId
        waitError?.let { throw it }
    }
}
