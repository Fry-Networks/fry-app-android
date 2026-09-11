package com.frynetworks.fryapp.domain

/**
 * Broad UI grouping for a [MinerFamily], mirroring the dashboard's portal/collection groupings
 * (dashb `lib/credentials-utils.ts` MINER_PORTAL_KEY / `collectionFor`) closely enough to render
 * consistent filter chips. Not every value is necessarily populated by a current prefix.
 */
enum class MinerCategory {
    HARDWARE, AIR, CAMERA, WEATHER, WATER, ENERGY, RADIATION, VIRTUAL, NODE, EDGE, IOTVPN, OTHER
}

/**
 * Miner-key prefix -> family vocabulary for the Fry Dashboard product catalog. Reward-asset
 * bucketing mirrors `pages/api/rewards/get-asset-totals.ts` on the dashboard: NODE_PREFIXES
 * (RDN/SVN/SDN/CN) plus AEM and FEM (and the virtual node prefixes VRDN/VSDN/VSVN) earn fNODE;
 * every other family earns tFRY. [isNode] mirrors that same route's `NODE_PREFIXES` set plus the
 * virtual node prefixes — note FEM and AEM earn fNODE but are *not* nodes.
 *
 * This is deliberately a separate, dashboard-scoped enum from [com.frynetworks.fryapp.util.MinerType]
 * (the Home-screen provisioning vocabulary, which only distinguishes FEM/IOTVPN for devices this
 * app itself provisions) — see the blueprint's decision to expand `MinerType` only if the operator
 * later wants a single unified vocabulary; `util/MinerType.kt` is left byte-identical here.
 */
enum class MinerFamily(
    val prefix: String,
    val label: String,
    val category: MinerCategory,
    val rewardAsset: FryAsset,
    val isNode: Boolean,
) {
    ISM("ISM", "ISM", MinerCategory.HARDWARE, FryAsset.TFRY, false),
    OSM("OSM", "OSM", MinerCategory.HARDWARE, FryAsset.TFRY, false),
    BM("BM", "BM", MinerCategory.HARDWARE, FryAsset.TFRY, false),
    FEM("FEM", "FEM", MinerCategory.HARDWARE, FryAsset.FNODE, false),
    IDM("IDM", "IDM", MinerCategory.HARDWARE, FryAsset.TFRY, false),
    ODM("ODM", "ODM", MinerCategory.HARDWARE, FryAsset.TFRY, false),
    SDN("SDN", "SDN", MinerCategory.HARDWARE, FryAsset.FNODE, true),
    SVN("SVN", "SVN", MinerCategory.HARDWARE, FryAsset.FNODE, true),
    RDN("RDN", "RDN", MinerCategory.HARDWARE, FryAsset.FNODE, true),
    CN("CN", "CN", MinerCategory.HARDWARE, FryAsset.FNODE, true),

    IHAQM("IHAQM", "IHAQM", MinerCategory.AIR, FryAsset.TFRY, false),
    ILAQM("ILAQM", "ILAQM", MinerCategory.AIR, FryAsset.TFRY, false),
    OMAQM("OMAQM", "OMAQM", MinerCategory.AIR, FryAsset.TFRY, false),
    IMAQM("IMAQM", "IMAQM", MinerCategory.AIR, FryAsset.TFRY, false),
    OHAQM("OHAQM", "OHAQM", MinerCategory.AIR, FryAsset.TFRY, false),

    AOWSCM("AOWSCM", "AOWSCM", MinerCategory.CAMERA, FryAsset.TFRY, false),
    AOWCM("AOWCM", "AOWCM", MinerCategory.CAMERA, FryAsset.TFRY, false),
    AIWCM("AIWCM", "AIWCM", MinerCategory.CAMERA, FryAsset.TFRY, false),
    AOSCM("AOSCM", "AOSCM", MinerCategory.CAMERA, FryAsset.TFRY, false),
    AISCM("AISCM", "AISCM", MinerCategory.CAMERA, FryAsset.TFRY, false),
    AOTCM("AOTCM", "AOTCM", MinerCategory.CAMERA, FryAsset.TFRY, false),
    AITCM("AITCM", "AITCM", MinerCategory.CAMERA, FryAsset.TFRY, false),
    AIWSCM("AIWSCM", "AIWSCM", MinerCategory.CAMERA, FryAsset.TFRY, false),

    HWM("HWM", "HWM", MinerCategory.WEATHER, FryAsset.TFRY, false),
    LWM("LWM", "LWM", MinerCategory.WEATHER, FryAsset.TFRY, false),

    OLWQM("OLWQM", "OLWQM", MinerCategory.WATER, FryAsset.TFRY, false),
    OHWQM("OHWQM", "OHWQM", MinerCategory.WATER, FryAsset.TFRY, false),

    EM("EM", "EM", MinerCategory.ENERGY, FryAsset.TFRY, false),

    IRM("IRM", "IRM", MinerCategory.RADIATION, FryAsset.TFRY, false),

    VRDN("VRDN", "VRDN", MinerCategory.VIRTUAL, FryAsset.FNODE, true),
    VSDN("VSDN", "VSDN", MinerCategory.VIRTUAL, FryAsset.FNODE, true),
    VSVN("VSVN", "VSVN", MinerCategory.VIRTUAL, FryAsset.FNODE, true),

    AEM("AEM", "AEM", MinerCategory.ENERGY, FryAsset.FNODE, false),

    IOT("IOT", "IOTVPN", MinerCategory.IOTVPN, FryAsset.TFRY, false),

    DVN("DVN", "DVN", MinerCategory.EDGE, FryAsset.TFRY, false),
    STO("STO", "STO", MinerCategory.EDGE, FryAsset.TFRY, false),

    /** No prefix matched (or the key was empty/had no `-`); renders as a generic "Other" chip. */
    UNKNOWN("", "Other", MinerCategory.OTHER, FryAsset.TFRY, false),
    ;

    companion object {
        /** Hardware families whose devices carry a wired/Wi-Fi MAC (PROTOCOL.md-style keys). */
        val hardwareMacPrefixes: Set<String> = setOf(
            "CN", "RDN", "SDN", "SVN", "BM", "FEM", "ISM", "OSM", "IDM", "ODM",
        )

        /** The prefix is the text before the first `-`, matched exactly; anything else is [UNKNOWN]. */
        fun fromMinerKey(minerKey: String): MinerFamily {
            val prefix = minerKey.substringBefore('-')
            if (prefix.isEmpty()) return UNKNOWN
            return entries.firstOrNull { it.prefix == prefix } ?: UNKNOWN
        }
    }
}
