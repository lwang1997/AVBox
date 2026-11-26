package com.github.avbox.osc.player.core

import android.text.TextUtils

object HttpHeaders {

    const val HEADER_FORMAT = "TVBox-Format"

    const val HEADER_ISO = "TVBox-Iso"

    @JvmStatic
    fun toRequestHeaders(headers: Map<String, String>?): HashMap<String, String> {
        val requestHeaders = HashMap<String, String>()
        if (headers == null) {
            return requestHeaders
        }
        for (entry in headers.entries) {
            val key = entry.key
            val value = entry.value
            if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                continue
            }
            if (HEADER_FORMAT.equals(key, ignoreCase = true)) {
                continue
            }
            if (HEADER_ISO.equals(key, ignoreCase = true)) {
                continue
            }
            // 这几个头由 HTTP 库自己管：站点给的 Accept-Encoding 会让 OkHttp 关掉透明 gzip 解压，
            // 服务器回压缩内容时播放器直接拿到 gzip 字节（表现为 HLS "#EXTM3U 解析失败"）。
            if (isLibraryManagedHeader(key)) {
                continue
            }
            requestHeaders[key] = value.trim { it <= ' ' }
        }
        return requestHeaders
    }

    private fun isLibraryManagedHeader(key: String): Boolean = when {
        key.equals("Accept-Encoding", ignoreCase = true) -> true
        key.equals("Range", ignoreCase = true) -> true
        key.equals("Host", ignoreCase = true) -> true
        key.equals("Connection", ignoreCase = true) -> true
        else -> false
    }
}
