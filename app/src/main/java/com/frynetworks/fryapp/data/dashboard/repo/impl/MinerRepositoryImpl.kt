package com.frynetworks.fryapp.data.dashboard.repo.impl

import com.frynetworks.fryapp.data.DeviceDao
import com.frynetworks.fryapp.data.dashboard.api.DashboardApi
import com.frynetworks.fryapp.data.dashboard.api.DashboardCalls
import com.frynetworks.fryapp.data.dashboard.api.DashboardErrorCodes
import com.frynetworks.fryapp.data.dashboard.api.toJsonRequestBody
import com.frynetworks.fryapp.data.dashboard.db.MinerDetailCacheDao
import com.frynetworks.fryapp.data.dashboard.db.MinerDetailCacheEntity
import com.frynetworks.fryapp.data.dashboard.db.RemoteMinerDao
import com.frynetworks.fryapp.data.dashboard.db.RemoteMinerEntity
import com.frynetworks.fryapp.data.dashboard.db.RewardSummaryCacheDao
import com.frynetworks.fryapp.data.dashboard.model.BatchDevicesResponse
import com.frynetworks.fryapp.data.dashboard.model.DashboardGson
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetail
import com.frynetworks.fryapp.data.dashboard.model.DeviceDetailResponse
import com.frynetworks.fryapp.data.dashboard.model.HardwareStatusEntry
import com.frynetworks.fryapp.data.dashboard.model.MinerListResponse
import com.frynetworks.fryapp.data.dashboard.model.MyKeysResponse
import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.RewardSummary
import com.frynetworks.fryapp.data.dashboard.model.StatusSummaryResponse
import com.frynetworks.fryapp.data.dashboard.repo.FleetSummary
import com.frynetworks.fryapp.data.dashboard.repo.MinerListItem
import com.frynetworks.fryapp.data.dashboard.repo.MinerMerge
import com.frynetworks.fryapp.data.dashboard.repo.MinerRepository
import com.frynetworks.fryapp.data.dashboard.repo.RewardsRepository
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.network.dashboard.DashboardException
import com.frynetworks.fryapp.network.dashboard.SessionEventBus
import com.frynetworks.fryapp.network.dashboard.jsonBody
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Retrofit + Room implementation of [MinerRepository]. See the interface for the route contract;
 * the wire shapes that differ from the modelled DTOs are handled here:
 *  - `POST /api/hardware/status` answers a flat `{ [miner_key]: {...} }` object (hardware/status.ts:196),
 *    not `{success, statuses}` — both shapes are accepted.
 *  - `POST /api/products/get-product` answers `{success, data: [Product]}` (get-product.ts:31).
 *  - `POST /api/devices/status-summary` answers 503 `ACTIVITY_UNAVAILABLE` when activity data is
 *    degraded (status-summary.ts:39-44) — surfaced as a null [FleetSummary], never an error.
 *  - `POST /api/change-name` only accepts POST (change-name.ts:19); the browser's PUT is a dashboard bug.
 * `sessionAddress` is read on every call so a sign-in/sign-out between calls is honoured.
 */
class MinerRepositoryImpl(
    private val api: DashboardApi,
    private val remoteDao: RemoteMinerDao,
    private val detailDao: MinerDetailCacheDao,
    private val summaryDao: RewardSummaryCacheDao,
    private val deviceDao: DeviceDao,
    private val rewards: RewardsRepository,
    private val sessionAddress: () -> String?,
    bus: SessionEventBus? = null,
    private val gson: Gson = DashboardGson.instance,
    private val now: () -> Long = System::currentTimeMillis,
) : MinerRepository {

    private val calls = DashboardCalls(bus, gson)
    private val fleet = MutableStateFlow<FleetSummary?>(null)
    private val hardware = MutableStateFlow<Map<String, HardwareStatusEntry>>(emptyMap())

    /** Last `/api/hardware/status` answer seen by [refresh] (hardware families only); empty before the first refresh. */
    val lastHardwareStatus: StateFlow<Map<String, HardwareStatusEntry>> = hardware

    private fun requireAddress(): String =
        sessionAddress()?.takeIf { it.isNotBlank() }
            ?: throw DashboardException(DashboardErrorCodes.UNAUTHORIZED, "Not signed in to the dashboard")

    override fun observeMiners(): Flow<List<MinerListItem>> =
        combine(remoteDao.observeAll(), deviceDao.observeAll(), summaryDao.observeAll()) { remote, local, cached ->
            val summaries = cached.mapNotNull { row ->
                runCatching { gson.fromJson(row.json, RewardSummary::class.java) }.getOrNull()?.let { row.minerKey to it }
            }.toMap()
            MinerMerge.mergeMiners(remote.map { it.toSummary() }, local, summaries, now())
        }

    override suspend fun refresh() {
        val address = requireAddress()

        // 1. list -> remote_miners (upsert, then trim stale keys — no empty window for observers)
        val list = calls.call(MinerListResponse::class.java) { api.listDevices(jsonBody { "address" to address }.toJsonRequestBody()) }
        val fetchedAt = now()
        val rows = list.minerKeys.mapNotNull { RemoteMinerEntity.from(it, fetchedAt) }.distinctBy { it.minerKey }
        if (rows.isEmpty()) {
            remoteDao.deleteAll()
        } else {
            remoteDao.upsertAll(rows)
            remoteDao.deleteNotIn(rows.map { it.minerKey })
        }
        val keys = rows.map { it.minerKey }

        // 2. batch details, <=200 keys per call, sequential
        for (chunk in keys.chunked(BATCH_CHUNK)) {
            val batch = calls.call(BatchDevicesResponse::class.java) {
                api.batchDevices(jsonBody { "miner_keys" to chunk }.toJsonRequestBody())
            }
            val stamp = now()
            val cached = batch.devices.mapNotNull { (key, detail) ->
                val raw = detail.raw ?: return@mapNotNull null
                MinerDetailCacheEntity(minerKey = key, json = raw.toString(), fetchedAt = stamp)
            }
            if (cached.isNotEmpty()) detailDao.upsertAll(cached)
        }

        // 3. reward summaries (<=50 per call, chunked by RewardsRepository) and 4. asset totals
        if (keys.isNotEmpty()) rewards.refreshSummaries(keys)
        rewards.refreshAssetTotals()

        // 5. fleet counts (503 -> null, not an error)
        fleet.value = fetchFleetSummary(address)

        // 6. hardware verification, hardware families only
        val hardwareKeys = keys.filter { it.substringBefore('-') in MinerFamily.hardwareMacPrefixes }
        if (hardwareKeys.isNotEmpty()) hardware.value = hardwareStatus(hardwareKeys)
    }

    private suspend fun fetchFleetSummary(address: String): FleetSummary? {
        val response = try {
            calls.call(StatusSummaryResponse::class.java) {
                api.statusSummary(jsonBody { "address" to address }.toJsonRequestBody())
            }
        } catch (e: DashboardException) {
            if (e.httpStatus == 503) return null
            throw e
        }
        return FleetSummary.from(response)
    }

    override fun observeDetail(minerKey: String): Flow<DeviceDetail?> =
        detailDao.observe(minerKey).map { row ->
            row?.let { runCatching { gson.fromJson(it.json, DeviceDetail::class.java) }.getOrNull() }
        }

    override suspend fun refreshDetail(minerKey: String): DeviceDetail {
        val address = requireAddress()
        val response = calls.call(DeviceDetailResponse::class.java) {
            api.deviceDetail(minerKey, jsonBody { "address" to address }.toJsonRequestBody())
        }
        val detail = response.device
            ?: throw DashboardException(DashboardErrorCodes.DEVICE_NOT_FOUND, "Device not found", httpStatus = 200)
        detailDao.upsert(MinerDetailCacheEntity(minerKey = minerKey, json = (detail.raw ?: JsonObject()).toString(), fetchedAt = now()))
        return detail
    }

    override fun observeFleetSummary(): Flow<FleetSummary?> = fleet

    override suspend fun hardwareStatus(minerKeys: List<String>): Map<String, HardwareStatusEntry> {
        val keys = minerKeys.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return emptyMap()
        requireAddress()
        val element = calls.execute { api.hardwareStatus(jsonBody { "miner_keys" to keys }.toJsonRequestBody()) }
        val obj = DashboardCalls.objectOrNull(element) ?: return emptyMap()
        val source = DashboardCalls.objectOrNull(obj.get("statuses")) ?: obj
        val result = LinkedHashMap<String, HardwareStatusEntry>()
        for ((key, value) in source.entrySet()) {
            if (key == "success" || !value.isJsonObject) continue
            result[key] = calls.parse(value, HardwareStatusEntry::class.java)
        }
        return result
    }

    override suspend fun myKeys(): MyKeysResponse {
        requireAddress()
        val element = calls.execute { api.myKeys() }
        return MyKeysMapper.attachIotCredentials(calls.parse(element, MyKeysResponse::class.java), element, gson)
    }

    override suspend fun product(minerKey: String): Product? {
        requireAddress()
        val element = calls.execute { api.getProduct(jsonBody { "miner_key" to minerKey }.toJsonRequestBody()) }
        val data = DashboardCalls.objectOrNull(element)?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val first = data.firstOrNull { it.isJsonObject } ?: return null
        return calls.parse(first, Product::class.java)
    }

    override suspend fun rename(minerKey: String, nickname: String) {
        val address = requireAddress()
        calls.execute { api.changeName(jsonBody { "address" to address; "name" to nickname; "miner_key" to minerKey }.toJsonRequestBody()) }
        remoteDao.updateNickname(minerKey, nickname)
    }

    override suspend fun setRewardWallet(minerKey: String, wallet: String) {
        val address = requireAddress()
        calls.execute {
            api.saveWalletInfo(jsonBody { "miner_key" to minerKey; "reward_wallet" to wallet; "address" to address }.toJsonRequestBody())
        }
    }

    override suspend fun clearCache() {
        remoteDao.deleteAll()
        detailDao.deleteAll()
        fleet.value = null
        hardware.value = emptyMap()
    }

    companion object {
        /** `MAX_BATCH_SIZE` in devices/batch.ts:18. */
        const val BATCH_CHUNK = 200
    }
}
