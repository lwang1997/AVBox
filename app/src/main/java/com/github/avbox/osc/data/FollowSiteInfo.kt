package com.github.avbox.osc.data

import com.github.avbox.osc.util.KV

/**
 * 追剧条目从绑定站点拉到的集数快照（本地缓存，不参与同步）。
 * 没有 TMDB 更新信息时，卡片用这里的集数展示「更新至第 N 集」。
 */
object FollowSiteInfo {

    private const val KEY_TOTAL = "follow_site_total"

    private const val KEY_CHECKED = "follow_site_checked"

    private const val MAX_ENTRIES = 500

    fun totals(): Map<String, Int> =
        read(KEY_TOTAL)
            .mapNotNull { (owner, raw) -> raw.toIntOrNull()?.takeIf { it > 1 }?.let { owner to it } }
            .toMap()

    fun checkedAt(owner: String): Long = read(KEY_CHECKED)[owner]?.toLongOrNull() ?: 0L

    @Synchronized
    fun putTotal(owner: String, total: Int?) {
        if (owner.isBlank()) return
        val map = read(KEY_TOTAL)
        val value = total?.takeIf { it > 1 }?.toString() ?: run {
            if (map.remove(owner) == null) return
            null
        }
        if (value != null) map[owner] = value
        trim(map)
        KV.put(KEY_TOTAL, map)
    }

    @Synchronized
    fun markChecked(owner: String, at: Long) {
        if (owner.isBlank()) return
        val map = read(KEY_CHECKED)
        map[owner] = at.toString()
        trim(map)
        KV.put(KEY_CHECKED, map)
    }

    private fun trim(map: HashMap<String, String>) {
        if (map.size <= MAX_ENTRIES) return
        map.keys.take(map.size - MAX_ENTRIES).forEach { map.remove(it) }
    }

    private fun read(key: String): HashMap<String, String> = KV.get(key, HashMap<String, String>())
}
