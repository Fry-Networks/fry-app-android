package com.frynetworks.fryapp.update

/** Synthetic C-6 values; the pin is the real public release-cert digest (not a secret). */
object UpdateFixtures {
    const val PIN = "3fbf44760c28c17c2ce81f16cc875c28b820cb4989da819aeef862c18a867dbc"
    const val OTHER_CERT = "0000000000000000000000000000000000000000000000000000000000000001"
    const val OLD_CERT = "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"
    const val PKG = "com.frynetworks.fryapp"

    fun json(
        channel: String = "stable",
        versionCode: Long = 7,
        url: String = "https://github.com/Fry-Networks/fry-app-android/releases/download/app-v0.4.1-rc.1/fryapp-0.4.1-rc.1.apk",
        sha256: String = "ab".repeat(32),
        size: Long = 1234,
        certs: String = "\"$PIN\"",
        pkg: String = PKG,
        extra: String = "",
    ) = """{"schema":1,"channel":"$channel","package":"$pkg","versionCode":$versionCode,"versionName":"0.4.1-rc.1","url":"$url","sha256":"$sha256","size":$size,"minSupportedVersionCode":6,"certSha256":[$certs],"notes":"Fixes","publishedAt":"2026-09-28T20:00:00Z"$extra}"""

    fun manifest(versionCode: Long = 7, sha256: String = "ab".repeat(32), size: Long = 1234, url: String = "https://github.com/Fry-Networks/fry-app-android/releases/download/app-v0.4.1-rc.1/fryapp-0.4.1-rc.1.apk") =
        UpdateManifest(1, UpdateChannel.STABLE, PKG, versionCode, "0.4.1-rc.1", url, sha256, size, 6, listOf(PIN), "Fixes", "2026-09-28T20:00:00Z")
}
