package com.frynetworks.fryapp.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * Android 9: `getPackageArchiveInfo` ignores GET_SIGNING_CERTIFICATES (honoured from API 29), so
 * the archive read must also request the legacy signatures and read them when `signingInfo` is
 * absent; otherwise every self-update is refused on that release.
 */
@Suppress("DEPRECATION")
class ApkInspectorSignaturesTest {

    @Test
    fun `an archive on Android 9 asks for both the signing certificates and the legacy signatures`() {
        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES, ApkInspector.signatureFlags(28, archive = true))
        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES, ApkInspector.signatureFlags(28, archive = false))
        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES, ApkInspector.signatureFlags(29, archive = true))
        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES, ApkInspector.signatureFlags(34, archive = false))
        assertEquals(PackageManager.GET_SIGNATURES, ApkInspector.signatureFlags(26, archive = true))
        assertEquals(PackageManager.GET_SIGNATURES, ApkInspector.signatureFlags(27, archive = false))
    }

    @Test
    fun `without signingInfo the legacy signatures still yield the signer digest`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val signature = mockk<Signature> { every { toByteArray() } returns bytes }
        val info = PackageInfo().apply {
            packageName = "com.frynetworks.fryapp"
            versionCode = 7
            signatures = arrayOf(signature)
        }
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(SignerFacts("com.frynetworks.fryapp", 7, setOf(expected)), ApkInspector.facts(info))
    }

    @Test
    fun `no signatures at all reads as no signer, which the policy refuses`() {
        val info = PackageInfo().apply { packageName = "com.frynetworks.fryapp"; versionCode = 7 }
        assertEquals(emptySet<String>(), ApkInspector.facts(info)?.signers)
        assertEquals(null, ApkInspector.facts(null))
    }
}
