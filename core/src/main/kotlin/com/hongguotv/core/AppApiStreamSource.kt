// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * The /novel/player/video_model/v1/ endpoint with the playback signature envelope.
 * Returns the multi-quality model and any alternate media URLs. Session recovery
 * belongs to the shared transport, so parallel foreground failures reuse one refresh.
 *
 * The comic content_type must be passed per-series (1004 for comic dramas, upstream
 * 1729ef4a); real/AI dramas use 1. The server tolerates 1 for comics too, but the real
 * value keeps responses consistent with the official app.
 */
internal class AppApiStreamSource(private val transport: AppApiTransport,
    private val host: String="api5-normal-sinfonlinea.fqnovel.com",
    override val name: String="app-api") : StreamSource {

    private fun playbackContentType(type: ContentType) = if (type == ContentType.COMIC) 1004 else 1

    override fun stream(id: String, maxQuality: Int, type: ContentType): StreamInfo {
        val payload = JSONObject()
            .put("video_id", id).put("content_type", playbackContentType(type))
            .put("biz_param", JSONObject().put("need_all_video_definition", true).put("video_platform", 3))
        val data = transport.playbackRequest("/novel/player/video_model/v1/", payload.toString(),host)
        val map = data.optJSONObject("data") ?: throw IOException("播放信息为空")
        var model = map.opt("video_model")
        if (model is String) model = JSONObject(model)
        val modelObj = model as? JSONObject ?: throw IOException("播放信息格式异常")
        val videoList = modelObj.optJSONObject("video_list") ?: run {
            val list = modelObj.optJSONArray("video_list")
            if (list != null) { val obj = JSONObject(); for (i in 0 until list.length()) obj.put("$i", list.getJSONObject(i)); obj }
            else throw IOException("没有可用清晰度")
        }
        // The current API flattens variant fields (definition/codec_type/vwidth/vheight/spade_a)
        // directly on each entry; older responses nested them under video_meta/encrypt_info.
        val rows = videoList.keys().asSequence().mapNotNull { videoList.optJSONObject(it) }
            .map { o ->
                val meta = o.optJSONObject("video_meta")
                val codec = (meta?.str("codec_type") ?: o.str("codec_type")).lowercase(Locale.ROOT)
                val definition = (meta?.str("definition") ?: o.str("definition")).substringBefore('p').toIntOrNull() ?: 0
                val height = (meta?.str("vheight") ?: o.str("vheight")).toIntOrNull() ?: 0
                val width = (meta?.str("vwidth") ?: o.str("vwidth")).toIntOrNull() ?: 0
                val quality = if (definition > 0) definition else if (width > 0 && (height == 0 || width < height)) width else height
                Triple(quality, codec, o)
            }.sortedByDescending { it.first }.toList()
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor;
        // never select a known incompatible variant (kept from the original kotlin-tv logic).
        val playable = rows.filter { it.second !in setOf("bytevc2","bvc2") }
        // Prefer h264 for TV compatibility; fall back to others.
        val within=playable.filter { it.first in 1..maxQuality }
        val capped=within.ifEmpty { playable.takeLast(1) }
        val compatible = capped.filter { it.second in setOf("h264","avc","avc1") }
        val pool = if (compatible.isNotEmpty()) compatible else capped
        val selected = pool.firstOrNull()
            ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item = selected.third
        var url = cleanUrl(item.str("main_url"))
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            // main_url may be base64-encoded
            val decoded = runCatching { String(java.util.Base64.getDecoder().decode(url), Charsets.UTF_8).trim() }.getOrNull()
            if (decoded != null && (decoded.startsWith("https://") || decoded.startsWith("http://"))) url = decoded
            else throw IOException("播放地址无效")
        }
        val encryption = item.optJSONObject("encrypt_info")
        val spade = encryption?.str("spade_a")?.ifEmpty { item.str("spade_a") } ?: item.str("spade_a")
        val key = if (spade.isNotEmpty()) runCatching { MediaCrypto.deriveKey(spade) }.getOrNull() else null
        val quality = if (selected.first > 0) "${selected.first}P" + (if (selected.first > maxQuality) "（兼容资源）" else "") else "自动"
        val backups=listOf("backup_url","backup_url_1").mapNotNull { field ->
            val raw=cleanUrl(item.str(field))
            val decoded=if(raw.startsWith("https://") || raw.startsWith("http://")) raw else
                runCatching { String(java.util.Base64.getDecoder().decode(raw),Charsets.UTF_8).trim() }.getOrNull()
            decoded?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }.filter { it!=url }.distinct()
        return StreamInfo(url, key, quality,backupUrls=backups)
    }
}
