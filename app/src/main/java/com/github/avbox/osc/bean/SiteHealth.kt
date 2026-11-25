package com.github.avbox.osc.bean

/**
 * 单个站点的实时健康快照。状态来自真实请求结果或闲时低优先级补查，
 * 不再做「只看本地文件是否存在」的静态推断。UI 只读。
 *
 * 字段全部有默认值，便于 Gson 从 KV 反序列化旧缓存。
 */
data class SiteHealth(
    val key: String = "",
    val state: State = State.UNKNOWN,
    val latencyMs: Long = 0L,
    val checkedAt: Long = 0L,
    /** 状态来源：use = 接口实际使用，play = 播放结果，idle = 闲时补查 */
    val via: String = "",
) {

    /**
     * 三档可见状态；UNKNOWN 仅表示还没有任何真实采样，不占用颜色。
     * 枚举名保持语义化，不直接写进 UI 文案。
     */
    enum class State { UNKNOWN, GOOD, SLOW, BAD }

    companion object {
        const val VIA_USE = "use"
        const val VIA_PLAY = "play"
        const val VIA_IDLE = "idle"
    }
}
