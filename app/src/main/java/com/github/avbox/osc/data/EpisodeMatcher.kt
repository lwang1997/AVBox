package com.github.avbox.osc.data

import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.util.AppLog
import java.util.ArrayList
import java.util.Locale
import java.util.regex.Pattern

object EpisodeMatcher {

    private data class ParsedEpisode(val number: Int, val special: Boolean)

    // 字幕组/分辨率等噪声先剥离，再按「集号优先、季号剔除」的顺序解析
    private val parenContent = Regex("[\\[\\(【（][^\\]\\)】）]*[\\]\\)】）]")
    private val fullDate = Regex("\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}")
    private val compactDate = Regex("(?<!\\d)\\d{8}(?!\\d)")
    // 保护「第2024集」里的集号，不要当成年份删掉
    private val year = Regex("(?<!第)\\b(?:19|20)\\d{2}\\b(?!\\s*集)")
    private val quality = Regex("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4|web-?dl|bluray|bdrip")
    private val specialMarker = Pattern.compile(
        "\\bspecials?(?![a-z])|\\bova\\b|\\boad\\b|\\bextra\\b|特别篇|特典|番外|特番|特辑|\\bsp(?![a-z])"
    )
    private val seasonZeroEpisode = Pattern.compile("\\bs\\s*0{1,2}\\s*e\\s*\\d{1,4}")
    private val seasonSE = Regex("\\bs\\s*\\d{1,2}\\s*e\\s*")
    private val seasonWordEpisode = Regex("\\bseason\\s*\\d+\\s*episode\\b")
    private val seasonWord = Regex("\\bseason\\s*\\d+\\b")
    private val seasonCn = Regex("第\\s*\\d{1,4}\\s*季")
    private val seasonX = Regex("\\b\\d{1,2}\\s*x\\s*(?=\\d{1,4})")
    private val cnEpisode = Regex("第\\s*([一二三四五六七八九十]{1,3})\\s*集")
    private val specialNumberPatterns = listOf(
        Regex("\\bsp\\s*(\\d{1,4})"),
        Regex("\\bspecials?\\s*(\\d{1,4})"),
        Regex("\\bova\\s*(\\d{1,4})"),
        Regex("\\boad\\s*(\\d{1,4})"),
        Regex("特别篇\\s*(\\d{1,4})"),
        Regex("第\\s*(\\d{1,4})\\s*特别篇"),
        Regex("特典\\s*(\\d{1,4})"),
        Regex("番外\\s*(\\d{1,4})"),
        Regex("\\be\\s*(\\d{1,4})"),
    )
    private val episodeNumberPatterns = listOf(
        Regex("第\\s*(\\d{1,4})\\s*集"),
        Regex("\\bep(?:isode)?\\s*(\\d{1,4})"),
        Regex("\\be\\s*(\\d{1,4})"),
        Regex("(?:^|[\\s.\\-_])(\\d{1,4})(?=$|[\\s.\\-_])"),
    )
    private val nonDigit = Regex("\\D+")
    private val cnDigits = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")

    private fun isEmpty(text: String?): Boolean {
        return text == null || text.length == 0
    }

    @JvmStatic
    fun lineFlagsInDisplayOrder(vod: VodInfo?): List<String> {
        val lineFlags = ArrayList<String>()
        if (vod == null) {
            return lineFlags
        }
        val seriesMap = vod.seriesMap ?: return lineFlags
        val seriesFlags = vod.seriesFlags
        if (seriesFlags != null) {
            for (flag in seriesFlags) {
                val name = flag.name
                if (!name.isNullOrEmpty() && seriesMap.containsKey(name) && !lineFlags.contains(name)) {
                    lineFlags.add(name)
                }
            }
        }
        for (flag in seriesMap.keys) {
            if (!flag.isNullOrEmpty() && !lineFlags.contains(flag)) {
                lineFlags.add(flag)
            }
        }
        return lineFlags
    }

    @JvmStatic
    fun lineFlagIndex(lineFlags: List<String>?, currentFlag: String?): Int {
        if (lineFlags == null || currentFlag.isNullOrEmpty()) {
            return -1
        }
        for (i in lineFlags.indices) {
            if (currentFlag == lineFlags[i]) {
                return i
            }
        }
        return -1
    }

    @JvmStatic
    fun sameEpisodeIndex(currentSeries: VodInfo.VodSeries?, targetList: List<VodInfo.VodSeries>?, fallbackIndex: Int): Int {
        if (targetList == null || targetList.isEmpty()) {
            return 0
        }
        if (targetList.size == 1) {
            return 0
        }
        if (currentSeries == null || currentSeries.name.isNullOrEmpty()) {
            return Math.max(0, Math.min(fallbackIndex, targetList.size - 1))
        }
        val currentEpisode = extractEpisodeNumber(currentSeries.name)
        var matchedIndex = -1
        var bestScore = 0
        for (i in targetList.indices) {
            val score = episodeMatchScore(currentSeries.name, currentEpisode, targetList[i].name)
            if (score > bestScore) {
                bestScore = score
                matchedIndex = i
            }
        }
        if (matchedIndex >= 0) {
            return matchedIndex
        }
        return Math.max(0, Math.min(fallbackIndex, targetList.size - 1))
    }

    @JvmStatic
    fun episodeMatchScore(currentName: String?, currentEpisode: Int, targetName: String?): Int {
        if (currentName.isNullOrEmpty() || targetName.isNullOrEmpty()) {
            return 0
        }
        if (targetName.equals(currentName, ignoreCase = true)) {
            return 100
        }
        if (currentEpisode >= 0 && extractEpisodeNumber(targetName) == currentEpisode) {
            return 80
        }
        val currentLower = currentName.lowercase(Locale.ROOT)
        val targetLower = targetName.lowercase(Locale.ROOT)
        if (currentEpisode < 0 && currentName.length >= 2 && targetLower.contains(currentLower)) {
            return 70
        }
        if (currentEpisode < 0 && targetName.length >= 2 && currentLower.contains(targetLower)) {
            return 60
        }
        return 0
    }

    /** 集号（跳过季号），取不到返回 -1；供跨线路匹配使用。 */
    @JvmStatic
    fun extractEpisodeNumber(name: String?): Int = parseEpisode(name).number

    /**
     * 选集卡片显示文本：普通集为「1」「2」，特别篇为「SP1」「SP」。
     * 取不到时返回空串，由调用方回退到序号。
     */
    @JvmStatic
    fun extractEpisodeLabel(name: String?): String {
        val parsed = parseEpisode(name)
        if (parsed.special) {
            return if (parsed.number > 0) "SP${parsed.number}" else "SP"
        }
        return if (parsed.number > 0) parsed.number.toString() else ""
    }

    private fun parseEpisode(name: String?): ParsedEpisode {
        if (isEmpty(name)) return ParsedEpisode(-1, false)
        val raw = name ?: return ParsedEpisode(-1, false)
        try {
            var text = raw.replace(parenContent, " ")
            text = text.replace(fullDate, " ")
            text = text.replace(compactDate, " ")
            text = text.replace(year, " ")
            text = text.lowercase(Locale.ROOT).replace(quality, " ")

            val special = specialMarker.matcher(text).find() || seasonZeroEpisode.matcher(text).find()

            // 显式剔除季号，只保留集号前缀：S01E02 -> e02、Season 2 Episode 10 -> Episode 10
            text = text.replace(seasonSE, "e ")
            text = text.replace(seasonWordEpisode, "episode")
            text = text.replace(seasonWord, " ")
            text = text.replace(seasonCn, " ")
            text = text.replace(seasonX, " ")

            var cnNumber = -1
            cnEpisode.find(text)?.let { cnNumber = cnToEpisodeNumber(it.groupValues[1]) }

            if (special) {
                var number = -1
                for (pattern in specialNumberPatterns) {
                    val value = pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (value != null) {
                        number = value
                        break
                    }
                }
                return ParsedEpisode(if (number >= 0) number else cnNumber, true)
            }

            for (pattern in episodeNumberPatterns) {
                val value = pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (value != null) return ParsedEpisode(value, false)
            }
            if (cnNumber >= 0) return ParsedEpisode(cnNumber, false)

            val digits = text.replace(nonDigit, "")
            if (digits.isNotEmpty()) return ParsedEpisode(digits.toInt(), false)
        } catch (ignored: Exception) {
            AppLog.d("EpisodeMatcher", "episode number extract failed, name=$name")
        }
        return ParsedEpisode(-1, false)
    }

    private fun cnToEpisodeNumber(text: String): Int {
        fun digit(c: String) = cnDigits.indexOf(c)
        if (text.length == 1) return digit(text)
        if (text == "十") return 10
        if (text.startsWith("十")) {
            val d = digit(text.substring(1))
            return if (d >= 0) 10 + d else -1
        }
        if (text.endsWith("十")) {
            val d = digit(text.substring(0, text.length - 1))
            return if (d >= 0) d * 10 else -1
        }
        val pos = text.indexOf('十')
        if (pos > 0) {
            val tens = digit(text.substring(0, pos))
            val ones = digit(text.substring(pos + 1))
            if (tens >= 0 && ones >= 0) return tens * 10 + ones
        }
        return -1
    }
}
