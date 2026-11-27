package com.github.avbox.osc.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistDetectTest {

    private fun sniff(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return ProgressiveDownloader.looksLikePlaylist(bytes, bytes.size)
    }

    @Test
    fun detectsPlainPlaylist() {
        assertTrue(sniff("#EXTM3U\n#EXT-X-VERSION:3\nseg.ts\n"))
    }

    @Test
    fun detectsBomAndLeadingWhitespace() {
        assertTrue(sniff("\uFEFF#EXTM3U\n"))
        assertTrue(sniff("\n\r\t #EXTM3U\n"))
    }

    @Test
    fun ignoresBinaryAndShortBuffers() {
        assertFalse(sniff("\u0000\u0000\u0000\u0018ftypisom"))
        assertFalse(sniff("#EXT"))
        assertFalse(sniff(""))
    }

    @Test
    fun onlyReadsWithinLength() {
        val bytes = "#EXTM3U\nrest".toByteArray(Charsets.UTF_8)
        assertTrue(ProgressiveDownloader.looksLikePlaylist(bytes, 8))
        assertFalse(ProgressiveDownloader.looksLikePlaylist(bytes, 4))
    }
}
