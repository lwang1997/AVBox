package com.github.avbox.osc.download

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ExternalDownloaderTest {

    private val packageName = "idm.internet.download.manager.plus"

    @Test
    fun viewIntentCarriesUrlPackageAndFileName() {
        val intent = ExternalDownloader.buildViewIntent(
            "https://a.com/v.mp4?token=abc",
            "三体 第1集.mp4",
            emptyMap(),
            packageName,
        )

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://a.com/v.mp4?token=abc", intent.dataString)
        assertEquals("video/mp4", intent.type)
        assertEquals(packageName, intent.`package`)
        assertEquals("三体 第1集.mp4", intent.getStringExtra("extra_filename"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun viewIntentTargets1dmDownloaderActivity() {
        val intent = ExternalDownloader.buildViewIntent(
            "https://a.com/v.mp4",
            null,
            emptyMap(),
            packageName,
        )

        // 显式指定 1DM 的下载入口，不再依赖它 VIEW 过滤器对 MIME 的匹配。
        assertEquals(packageName, intent.component?.packageName)
        assertEquals("idm.internet.download.manager.Downloader", intent.component?.className)
    }

    @Test
    fun requestHeadersAreForwardedAsBundle() {
        val intent = ExternalDownloader.buildViewIntent(
            "https://a.com/v.mp4",
            null,
            linkedMapOf("Referer" to "https://a.com/", "User-Agent" to "AVBox"),
            packageName,
        )

        val bundle = intent.getBundleExtra("extra_headers")
        assertEquals("https://a.com/", bundle?.getString("Referer"))
        assertEquals("AVBox", bundle?.getString("User-Agent"))
    }

    @Test
    fun hopByHopHeadersAreNotForwarded() {
        val intent = ExternalDownloader.buildViewIntent(
            "https://a.com/v.mp4",
            null,
            mapOf(
                "Host" to "evil.example.com",
                "Range" to "bytes=0-",
                "Accept-Encoding" to "gzip",
                "Cookie" to "sid=1",
            ),
            packageName,
        )

        val bundle = intent.getBundleExtra("extra_headers")
        assertNull(bundle?.getString("Host"))
        assertNull(bundle?.getString("Range"))
        assertNull(bundle?.getString("Accept-Encoding"))
        assertEquals("sid=1", bundle?.getString("Cookie"))
    }

    @Test
    fun emptyHeadersDoNotAttachBundle() {
        val intent = ExternalDownloader.buildViewIntent(
            "https://a.com/v.mp4",
            null,
            emptyMap(),
            packageName,
        )

        assertFalse(intent.hasExtra("extra_headers"))
    }

    @Test
    fun hlsUrlUsesMpegUrlMimeType() {
        val intent = ExternalDownloader.buildViewIntent(
            "https://a.com/v/index.m3u8?sign=1",
            "index.m3u8",
            emptyMap(),
            packageName,
        )
        assertEquals("application/vnd.apple.mpegurl", intent.type)
    }

    @Test
    fun blankFileNameIsNotAttached() {
        val intent = ExternalDownloader.buildViewIntent(
            "https://a.com/v.mp4",
            "  ",
            emptyMap(),
            packageName,
        )
        assertFalse(intent.hasExtra("extra_filename"))
    }

    @Test
    fun headerTextIsPlainUrlWithoutHeaders() {
        assertEquals(
            "https://a.com/v.mp4",
            ExternalDownloader.headerText("https://a.com/v.mp4", emptyMap()),
        )
    }

    @Test
    fun headerTextAppendsHeadersLineByLine() {
        val text = ExternalDownloader.headerText(
            "https://a.com/v.mp4",
            linkedMapOf("Referer" to "https://a.com/", "User-Agent" to "AVBox"),
        )

        assertEquals(
            "https://a.com/v.mp4\nReferer: https://a.com/\nUser-Agent: AVBox",
            text,
        )
    }
}
