package com.frynetworks.fryapp.update

import com.frynetworks.fryapp.update.UpdateFixtures.PIN
import com.frynetworks.fryapp.update.UpdateFixtures.PKG
import com.frynetworks.fryapp.update.UpdateFixtures.manifest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The package check compares the download with the manifest AND with the installed app; the
 * shared fixture keeps those two names equal, so each mismatch is isolated here (an `&&` in
 * place of the `||` would let either one through).
 */
class SignerPolicyPackageTest {

    private val installed = SignerFacts(PKG, 6, setOf(PIN))
    private val candidate = SignerFacts(PKG, 7, setOf(PIN), lineage = setOf(PIN))

    @Test
    fun `a manifest that promised another package is refused even when the download matches the installed app`() {
        val verdict = SignerPolicy.check(installed, candidate, manifest(7).copy(packageName = "com.other"))
        assertTrue("$verdict", verdict is SignerVerdict.Deny)
    }

    @Test
    fun `a download for the manifest's package is refused when it is not the installed app's package`() {
        val other = candidate.copy(packageName = "com.other")
        val verdict = SignerPolicy.check(installed, other, manifest(7).copy(packageName = "com.other"))
        assertTrue("$verdict", verdict is SignerVerdict.Deny && verdict.reason.contains("com.other"))
    }
}
