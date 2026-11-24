package com.github.avbox.osc.api

import android.text.TextUtils

import com.github.catvod.crawler.SpiderReaper
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.net.StreamProxy
import com.github.avbox.osc.util.DefaultConfig
import com.github.avbox.osc.util.KVKeys
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog

import java.net.URLDecoder
import java.util.HashMap

class ProxyEntry(private val owner: ApiConfig, private val spiderLoader: SpiderLoader) {
    private var currentPlaySourceKey = ""

    fun proxyLocal(param: MutableMap<String, String>): Array<Any?>? {
        val action = param["do"]
        // 弹幕代理（appdanmu/danmaku/danmu）由 spider.jar 的 Proxy/Danmu 处理，站点自身的 py/js 代理不实现它；
        // 之前按站点类型路由会把请求交给 py/js 的 localProxy（通常返回空），线路弹幕因此永远是空包。
        if (isDanmakuAction(action)) {
            val result = proxyDanmakuByJar(param)
            if (result != null) return result
        }

        val source = currentProxySource(param)
        val api = source.api ?: return null

        val siteKey = param["siteKey"]

        val isJs = "js" == action
        val isPy = "py" == action
        val isLive = KV.get(KVKeys.PLAYER_IS_LIVE, false)
        val isApiJs = api.contains(".js")
        val isApiPy = api.contains(".py")

        val canUseType3 = !TextUtils.isEmpty(siteKey)
                && source.type == 3
                && !isJs
                && !isPy
                && !isLive
                && !isApiJs
                && !isApiPy

        if (canUseType3) {
            try {
                val spider = owner.getCSP(source)

                var result: Array<Any?>? = null
                try {
                    result = SpiderReaper.track(spider) { spider.proxy(param) }
                } catch (th: Throwable) {
                    AppLog.e("echo-proxy-route: spider.proxy error, fallback | " + th)
                }
                if (result != null) {
                    AppLog.i("echo-proxy-route: spider.proxy ok | " + describeResult(result))
                    return result
                }
                AppLog.e("echo-proxy-route: spider.proxy null, try proxyInvokeJar")

                result = spiderLoader.proxyInvokeJar(param)
                if (result != null) {
                    AppLog.i("echo-proxy-route: proxyInvokeJar ok | " + describeResult(result))
                    return result
                }
                AppLog.e("echo-proxy-route: proxyInvokeJar null, try proxyDirect")

                result = proxyDirect(param)
                if (result != null) {
                    AppLog.i("echo-proxy-route: proxyDirect ok | " + describeResult(result))
                    return result
                }
                AppLog.e("echo-proxy-route: proxyDirect null, give up")

                return null
            } catch (th: Throwable) {
                AppLog.e("echo-proxy-route: type3 route error | " + th + " | msg=" + th.message)
                return null
            }
        }

        if (isJs) {
            return spiderLoader.proxyInvokeJs(param)
        }

        if (isLive) {
            val liveApi = spiderLoader.currentLiveSpider ?: ""

            if (liveApi.contains(".py")) {
                return spiderLoader.proxyInvokePy(param, spiderLoader.currentLivePyKey)
            }
            if (liveApi.contains(".js")) {
                return spiderLoader.proxyInvokeJs(param)
            }
            return spiderLoader.proxyInvokeJar(param)
        }

        if (isPy) {
            return spiderLoader.proxyInvokePy(param, getCurrentPyKey())
        }

        if (isApiPy) {
            return spiderLoader.proxyInvokePy(param, getCurrentPyKey())
        }

        return spiderLoader.proxyInvokeJar(param)
    }

    private fun isDanmakuAction(action: String?): Boolean {
        return action == "appdanmu" || action == "danmaku" || action == "danmu"
    }

    /**
     * 弹幕代理统一交给 spider.jar（jar 的 Proxy.proxy 按 do 分发到 Danmu.AppDanmu / Danmaku / Danmu）。
     * jar 参数里不接受 siteKey（JarLoader 会按它找站点 jar，py/js 站点没有对应 jar），所以临时摘掉。
     */
    private fun proxyDanmakuByJar(param: MutableMap<String, String>): Array<Any?>? {
        val siteKey = param.remove("siteKey")
        try {
            val result = spiderLoader.proxyInvokeJar(param)
            AppLog.i("echo-proxy-danmu-route: jar " + (if (result == null) "miss" else describeResult(result)))
            return result
        } catch (th: Throwable) {
            AppLog.e("echo-proxy-danmu-route: jar error | " + th)
            return null
        } finally {
            if (siteKey != null) param["siteKey"] = siteKey
        }
    }

    private fun proxyDirect(param: MutableMap<String, String>): Array<Any?>? {
        try {
            var url = param["url"]
            if (url.isNullOrEmpty()) return null
            url = URLDecoder.decode(url, "UTF-8")
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            if (!DefaultConfig.isVideoFormat(url)) return null
            if (url.contains(".m3u8")) {
                param["url"] = url
                param["go"] = "live"
                param["type"] = "m3u8"
                return StreamProxy.itv(param)
            }
            return null
        } catch (th: Throwable) {
            AppLog.e("echo-proxy direct fallback error: " + th.message)
            return null
        }
    }

    private fun describeResult(result: Array<Any?>): String {
        if (result.isEmpty()) return "empty"
        val status = result[0]
        if (status !is Int) return "body=" + (status?.javaClass?.simpleName ?: "null")
        return "status=" + status + " mime=" + result.getOrNull(1) +
            " stream=" + (result.getOrNull(2)?.javaClass?.simpleName ?: "null")
    }

    private fun currentProxySource(param: MutableMap<String, String>): SourceBean {
        var siteKey = param["siteKey"]
        if (TextUtils.isEmpty(siteKey)) {
            siteKey = currentPlaySourceKey
            if (!TextUtils.isEmpty(siteKey)) param["siteKey"] = siteKey
        }
        val sourceBean = if (TextUtils.isEmpty(siteKey)) null else owner.getSource(siteKey)
        return sourceBean ?: owner.homeSourceBean()
    }

    fun setCurrentPlaySourceKey(sourceKey: String?) {
        currentPlaySourceKey = sourceKey ?: ""
    }

    private fun getCurrentPyKey(): String? {
        val sourceBean = currentProxySource(HashMap<String, String>())
        if (sourceBean.api?.contains(".py") == true) {
            if (sourceBean.key != spiderLoader.currentPyKey) {
                spiderLoader.currentPyKey = sourceBean.key
                spiderLoader.pySpider(sourceBean.key, sourceBean.api, sourceBean.ext)
            }
            return spiderLoader.currentPyKey
        }
        return spiderLoader.currentPyKey
    }
}
