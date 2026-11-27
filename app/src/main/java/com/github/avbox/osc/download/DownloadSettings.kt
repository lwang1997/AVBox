package com.github.avbox.osc.download

import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys

object DownloadSettings {

    const val METHOD_ASK = 0
    const val METHOD_BUILTIN = 1
    const val METHOD_IDM = 2

    var concurrency: Int
        get() = KV.get(KVKeys.DOWNLOAD_CONCURRENCY, 2).coerceIn(1, 3)
        set(value) {
            KV.put(KVKeys.DOWNLOAD_CONCURRENCY, value.coerceIn(1, 3))
        }

    var wifiOnly: Boolean
        get() = KV.get(KVKeys.DOWNLOAD_WIFI_ONLY, false)
        set(value) {
            KV.put(KVKeys.DOWNLOAD_WIFI_ONLY, value)
            // 关掉“仅 Wi-Fi”时，队列里可能正躺着一批等网络的任务，立即放行。
            if (!value) DownloadManager.retryPending()
        }

    var method: Int
        get() = KV.get(KVKeys.DOWNLOAD_METHOD, METHOD_ASK).coerceIn(0, 2)
        set(value) {
            KV.put(KVKeys.DOWNLOAD_METHOD, value.coerceIn(0, 2))
        }

    var autoResume: Boolean
        get() = KV.get(KVKeys.DOWNLOAD_AUTO_RESUME, false)
        set(value) {
            KV.put(KVKeys.DOWNLOAD_AUTO_RESUME, value)
        }

    var notifyCompleted: Boolean
        get() = KV.get(KVKeys.DOWNLOAD_NOTIFY, true)
        set(value) {
            KV.put(KVKeys.DOWNLOAD_NOTIFY, value)
        }
}
