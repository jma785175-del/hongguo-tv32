package com.hongguotv.core

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionRecoveryTest {
    @Test fun emptyPlaybackRefreshesSessionOnceAndRetriesTheSameEpisode() {
        val registrations=AtomicInteger()
        val requests=AtomicInteger()
        val session=PlaybackDeviceSession {
            val n=registrations.incrementAndGet()
            DeviceRegister.Registered("${100+n}","${200+n}")
        }
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            requests.incrementAndGet()
            val body=if(request.url.queryParameter("device_id")=="101") "" else """{"code":0}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val api=AppApiTransport(http,playbackSession=session)
        assertEquals(0,api.playbackRequest("/novel/player/video_model/v1/","{}","example.test").getInt("code"))
        assertEquals(2,registrations.get())
        assertEquals(2,requests.get())
        val recovered=session.profile(http)
        assertFalse(session.recover(http,recovered))
        assertEquals(2,registrations.get())
    }

    @Test fun speculativeFailureDoesNotRefreshThePlaybackSession() {
        val registrations=AtomicInteger()
        val session=PlaybackDeviceSession {
            registrations.incrementAndGet()
            DeviceRegister.Registered("101","201")
        }
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("".toResponseBody()).build()
        }.build()
        val api=AppApiTransport(http,playbackSession=session)
        assertThrows(AppApiEmptyResponseException::class.java) {
            RequestScope(speculative=true).run { api.playbackRequest("/novel/player/video_model/v1/","{}","example.test") }
        }
        assertEquals(1,registrations.get())
    }
}
