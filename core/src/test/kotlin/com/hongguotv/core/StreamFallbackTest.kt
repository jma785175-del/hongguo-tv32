// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StreamFallbackTest {
    private fun source(label: String, resolve: () -> StreamInfo) = object : StreamSource {
        override val name = label
        override fun stream(id: String, maxQuality: Int, type: ContentType) = resolve()
    }

    @Test fun malformedAppResponseStillReachesPublicPlayback() {
        val clear = StreamInfo("https://example.org/public.mp4", null, "360P")
        val scheduler = StreamScheduler(listOf(
            source("changed-app-response") { throw JSONException("invalid envelope") },
            source("public-playback") { clear }
        ))
        assertEquals(clear.copy(source="public-playback"), scheduler.stream("episode", 1080, ContentType.SHORT))
    }

    @Test fun explicitChoiceUsesOnlyRequestedSource() {
        var primaryCalls=0
        val scheduler=StreamScheduler(listOf(
            source("app-api") { primaryCalls++; StreamInfo("https://example.org/one",null,"720P") },
            source("app-api-b") { throw IOException("备用线路不可用") }
        ))
        assertThrows(IOException::class.java) { scheduler.stream("123",720,ContentType.SHORT,PlaybackSource.SECONDARY) }
        assertEquals(0,primaryCalls)
        assertEquals(false,scheduler.status("app-api-b"))
        assertEquals("app-api",scheduler.stream("123",720,ContentType.SHORT).source)
        assertEquals(true,scheduler.status("app-api"))
    }

    @Test fun malformedLastResponseIsReportedAsRecoverableIoFailure() {
        val scheduler = StreamScheduler(listOf(source("malformed") { throw JSONException("invalid envelope") }))
        try {
            scheduler.stream("episode", 1080, ContentType.SHORT)
            fail("Expected a recoverable playback failure")
        } catch (error: IOException) {
            assertTrue(error.cause is JSONException)
        }
    }
}
