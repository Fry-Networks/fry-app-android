package com.frynetworks.fryapp.update

import com.frynetworks.fryapp.update.UpdateFixtures.OLD_CERT
import com.frynetworks.fryapp.update.UpdateFixtures.OTHER_CERT
import com.frynetworks.fryapp.update.UpdateFixtures.PIN
import com.frynetworks.fryapp.update.UpdateFixtures.PKG
import com.frynetworks.fryapp.update.UpdateFixtures.manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignerPolicyTest {

    private val installed = SignerFacts(PKG, 6, setOf(PIN))
    private val candidate = SignerFacts(PKG, 7, setOf(PIN), lineage = setOf(PIN))

    private fun denied(i: SignerFacts?, c: SignerFacts?) = assertTrue(SignerPolicy.check(i, c, manifest(7)) is SignerVerdict.Deny)

    @Test
    fun `same pinned signer, promised package and version - allowed`() {
        assertEquals(SignerVerdict.Allow, SignerPolicy.check(installed, candidate, manifest(7)))
    }

    @Test
    fun `a rotated key whose lineage contains the installed signer is allowed when pinned`() {
        val rotated = SignerFacts(PKG, 7, setOf(PIN), lineage = setOf(OLD_CERT, PIN))
        assertEquals(SignerVerdict.Allow, SignerPolicy.check(SignerFacts(PKG, 6, setOf(OLD_CERT)), rotated, manifest(7)))
    }

    @Test
    fun `a different certificate is refused even when the manifest pins it`() {
        val m = manifest(7).copy(certSha256 = listOf(PIN, OTHER_CERT))
        assertTrue(SignerPolicy.check(installed, SignerFacts(PKG, 7, setOf(OTHER_CERT)), m) is SignerVerdict.Deny)
    }

    @Test
    fun `an unpinned certificate is refused even when it matches the installed one`() {
        denied(SignerFacts(PKG, 6, setOf(OTHER_CERT)), SignerFacts(PKG, 7, setOf(OTHER_CERT)))
    }

    @Test
    fun `wrong package or version, and unreadable signatures, fail closed`() {
        denied(installed, candidate.copy(packageName = "com.evil"))
        denied(installed, candidate.copy(versionCode = 8))
        denied(null, candidate)
        denied(installed, null)
        denied(installed.copy(signers = emptySet()), candidate)
        denied(installed, candidate.copy(signers = emptySet()))
    }
}
