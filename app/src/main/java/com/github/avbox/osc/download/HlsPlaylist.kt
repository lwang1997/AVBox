package com.github.avbox.osc.download

import com.github.avbox.osc.bean.DownloadErrorKind

import java.net.URL

internal data class HlsVariant(
    val bandwidth: Long,
    val uri: String,
)

internal data class HlsByteRange(
    val length: Long,
    val offset: Long,
)

internal data class HlsKey(
    val method: String,
    val uri: String?,
    val iv: ByteArray?,
)

internal data class HlsSegment(
    val index: Int,
    val uri: String,
    val durationMs: Long,
    val sequence: Long,
    val key: HlsKey?,
    val range: HlsByteRange?,
)

internal data class HlsPlaylist(
    val master: Boolean,
    val variants: List<HlsVariant>,
    val segments: List<HlsSegment>,
    val initUri: String?,
    val initRange: HlsByteRange?,
    val live: Boolean,
    val totalDurationMs: Long,
    val hasSeparateAudio: Boolean,
)

internal object HlsPlaylistParser {

    private val attrRegex = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    fun parse(content: String, baseUrl: String): HlsPlaylist {
        val text = content.removePrefix("\uFEFF")
        if (!text.startsWith("#EXTM3U")) {
            throw DownloadException(DownloadErrorKind.UNSUPPORTED, "不是有效的 m3u8 播放列表")
        }
        val lines = text.split("\r\n", "\n")
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            return parseMaster(lines, baseUrl)
        }
        return parseMedia(lines, text, baseUrl)
    }

    private fun parseMaster(lines: List<String>, baseUrl: String): HlsPlaylist {
        val variants = ArrayList<HlsVariant>()
        var pendingBandwidth = -1L
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                pendingBandwidth = parseAttrs(trimmed)["BANDWIDTH"]?.toLongOrNull() ?: 0L
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (pendingBandwidth >= 0) {
                variants.add(HlsVariant(pendingBandwidth, resolve(baseUrl, trimmed)))
                pendingBandwidth = -1L
            }
        }
        if (variants.isEmpty()) {
            throw DownloadException(DownloadErrorKind.UNSUPPORTED, "主播放列表没有可用清晰度")
        }
        return HlsPlaylist(
            master = true,
            variants = variants,
            segments = emptyList(),
            initUri = null,
            initRange = null,
            live = false,
            totalDurationMs = 0L,
            hasSeparateAudio = lines.any { line ->
                val trimmed = line.trim()
                trimmed.startsWith("#EXT-X-MEDIA:") &&
                    trimmed.contains("TYPE=AUDIO", ignoreCase = true) &&
                    trimmed.contains("URI=")
            },
        )
    }

    private fun parseMedia(lines: List<String>, raw: String, baseUrl: String): HlsPlaylist {
        val segments = ArrayList<HlsSegment>()
        var currentKey: HlsKey? = null
        var pendingRange: HlsByteRange? = null
        var pendingDurationMs = 0L
        var initUri: String? = null
        var initRange: HlsByteRange? = null
        var hasSeparateAudio = false
        var sequence = 0L
        var index = 0

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-KEY") -> {
                    val attrs = parseAttrs(trimmed)
                    val method = attrs["METHOD"]?.uppercase() ?: "NONE"
                    if (method == "NONE") {
                        currentKey = null
                    } else {
                        val uri = attrs["URI"]?.trim('"')
                        val iv = attrs["IV"]?.trim('"')?.let(::parseIv)
                        currentKey = HlsKey(method, uri?.let { resolve(baseUrl, it) }, iv)
                    }
                }
                trimmed.startsWith("#EXT-X-MAP") -> {
                    val attrs = parseAttrs(trimmed)
                    val uri = attrs["URI"]?.trim('"')
                    if (!uri.isNullOrEmpty()) {
                        initUri = resolve(baseUrl, uri)
                        initRange = parseRange(attrs["BYTERANGE"]?.trim('"'))
                    }
                }
                trimmed.startsWith("#EXT-X-MEDIA:") -> {
                    if (trimmed.contains("TYPE=AUDIO", ignoreCase = true) && trimmed.contains("URI=")) {
                        hasSeparateAudio = true
                    }
                }
                trimmed.startsWith("#EXT-X-BYTERANGE") -> {
                    pendingRange = parseRange(trimmed.substringAfter(':', ""))
                }
                trimmed.startsWith("#EXTINF") -> {
                    pendingDurationMs = ((trimmed.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0) * 1000).toLong()
                }
                trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE") -> {
                    sequence = trimmed.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                trimmed.isEmpty() || trimmed.startsWith("#") -> Unit
                else -> {
                    segments.add(
                        HlsSegment(
                            index = index,
                            uri = resolve(baseUrl, trimmed),
                            durationMs = pendingDurationMs,
                            sequence = sequence + index,
                            key = currentKey,
                            range = pendingRange,
                        ),
                    )
                    index++
                    pendingDurationMs = 0L
                    pendingRange = null
                }
            }
        }
        if (segments.isEmpty()) {
            throw DownloadException(DownloadErrorKind.UNSUPPORTED, "播放列表没有分片")
        }
        val live = !raw.contains("#EXT-X-ENDLIST")
        return HlsPlaylist(
            master = false,
            variants = emptyList(),
            segments = segments,
            initUri = initUri,
            initRange = initRange,
            live = live,
            totalDurationMs = segments.sumOf { it.durationMs },
            hasSeparateAudio = hasSeparateAudio,
        )
    }

    private fun parseAttrs(line: String): Map<String, String> {
        val start = line.indexOf(':')
        if (start < 0) return emptyMap()
        val body = line.substring(start + 1)
        val result = HashMap<String, String>()
        attrRegex.findAll(body).forEach { match ->
            result[match.groupValues[1]] = match.groupValues[2].trim('"')
        }
        return result
    }

    fun parseRange(value: String?): HlsByteRange? {
        if (value.isNullOrEmpty()) return null
        val parts = value.trim().trim('"').split('@')
        val length = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val offset = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        if (length <= 0) return null
        return HlsByteRange(length, offset)
    }

    private fun parseIv(value: String): ByteArray? {
        val hex = value.removePrefix("0x").removePrefix("0X")
        if (hex.length != 32) return null
        return runCatching {
            ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    fun resolve(baseUrl: String, uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        if (uri.startsWith("//")) {
            val scheme = baseUrl.substringBefore("://", "https")
            return "$scheme:$uri"
        }
        return try {
            URL(URL(baseUrl), uri).toString()
        } catch (_: Throwable) {
            uri
        }
    }
}
