package com.frynetworks.fryapp.update

import com.frynetworks.fryapp.update.UpdateFixtures.manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionPolicyTest {

    @Test
    fun `only a strictly higher versionCode is offered`() {
        assertEquals(UpdateDecision.Offer(manifest(7), required = false), VersionPolicy.decide(UpdateFixtures.PKG, 6, manifest(7)))
        assertEquals(UpdateDecision.UpToDate, VersionPolicy.decide(UpdateFixtures.PKG, 7, manifest(7)))
    }

    @Test
    fun `never a downgrade`() {
        assertEquals(UpdateDecision.UpToDate, VersionPolicy.decide(UpdateFixtures.PKG, 8, manifest(7)))
    }

    @Test
    fun `below minSupportedVersionCode the update is required`() {
        assertEquals(UpdateDecision.Offer(manifest(7), required = true), VersionPolicy.decide(UpdateFixtures.PKG, 5, manifest(7)))
    }

    @Test
    fun `another package (the debug build) never takes a release manifest`() {
        assertTrue(VersionPolicy.decide("com.frynetworks.fryapp.debug", 1, manifest(7)) is UpdateDecision.Rejected)
    }
}
