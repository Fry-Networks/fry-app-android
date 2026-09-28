package com.frynetworks.fryapp.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Contract C-6 `fryapp-update.json`. */
data class UpdateManifest(
    val schema: Int,
    val channel: UpdateChannel,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val minSupportedVersionCode: Long,
    val certSha256: List<String>,
    val notes: String,
    val publishedAt: String,
)

sealed interface ManifestParse {
    data class Ok(val manifest: UpdateManifest) : ManifestParse
    data class Invalid(val reason: String) : ManifestParse
}

/** Where manifests and APKs may come from, including every redirect hop (C-6). HTTPS only. */
object UpdateHosts {
    const val REPO_RELEASES_PATH = "/Fry-Networks/fry-app-android/releases/"
    private val ASSET_HOSTS = setOf("objects.githubusercontent.com", "release-assets.githubusercontent.com")

    fun allowed(url: HttpUrl): Boolean = url.isHttps && when (url.host) {
        "github.com" -> url.encodedPath.startsWith(REPO_RELEASES_PATH)
        in ASSET_HOSTS -> true
        else -> false
    }

    fun allowed(url: String): Boolean = url.toHttpUrlOrNull()?.let { allowed(it) } ?: false
}

/**
 * Strict C-6 parser: every field must be present and well-formed, the APK must live under this
 * repository's releases on github.com, and the manifest must be for the channel that was asked
 * for. Anything else is [ManifestParse.Invalid] and nothing is downloaded.
 */
object UpdateManifestParser {
    const val SCHEMA = 1
    const val PACKAGE = "com.frynetworks.fryapp"
    /** Hard ceiling for an APK download; the manifest's own `size` must also match exactly. */
    const val MAX_APK_BYTES = 150L * 1024 * 1024
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun parse(json: String?, expected: UpdateChannel): ManifestParse {
        val o = runCatching { JsonParser.parseString(json.orEmpty()).asJsonObject }.getOrNull()
            ?: return ManifestParse.Invalid("not a JSON object")
        val schema = o.long("schema") ?: return ManifestParse.Invalid("schema missing")
        if (schema != SCHEMA.toLong()) return ManifestParse.Invalid("unsupported schema $schema")
        val channel = UpdateChannel.fromWire(o.str("channel")) ?: return ManifestParse.Invalid("unknown channel")
        if (channel != expected) return ManifestParse.Invalid("manifest is for channel ${channel.wire}, asked for ${expected.wire}")
        val pkg = o.str("package")
        if (pkg != PACKAGE) return ManifestParse.Invalid("package $pkg is not $PACKAGE")
        val versionCode = o.long("versionCode")?.takeIf { it > 0 } ?: return ManifestParse.Invalid("versionCode missing")
        val versionName = o.str("versionName")?.takeIf { it.isNotBlank() } ?: return ManifestParse.Invalid("versionName missing")
        val url = o.str("url") ?: return ManifestParse.Invalid("url missing")
        val parsedUrl = url.toHttpUrlOrNull()
        if (parsedUrl == null || parsedUrl.host != "github.com" || !UpdateHosts.allowed(parsedUrl) || !parsedUrl.encodedPath.endsWith(".apk")) {
            return ManifestParse.Invalid("url is not a release asset of this repository")
        }
        val sha = o.str("sha256")?.lowercase()?.takeIf { HEX64.matches(it) } ?: return ManifestParse.Invalid("sha256 missing or malformed")
        val size = o.long("size")?.takeIf { it in 1..MAX_APK_BYTES } ?: return ManifestParse.Invalid("size missing or over ${MAX_APK_BYTES} bytes")
        val minSupported = o.long("minSupportedVersionCode")?.takeIf { it >= 0 } ?: return ManifestParse.Invalid("minSupportedVersionCode missing")
        val certs = o.get("certSha256")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.map { e -> e.takeIf { it.isJsonPrimitive }?.asString?.lowercase() }
        if (certs.isNullOrEmpty() || certs.any { it == null || !HEX64.matches(it) }) return ManifestParse.Invalid("certSha256 missing or malformed")
        val notes = o.str("notes").orEmpty()
        if (notes.length > 500) return ManifestParse.Invalid("notes longer than 500 characters")
        return ManifestParse.Ok(
            UpdateManifest(
                schema = SCHEMA,
                channel = channel,
                packageName = pkg,
                versionCode = versionCode,
                versionName = versionName,
                url = url,
                sha256 = sha,
                size = size,
                minSupportedVersionCode = minSupported,
                certSha256 = certs.filterNotNull(),
                notes = notes,
                publishedAt = o.str("publishedAt").orEmpty(),
            ),
        )
    }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.long(k: String): Long? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asNumber?.let {
        val d = it.toDouble()
        if (d % 1.0 == 0.0) it.toLong() else null
    }
}
