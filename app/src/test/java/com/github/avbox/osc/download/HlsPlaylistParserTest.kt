package com.github.avbox.osc.download

import com.github.avbox.osc.bean.DownloadErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistParserTest {

    private val baseUrl = "https://cdn.example.com/vod/index.m3u8"

    @Test
    fun master_listParsesVariantsAndResolvesUrls() {
        val content = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            https://other.example.com/high.m3u8
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, baseUrl)

        assertTrue(playlist.master)
        assertEquals(2, playlist.variants.size)
        assertEquals(800000L, playlist.variants[0].bandwidth)
        assertEquals("https://cdn.example.com/vod/low/index.m3u8", playlist.variants[0].uri)
        assertEquals(2000000L, playlist.variants[1].bandwidth)
        assertEquals("https://other.example.com/high.m3u8", playlist.variants[1].uri)
        assertTrue(playlist.segments.isEmpty())
        assertFalse(playlist.hasSeparateAudio)
    }

    @Test
    fun masterListDetectsSeparateAudioTrack() {
        val content = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1800000,AUDIO="aud"
            video/main.m3u8
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, baseUrl)

        assertTrue(playlist.master)
        assertTrue(playlist.hasSeparateAudio)
    }

    @Test
    fun media_listParsesSegmentsKeyMapAndByteRange() {
        val content = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
            #EXT-X-KEY:METHOD=AES-128,URI="../key/key.bin",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:5.5,
            seg2.ts
            #EXT-X-BYTERANGE:1024@4096
            #EXTINF:4.0,
            seg3.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, baseUrl)

        assertFalse(playlist.master)
        assertFalse(playlist.live)
        assertEquals("https://cdn.example.com/vod/init.mp4", playlist.initUri)
        assertEquals(HlsByteRange(720L, 0L), playlist.initRange)
        assertEquals(3, playlist.segments.size)
        assertEquals(19500L, playlist.totalDurationMs)

        val first = playlist.segments[0]
        assertEquals("https://cdn.example.com/vod/seg1.ts", first.uri)
        assertEquals(10000L, first.durationMs)
        assertEquals(100L, first.sequence)
        assertEquals("AES-128", first.key?.method)
        assertEquals("https://cdn.example.com/key/key.bin", first.key?.uri)
        assertEquals(16, first.key?.iv?.size)

        assertEquals(101L, playlist.segments[1].sequence)
        assertEquals(102L, playlist.segments[2].sequence)
        assertNull(playlist.segments[1].range)
        assertEquals(HlsByteRange(1024L, 4096L), playlist.segments[2].range)
    }

    @Test
    fun mediaSequenceIsNotTreatedAsSeparateAudio() {
        val content = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:42
            #EXTINF:2.0,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, baseUrl)

        assertFalse(playlist.hasSeparateAudio)
        assertEquals(42L, playlist.segments[0].sequence)
    }

    @Test
    fun separateAudioTrackIsDetected() {
        val content = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",URI="audio.m3u8"
            #EXTINF:2.0,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        assertTrue(HlsPlaylistParser.parse(content, baseUrl).hasSeparateAudio)
    }

    @Test
    fun missingEndListMarksPlaylistLive() {
        val content = """
            #EXTM3U
            #EXTINF:2.0,
            a.ts
        """.trimIndent()

        assertTrue(HlsPlaylistParser.parse(content, baseUrl).live)
    }

    @Test
    fun keyMethodNoneClearsKey() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:2.0,
            a.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:2.0,
            b.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = HlsPlaylistParser.parse(content, baseUrl)

        assertEquals("AES-128", playlist.segments[0].key?.method)
        assertNull(playlist.segments[1].key)
    }

    @Test
    fun invalidContentThrowsUnsupported() {
        val error = runCatching { HlsPlaylistParser.parse("<html></html>", baseUrl) }.exceptionOrNull()
        assertTrue(error is DownloadException)
        assertEquals(DownloadErrorKind.UNSUPPORTED, (error as DownloadException).errorKind)
    }

    @Test
    fun emptyMediaListThrowsUnsupported() {
        val content = "#EXTM3U\n#EXT-X-ENDLIST"
        val error = runCatching { HlsPlaylistParser.parse(content, baseUrl) }.exceptionOrNull()
        assertTrue(error is DownloadException)
    }

    @Test
    fun parseRangeHandlesLengthAndOffset() {
        assertEquals(HlsByteRange(720L, 0L), HlsPlaylistParser.parseRange("720"))
        assertEquals(HlsByteRange(1024L, 2048L), HlsPlaylistParser.parseRange("1024@2048"))
        assertNull(HlsPlaylistParser.parseRange(null))
        assertNull(HlsPlaylistParser.parseRange(""))
        assertNull(HlsPlaylistParser.parseRange("0"))
        assertNull(HlsPlaylistParser.parseRange("abc"))
    }

    @Test
    fun resolveHandlesAbsoluteProtocolRelativeAndRelative() {
        assertEquals("https://a.com/x.ts", HlsPlaylistParser.resolve(baseUrl, "https://a.com/x.ts"))
        assertEquals("https://cdn.example.com/x.ts", HlsPlaylistParser.resolve(baseUrl, "//cdn.example.com/x.ts"))
        assertEquals("https://cdn.example.com/vod/x.ts", HlsPlaylistParser.resolve(baseUrl, "x.ts"))
        assertEquals("https://cdn.example.com/vod/sub/x.ts", HlsPlaylistParser.resolve(baseUrl, "sub/x.ts"))
    }
}
