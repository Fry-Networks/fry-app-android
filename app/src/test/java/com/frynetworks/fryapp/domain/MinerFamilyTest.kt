package com.frynetworks.fryapp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinerFamilyTest {

    private val fnodeFamilies = setOf(
        MinerFamily.RDN, MinerFamily.SVN, MinerFamily.SDN, MinerFamily.CN,
        MinerFamily.AEM, MinerFamily.FEM,
    )

    private val nodeFamilies = setOf(
        MinerFamily.RDN, MinerFamily.SVN, MinerFamily.SDN, MinerFamily.CN,
    )

    @Test
    fun `fromMinerKey resolves every known prefix exactly`() {
        val cases = mapOf(
            "ISM-ABCDEF" to MinerFamily.ISM,
            "OSM-ABCDEF" to MinerFamily.OSM,
            "BM-ABCDEF" to MinerFamily.BM,
            "FEM-ABCDEF" to MinerFamily.FEM,
            "IDM-ABCDEF" to MinerFamily.IDM,
            "ODM-ABCDEF" to MinerFamily.ODM,
            "SDN-ABCDEF" to MinerFamily.SDN,
            "SVN-ABCDEF" to MinerFamily.SVN,
            "RDN-ABCDEF" to MinerFamily.RDN,
            "CN-ABCDEF" to MinerFamily.CN,
            "IHAQM-ABCDEF" to MinerFamily.IHAQM,
            "ILAQM-ABCDEF" to MinerFamily.ILAQM,
            "OMAQM-ABCDEF" to MinerFamily.OMAQM,
            "IMAQM-ABCDEF" to MinerFamily.IMAQM,
            "OHAQM-ABCDEF" to MinerFamily.OHAQM,
            "AOWSCM-ABCDEF" to MinerFamily.AOWSCM,
            "AOWCM-ABCDEF" to MinerFamily.AOWCM,
            "AIWCM-ABCDEF" to MinerFamily.AIWCM,
            "AOSCM-ABCDEF" to MinerFamily.AOSCM,
            "AISCM-ABCDEF" to MinerFamily.AISCM,
            "AOTCM-ABCDEF" to MinerFamily.AOTCM,
            "AITCM-ABCDEF" to MinerFamily.AITCM,
            "AIWSCM-ABCDEF" to MinerFamily.AIWSCM,
            "HWM-ABCDEF" to MinerFamily.HWM,
            "LWM-ABCDEF" to MinerFamily.LWM,
            "OLWQM-ABCDEF" to MinerFamily.OLWQM,
            "OHWQM-ABCDEF" to MinerFamily.OHWQM,
            "EM-ABCDEF" to MinerFamily.EM,
            "IRM-ABCDEF" to MinerFamily.IRM,
            "VRDN-ABCDEF" to MinerFamily.VRDN,
            "VSDN-ABCDEF" to MinerFamily.VSDN,
            "VSVN-ABCDEF" to MinerFamily.VSVN,
            "AEM-ABCDEF" to MinerFamily.AEM,
            "IOT-ABCDEF" to MinerFamily.IOT,
            "DVN-ABCDEF" to MinerFamily.DVN,
            "STO-ABCDEF" to MinerFamily.STO,
        )
        cases.forEach { (key, expected) ->
            assertEquals("key=$key", expected, MinerFamily.fromMinerKey(key))
        }
    }

    @Test
    fun `every MinerFamily entry round-trips through its own prefix`() {
        MinerFamily.entries.filter { it != MinerFamily.UNKNOWN }.forEach { family ->
            assertEquals(family, MinerFamily.fromMinerKey("${family.prefix}-000000"))
        }
    }

    @Test
    fun `IOT label is IOTVPN`() {
        assertEquals("IOTVPN", MinerFamily.IOT.label)
    }

    @Test
    fun `FEM and IOT resolve to distinct families (no prefix collision)`() {
        assertEquals(MinerFamily.FEM, MinerFamily.fromMinerKey("FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"))
        assertEquals(MinerFamily.IOT, MinerFamily.fromMinerKey("IOT-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"))
        assertTrue(MinerFamily.FEM != MinerFamily.IOT)
    }

    @Test
    fun `unrecognised prefix maps to UNKNOWN`() {
        assertEquals(MinerFamily.UNKNOWN, MinerFamily.fromMinerKey("ZZZ-000000"))
        assertEquals(MinerFamily.UNKNOWN, MinerFamily.fromMinerKey("XYZ123-ABC"))
    }

    @Test
    fun `empty or dashless key maps to UNKNOWN`() {
        assertEquals(MinerFamily.UNKNOWN, MinerFamily.fromMinerKey(""))
        assertEquals(MinerFamily.UNKNOWN, MinerFamily.fromMinerKey("NODASHATALL"))
    }

    @Test
    fun `UNKNOWN has an empty prefix and Other label`() {
        assertEquals("", MinerFamily.UNKNOWN.prefix)
        assertEquals("Other", MinerFamily.UNKNOWN.label)
        assertEquals(MinerCategory.OTHER, MinerFamily.UNKNOWN.category)
    }

    @Test
    fun `rewardAsset is fNODE for node, AEM and FEM families only`() {
        fnodeFamilies.forEach { family ->
            assertEquals("family=$family", FryAsset.FNODE, family.rewardAsset)
        }
    }

    @Test
    fun `rewardAsset is tFRY for every other family including UNKNOWN`() {
        (MinerFamily.entries.toSet() - fnodeFamilies).forEach { family ->
            assertEquals("family=$family", FryAsset.TFRY, family.rewardAsset)
        }
    }

    @Test
    fun `isNode is true only for RDN SVN SDN CN`() {
        nodeFamilies.forEach { family ->
            assertTrue("family=$family should be a node", family.isNode)
        }
    }

    @Test
    fun `virtual activation prefixes VRDN VSDN VSVN earn tFRY and are not nodes (get-asset-totals NODE_PREFIXES)`() {
        listOf(MinerFamily.VRDN, MinerFamily.VSDN, MinerFamily.VSVN).forEach { family ->
            assertEquals("family=$family", FryAsset.TFRY, family.rewardAsset)
            assertTrue("family=$family must not be a node", !family.isNode)
        }
    }

    @Test
    fun `isNode is false for FEM and AEM even though they earn fNODE`() {
        assertFalse(MinerFamily.FEM.isNode)
        assertFalse(MinerFamily.AEM.isNode)
    }

    @Test
    fun `isNode is false for every non-node family`() {
        (MinerFamily.entries.toSet() - nodeFamilies).forEach { family ->
            assertFalse("family=$family should not be a node", family.isNode)
        }
    }

    @Test
    fun `hardwareMacPrefixes matches the ten hardware-category prefixes`() {
        val expected = setOf("CN", "RDN", "SDN", "SVN", "BM", "FEM", "ISM", "OSM", "IDM", "ODM")
        assertEquals(expected, MinerFamily.hardwareMacPrefixes)
    }

    @Test
    fun `category HARDWARE covers exactly the ten hardware families`() {
        val expected = setOf(
            MinerFamily.ISM, MinerFamily.OSM, MinerFamily.BM, MinerFamily.FEM, MinerFamily.IDM,
            MinerFamily.ODM, MinerFamily.SDN, MinerFamily.SVN, MinerFamily.RDN, MinerFamily.CN,
        )
        assertEquals(expected, MinerFamily.entries.filter { it.category == MinerCategory.HARDWARE }.toSet())
    }

    @Test
    fun `category AIR covers the five air-quality families`() {
        val expected = setOf(
            MinerFamily.IHAQM, MinerFamily.ILAQM, MinerFamily.OMAQM, MinerFamily.IMAQM, MinerFamily.OHAQM,
        )
        assertEquals(expected, MinerFamily.entries.filter { it.category == MinerCategory.AIR }.toSet())
    }

    @Test
    fun `category CAMERA covers the eight camera families`() {
        val expected = setOf(
            MinerFamily.AOWSCM, MinerFamily.AOWCM, MinerFamily.AIWCM, MinerFamily.AOSCM,
            MinerFamily.AISCM, MinerFamily.AOTCM, MinerFamily.AITCM, MinerFamily.AIWSCM,
        )
        assertEquals(expected, MinerFamily.entries.filter { it.category == MinerCategory.CAMERA }.toSet())
    }

    @Test
    fun `category WEATHER covers HWM and LWM`() {
        assertEquals(
            setOf(MinerFamily.HWM, MinerFamily.LWM),
            MinerFamily.entries.filter { it.category == MinerCategory.WEATHER }.toSet(),
        )
    }

    @Test
    fun `category WATER covers OLWQM and OHWQM`() {
        assertEquals(
            setOf(MinerFamily.OLWQM, MinerFamily.OHWQM),
            MinerFamily.entries.filter { it.category == MinerCategory.WATER }.toSet(),
        )
    }

    @Test
    fun `category RADIATION covers IRM`() {
        assertEquals(setOf(MinerFamily.IRM), MinerFamily.entries.filter { it.category == MinerCategory.RADIATION }.toSet())
    }

    @Test
    fun `category VIRTUAL covers VRDN VSDN VSVN`() {
        assertEquals(
            setOf(MinerFamily.VRDN, MinerFamily.VSDN, MinerFamily.VSVN),
            MinerFamily.entries.filter { it.category == MinerCategory.VIRTUAL }.toSet(),
        )
    }

    @Test
    fun `category IOTVPN covers IOT`() {
        assertEquals(setOf(MinerFamily.IOT), MinerFamily.entries.filter { it.category == MinerCategory.IOTVPN }.toSet())
    }

    @Test
    fun `every prefix is unique across the enum except UNKNOWN`() {
        val prefixes = MinerFamily.entries.filter { it != MinerFamily.UNKNOWN }.map { it.prefix }
        assertEquals(prefixes.size, prefixes.toSet().size)
    }
}
