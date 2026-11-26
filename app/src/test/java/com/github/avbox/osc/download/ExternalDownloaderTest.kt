package com.github.avbox.osc.download

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun hlsUrlUsesMpegUrlMimeType() {
        val intent = ExternalDownloader.buildViewIntent(
            "https://a.com/v/index.m3u8?sign=1",
            "index.m3u8",
            packageName,
        )
        assertEquals("application/vnd.apple.mpegurl", intent.type)
    }

    @Test
    fun blankFileNameIsNotAttached() {
        val intent = ExternalDownloader.buildViewIntent("https://a.com/v.mp4", "  ", packageName)
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
