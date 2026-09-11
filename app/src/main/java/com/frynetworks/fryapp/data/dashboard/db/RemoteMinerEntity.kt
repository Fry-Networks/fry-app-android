package com.frynetworks.fryapp.data.dashboard.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.frynetworks.fryapp.data.dashboard.model.MinerSummary

/**
 * One cached row of `POST /api/devices/list` (`remote_miners`, Room v2). Columns mirror
 * [MinerSummary] one-to-one so the merge with the local `devices` table never needs a JSON parse.
 * [fetchedAt] is the phone's clock at the refresh that wrote the row.
 */
@Entity(tableName = "remote_miners")
data class RemoteMinerEntity(
    @PrimaryKey val minerKey: String,
    val nickname: String?,
    val productName: String?,
    val status: String?,
    val isRegistered: Boolean,
    val virtual: Boolean,
    val activated: Boolean,
    val verified: Boolean,
    val staked: Boolean,
    val legacyStakeUnlocked: Boolean,
    val byod: Boolean,
    val node: Boolean,
    val fetchedAt: Long,
) {
    fun toSummary(): MinerSummary = MinerSummary(
        minerKey = minerKey,
        nickname = nickname,
        productName = productName,
        status = status,
        isRegistered = isRegistered,
        virtual = virtual,
        activated = activated,
        verified = verified,
        staked = staked,
        legacyStakeUnlocked = legacyStakeUnlocked,
        byod = byod,
        node = node,
    )

    companion object {
        /** null when the dashboard row has no usable `miner_key` (never cached). */
        fun from(summary: MinerSummary, fetchedAt: Long): RemoteMinerEntity? {
            val key = summary.minerKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return RemoteMinerEntity(
                minerKey = key,
                nickname = summary.nickname,
                productName = summary.productName,
                status = summary.status,
                isRegistered = summary.isRegistered,
                virtual = summary.virtual,
                activated = summary.activated,
                verified = summary.verified,
                staked = summary.staked,
                legacyStakeUnlocked = summary.legacyStakeUnlocked,
                byod = summary.byod,
                node = summary.node,
                fetchedAt = fetchedAt,
            )
        }
    }
}
