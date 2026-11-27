package com.github.avbox.osc.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadFileNameTest {

    @Test
    fun sanitizeReplacesIllegalChars() {
        assertEquals("a_b_c_d_e_f_g_h_i", DownloadStorage.sanitizeName("a/b\\c:d*e?f\"g<h>i"))
        assertEquals("plain", DownloadStorage.sanitizeName("  plain  "))
        assertEquals("download", DownloadStorage.sanitizeName(null))
        assertEquals("download", DownloadStorage.sanitizeName("   "))
        assertEquals("name", DownloadStorage.sanitizeName("name..."))
    }

    @Test
    fun sanitizeTruncatesLongNames() {
        val long = "x".repeat(200)
        assertEquals(120, DownloadStorage.sanitizeName(long).length)
    }

    @Test
    fun buildFileNameCombinesVodAndEpisode() {
        assertEquals("三体 - 第1集.mp4", DownloadStorage.buildFileName("三体", "第1集", "ignored", "mp4"))
    }

    @Test
    fun buildFileNameFallsBackWhenOnePartIsMissing() {
        assertEquals("三体.mp4", DownloadStorage.buildFileName("三体", null, null, "mp4"))
        assertEquals("第1集.mkv", DownloadStorage.buildFileName(null, "第1集", null, "mkv"))
        assertEquals("标题.ts", DownloadStorage.buildFileName(null, null, "标题", "ts"))
    }

    @Test
    fun buildFileNameKeepsNoDotWhenExtensionBlank() {
        assertEquals("三体 - 第1集", DownloadStorage.buildFileName("三体", "第1集", null, " "))
    }

    @Test
    fun buildFileNameSanitizesExtensionInput() {
        assertEquals("三体.mp4", DownloadStorage.buildFileName("三体", null, null, ".mp4"))
    }

    @Test
    fun buildFileNameDoesNotRepeatKnownExtension() {
        assertEquals("标题.mp4", DownloadStorage.buildFileName(null, null, "标题.mp4", "mp4"))
        assertEquals("标题.mp4", DownloadStorage.buildFileName(null, null, "标题.ts", "mp4"))
        // 不是媒体扩展名的点号要保留
        assertEquals("第1.5集", DownloadStorage.buildFileName(null, null, "第1.5集", ""))
    }

    @Test
    fun extensionFromUrlHandlesQueryAndFragment() {
        assertEquals("mp4", DownloadStorage.extensionFromUrl("https://a.com/v/x.MP4?token=1#t"))
        assertEquals("ts", DownloadStorage.extensionFromUrl("https://a.com/v/x.ts"))
        assertNull(DownloadStorage.extensionFromUrl("https://a.com/v/segment"))
        assertNull(DownloadStorage.extensionFromUrl(null))
        assertNull(DownloadStorage.extensionFromUrl("https://a.com/v/x.a"))
        assertNull(DownloadStorage.extensionFromUrl("https://a.com/v/x.toolongext"))
        assertNull(DownloadStorage.extensionFromUrl("https://a.com/v/x.m-p4"))
    }

    @Test
    fun mimeMapsBothDirections() {
        assertEquals("video/mp4", DownloadStorage.mimeForExtension("MP4"))
        assertEquals("video/mp2t", DownloadStorage.mimeForExtension("ts"))
        assertEquals("application/octet-stream", DownloadStorage.mimeForExtension("unknown"))
        assertEquals("mp4", DownloadStorage.extensionFromMime("video/mp4; charset=utf-8"))
        assertEquals("mkv", DownloadStorage.extensionFromMime("VIDEO/X-MATROSKA"))
        assertNull(DownloadStorage.extensionFromMime(null))
        assertNull(DownloadStorage.extensionFromMime("text/html"))
    }
}
