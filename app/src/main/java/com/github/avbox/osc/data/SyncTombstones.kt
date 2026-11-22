package com.github.avbox.osc.data

import com.github.avbox.osc.util.KV
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 同步删除墓碑。不新增 Room 表 / 不改 schema，借用 MMKV 单键存 JSON map。
 * 键格式：
 *   H<SEP>cid<SEP>sourceKey<SEP>vodId   历史单条删除
 *   C<SEP>cid<SEP>sourceKey<SEP>vodId   收藏单条删除
 *   F<SEP>cid<SEP>sourceKey<SEP>vodId   追剧单条删除
 *   P<SEP>progressKey                   进度单条删除
 *   X<SEP>kind<SEP>cid                  某类型按 cid 清空
 */
object SyncTombstones {

    const val KIND_HISTORY = "H"
    const val KIND_COLLECT = "C"
    const val KIND_FOLLOW = "F"
    const val KIND_PROGRESS = "P"
    const val KIND_CLEAR = "X"

    private const val SEP = "\u0001"
    private const val KEY = "sync_tombstones"

    private val gson = Gson()
    private val mapType = object : TypeToken<HashMap<String, Long>>() {}.type

    @Synchronized
    fun read(): HashMap<String, Long> = readLocked()

    private fun readLocked(): HashMap<String, Long> {
        val raw = KV.get(KEY, "")
        if (raw.isNullOrEmpty()) return HashMap()
        return try {
            gson.fromJson<HashMap<String, Long>>(raw, mapType) ?: HashMap()
        } catch (e: Exception) {
            HashMap()
        }
    }

    @Synchronized
    fun write(map: Map<String, Long>) {
        KV.put(KEY, gson.toJson(map))
    }

    @Synchronized
    fun mark(kind: String, cid: String?, sourceKey: String?, vodId: String?, at: Long = System.currentTimeMillis()) {
        val key = listOf(kind, cid.orEmpty(), sourceKey.orEmpty(), vodId.orEmpty()).joinToString(SEP)
        putMax(key, at)
    }

    @Synchronized
    fun markProgress(progressKey: String, at: Long = System.currentTimeMillis()) {
        if (progressKey.isEmpty()) return
        putMax(KIND_PROGRESS + SEP + progressKey, at)
    }

    @Synchronized
    fun markClear(kind: String, cid: String?, at: Long = System.currentTimeMillis()) {
        putMax(listOf(KIND_CLEAR, kind, cid.orEmpty()).joinToString(SEP), at)
    }

    @Synchronized
    private fun putMax(key: String, at: Long) {
        val map = readLocked()
        val old = map[key] ?: 0L
        if (at > old) {
            map[key] = at
            KV.put(KEY, gson.toJson(map))
        }
    }

    fun historyKey(cid: String?, sourceKey: String?, vodId: String?): String =
        listOf(KIND_HISTORY, cid.orEmpty(), sourceKey.orEmpty(), vodId.orEmpty()).joinToString(SEP)

    fun collectKey(cid: String?, sourceKey: String?, vodId: String?): String =
        listOf(KIND_COLLECT, cid.orEmpty(), sourceKey.orEmpty(), vodId.orEmpty()).joinToString(SEP)

    fun followKey(cid: String?, sourceKey: String?, vodId: String?): String =
        listOf(KIND_FOLLOW, cid.orEmpty(), sourceKey.orEmpty(), vodId.orEmpty()).joinToString(SEP)

    fun progressKey(progressKey: String): String = KIND_PROGRESS + SEP + progressKey

    fun clearKey(kind: String, cid: String?): String =
        listOf(KIND_CLEAR, kind, cid.orEmpty()).joinToString(SEP)
}
