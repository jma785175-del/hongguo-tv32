// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.ContentType
import com.hongguotv.core.StreamInfo
import com.hongguotv.core.StreamSource
import dev.openhongguo.ClientOptions
import dev.openhongguo.OpenhongguoClient
import dev.openhongguo.Stream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Registered anonymous playback; the SDK includes an actual ARMv7 native build. */
class SdkStreamSource(private val context: Context): StreamSource {
    override val name="openhongguo-0.2.0"
    private val client by lazy {
        OpenhongguoClient.create(context,ClientOptions(retryAttempts=2,deviceResets=1))
    }
    private fun resolution(stream: Stream)=stream.definition.filter(Char::isDigit).toIntOrNull()
        ?: minOf(stream.width,stream.height)

    override fun stream(id: String,maxQuality: Int,type: ContentType): StreamInfo {
        try {
            return runBlocking {
                withTimeout(30000) {
                    val result=client.resolvePlayback(id)
                    val supported=result.playback.streams.filter {
                        !it.hls && !it.dash && it.url.startsWith("https://") &&
                            it.codec.lowercase() !in setOf("bytevc2","bvc2","av1","vp9")
                    }
                    val within=supported.filter { resolution(it)<=PlaybackCompatibility.qualityLimit(maxQuality) }
                    val candidates=within.ifEmpty {
                        val lowest=supported.minOfOrNull(::resolution)
                        supported.filter { resolution(it)==lowest }
                    }
                    val chosen=candidates.maxWithOrNull(compareBy<Stream> { resolution(it) }
                        .thenBy { it.codec.lowercase() in setOf("h264","avc","avc1") }
                        .thenBy { it.bitrate }) ?: throw IOException("当前分集没有电视可用的视频格式")
                    val hex=result.contentKey?.key.orEmpty()
                    val key=hex.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }
                        ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                    if((chosen.protected || result.playback.protected) && key==null)
                        throw IOException("当前分集未返回可用的播放授权",result.contentKeyError)
                    android.util.Log.i("HongguoTV","SDK playback resolved episode=$id quality=${chosen.definition} codec=${chosen.codec}")
                    StreamInfo(chosen.url,key,chosen.definition.uppercase())
                }
            }
        } catch(error: CancellationException) {
            if(Thread.currentThread().isInterrupted) throw IOException("播放请求已取消",error)
            throw IOException("播放接口请求超时",error)
        } catch(error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("播放请求已取消",error)
        } catch(error: IOException) {
            throw error
        } catch(error: Exception) {
            throw IOException("播放接口暂时不可用",error)
        }
    }
}
