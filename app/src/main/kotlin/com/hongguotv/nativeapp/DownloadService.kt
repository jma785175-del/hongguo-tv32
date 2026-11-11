// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import com.hongguotv.core.*
import java.io.File
import org.json.JSONObject

object DownloadRuntime {
    @Volatile private var instance: OfflineDownloads?=null
    @Synchronized fun get(context: Context): OfflineDownloads {
        return instance ?: run {
            val app=context.applicationContext
            val repository=ContentRepository(preferredStream=SdkStreamSource(app))
            OfflineDownloads(File(app.filesDir,"offline-v1"),{ id,quality,type,source ->
                repository.openVideo(id,quality,type,source)
            }).also { instance=it }
        }
    }
    fun enqueue(context: Context,detail: Detail,quality: Int,source: PlaybackSource) {
        launch(context,Intent(context,DownloadService::class.java).setAction("enqueue")
            .putExtra("series",detail.series.toJson().toString())
            .putExtra("episodes",detail.episodes.toTypedArray()).putExtra("type",detail.contentType.storedValue)
            .putExtra("quality",quality).putExtra("source",source.id))
    }
    fun resume(context: Context,id: String) {
        launch(context,Intent(context,DownloadService::class.java).setAction("resume").putExtra("id",id))
    }
    private fun launch(context: Context,intent: Intent) {
        if(Build.VERSION.SDK_INT>=26) context.startForegroundService(intent) else context.startService(intent)
    }
}

/** Keep the single transfer queue alive when the user leaves the activity. */
class DownloadService: Service() {
    private val main=Handler(Looper.getMainLooper())
    private lateinit var downloads: OfflineDownloads
    private var startedAt=0L
    private val check=object: Runnable {
        override fun run() {
            val state=downloads.snapshot()
            val current=state.series.firstOrNull { it.series.id==state.activeSeries }
            val text=when {
                state.storageError.isNotEmpty() -> state.storageError
                state.waitingForPlayback -> "观看优先，暂停下载；停止观看后继续"
                current!=null -> "${current.series.title} · 已完成 ${current.completeCount}/${current.episodes.size} 集"
                else -> "正在处理下载队列"
            }
            getSystemService(NotificationManager::class.java).notify(112,notification(text))
            if(!state.loading && SystemClock.elapsedRealtime()-startedAt>3000 &&
                (!state.pending || state.storageError.isNotEmpty())) { stopSelf(); return }
            main.postDelayed(this,1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        if(Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads","剧集下载",NotificationManager.IMPORTANCE_LOW))
        startForeground(112,notification("正在准备下载"))
        downloads=DownloadRuntime.get(this)
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        startedAt=SystemClock.elapsedRealtime()
        downloads.start()
        try {
            when(intent?.action) {
                "enqueue" -> {
                    val series=Series.fromJson(JSONObject(intent.getStringExtra("series") ?: ""))
                    downloads.enqueue(Detail(series,intent.getStringArrayExtra("episodes")?.toList().orEmpty(),
                        ContentType.fromStored(intent.getStringExtra("type").orEmpty())),
                        PlaybackQuality.normalize(intent.getIntExtra("quality",720)),
                        PlaybackSource.fromStored(intent.getStringExtra("source").orEmpty()))
                }
                "resume" -> intent.getStringExtra("id")?.let(downloads::resume)
            }
        } catch(_: Exception) { /* Unexported service; an invalid command must not crash the app. */ }
        main.removeCallbacks(check); main.postDelayed(check,500)
        return START_STICKY
    }
    private fun notification(message: String): Notification {
        val intent=PendingIntent.getActivity(this,112,Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,"downloads") else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.personal_icon).setContentTitle("红果大屏 · 下载管理")
            .setContentText(message).setContentIntent(intent).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    override fun onDestroy() { main.removeCallbacks(check); downloads.stop(); super.onDestroy() }
    override fun onBind(intent: Intent?)=null
}
