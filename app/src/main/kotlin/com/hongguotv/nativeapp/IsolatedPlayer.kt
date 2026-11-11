// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.RemoteException
import android.os.SystemClock
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.hongguotv.core.VideoInput
import java.util.UUID

internal class DecoderDisconnected : IllegalStateException("Software decoder process stopped")

/** Adapts the private decoder service to the same PlayerView and remote controls as ExoPlayer. */
@RequiresApi(26)
@androidx.annotation.OptIn(UnstableApi::class)
internal class IsolatedPlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val storage = app.getSystemService(StorageManager::class.java)
    private val reader = HandlerThread("video-input").apply { start() }
    private val readerHandler = Handler(reader.looper)
    private val session = UUID.randomUUID().toString()
    private val commands = Player.Commands.Builder().addAll(
        COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_TRACKS,
        COMMAND_SET_VIDEO_SURFACE, COMMAND_PLAY_PAUSE, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        COMMAND_SET_SPEED_AND_PITCH, COMMAND_STOP, COMMAND_RELEASE
    ).build()
    private val videoTracks = Tracks(listOf(Tracks.Group(
        TrackGroup("vlc-video", Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()),
        false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)
    )))
    private var item: MediaItem? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var worker: Messenger? = null
    private var bound = false
    private var opened = false
    private var closed = false
    private var readyToPlay = false
    private var parameters = PlaybackParameters.DEFAULT
    private var playback = STATE_IDLE
    private var error: PlaybackException? = null
    private var position = 0L
    private var initialPosition: Long? = null
    private var lastPlaybackAdvance = -1L
    private var duration = C.TIME_UNSET
    private var seekable = false
    private var videoSize = VideoSize.UNKNOWN
    private var output: SurfaceHolder? = null
    private var surface: Surface? = null
    private var width = 1
    private var height = 1
    private var hasAudioFocus = false
    private val audioFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({ change -> if (change < 0 && readyToPlay && !closed) pause() }, handler)
        .build()
    private val timeout = Runnable {
        val failure = PlaybackException("解码器响应超时", null, PlaybackException.ERROR_CODE_TIMEOUT)
        fail(failure)
    }
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == DecoderMessage.STATUS && message.data.getString("session") == session && !closed && error == null) {
            val state = message.data
            if (state.getBoolean("error")) {
                fail(PlaybackException("软件解码失败", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
            } else {
                val reportedPosition = state.getLong("position")
                playback = state.getInt("state")
                // A paused VLC open reports zero until its start-time seek runs.
                // Keep the requested checkpoint visible and available to the next source switch.
                val initial=initialPosition
                val nextPosition=if(initial!=null && reportedPosition+2000<initial && playback!=STATE_ENDED) initial
                    else reportedPosition.also { initialPosition=null }
                if (playback != STATE_READY || !readyToPlay) lastPlaybackAdvance = -1L
                else if (nextPosition > position) lastPlaybackAdvance = SystemClock.elapsedRealtime()
                position = nextPosition
                duration = state.getLong("duration").takeIf { it > 0 } ?: C.TIME_UNSET
                seekable = state.getBoolean("seekable")
                val firstFrame = videoSize == VideoSize.UNKNOWN && state.getInt("width") > 0
                videoSize = VideoSize(state.getInt("width"), state.getInt("height"), state.getFloat("pixelRatio", 1f))
                if (playback == STATE_READY || playback == STATE_ENDED) handler.removeCallbacks(timeout)
                newlyRendered = firstFrame
                invalidateState()
                newlyRendered = false
            }
        }
        true
    })
    private var newlyRendered = false

    // VLC's file input does not report a buffered timeline. Use fresh decoder progress.
    fun hasRecentPlaybackAdvance(now: Long): Boolean =
        lastPlaybackAdvance >= 0 && now - lastPlaybackAdvance in 0L..1500L
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed || error != null) return
            worker = Messenger(service)
            startIfReady()
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) { updateSurface(holder) }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            this@IsolatedPlayer.width = width.coerceAtLeast(1)
            this@IsolatedPlayer.height = height.coerceAtLeast(1)
            if (opened) send(DecoderMessage.SIZE) { putSize() } else updateSurface(holder)
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surface = null
            if (opened) send(DecoderMessage.SURFACE)
        }
    }

    fun open(video: VideoInput, media: MediaItem, positionMs: Long, play: Boolean) {
        check(item == null)
        item = media
        position = positionMs
        initialPosition=positionMs.takeIf { it>0 }
        readyToPlay = play && acquireAudioFocus()
        playback = STATE_BUFFERING
        invalidateState()
        try {
            descriptor = storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, VideoFile(video), readerHandler
            )
            bound = app.bindService(Intent(app, SoftwarePlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) { disconnected(); return }
            handler.postDelayed(timeout, 35_000)
        } catch (failure: Exception) {
            fail(PlaybackException("视频输入无法打开", failure, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        }
    }

    private fun acquireAudioFocus(): Boolean {
        if (!hasAudioFocus) hasAudioFocus = audio.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) audio.abandonAudioFocusRequest(audioFocus)
        hasAudioFocus = false
    }

    private fun disconnected() {
        worker = null
        if (!closed && item != null) {
            fail(PlaybackException("解码器意外停止", DecoderDisconnected(), PlaybackException.ERROR_CODE_DECODING_FAILED))
        }
    }

    private fun fail(failure: PlaybackException) {
        if (closed || error != null) return
        handler.removeCallbacks(timeout)
        error = failure
        playback = STATE_IDLE
        readyToPlay = false
        closeWorker()
        abandonAudioFocus()
        invalidateState()
    }

    private fun closeWorker() {
        if (opened) send(DecoderMessage.CLOSE)
        opened = false
        worker = null
        if (bound) { app.unbindService(connection); bound = false }
        runCatching { descriptor?.close() }
        descriptor = null
    }

    private fun send(command: Int, configure: Bundle.() -> Unit = {}) {
        val remote = worker ?: return
        val message = Message.obtain(null, command)
        message.replyTo = replies
        message.data = Bundle().apply { putString("session", session); configure() }
        try { remote.send(message) } catch (_: RemoteException) { disconnected() }
    }

    private fun Bundle.putSize() {
        putInt("width", width)
        putInt("height", height)
    }

    private fun startIfReady() {
        val input = descriptor ?: return
        val target = surface?.takeIf { it.isValid } ?: return
        if (worker == null || opened || closed || error != null) return
        opened = true
        send(DecoderMessage.OPEN) {
            putParcelable("input", input)
            putParcelable("surface", target)
            putSize()
            putBoolean("play", readyToPlay)
            putFloat("speed", parameters.speed)
            putLong("position", position)
        }
        runCatching { input.close() }
        descriptor = null
    }

    private fun updateSurface(holder: SurfaceHolder) {
        val target = holder.surface.takeIf { it.isValid } ?: return
        val oldSurface = surface
        surface = target
        width = holder.surfaceFrame.width().coerceAtLeast(1)
        height = holder.surfaceFrame.height().coerceAtLeast(1)
        if (opened && oldSurface !== target) send(DecoderMessage.SURFACE) {
            putParcelable("surface", target)
            putSize()
        } else startIfReady()
    }

    override fun getState(): State {
        val media = item
        val builder = State.Builder().setAvailableCommands(commands)
            .setPlaybackState(playback).setPlayerError(error)
            .setPlayWhenReady(readyToPlay, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(parameters).setVideoSize(videoSize).setIsLoading(playback == STATE_BUFFERING)
            .setNewlyRenderedFirstFrame(newlyRendered)
        if (media != null) builder.setPlaylist(listOf(MediaItemData.Builder(media.mediaId)
            .setMediaItem(media).setIsSeekable(seekable)
            .setDurationUs(if (duration == C.TIME_UNSET) C.TIME_UNSET else duration * 1000)
            .setTracks(if (videoSize.width > 0) videoTracks else Tracks.EMPTY).build()))
            .setCurrentMediaItemIndex(0).setContentPositionMs(position)
            .setContentBufferedPositionMs(PositionSupplier { position })
        return builder.build()
    }

    override fun handleSetVideoOutput(videoOutput: Any) = Futures.immediateVoidFuture().also {
        output?.removeCallback(surfaceCallback)
        output = when (videoOutput) {
            is SurfaceView -> videoOutput.holder
            is SurfaceHolder -> videoOutput
            else -> null
        }
        output?.let { holder -> holder.addCallback(surfaceCallback); updateSurface(holder) }
    }

    override fun handleClearVideoOutput(videoOutput: Any?) = Futures.immediateVoidFuture().also {
        output?.removeCallback(surfaceCallback)
        output = null
        surface = null
        if (opened) send(DecoderMessage.SURFACE)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
        readyToPlay = playWhenReady && acquireAudioFocus()
        if (!readyToPlay) lastPlaybackAdvance = -1L
        if (opened) send(DecoderMessage.PLAY) { putBoolean("value", readyToPlay) }
        if (!readyToPlay) abandonAudioFocus()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters) = Futures.immediateVoidFuture().also {
        parameters = playbackParameters
        if (opened) send(DecoderMessage.SPEED) { putFloat("speed", parameters.speed) }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also {
        initialPosition=null
        lastPlaybackAdvance = -1L
        position = positionMs.coerceAtLeast(0)
        if (opened) send(DecoderMessage.SEEK) { putLong("position", position) }
    }

    override fun handleStop() = Futures.immediateVoidFuture().also {
        handler.removeCallbacks(timeout)
        closeWorker()
        abandonAudioFocus()
        playback = STATE_IDLE
        readyToPlay = false
    }

    override fun handleRelease() = Futures.immediateVoidFuture().also {
        closed = true
        handler.removeCallbacks(timeout)
        output?.removeCallback(surfaceCallback)
        output = null
        surface = null
        closeWorker()
        abandonAudioFocus()
        reader.quitSafely()
    }

    private class VideoFile(private val video: VideoInput) : ProxyFileDescriptorCallback() {
        private var cacheStart = -1L
        private var cache = ByteArray(0)
        override fun onGetSize() = video.total
        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            var copied = 0
            try {
                while (copied < size && offset + copied < video.total) {
                    val position = offset + copied
                    if (position < cacheStart || position >= cacheStart + cache.size) {
                        cache = video.read(position, minOf(512 * 1024L, video.total - position).toInt())
                        cacheStart = position
                        if (cache.isEmpty()) throw java.io.EOFException()
                    }
                    val start = (position - cacheStart).toInt()
                    val count = minOf(size - copied, cache.size - start)
                    cache.copyInto(data, copied, start, start + count)
                    copied += count
                }
                return copied
            } catch (failure: Exception) {
                throw ErrnoException("video read", OsConstants.EIO, failure)
            }
        }
        override fun onRelease() { cache = ByteArray(0) }
    }
}
