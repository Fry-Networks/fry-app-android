package com.frynetworks.fryapp.data.dashboard.repo

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
import com.frynetworks.fryapp.domain.StakeContext
import com.frynetworks.fryapp.network.dashboard.DashboardException
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Placeholder bindings so the app graph compiles before the data layer lands. Every call throws
 * [DashboardException] `NOT_WIRED`; observers emit nothing useful. Replaced by the real
 * implementations in `DashboardRepositoryModule` (data layer) — never ship these.
 */
private fun notWired(): Nothing = throw DashboardException("NOT_WIRED", "Dashboard data layer is not wired yet")

class NotWiredMinerRepository : MinerRepository {
    override fun observeMiners(): Flow<List<MinerListItem>> = flowOf(emptyList())
    override suspend fun refresh() = notWired()
    override fun observeDetail(minerKey: String): Flow<DeviceDetail?> = flowOf(null)
    override suspend fun refreshDetail(minerKey: String): DeviceDetail = notWired()
    override fun observeFleetSummary(): Flow<FleetSummary?> = flowOf(null)
    override suspend fun hardwareStatus(minerKeys: List<String>): Map<String, HardwareStatusEntry> = notWired()
    override suspend fun myKeys(): MyKeysResponse = notWired()
    override suspend fun product(minerKey: String): Product? = notWired()
    override suspend fun rename(minerKey: String, nickname: String) = notWired()
    override suspend fun setRewardWallet(minerKey: String, wallet: String) = notWired()
    override suspend fun clearCache() {}
}

class NotWiredRewardsRepository : RewardsRepository {
    override fun observeAssetTotals(): Flow<AssetTotalsResponse?> = flowOf(null)
    override suspend fun refreshAssetTotals(): AssetTotalsResponse = notWired()
    override fun observeSummary(minerKey: String): Flow<RewardSummary?> = flowOf(null)
    override suspend fun refreshSummaries(minerKeys: List<String>): Map<String, RewardSummary> = notWired()
    override suspend fun refreshSummary(minerKey: String): RewardSummary? = notWired()
    override suspend fun historyPage(minerKey: String, page: Int): RewardsPageResponse = notWired()
    override suspend fun activity(): List<ActivityEvent> = notWired()
    override suspend fun prices(assetIds: List<Long>): Map<Long, BigDecimal> = notWired()
    override suspend fun claimPreview(minerKey: String, no: Int?): ClaimPreviewResponse = notWired()
    override suspend fun claimEnvelope(minerKey: String, no: Int?): ClaimEnvelopeResponse = notWired()
    override suspend fun claimConfirm(groupId: String, signedUserLegB64: String): ConfirmResultResponse = notWired()
    override suspend fun claimConfirmCustodial(txId: String): ConfirmResultResponse = notWired()
    override suspend fun clearCache() {}
}

class NotWiredStakeRepository : StakeRepository {
    override suspend fun withdrawable(address: String, minerKey: String, context: StakeContext): WithdrawableResponse = notWired()
    override suspend fun precheck(minerKey: String, address: String, context: StakeContext): PrecheckResponse = notWired()
    override suspend fun verifyTxn(address: String, txId: String) = notWired()
    override suspend fun submit(context: StakeContext, payload: StakeSubmitPayload): StakeSubmitResponse = notWired()
    override suspend fun withdraw(context: StakeContext, address: String, minerKey: String): StakeSubmitResponse = notWired()
}

class NotWiredAlgodRepository : AlgodRepository {
    override suspend fun algoBalanceMicro(address: String): Long = notWired()
    override suspend fun assetBalanceMicro(address: String, assetId: Long): Long? = notWired()
    override suspend fun authAddress(address: String): String? = notWired()
    override suspend fun waitConfirmed(txId: String, rounds: Int) = notWired()
}
