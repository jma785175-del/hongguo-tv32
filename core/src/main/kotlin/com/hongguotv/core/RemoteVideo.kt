// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(private val http: OkHttpClient, override val info: StreamInfo): VideoInput {
    @Volatile private var closed=false
    private val urls=(listOf(info.url)+info.backupUrls).distinct()
    @Volatile private var preferredUrl=0
    private val calls=mutableSetOf<Call>()
    data class Range(val bytes: ByteArray,val total: Long)
    override var total: Long=0; private set
    private lateinit var prepared: MediaCrypto.Prepared
    @Volatile private var prefix=byteArrayOf()
    private fun range(start: Long,end: Long): Range {
        var failure: IOException?=null
        val order=listOf(preferredUrl)+urls.indices.filter { it!=preferredUrl }
        for(index in order) {
            try {
                val result=rangeAt(urls[index],start,end)
                if(index!=preferredUrl) java.util.logging.Logger.getLogger("com.hongguotv.core.stream")
                    .info("Media backup selected candidate=${index+1}")
                preferredUrl=index
                return result
            } catch(error: IOException) {
                if(closed || Thread.currentThread().isInterrupted) throw error
                failure=error
            }
        }
        throw failure ?: IOException("视频媒体服务器暂不可用")
    }
    private fun rangeAt(url: String,start: Long,end: Long): Range {
        if(closed) throw IOException("播放请求已取消")
        require(start>=0 && end>=start && end-start<8*1024*1024)
        val request=Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Referer","https://novel.snssdk.com/").header("Range","bytes=$start-$end").header("Accept-Encoding","identity").build()
        val call=http.newCall(request)
        synchronized(calls) { if(closed) throw IOException("播放请求已取消"); calls+=call }
        try {
            return RequestScope.execute(call) { response ->
                if(response.code!=206) throw IOException("视频服务器不支持分段读取（HTTP ${response.code}）")
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty()) ?: throw IOException("视频分段响应异常")
                val (begin,finish,size)=match.destructured
                val full=size.toLong(); val last=minOf(end,full-1)
                if(begin.toLong()!=start || finish.toLong()!=last || full<=0) throw IOException("视频分段范围不匹配")
                if(total!=0L && full!=total) throw IOException("视频资源已变化，请重试")
                val n=(last-start+1).toInt(); val body=response.body ?: throw IOException("视频分段为空")
                val bytes=body.source().readByteArray(n.toLong())
                Range(bytes,full)
            }
        } finally { synchronized(calls) { calls-=call } }
    }
    @Synchronized fun prepare(): RemoteVideo {
        if(::prepared.isInitialized) return this
        val first=range(0,256*1024-1); total=first.total
        prefix=first.bytes
        val end=MediaCrypto.headerEnd(first.bytes,total)
        val header=if(end>first.bytes.size) first.bytes+range(first.bytes.size.toLong(),end-1L).bytes else first.bytes.copyOf(end)
        prepared=MediaCrypto.prepare(header,info.key)
        if(prepared.samples.any { it.end>total }) throw IOException("视频样本超出资源大小")
        return this
    }
    /** Bounded warm-up. Retains raw bytes so decryption still uses the absolute sample offset. */
    @Synchronized fun warm(): RemoteVideo {
        prepare()
        val size=minOf(total,1024*1024L).toInt()
        if(prefix.size<size) prefix=prefix+range(prefix.size.toLong(),size-1L).bytes
        return this
    }
    override fun read(start: Long,length: Int): ByteArray {
        require(length in 1..1024*1024 && start>=0)
        if(closed) throw IOException("播放请求已取消")
        if(start>=total) return byteArrayOf()
        val cached=prefix; val end=minOf(total,start+length)
        val available=(minOf(end,cached.size.toLong())-start).coerceAtLeast(0).toInt()
        val first=if(available>0) cached.copyOfRange(start.toInt(),start.toInt()+available) else byteArrayOf()
        val raw=if(start+available<end) first+range(start+available,end-1).bytes else first
        return MediaCrypto.decrypt(raw,start,info.key,prepared)
    }
    override fun close() { synchronized(calls) { closed=true; calls.toList().forEach { it.cancel() }; calls.clear() } }
}
