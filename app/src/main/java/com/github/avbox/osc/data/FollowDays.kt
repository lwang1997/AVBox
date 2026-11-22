package com.github.avbox.osc.data

import java.time.LocalDate

object FollowDays {

    private const val DAY_COUNT = 7

    fun encode(days: Set<Int>): String =
        days.filter { it in 0 until DAY_COUNT }
            .distinct()
            .sorted()
            .joinToString(",")

    fun decode(value: String?): Set<Int> =
        value.orEmpty()
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0 until DAY_COUNT }
            .toSet()

    fun todayIndex(): Int = LocalDate.now().dayOfWeek.value - 1

    /** 把 TMDB 的 air_date（yyyy-MM-dd）转成周一=0 的索引；非法日期返回 null。 */
    fun dayIndexOfAirDate(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(value.trim()).dayOfWeek.value - 1 }.getOrNull()
    }
}
