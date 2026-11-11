// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException

enum class PlaybackSource(val id: String,val label: String) {
    AUTO("auto","自动选择"),
    PRIMARY("app-api","线路一"),
    SECONDARY("app-api-b","线路二"),
    BACKUP("app-api-hl","线路三"),
    LF("app-api-lf","线路四"),
    C("app-api-c","线路五"),
    READING("app-reading","线路六"),
    ORIGINAL("openhongguo-0.2.0","原有线路"),
    WEB("public-mp4","网页线路 · 360P");

    companion object {
        fun fromStored(value: String?)=entries.firstOrNull { it.id==value } ?: AUTO
        fun labelFor(id: String)=if(id=="offline") "本地下载" else entries.firstOrNull { it.id==id }?.label ?: "备用线路"
    }
}

/**
 * One playback address channel. The playback chain has been reworked four times
 * (drpys x-argus -> signed App API -> website public MP4 -> unsigned App API), so each
 * channel lives in its own class: when an endpoint changes, fix or replace the affected
 * channel only, or register a new one in [Companion.default] - the repository and the
 * app never change.
 *
 * Implementations must be stateless per call (thread-safe) and throw [IOException]
 * with a user-facing message on failure; the scheduler falls through to the next
 * channel and surfaces the LAST error if all channels fail.
 */
interface StreamSource {
    /** Stable identifier for logging ("app-api", "public-mp4", ...). */
    val name: String

    /** Resolve the playback address for one episode. */
    fun stream(id: String, maxQuality: Int, type: ContentType): StreamInfo

    companion object {
        /**
         * Current signed playback endpoints first, followed by legacy API and web
         * compatibility probes. These hosts belong to the same content platform.
         */
        internal fun default(transport: AppApiTransport, http: okhttp3.OkHttpClient, webUa: String, site: String): List<StreamSource> =
            listOf(
                AppApiStreamSource(transport),
                AppApiStreamSource(transport,"api5-normal-sinfonlineb.fqnovel.com","app-api-b"),
                AppApiStreamSource(transport,"api5-normal-hl.fqnovel.com","app-api-hl"),
                AppApiStreamSource(transport,"api5-normal-lf.fqnovel.com","app-api-lf"),
                AppApiStreamSource(transport,"api5-normal-sinfonlinec.fqnovel.com","app-api-c"),
                AppApiStreamSource(transport,"reading.snssdk.com","app-reading"),
                MultiVideoModelStreamSource(transport, http),
                PublicMp4StreamSource(http, webUa, site),
            )
    }
}

/**
 * Runs the fallback chain: first channel that resolves wins; failures are logged and
 * skipped. Throws the last channel's error when everything fails.
 */
internal class StreamScheduler(private val sources: List<StreamSource>) {
    private val log = java.util.logging.Logger.getLogger("com.hongguotv.core.stream")
    private val results=mutableMapOf<String,Boolean>()
    @Synchronized fun status(id: String): Boolean?=results[id]
    @Synchronized private fun record(id: String,success: Boolean) { results[id]=success }

    fun stream(id: String,maxQuality: Int,type: ContentType,choice: PlaybackSource=PlaybackSource.AUTO): StreamInfo =
        resolve(id,maxQuality,type,choice) { it }

    fun video(id: String,maxQuality: Int,type: ContentType,choice: PlaybackSource,http: okhttp3.OkHttpClient): RemoteVideo =
        resolve(id,maxQuality,type,choice) { info ->
            val video=RemoteVideo(http,info)
            try { video.prepare() } catch(error: Exception) {
                video.close()
                throw if(error is IOException) error else IOException("视频数据无法处理",error)
            }
        }

    private fun <T> resolve(id: String,maxQuality: Int,type: ContentType,choice: PlaybackSource,prepare: (StreamInfo)->T): T {
        var last: IOException? = null
        val selected=if(choice==PlaybackSource.AUTO) sources else sources.filter { it.name==choice.id }
        val candidates=if(RequestScope.isSpeculative()) selected.take(1) else selected
        if(candidates.isEmpty()) throw IOException("所选播放源暂未配置，请选择其他线路")
        for (source in candidates) {
            if(Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
            try {
                val info=source.stream(id,maxQuality,type).copy(source=source.name)
                val result=prepare(info)
                record(source.name,true)
                return result
            } catch (e: IOException) {
                if(e is InterruptedIOException && e !is java.net.SocketTimeoutException || Thread.currentThread().isInterrupted) throw e
                record(source.name,false)
                last = e
                log.warning("channel ${source.name} failed for $id: $e")
            } catch (e: org.json.JSONException) {
                record(source.name,false)
                // A changed or non-JSON response is a channel failure. Android's
                // JSONObject parser can reject envelopes accepted on the JVM.
                last = IOException("播放接口返回无效数据", e)
                log.warning("channel ${source.name} returned invalid JSON")
            }
        }
        throw last ?: IOException("没有可用的播放通道")
    }
}
