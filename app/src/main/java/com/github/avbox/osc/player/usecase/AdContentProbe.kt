package com.github.avbox.osc.player.usecase

import androidx.media3.common.util.UriUtil
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 视频流广告内容探测（参考 type4-server player/adfilter.js）。
 *
 * 对清单做同步规则过滤之外，拉取各候选块首分片的头部字节（48KB→96KB），
 * 解析 TS 的 PAT/PMT 拿音视频 PID，取首个 SPS 与 AAC 帧头组成指纹，
 * 按 DISCONTINUITY 块做多数派投票，识别「同目录混剪」广告。
 *
 * 只处理 VOD 清单（带 #EXT-X-ENDLIST / PLAYLIST-TYPE），跳过首尾块，
 * 并发 10、整体 8s 超时（超时按已返回的结果过滤）。
 */
object AdContentProbe {

    private const val TAG = "echo-adprobe"

    private const val FIRST_BYTES = 49152
    private const val FULL_BYTES = 98304
    private const val MAX_BLOCKS = 240
    private const val CONCURRENCY = 10
    private const val TIMEOUT_MS = 8000L
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    private data class CacheEntry(val fp: Fingerprint?, val size: Long, val ts: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** [vcodec, acodec, rate, spsHash, aprof, ach] */
    private data class Fingerprint(val vcodec: Int, val acodec: Int, val rate: Int, val sps: String, val aprof: Int, val ach: Int)

    private fun fpKey(fp: Fingerprint): String = "${fp.vcodec}|${fp.acodec}|${fp.sps}"

    private class Block(var start: Int, var end: Int) {
        var firstUri = ""
        var firstDur = 0.0
        var segs = 0
    }

    /**
     * 探测并剔除广告块，返回过滤后的清单；无需/无法过滤时返回原内容。
     */
    suspend fun dropAdSegments(content: String, baseUrl: String, headers: Map<String, String>?): String {
        return try {
            doDrop(content, baseUrl, headers)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("$TAG probe failed: $e")
            content
        }
    }

    private suspend fun doDrop(content: String, baseUrl: String, headers: Map<String, String>?): String {
        if (!content.startsWith("#EXTM3U")) return content
        if (content.contains("#EXT-X-STREAM-INF")) return content
        if (!isVod(content)) return content

        val normalized = content.replace(Regex("\r\n"), "\n")
        val lines = normalized.split("\n").toTypedArray()
        val blocks = parseBlocks(lines, baseUrl)
        if (blocks.size < 3 || blocks.size > MAX_BLOCKS) {
            AppLog.i("$TAG skip: blocks=${blocks.size}")
            return content
        }

        val totalSegs = blocks.sumOf { it.segs }
        if (totalSegs <= 0) return content

        // 跳过首尾块；candidates 存原 blocks 下标
        val candIndices = blocks.indices.filter { it in 1 until blocks.size - 1 && blocks[it].segs > 0 && blocks[it].firstUri.isNotEmpty() }
        if (candIndices.isEmpty()) return content
        val candidates = candIndices.map { blocks[it] }

        val fps = probeAll(candidates, headers)
        val adCand = classify(candidates, fps)
        if (adCand.isEmpty()) {
            AppLog.i("$TAG no ad blocks detected")
            return content
        }

        val adSet = adCand.map { candIndices[it] }.toSet()
        val adSegs = adSet.sumOf { blocks[it].segs }
        if (adSegs > totalSegs * 0.5) {
            AppLog.e("$TAG refuse to drop $adSegs/$totalSegs")
            return content
        }

        val out = StringBuilder()
        var keptSegs = 0
        var droppedSegs = 0
        for (i in blocks.indices) {
            val b = blocks[i]
            if (adSet.contains(i)) {
                droppedSegs += b.segs
                for (li in b.start until b.end) {
                    val t = lines[li].trim()
                    // EXT-X-KEY/MAP 状态需向后延续，不能随广告块一起删
                    if (t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MAP")) out.append(lines[li]).append('\n')
                }
            } else {
                for (li in b.start until b.end) out.append(lines[li]).append('\n')
                keptSegs += b.segs
            }
        }
        val result = out.toString()
        if (keptSegs <= 0 || !result.startsWith("#EXTM3U")) {
            AppLog.e("$TAG invalid playlist after probe drop, keep original")
            return content
        }
        AppLog.i("$TAG dropped $droppedSegs seg(s) in ${adSet.size} block(s)")
        return result
    }

    private fun isVod(content: String): Boolean {
        val m = Regex("(?i)#EXT-X-PLAYLIST-TYPE\\s*:\\s*(\\w+)").find(content)
        if (m != null) {
            val t = m.groupValues[1].uppercase()
            return t == "VOD" || t == "EVENT"
        }
        return content.contains("#EXT-X-ENDLIST")
    }

    private fun parseBlocks(lines: Array<String>, baseUrl: String): List<Block> {
        val blocks = ArrayList<Block>()
        blocks.add(Block(0, lines.size))
        var dur = 0.0
        for (i in lines.indices) {
            val t = lines[i].trim()
            if (t.isEmpty()) continue
            if (t.startsWith("#EXT-X-DISCONTINUITY") && !t.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) {
                if (blocks.last().segs > 0) {
                    blocks.last().end = i
                    blocks.add(Block(i, lines.size))
                }
                continue
            }
            val b = blocks.last()
            if (t.startsWith("#EXTINF")) {
                dur = parseDur(t)
                continue
            }
            if (t.startsWith("#")) continue
            b.segs += 1
            if (b.firstUri.isEmpty()) {
                b.firstUri = UriUtil.resolve(baseUrl, t)
                b.firstDur = dur
            }
        }
        // 空块（无分片）并入前一块，保证行区间连续无空洞
        val merged = ArrayList<Block>(blocks.size)
        for (b in blocks) {
            if (b.segs == 0) {
                if (merged.isNotEmpty()) merged.last().end = b.end
                else merged.add(b)
            } else {
                merged.add(b)
            }
        }
        return merged
    }

    private fun parseDur(extinf: String): Double {
        val body = extinf.substringAfter(":", "")
        val num = body.substringBefore(",").trim()
        return num.toDoubleOrNull() ?: 0.0
    }

    private suspend fun probeAll(candidates: List<Block>, headers: Map<String, String>?): Array<Fingerprint?> {
        val fps = arrayOfNulls<Fingerprint>(candidates.size)
        coroutineScope {
            val sem = Semaphore(CONCURRENCY)
            val jobs = candidates.mapIndexed { idx, block ->
                async {
                    sem.withPermit {
                        val v = probe(block.firstUri, block.firstDur, headers)
                        fps[idx] = v.fp
                    }
                }
            }
            val completed = withTimeoutOrNull(TIMEOUT_MS) { jobs.joinAll(); true }
            if (completed == null) {
                AppLog.i("$TAG timeout, use partial results")
                jobs.forEach { it.cancel() }
            }
        }
        return fps
    }

    private suspend fun probe(url: String, dur: Double, headers: Map<String, String>?): CacheEntry {
        val now = System.currentTimeMillis()
        cache[url]?.let { if (now - it.ts < CACHE_TTL_MS) return it }
        return try {
            var budget = FIRST_BYTES
            var fetched = fetch(url, budget, headers)
            var fp = parseTs(fetched.bytes)
            val wholeSegment = fetched.total in 1..budget.toLong()
            if (fp == null && !wholeSegment) {
                budget = FULL_BYTES
                fetched = fetch(url, budget, headers)
                fp = parseTs(fetched.bytes)
            }
            CacheEntry(fp, fetched.total, now).also { cache[url] = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("$TAG probe segment failed: $e")
            CacheEntry(null, 0L, now).also { cache[url] = it }
        }
    }

    private class FetchResult(val bytes: ByteArray, val total: Long)

    private suspend fun fetch(url: String, budget: Int, headers: Map<String, String>?): FetchResult {
        val resp = Http.getRaw(url) {
            if (headers != null) for ((k, v) in headers) headers(k, v)
            headers("Range", "bytes=0-" + (budget - 1))
        }
        var total = 0L
        val cr = resp.headers["Content-Range"] ?: ""
        if (cr.contains("/")) {
            cr.substringAfter("/", "").trim().toLongOrNull()?.let { total = it }
        }
        if (total <= 0L) total = (resp.headers["Content-Length"] ?: "").toLongOrNull() ?: 0L
        val bytes = if (resp.body.size > budget) resp.body.copyOfRange(0, budget) else resp.body
        return FetchResult(bytes, total)
    }

    /** 多数派投票 + 码率兜底（参考 type4-server classify）。返回 candidates 下标。 */
    private fun classify(candidates: List<Block>, fps: Array<Fingerprint?>): List<Int> {
        val counts = HashMap<String, Int>()
        var parsed = 0
        for (fp in fps) {
            if (fp == null) continue
            parsed++
            val k = fpKey(fp)
            counts[k] = (counts[k] ?: 0) + 1
        }
        if (parsed < 2) return emptyList()
        var best = ""
        var bn = 0
        for ((k, c) in counts) if (c > bn) { bn = c; best = k }
        if (bn.toDouble() / parsed < 0.6) return emptyList()

        // 多数派里能读到的采样率，用于区分 SPS 恰好相同的广告
        val rateCount = HashMap<Int, Int>()
        var bestRate = 0
        var rn = 0
        for (fp in fps) {
            if (fp == null || fpKey(fp) != best || fp.rate == 0) continue
            rateCount[fp.rate] = (rateCount[fp.rate] ?: 0) + 1
            if ((rateCount[fp.rate] ?: 0) > rn) { rn = rateCount[fp.rate] ?: 0; bestRate = fp.rate }
        }
        val ad = HashSet<Int>()
        val mainIdx = ArrayList<Int>()
        for (i in fps.indices) {
            val fp = fps[i] ?: continue
            if (fpKey(fp) != best) { ad.add(i); continue }
            if (bestRate > 0 && rn >= 3 && fp.rate != 0 && fp.rate != bestRate) { ad.add(i); continue }
            mainIdx.add(i)
        }

        // 码率只兜底「指纹解析失败」的块，阈值为正片码率中位数的 4 倍
        val kbps = DoubleArray(fps.size)
        for (i in fps.indices) {
            val dur = candidates[i].firstDur
            val size = cache[candidates[i].firstUri]?.size ?: 0L
            if (size > 0 && dur >= 1.0) kbps[i] = size * 8.0 / dur / 1000.0
        }
        val vals = mainIdx.map { kbps[it] }.filter { it > 0.0 }.sorted()
        if (vals.size >= 3) {
            val med = vals[vals.size / 2]
            if (med > 0) {
                for (i in fps.indices) {
                    if (fps[i] != null) continue
                    val v = kbps[i]
                    if (v > 0 && (v > med * 4 || v < med * 0.25)) ad.add(i)
                }
            }
        }
        return ad.sorted()
    }

    /* ---------------- TS 解析 ---------------- */

    private fun parseTs(buf: ByteArray): Fingerprint? {
        val n = buf.size
        var pmtPid = -1
        var vpid = -1
        var apid = -1
        var vcodec = 0
        var acodec = 0
        val vid = ArrayList<ByteArray>()
        val aud = ArrayList<ByteArray>()
        var vlen = 0
        var alen = 0
        var p = 0
        while (p + 188 <= n) {
            if (buf[p] != 0x47.toByte()) {
                var k = p + 1
                while (k + 188 <= n && buf[k] != 0x47.toByte()) k++
                p = k - 188
                continue
            }
            val b1 = buf[p + 1].toInt() and 0xff
            val afc = (buf[p + 3].toInt() shr 4) and 3
            val pid = ((b1 and 0x1f) shl 8) or (buf[p + 2].toInt() and 0xff)
            val pusi = (b1 and 0x40) != 0
            var st = p + 4
            if (afc and 2 != 0) st += 1 + (buf[p + 4].toInt() and 0xff)
            if (afc and 1 == 0 || st >= p + 188) { p += 188; continue }
            val pay = buf.copyOfRange(st, p + 188)
            when {
                pid == 0 -> {
                    if (pusi && pmtPid < 0) pmtPid = patPid(pay)
                }
                pid == pmtPid -> {
                    if (pusi && vpid < 0) {
                        val pm = pmtStreams(pay)
                        if (pm != null) { vpid = pm[0]; vcodec = pm[1]; apid = pm[2]; acodec = pm[3] }
                    }
                }
                pid == vpid && vlen < 65536 -> { vid.add(pay); vlen += pay.size }
                pid == apid && alen < 32768 -> { aud.add(pay); alen += pay.size }
            }
            if (vpid >= 0 && apid >= 0 && vlen >= 32768 && alen >= 8192) break
            p += 188
        }
        if (vpid < 0) return null
        val vbuf = concat(vid)
        val abuf = concat(aud)
        var rate = 0
        var aprof = -1
        var ach = 0
        if (acodec == 0x0f && abuf.isNotEmpty()) {
            var j = 0
            while (j + 6 < abuf.size) {
                if (abuf[j] == 0xff.toByte() && (abuf[j + 1] == 0xf1.toByte() || abuf[j + 1] == 0xf9.toByte())) {
                    val idx = ((abuf[j + 2].toInt() and 0xff) shr 2) and 0x0f
                    val flen = (((abuf[j + 3].toInt() and 3) shl 11) or ((abuf[j + 4].toInt() and 0xff) shl 3) or (((abuf[j + 5].toInt() and 0xff) shr 5)))
                    val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
                    if (idx < rates.size && flen > 10 && flen < 9000) {
                        rate = rates[idx]
                        aprof = ((abuf[j + 2].toInt() and 0xff) shr 6) and 3
                        ach = (((abuf[j + 2].toInt() and 1) shl 2) or (((abuf[j + 3].toInt() and 0xff) shr 6) and 3))
                        break
                    }
                }
                j++
            }
        }
        val sps = spsHash(vbuf) ?: return null
        return Fingerprint(vcodec, acodec, rate, sps, aprof, ach)
    }

    private fun concat(list: List<ByteArray>): ByteArray {
        var n = 0
        for (b in list) n += b.size
        val out = ByteArray(n)
        var o = 0
        for (b in list) { System.arraycopy(b, 0, out, o, b.size); o += b.size }
        return out
    }

    private fun patPid(sec: ByteArray): Int {
        if (sec.size < 8) return -1
        val off = 1 + (sec[0].toInt() and 0xff)
        if (off + 8 > sec.size || sec[off] != 0x00.toByte()) return -1
        val end = minOf(sec.size, off + 3 + (((sec[off + 1].toInt() and 0x0f) shl 8) or (sec[off + 2].toInt() and 0xff)) - 4)
        var i = off + 8
        while (i + 4 <= end) {
            val prog = ((sec[i].toInt() and 0xff) shl 8) or (sec[i + 1].toInt() and 0xff)
            if (prog != 0) return (((sec[i + 2].toInt() and 0x1f) shl 8) or (sec[i + 3].toInt() and 0xff))
            i += 4
        }
        return -1
    }

    private fun pmtStreams(sec: ByteArray): IntArray? {
        if (sec.size < 16) return null
        val off = 1 + (sec[0].toInt() and 0xff)
        if (off + 12 > sec.size || sec[off] != 0x02.toByte()) return null
        val end = minOf(sec.size, off + 3 + (((sec[off + 1].toInt() and 0x0f) shl 8) or (sec[off + 2].toInt() and 0xff)) - 4)
        val pil = ((sec[off + 10].toInt() and 0x0f) shl 8) or (sec[off + 11].toInt() and 0xff)
        var i = off + 12 + pil
        var v = -1; var vc = 0; var a = -1; var ac = 0
        while (i + 5 <= end) {
            val st = sec[i].toInt() and 0xff
            val pid = (((sec[i + 1].toInt() and 0x1f) shl 8) or (sec[i + 2].toInt() and 0xff))
            val esil = ((sec[i + 3].toInt() and 0x0f) shl 8) or (sec[i + 4].toInt() and 0xff)
            if (st == 0x01 || st == 0x02 || st == 0x1b || st == 0x24) {
                if (v < 0) { v = pid; vc = st }
            } else if (st == 0x0f || st == 0x11 || st == 0x81) {
                if (a < 0) { a = pid; ac = st }
            }
            i += 5 + esil
        }
        return if (v >= 0) intArrayOf(v, vc, a, ac) else null
    }

    private fun spsHash(buf: ByteArray): String? {
        var i = 0
        while (i + 4 < buf.size) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 1.toByte()) {
                val t = buf[i + 3].toInt() and 0x1f
                if (t == 7) {
                    var j = i + 4
                    var len = 0
                    while (j + len < buf.size && len < 512) {
                        if (buf[j + len] == 0.toByte() && j + len + 2 < buf.size &&
                            buf[j + len + 1] == 0.toByte() && (buf[j + len + 2].toInt() and 0xff) <= 1
                        ) break
                        len++
                    }
                    if (len >= 4) return fnv(buf, j, len)
                }
            }
            i++
        }
        return null
    }

    private fun fnv(bytes: ByteArray, off: Int, len: Int): String {
        var h = 0x811c9dc5.toInt()
        for (i in 0 until len) {
            h = h xor (bytes[off + i].toInt() and 0xff)
            h = h + (h shl 1) + (h shl 4) + (h shl 7) + (h shl 8) + (h shl 24)
        }
        return String.format("%08x", h.toLong() and 0xffffffffL)
    }
}
