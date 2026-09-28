package com.frynetworks.fryapp.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface DownloadResult {
    data class Ok(val file: File) : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}

/**
 * Fetches update manifests and APKs. Redirects are followed by hand so every hop is checked
 * against [UpdateHosts] (GitHub answers a release download with a 302 to its asset CDN). An APK
 * is streamed to disk while its SHA-256 is computed; a wrong size or hash deletes the file, so
 * nothing unverified is ever left for the installer.
 */
class ApkDownloader(
    client: OkHttpClient = OkHttpClient(),
    private val maxRedirects: Int = 5,
    private val isAllowed: (HttpUrl) -> Boolean = { UpdateHosts.allowed(it) },
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** The manifest text, or null when unreachable, not allowed, or larger than [maxBytes]. */
    fun fetchText(url: String, maxBytes: Long = MAX_MANIFEST_BYTES): String? = try {
        open(url)?.use { response ->
            val body = response.body ?: return null
            if (body.contentLength() > maxBytes) return null
            val bytes = body.byteStream().use { it.readNBytesCompat(maxBytes + 1) }
            if (bytes.size > maxBytes) null else String(bytes, Charsets.UTF_8)
        }
    } catch (e: IOException) {
        null
    }

    fun download(manifest: UpdateManifest, dest: File): DownloadResult {
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            dest.parentFile?.mkdirs()
            part.delete()
            val response = open(manifest.url) ?: return DownloadResult.Failed("download refused: not an allowed release location")
            response.use {
                val body = it.body ?: return DownloadResult.Failed("download returned no body")
                val declared = body.contentLength()
                if (declared > manifest.size || declared > UpdateManifestParser.MAX_APK_BYTES) {
                    return DownloadResult.Failed("download is larger than the manifest says")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > manifest.size) return fail(part, "download is larger than the manifest says")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (total != manifest.size) return fail(part, "download size $total does not match ${manifest.size}")
                val sha = digest.digest().joinToString("") { b -> "%02x".format(b) }
                if (sha != manifest.sha256) return fail(part, "download SHA-256 does not match the manifest")
            }
            dest.delete()
            if (!part.renameTo(dest)) return fail(part, "could not store the download")
            return DownloadResult.Ok(dest)
        } catch (e: IOException) {
            return fail(part, "download failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Follows up to [maxRedirects] redirects, each hop allowlisted; null when a hop is not allowed. */
    private fun open(url: String): Response? {
        var current: HttpUrl = url.toHttpUrlOrNull() ?: return null
        repeat(maxRedirects + 1) {
            if (!isAllowed(current)) return null
            val response = client.newCall(Request.Builder().url(current).build()).execute()
            if (response.isRedirect) {
                val next = response.header("Location")?.let { current.resolve(it) }
                response.close()
                current = next ?: return null
                return@repeat
            }
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            return response
        }
        return null
    }

    private fun fail(part: File, reason: String): DownloadResult {
        part.delete()
        return DownloadResult.Failed(reason)
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0L
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 16L * 1024
    }
}
