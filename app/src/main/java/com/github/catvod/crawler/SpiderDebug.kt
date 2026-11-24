package com.github.catvod.crawler

import com.github.avbox.osc.util.AppLog
import java.util.concurrent.atomic.AtomicInteger

class SpiderDebug {

    companion object {

        /** 把 jar 的调试日志镜像进应用日志，但限量，避免刷掉其它日志。 */
        private const val MIRROR_MAX = 500
        private val mirrored = AtomicInteger(0)

        /** 日志会落盘到用户可见目录，含凭据的行不镜像。 */
        private val SENSITIVE = arrayOf(
            "token", "cookie", "authorization", "headers", "password",
            "secret", "oauth", "x-client-id", "x-device-id", "sign=",
        )

        private fun looksSensitive(text: String): Boolean {
            val lower = text.lowercase(java.util.Locale.ROOT)
            return SENSITIVE.any { lower.contains(it) }
        }

        private fun mirror(level: Char, text: String) {
            if (mirrored.get() >= MIRROR_MAX) return
            if (looksSensitive(text)) return
            mirrored.incrementAndGet()
            try {
                if (level == 'E') AppLog.e(text) else AppLog.i(text)
            } catch (th: Throwable) {
            }
        }

        @JvmStatic
        fun log(th: Throwable?) {
            val error = th ?: return
            try {
                android.util.Log.d("SpiderLog", "" + error.message, error)
            } catch (th1: Throwable) {
            }
            mirror('E', "echo-spider: " + (error.message ?: error.javaClass.simpleName))
        }

        @JvmStatic
        fun log(msg: String?) {
            try {
                android.util.Log.d("SpiderLog", "" + msg)
            } catch (th1: Throwable) {
            }
            mirror('I', "echo-spider: " + (msg ?: ""))
        }

        @JvmStatic
        fun ec(i: Int): String {
            return ""
        }
    }
}
