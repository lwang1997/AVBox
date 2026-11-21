package com.github.avbox.osc.util.kv

import com.github.avbox.osc.util.kvcodec.KVDecoder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NestedMapDefaultValueTest {

    @Test
    fun rawHashMapDefault_degradesInnerMapType() {
        val decoder = KVDecoder()
        decoder.setRegistry(KVKeySpec())
        val stored = HashMap<String, HashMap<String, String>>()
        stored["api"] = hashMapOf("site" to "1")
        val raw = decoder.encode(stored)

        val byDefault = decoder.decode("checked_sources_for_search", raw, HashMap<String, HashMap<String, String>>())
        val inner = (byDefault as Map<*, *>)["api"]
        // 精确类型比较：gson 2.15 起无类型解码产物是 LinkedHashMap（HashMap 子类），
        // 用 is HashMap 无法区分「裸默认值降级」与「注册表精确类型」两条路径
        assertFalse("裸 HashMap 默认值下内层不应是精确的 HashMap", inner?.javaClass == HashMap::class.java)

        val byRegistry = decoder.decode<HashMap<String, HashMap<String, String>>?>(
            "checked_sources_for_search",
            raw,
            null,
        )
        assertTrue("走登记表读取时内层必须是 HashMap", (byRegistry as Map<*, *>)["api"]?.javaClass == HashMap::class.java)
    }
}
