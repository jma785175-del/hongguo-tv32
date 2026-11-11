package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * JVM end-to-end probe: fetch a real playback URL through the new
 * multi_video_model/v1 + six-header signature chain.
 * Run manually (not part of the test suite - hits the live API):
 *   gradlew :core:test --tests "com.hongguotv.core.StreamLiveProbeTest"
 */
class StreamLiveProbeTest {
    @org.junit.Test
    fun continuousEpisodeRequests() {
        val series=System.getenv("HONGGUO_SEQUENCE_SERIES")
        org.junit.Assume.assumeTrue(series!=null)
        val http=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
            .callTimeout(25,TimeUnit.SECONDS).build()
        try {
            val detail=ContentRepository(http).detail(series!!,ContentType.COMIC)
            val source=AppApiStreamSource(AppApiTransport(http,playbackSession=PlaybackDeviceSession()))
            val episodes=detail.episodes.drop(150).take(32)
            org.junit.Assert.assertEquals(32,episodes.size)
            for((index,id) in episodes.withIndex()) {
                val info=source.stream(id,720,ContentType.COMIC)
                RemoteVideo(http,info).use { video ->
                    video.prepare()
                    org.junit.Assert.assertTrue(video.read(0,4096).isNotEmpty())
                }
                println("sequence episode=${151+index} verified")
                Thread.sleep(500)
            }
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
    @org.junit.Test
    fun probe() {
        org.junit.Assume.assumeTrue(System.getenv("HONGGUO_LIVE_SOURCE")=="1")
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val hosts=(System.getenv("HONGGUO_PROBE_HOST") ?: "api5-normal-sinfonlinea.fqnovel.com").split(',')
        // 糯糯下山 第 1 集（与 DanmakuLiveProbeTest 同一真实剧集）
        val videoId = System.getenv("HONGGUO_PROBE_EPISODE") ?: "7677804745699888153"
        try {
            for(host in hosts) {
                val transport = AppApiTransport(http,playbackSession=PlaybackDeviceSession())
                val source = AppApiStreamSource(transport,host=host)
                val info = source.stream(videoId,720,ContentType.SHORT)
                println("apiHost=$host")
                println("channel=${source.name}")
                println("quality=${info.quality}")
                println("mediaHost=${java.net.URI(info.url).host}")
                org.junit.Assert.assertTrue("playback url is empty",info.url.startsWith("http"))
                val head=okhttp3.Request.Builder().url(info.url).head().build()
                http.newCall(head).execute().use { response ->
                    println("media HEAD: HTTP ${response.code} content-type=${response.header("Content-Type")}")
                    org.junit.Assert.assertTrue("media url unreachable: HTTP ${response.code}",response.isSuccessful)
                }
                RemoteVideo(http,info).use { video ->
                    video.prepare()
                    org.junit.Assert.assertTrue(video.read(0,65536).isNotEmpty())
                    println("media range and header verified bytes=${video.total}")
                }
            }
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}
