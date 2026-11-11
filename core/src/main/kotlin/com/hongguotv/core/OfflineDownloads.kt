// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

data class OfflineEpisode(val id: String,val index: Int,val bytes: Long,val total: Long,
                          val complete: Boolean,val skipped: Boolean)
data class OfflineSeries(val series: Series,val episodes: List<OfflineEpisode>,val type: ContentType,
                         val quality: Int,val paused: Boolean,val error: String) {
    val completeCount get()=episodes.count { it.complete }
    val bytes get()=episodes.sumOf { it.bytes }
    val pending get()=!paused && episodes.any { !it.complete && !it.skipped }
}
data class OfflineSnapshot(val loading: Boolean=true,val series: List<OfflineSeries> = emptyList(),
                           val activeSeries: String?=null,val activeEpisode: String?=null,
                           val waitingForPlayback: Boolean=false,val storageError: String="") {
    val pending get()=series.any { it.pending }
}

/** One disk/network worker owns the catalog. Cancellation interrupts only its active transfer. */
class OfflineDownloads(
    private val root: File,
    private val resolve: (String,Int,ContentType,PlaybackSource)->VideoInput,
    private val reserveBytes: Long=512L*1024*1024
) : Closeable {
    private class Checkpoint(var bytes: Long=0,var total: Long=0,var signature: String="",
                             var complete: Boolean=false,var skipped: Boolean=false)
    private class Entry(val series: Series,var ids: List<String>,val type: ContentType,
                        var quality: Int,var source: PlaybackSource,var paused: Boolean=false,
                        var error: String="",val files: MutableMap<String,Checkpoint> = linkedMapOf())
    private class Active(val series: String,val episode: String) {
        val cancelled=AtomicBoolean()
        val scope=RequestScope()
        @Volatile var video: VideoInput?=null
        fun cancel() {
            cancelled.set(true)
            scope.cancel()
            runCatching { video?.close() }
        }
    }
    private val worker=Executors.newSingleThreadExecutor { task ->
        Thread(task,"offline-download").apply { priority=Thread.MIN_PRIORITY }
    }
    private val entries=linkedMapOf<String,Entry>()
    private val index=File(root,"index.json")
    @Volatile private var active: Active?=null
    @Volatile private var enabled=false
    @Volatile private var playback=false
    @Volatile private var state=OfflineSnapshot()
    private var scheduled=false
    private var storageError=""
    private var catalogLocked=false
    init { worker.execute { load(); publish() } }

    fun snapshot()=state
    fun start() { enabled=true; worker.execute { schedule() } }
    fun stop() { enabled=false; active?.cancel() }
    fun setPlaybackActive(value: Boolean) {
        if(playback==value) return
        playback=value
        if(value) active?.cancel()
        worker.execute { publish(); schedule() }
    }
    fun enqueue(detail: Detail,quality: Int,source: PlaybackSource) {
        validId(detail.series.id)
        require(detail.episodes.isNotEmpty())
        detail.episodes.forEach(::validId)
        worker.execute {
            if(catalogLocked) return@execute
            val prior=entries[detail.series.id]
            val entry=prior ?: Entry(detail.series,detail.episodes,detail.contentType,quality,source)
            entry.ids=(detail.episodes+entry.ids).distinct()
            entry.quality=quality
            entry.source=source
            entry.paused=false
            entry.error=""
            entry.files.values.forEach { it.skipped=false }
            entries[detail.series.id]=entry
            saveAndPublish()
            schedule()
        }
    }
    fun pause(id: String) {
        active?.takeIf { it.series==id }?.cancel()
        worker.execute { if(!catalogLocked) { entries[id]?.paused=true; saveAndPublish() } }
    }
    fun resume(id: String) {
        worker.execute {
            if(catalogLocked) return@execute
            entries[id]?.let { it.paused=false; it.error="" }
            saveAndPublish()
            schedule()
        }
    }
    fun deleteEpisode(seriesId: String,episodeId: String) {
        validId(seriesId); validId(episodeId)
        active?.takeIf { it.series==seriesId && it.episode==episodeId }?.cancel()
        worker.execute {
            if(catalogLocked) return@execute
            val entry=entries[seriesId] ?: return@execute
            try {
                remove(file(seriesId,episodeId,true))
                remove(file(seriesId,episodeId,false))
                entry.files[episodeId]=Checkpoint(skipped=true)
                entry.error=""
            } catch(error: IOException) { entry.paused=true; entry.error=error.message.orEmpty() }
            saveAndPublish()
            schedule()
        }
    }
    fun deleteSeries(id: String) {
        validId(id)
        active?.takeIf { it.series==id }?.cancel()
        worker.execute {
            if(catalogLocked) return@execute
            try {
                val dir=directory(id)
                dir.listFiles()?.forEach { remove(it) }
                remove(dir)
                entries.remove(id)
            } catch(error: IOException) {
                entries[id]?.let { it.paused=true; it.error=error.message.orEmpty(); inspect(it) }
            }
            saveAndPublish()
            schedule()
        }
    }
    fun open(seriesId: String,episodeId: String): VideoInput? {
        val entry=state.series.firstOrNull { it.series.id==seriesId } ?: return null
        val episode=entry.episodes.firstOrNull { it.id==episodeId && it.complete } ?: return null
        return LocalVideo(file(seriesId,episodeId,false),episode.total,entry.quality)
    }

    private fun schedule() {
        if(!enabled || playback || scheduled || storageError.isNotEmpty()) return
        if(entries.values.none { entry -> !entry.paused && entry.ids.any { pending(entry,it) } }) return
        scheduled=true
        worker.execute {
            scheduled=false
            if(!enabled || playback) return@execute
            val entry=entries.values.firstOrNull { e -> !e.paused && e.ids.any { pending(e,it) } } ?: return@execute
            val episode=entry.ids.first { pending(entry,it) }
            transfer(entry,episode)
            publish()
            schedule()
        }
    }
    private fun pending(entry: Entry,id: String)=entry.files[id]?.let { !it.complete && !it.skipped } ?: true
    private fun transfer(entry: Entry,id: String) {
        val task=Active(entry.series.id,id)
        active=task
        publish()
        val checkpoint=entry.files.getOrPut(id) { Checkpoint() }
        try {
            task.scope.run {
                requireSpace()
                val video=resolve(id,entry.quality,entry.type,entry.source)
                task.video=video
                video.use {
                    checkActive(task)
                    if(video.total<=0) throw IOException("视频长度无效")
                    val signature=MessageDigest.getInstance("SHA-256")
                        .digest(video.read(0,minOf(video.total,65536L).toInt()))
                        .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
                    val partial=file(entry.series.id,id,true)
                    partial.parentFile.mkdirs()
                    RandomAccessFile(partial,"rw").use { output ->
                        if(checkpoint.total!=video.total || checkpoint.signature!=signature || output.length()>video.total)
                            output.setLength(0)
                        checkpoint.total=video.total
                        checkpoint.signature=signature
                        checkpoint.bytes=output.length()
                        checkpoint.complete=false
                        save()
                        output.seek(checkpoint.bytes)
                        var publishedAt=0L
                        try {
                            while(checkpoint.bytes<video.total) {
                                checkActive(task)
                                requireSpace()
                                val count=minOf(256*1024L,video.total-checkpoint.bytes).toInt()
                                val bytes=video.read(checkpoint.bytes,count)
                                if(bytes.size!=count) throw IOException("视频分段不完整，请继续下载")
                                checkActive(task)
                                output.write(bytes)
                                checkpoint.bytes+=bytes.size
                                val now=System.nanoTime()/1_000_000
                                if(now-publishedAt>=700) { publish(); publishedAt=now }
                            }
                        } finally { output.fd.sync() }
                    }
                    checkActive(task)
                    move(partial,file(entry.series.id,id,false))
                    checkpoint.complete=true
                    entry.error=""
                    save()
                }
            }
        } catch(error: Exception) {
            if(!task.cancelled.get()) {
                entry.paused=true
                entry.error=error.message?.replace(Regex("https?://\\S+"),"媒体地址")?.take(180) ?: "下载失败，请继续重试"
            }
            save()
        } finally {
            task.video=null
            active=null
        }
    }
    private fun checkActive(task: Active) {
        if(task.cancelled.get() || !enabled || playback) throw IOException("下载已暂停")
    }
    private fun requireSpace() {
        root.mkdirs()
        if(root.usableSpace<reserveBytes) throw IOException("剩余空间不足，已暂停下载，请先删除部分下载")
    }
    private fun publish() {
        state=OfflineSnapshot(false,entries.values.map { entry ->
            OfflineSeries(entry.series,entry.ids.mapIndexed { index,id ->
                val p=entry.files[id] ?: Checkpoint()
                OfflineEpisode(id,index,p.bytes,p.total,p.complete,p.skipped)
            },entry.type,entry.quality,entry.paused,entry.error)
        },active?.series,active?.episode,playback,storageError)
    }
    private fun saveAndPublish() {
        save()
        publish()
    }
    private fun save() {
        if(catalogLocked) return
        try {
            root.mkdirs()
            val rows=entries.values.map { entry ->
                val files=JSONObject()
                entry.files.forEach { (id,p) ->
                    files.put(id,JSONObject().put("total",p.total).put("signature",p.signature)
                        .put("complete",p.complete).put("skipped",p.skipped))
                }
                JSONObject().put("series",entry.series.toJson()).put("episodes",JSONArray(entry.ids))
                    .put("type",entry.type.storedValue).put("quality",entry.quality).put("source",entry.source.id)
                    .put("paused",entry.paused).put("error",entry.error).put("files",files)
            }
            val temporary=File(root,"index.tmp")
            FileOutputStream(temporary).use { output ->
                output.write(JSONObject().put("version",1).put("series",JSONArray(rows)).toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            move(temporary,index)
            storageError=""
        } catch(error: IOException) {
            storageError="下载记录保存失败，请检查剩余空间"
            enabled=false
        }
    }
    private fun load() {
        if(!index.exists()) return
        try {
            if(index.length()>16*1024*1024) throw IOException("下载记录过大")
            val rows=JSONObject(index.readText()).getJSONArray("series")
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i)
                val series=Series.fromJson(row.getJSONObject("series"))
                validId(series.id)
                val raw=row.getJSONArray("episodes")
                val ids=(0 until raw.length()).map { validId(raw.getString(it)) }.distinct()
                val entry=Entry(series,ids,ContentType.fromStored(row.optString("type")),
                    PlaybackQuality.normalize(row.optInt("quality",720)),PlaybackSource.fromStored(row.optString("source")),
                    row.optBoolean("paused"),row.optString("error"))
                val files=row.optJSONObject("files") ?: JSONObject()
                files.keys().forEach { id ->
                    validId(id)
                    val p=files.getJSONObject(id)
                    entry.files[id]=Checkpoint(total=p.optLong("total"),signature=p.optString("signature"),
                        complete=p.optBoolean("complete"),skipped=p.optBoolean("skipped"))
                }
                inspect(entry)
                entries[series.id]=entry
            }
        } catch(error: Exception) {
            entries.clear()
            catalogLocked=true
            storageError="下载记录无法读取，原文件已保留"
        }
    }
    private fun inspect(entry: Entry) {
        entry.files.forEach { (id,p) ->
            val finished=file(entry.series.id,id,false)
            if(finished.isFile && finished.length()==p.total && p.total>0) {
                p.complete=true; p.bytes=p.total
            } else {
                p.complete=false; p.bytes=file(entry.series.id,id,true).length()
            }
        }
    }
    private fun directory(id: String): File {
        validId(id)
        val dir=File(root,id).canonicalFile
        require(dir.parentFile==root.canonicalFile) { "下载目录无效" }
        return dir
    }
    private fun file(series: String,episode: String,partial: Boolean): File {
        validId(episode)
        return File(directory(series),episode+if(partial) ".part" else ".mp4")
    }
    private fun remove(file: File) {
        require(file.canonicalPath.startsWith(root.canonicalPath+File.separator))
        if(file.exists() && !file.delete()) throw IOException("文件暂时无法删除，请稍后重试")
    }
    private fun move(from: File,to: File) {
        try {
            Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        } catch(error: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private fun validId(id: String)=id.also { require(it.matches(Regex("[0-9]{1,30}"))) { "剧集标识无效" } }
    override fun close() {
        stop()
        worker.shutdown()
    }
    fun awaitClosed(timeout: Long,unit: java.util.concurrent.TimeUnit)=worker.awaitTermination(timeout,unit)
}
