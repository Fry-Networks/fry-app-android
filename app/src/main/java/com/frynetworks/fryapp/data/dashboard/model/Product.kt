package com.frynetworks.fryapp.data.dashboard.model

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/**
 * `POST /api/products/get-product` entry / the dashboard's `products` collection shape
 * (dashb `lib/types.ts` `Product`). `key` is the miner-key prefix (e.g. `"FEM"`) this product
 * describes; `reward.stake` and `reward.tokens` are absent for products with no staking tiers.
 */
data class Product(
    @SerializedName("name") val name: String? = null,
    @SerializedName("key") val key: String? = null,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("color") val color: String? = null,
    @SerializedName("reward") val reward: ProductReward? = null,
)

data class ProductReward(
    @SerializedName("unverified") val unverified: BigDecimal? = null,
    @SerializedName("verified") val verified: BigDecimal? = null,
    @SerializedName("stake") val stake: ProductStake? = null,
    @SerializedName("tokens") val tokens: ProductTokens? = null,
)

/** USD amounts for the four stake contexts (registration/node) and verification tiers one/two. */
data class ProductStake(
    @SerializedName("stake_one") val stakeOne: BigDecimal? = null,
    @SerializedName("stake_two") val stakeTwo: BigDecimal? = null,
    @SerializedName("register") val register: BigDecimal? = null,
    @SerializedName("node") val node: BigDecimal? = null,
)

/** Asset symbols (e.g. `"tFRY"`) the dashboard expects a stake/reward to be paid in, per context. */
data class ProductTokens(
    @SerializedName("stake") val stake: String? = null,
    @SerializedName("reward") val reward: String? = null,
    @SerializedName("register") val register: String? = null,
    @SerializedName("node") val node: String? = null,
)
