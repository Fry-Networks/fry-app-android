package com.frynetworks.fryapp.update

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ApkDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val apk = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    @Before fun start() { server.start(); elsewhere.start() }
    @After fun stop() { server.shutdown(); elsewhere.shutdown() }

    /** Only [server] is an allowed host here; [elsewhere] plays a CDN that is not on the allowlist. */
    private fun downloader() = ApkDownloader(isAllowed = { it.port == server.port && it.host == server.hostName })
    private fun manifest(size: Long = apk.size.toLong(), hash: String = sha) = UpdateFixtures.manifest(size = size, sha256 = hash, url = server.url("/releases/fryapp.apk").toString())
    private fun dest() = File(tmp.root, "updates/fryapp-7.apk")
    private fun body(bytes: ByteArray = apk) = MockResponse().setBody(Buffer().write(bytes))

    @Test
    fun `a matching download is streamed to disk and kept`() {
        server.enqueue(body())
        val result = downloader().download(manifest(), dest())
        assertTrue("$result", result is DownloadResult.Ok)
        assertTrue(dest().readBytes().contentEquals(apk))
        assertFalse(File(dest().path + ".part").exists())
    }

    @Test
    fun `a tampered download is deleted`() {
        server.enqueue(body(apk.copyOf().also { it[1000] = (it[1000] + 1).toByte() }))
        val result = downloader().download(manifest(), dest())
        assertTrue(result is DownloadResult.Failed && result.reason.contains("SHA-256"))
        assertFalse(dest().exists())
        assertFalse(File(dest().path + ".part").exists())
    }

    @Test
    fun `too many or too few bytes are refused and nothing is left behind`() {
        server.enqueue(body(apk + ByteArray(10)))
        assertTrue(downloader().download(manifest(), dest()) is DownloadResult.Failed)
        server.enqueue(body(apk.copyOf(apk.size - 10)))
        assertTrue(downloader().download(manifest(), dest()) is DownloadResult.Failed)
        assertEquals(0, tmp.root.walk().count { it.isFile })
    }

    @Test
    fun `allowed redirects are followed, a redirect off the allowlist is never requested`() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/cdn/asset"))
        server.enqueue(body())
        assertTrue(downloader().download(manifest(), dest()) is DownloadResult.Ok)

        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", elsewhere.url("/asset").toString()))
        elsewhere.enqueue(body())
        val refused = downloader().download(manifest(), dest())
        assertTrue(refused is DownloadResult.Failed)
        assertEquals(0, elsewhere.requestCount)
    }

    @Test
    fun `a redirect loop gives up`() {
        repeat(10) { server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/again")) }
        assertTrue(downloader().download(manifest(), dest()) is DownloadResult.Failed)
        assertTrue(server.requestCount <= 6)
    }

    @Test
    fun `manifest text is size-capped and follows the same allowlist`() {
        server.enqueue(MockResponse().setBody("{\"schema\":1}"))
        assertEquals("{\"schema\":1}", downloader().fetchText(server.url("/m.json").toString()))
        server.enqueue(MockResponse().setBody("x".repeat(20_000)))
        assertNull(downloader().fetchText(server.url("/m.json").toString()))
        assertNull(downloader().fetchText(elsewhere.url("/m.json").toString()))
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(downloader().fetchText(server.url("/m.json").toString()))
    }
}
