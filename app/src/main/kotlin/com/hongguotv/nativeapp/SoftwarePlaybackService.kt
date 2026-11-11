// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import android.view.Surface
import androidx.media3.common.Player
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

internal object DecoderMessage {
    const val OPEN = 1
    const val PLAY = 2
    const val SEEK = 3
    const val SPEED = 4
    const val SURFACE = 5
    const val SIZE = 6
    const val CLOSE = 7
    const val STATUS = 8
}

/** Owns every VLC native object in the :decoder process. The UI owns the input data. */
class SoftwarePlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val mailbox = Messenger(Handler(Looper.getMainLooper(), ::receive))
    private var engine: LibVLC? = null
    private var player: MediaPlayer? = null
    private var input: ParcelFileDescriptor? = null
    private var surface: Surface? = null
    private var reply: Messenger? = null
    private var session = ""
    private var state = Player.STATE_BUFFERING
    private var playingRequested = false
    private var started = false
    private var hasVideoOutput = false
    private var visibleWidth = 0
    private var visibleHeight = 0
    private var pixelRatio = 1f
    private var width = 1
    private var height = 1
    private var speed = 1f
    private var pendingSeek: Long? = null
    private val tick = object : Runnable {
        override fun run() {
            if (player == null || reply == null) return
            publish()
            handler.postDelayed(this, 400)
        }
    }

    override fun onBind(intent: Intent): IBinder = mailbox.binder

    private fun receive(message: Message): Boolean {
        val data = message.data
        val token = data.getString("session").orEmpty()
        if (message.what != DecoderMessage.OPEN && token != session) return true
        try {
            when (message.what) {
                DecoderMessage.OPEN -> open(data, token, message.replyTo)
                DecoderMessage.PLAY -> setPlaying(data.getBoolean("value"))
                DecoderMessage.SEEK -> seek(data.getLong("position"))
                DecoderMessage.SPEED -> {
                    speed = data.getFloat("speed", 1f)
                    if (started) player?.setRate(speed)
                }
                DecoderMessage.SURFACE -> {
                    updateSize(data)
                    attach(data.getParcelable<Surface>("surface"))
                    if (surface != null && playingRequested) player?.play()
                }
                DecoderMessage.SIZE -> {
                    updateSize(data)
                    player?.vlcVout?.setWindowSize(width, height)
                }
                DecoderMessage.CLOSE -> closePlayback()
            }
        } catch (failure: Exception) {
            fail(failure)
        } catch (failure: LinkageError) {
            fail(failure)
        }
        return true
    }

    private fun open(data: Bundle, token: String, client: Messenger) {
        closePlayback()
        reply = client
        session = token
        input = requireNotNull(data.getParcelable<ParcelFileDescriptor>("input"))
        val output = requireNotNull(data.getParcelable<Surface>("surface"))
        updateSize(data)
        playingRequested = data.getBoolean("play")
        speed = data.getFloat("speed", 1f)
        started = false
        hasVideoOutput = false
        visibleWidth = 0
        visibleHeight = 0
        pixelRatio = 1f
        state = Player.STATE_BUFFERING
        val lib = engine ?: LibVLC(this, mutableListOf(
            "--no-video-title-show", "--vout=android_display", "--avcodec-hw=none", "--avcodec-threads=2"
        ))
            .also { engine = it }
        val playback = MediaPlayer(lib).also { player = it }
        playback.setEventListener { event ->
            if (player !== playback) return@setEventListener
            try {
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        if (!started) {
                            started = true
                            playback.setRate(speed)
                            pendingSeek?.let { seek(it) }
                        }
                        if (!playingRequested) playback.pause()
                        if (visibleWidth > 0) state = Player.STATE_READY
                    }
                    MediaPlayer.Event.Paused -> if (visibleWidth > 0) state = Player.STATE_READY
                    MediaPlayer.Event.Vout -> hasVideoOutput = event.voutCount > 0
                    MediaPlayer.Event.Buffering -> if (started && visibleWidth > 0) {
                        state = if (event.buffering >= 100f) Player.STATE_READY else Player.STATE_BUFFERING
                    }
                    MediaPlayer.Event.EndReached -> state = Player.STATE_ENDED
                    MediaPlayer.Event.EncounteredError -> {
                        fail(IllegalStateException("VLC playback failed"))
                        return@setEventListener
                    }
                    else -> return@setEventListener
                }
                publish()
            } catch (failure: Exception) {
                fail(failure)
            }
        }
        attach(output)
        val media = Media(lib, input!!.fileDescriptor)
        try {
            // An explicit software codec also prevents LibVLC from selecting a device default.
            media.addOption(":codec=avcodec")
            media.addOption(":file-caching=900")
            val position = data.getLong("position")
            if (position > 0) media.addOption(":start-time=${position / 1000.0}")
            playback.media = media
        } finally {
            media.release()
        }
        playback.setVolume(if (playingRequested) 100 else 0)
        Log.i("HongguoTV", "VLC software decoder started in process ${android.os.Process.myPid()} position=${data.getLong("position")} autoplay=$playingRequested")
        playback.play()
        handler.post(tick)
    }

    private fun updateSize(data: Bundle) {
        width = data.getInt("width", width).coerceAtLeast(1)
        height = data.getInt("height", height).coerceAtLeast(1)
    }

    private fun attach(output: Surface?) {
        val playback = player ?: run { output?.release(); return }
        val vout = playback.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        surface?.release()
        surface = output
        if (output == null) {
            playback.pause()
            return
        }
        vout.setVideoSurface(output, null)
        vout.setWindowSize(width, height)
        vout.attachViews { _, _, _, videoWidth, videoHeight, sarNum, sarDen ->
            if (player === playback && videoWidth > 0 && videoHeight > 0) {
                visibleWidth = videoWidth
                visibleHeight = videoHeight
                pixelRatio = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f
                if (started) state = Player.STATE_READY
                publish()
            }
        }
    }

    private fun setPlaying(play: Boolean) {
        playingRequested = play
        val playback = player ?: return
        if (play) {
            playback.setVolume(100)
            if (surface != null && !playback.isPlaying) playback.play()
        } else {
            playback.setVolume(0)
            if (started && playback.isPlaying) playback.pause()
        }
    }

    private fun seek(position: Long) {
        val playback = player ?: return
        if (started && playback.isSeekable) {
            pendingSeek = null
            playback.setTime(position)
        } else pendingSeek = position
    }

    private fun publish(error: Boolean = false) {
        val playback = player
        val client = reply ?: return
        if (!error && started && hasVideoOutput && visibleWidth == 0) {
            // Some Android vout implementations do not emit a layout callback.
            playback?.currentVideoTrack?.let { track ->
                if (track.width > 0 && track.height > 0) {
                    visibleWidth = track.width
                    visibleHeight = track.height
                    pixelRatio = if (track.sarNum > 0 && track.sarDen > 0) track.sarNum.toFloat() / track.sarDen else 1f
                    state = Player.STATE_READY
                }
            }
        }
        val status = Message.obtain(null, DecoderMessage.STATUS)
        status.data = Bundle().apply {
            putString("session", session)
            putBoolean("error", error)
            putInt("state", state)
            putLong("position", playback?.time?.coerceAtLeast(0) ?: 0)
            putLong("duration", playback?.length?.coerceAtLeast(0) ?: 0)
            putBoolean("seekable", playback?.isSeekable == true)
            putInt("width", visibleWidth)
            putInt("height", visibleHeight)
            putFloat("pixelRatio", pixelRatio)
        }
        try { client.send(status) } catch (_: RemoteException) { closePlayback() }
    }

    private fun fail(failure: Throwable) {
        Log.w("HongguoTV", "VLC playback error", failure)
        try { publish(error = true) } finally { closePlayback() }
    }

    private fun closePlayback() {
        handler.removeCallbacks(tick)
        reply = null
        session = ""
        pendingSeek = null
        val playback = player
        player = null
        playback?.setEventListener(null)
        playback?.stop()
        playback?.vlcVout?.detachViews()
        playback?.release()
        surface?.release()
        surface = null
        input?.close()
        input = null
    }

    override fun onUnbind(intent: Intent): Boolean {
        closePlayback()
        return false
    }

    override fun onDestroy() {
        closePlayback()
        engine?.release()
        engine = null
        super.onDestroy()
    }
}
