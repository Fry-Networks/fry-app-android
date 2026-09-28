package com.frynetworks.fryapp.update

import com.frynetworks.fryapp.update.UpdateFixtures.PIN
import com.frynetworks.fryapp.update.UpdateFixtures.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestParserTest {

    private fun ok(text: String, channel: UpdateChannel = UpdateChannel.STABLE) = (UpdateManifestParser.parse(text, channel) as ManifestParse.Ok).manifest
    private fun invalid(text: String?, channel: UpdateChannel = UpdateChannel.STABLE) =
        assertTrue(text ?: "null", UpdateManifestParser.parse(text, channel) is ManifestParse.Invalid)

    @Test
    fun `a C-6 manifest parses completely`() {
        val m = ok(json())
        assertEquals(UpdateChannel.STABLE, m.channel)
        assertEquals("com.frynetworks.fryapp", m.packageName)
        assertEquals(7L, m.versionCode)
        assertEquals("0.4.1-rc.1", m.versionName)
        assertEquals("ab".repeat(32), m.sha256)
        assertEquals(1234L, m.size)
        assertEquals(6L, m.minSupportedVersionCode)
        assertEquals(listOf(PIN), m.certSha256)
        assertEquals("Fixes", m.notes)
    }

    @Test
    fun `the manifest must be for the channel that was asked for`() {
        invalid(json(channel = "test"), UpdateChannel.STABLE)
        assertEquals(UpdateChannel.TEST, ok(json(channel = "test"), UpdateChannel.TEST).channel)
        invalid(json(channel = "beta"), UpdateChannel.STABLE)
    }

    @Test
    fun `the APK must be a release asset of this repository on github-com over https`() {
        invalid(json(url = "http://github.com/Fry-Networks/fry-app-android/releases/download/x/fryapp.apk"))
        invalid(json(url = "https://github.com/evil/fry-app-android/releases/download/x/fryapp.apk"))
        invalid(json(url = "https://objects.githubusercontent.com/x/fryapp.apk"))
        invalid(json(url = "https://example.com/Fry-Networks/fry-app-android/releases/download/x/fryapp.apk"))
        invalid(json(url = "https://github.com/Fry-Networks/fry-app-android/releases/download/x/fryapp.zip"))
    }

    @Test
    fun `hashes, sizes, certs and package are checked`() {
        invalid(json(sha256 = "AB".repeat(31)))
        invalid(json(sha256 = "zz".repeat(32)))
        invalid(json(size = 0))
        invalid(json(size = UpdateManifestParser.MAX_APK_BYTES + 1))
        invalid(json(certs = ""))
        invalid(json(certs = "\"short\""))
        invalid(json(pkg = "com.frynetworks.fryapp.debug"))
        invalid(json(versionCode = 0))
        assertEquals("upper-case hex is normalised", "ab".repeat(32), ok(json(sha256 = "AB".repeat(32))).sha256)
    }

    @Test
    fun `garbage, other schemas and long notes are refused`() {
        invalid(null)
        invalid("")
        invalid("[]")
        invalid(json().replace("\"schema\":1", "\"schema\":2"))
        invalid(json().replace("\"notes\":\"Fixes\"", "\"notes\":\"${"x".repeat(501)}\""))
        invalid(json().replace("\"versionCode\":7", "\"versionCode\":7.5"))
    }

    @Test
    fun `the host allowlist covers every redirect hop and nothing else`() {
        assertTrue(UpdateHosts.allowed("https://github.com/Fry-Networks/fry-app-android/releases/latest/download/fryapp-update.json"))
        assertTrue(UpdateHosts.allowed("https://objects.githubusercontent.com/github-production-release-asset/1/2"))
        assertTrue(UpdateHosts.allowed("https://release-assets.githubusercontent.com/github-production-release-asset/1/2"))
        assertFalse(UpdateHosts.allowed("https://github.com/Fry-Networks/fry-firmware/releases/latest/download/manifest.json"))
        assertFalse(UpdateHosts.allowed("http://objects.githubusercontent.com/a"))
        assertFalse(UpdateHosts.allowed("https://githubusercontent.com.evil.example/a"))
        assertFalse(UpdateHosts.allowed("not a url"))
    }
}
