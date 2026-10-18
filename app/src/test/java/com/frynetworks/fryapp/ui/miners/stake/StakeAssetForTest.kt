package com.frynetworks.fryapp.ui.miners.stake

import com.frynetworks.fryapp.data.dashboard.model.Product
import com.frynetworks.fryapp.data.dashboard.model.ProductReward
import com.frynetworks.fryapp.data.dashboard.model.ProductTokens
import com.frynetworks.fryapp.domain.FryAsset
import com.frynetworks.fryapp.domain.MinerFamily
import com.frynetworks.fryapp.domain.StakeContext
import org.junit.Assert.assertEquals
import org.junit.Test

/** `tokens.register` may be a symbol or a numeric ASA id string; an unknown numeric id must not fall back silently. */
class StakeAssetForTest {

    // OSM's reward asset is TFRY, so any FNODE result below can only come from the token string.
    private val family = MinerFamily.OSM

    private fun registerAsset(register: String?) = StakeViewModel.assetFor(
        Product(key = "FEM", reward = ProductReward(tokens = ProductTokens(register = register))),
        StakeContext.Registration,
        family,
    )

    @Test fun `numeric ASA id resolves by id`() = assertEquals(FryAsset.FNODE, registerAsset("2485202024"))

    @Test fun `symbol still resolves case-insensitively`() {
        assertEquals(FryAsset.FNODE, registerAsset("fNODE"))
        assertEquals(FryAsset.FNODE, registerAsset("FNODE"))
    }

    @Test fun `none keeps the family reward asset`() = assertEquals(family.rewardAsset, registerAsset("none"))

    @Test fun `null keeps the family reward asset`() = assertEquals(family.rewardAsset, registerAsset(null))

    @Test fun `numeric id with no known asset yields null`() = assertEquals(null, registerAsset("999"))
}
