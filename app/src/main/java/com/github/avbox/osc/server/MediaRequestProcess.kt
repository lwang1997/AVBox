package com.github.avbox.osc.server

import com.github.avbox.osc.data.PlaybackPorts

import fi.iki.elonen.NanoHTTPD

/**
 * spider.jar 兼容端点：`GET /media`。
 * jar 的弹幕兜底分支（360kan）会请求 `http://127.0.0.1:9978/media` 读当前播放时长。
 */
class MediaRequestProcess : RequestProcess {

    override fun isRequest(session: NanoHTTPD.IHTTPSession, fileName: String?): Boolean {
        return fileName == "/media"
    }

    override fun doResponse(
        session: NanoHTTPD.IHTTPSession,
        fileName: String?,
        params: MutableMap<String, String>?,
        files: MutableMap<String, String>?,
    ): NanoHTTPD.Response {
        val json = try {
            PlaybackPorts.ports?.mediaJson() ?: "{}"
        } catch (th: Throwable) {
            "{}"
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json)
    }
}
