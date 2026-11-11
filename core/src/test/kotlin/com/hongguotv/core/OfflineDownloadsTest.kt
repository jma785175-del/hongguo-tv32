package com.hongguotv.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class OfflineDownloadsTest {
    private val bytes=ByteArray(780123) { (it*31).toByte() }
    private val detail=Detail(Series("100","下载测试"),listOf("101","102"))
    private class Gate {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val used=AtomicBoolean()
    }
    private inner class Source(val reads: MutableList<Long> = Collections.synchronizedList(mutableListOf()),val gate: Gate?=null): VideoInput {
        override val total=bytes.size.toLong()
        override val info=StreamInfo("test://video",null,"720P","test")
        @Volatile var closed=false
        override fun read(start: Long,length: Int): ByteArray {
            reads+=start
            if(start>=262144 && gate?.used?.compareAndSet(false,true)==true) {
                gate.entered.countDown()
                check(gate.release.await(5,TimeUnit.SECONDS))
            }
            if(closed) throw IOException("closed")
            return bytes.copyOfRange(start.toInt(),minOf(bytes.size,start.toInt()+length))
        }
        override fun close() { closed=true; gate?.release?.countDown() }
    }
    private fun waitFor(predicate: ()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(!predicate()) { if(System.nanoTime()>end) fail("Timed out waiting for download state"); Thread.sleep(10) }
    }
    private fun stop(engine: OfflineDownloads) { engine.close(); assertTrue(engine.awaitClosed(5,TimeUnit.SECONDS)) }
    private fun temp()=Files.createTempDirectory("hongguo-download-test-").toFile()

    @Test fun `whole series completes persists and plays without resolving remote media`() {
        val root=temp()
        val engine=OfflineDownloads(root,{ _,_,_,_-> Source() },0)
        try {
            engine.start(); engine.enqueue(detail,720,PlaybackSource.AUTO)
            waitFor { engine.snapshot().series.firstOrNull()?.completeCount==2 }
            stop(engine)
            val offline=OfflineDownloads(root,{ _,_,_,_-> error("Offline playback must not resolve media") },0)
            try {
                waitFor { !offline.snapshot().loading }
                assertEquals(2,offline.snapshot().series.single().completeCount)
                offline.open("100","102")!!.use {
                    assertEquals("offline",it.info.source)
                    assertArrayEquals(bytes.copyOfRange(777,777+350000),it.read(777,350000))
                }
            } finally { stop(offline) }
        } finally { stop(engine); root.deleteRecursively() }
    }
    @Test fun `paused partial resumes after restart from committed bytes`() {
        val root=temp(); val gate=Gate()
        val engine=OfflineDownloads(root,{ _,_,_,_-> Source(gate=gate) },0)
        try {
            engine.start(); engine.enqueue(detail.copy(episodes=listOf("101")),720,PlaybackSource.AUTO)
            assertTrue(gate.entered.await(5,TimeUnit.SECONDS))
            engine.pause("100")
            waitFor { engine.snapshot().series.firstOrNull()?.paused==true }
            val count=File(root,"100/101.part").length()
            assertEquals(262144L,count)
            stop(engine)
            val reads=Collections.synchronizedList(mutableListOf<Long>())
            val resumed=OfflineDownloads(root,{ _,_,_,_-> Source(reads) },0)
            try {
                resumed.start(); resumed.resume("100")
                waitFor { resumed.snapshot().series.firstOrNull()?.completeCount==1 }
                assertEquals(listOf(0L,count,count+262144),reads.toList())
                assertArrayEquals(bytes,File(root,"100/101.mp4").readBytes())
            } finally { stop(resumed) }
        } finally { stop(engine); root.deleteRecursively() }
    }
    @Test fun `deleting single episode skips redownload and whole series frees managed files`() {
        val root=temp(); val engine=OfflineDownloads(root,{ _,_,_,_-> Source() },0)
        try {
            engine.start(); engine.enqueue(detail,720,PlaybackSource.AUTO)
            waitFor { engine.snapshot().series.firstOrNull()?.completeCount==2 }
            engine.deleteEpisode("100","101")
            waitFor { engine.snapshot().series.single().episodes[0].skipped }
            assertNull(engine.open("100","101"))
            assertFalse(File(root,"100/101.mp4").exists())
            assertTrue(File(root,"100/102.mp4").exists())
            engine.resume("100")
            engine.deleteSeries("100")
            waitFor { engine.snapshot().series.isEmpty() }
            assertFalse(File(root,"100").exists())
        } finally { stop(engine); root.deleteRecursively() }
    }
    @Test fun `deleting active transfer cancels before removing files and cannot resurrect task`() {
        val root=temp(); val gate=Gate(); val engine=OfflineDownloads(root,{ _,_,_,_-> Source(gate=gate) },0)
        try {
            engine.start(); engine.enqueue(detail,720,PlaybackSource.AUTO)
            assertTrue(gate.entered.await(5,TimeUnit.SECONDS))
            engine.deleteSeries("100")
            waitFor { engine.snapshot().series.isEmpty() }
            stop(engine)
            assertFalse(File(root,"100").exists())
            assertTrue(org.json.JSONObject(File(root,"index.json").readText()).getJSONArray("series").isEmpty)
        } finally { stop(engine); root.deleteRecursively() }
    }
    @Test fun `online playback interrupts download then resumes queue automatically`() {
        val root=temp(); val gate=Gate(); val engine=OfflineDownloads(root,{ _,_,_,_-> Source(gate=gate) },0)
        try {
            engine.start(); engine.enqueue(detail,720,PlaybackSource.AUTO)
            assertTrue(gate.entered.await(5,TimeUnit.SECONDS))
            engine.setPlaybackActive(true)
            waitFor { engine.snapshot().waitingForPlayback && engine.snapshot().activeEpisode==null }
            assertTrue(engine.snapshot().pending)
            assertFalse(engine.snapshot().series.single().paused)
            engine.setPlaybackActive(false)
            waitFor { engine.snapshot().series.firstOrNull()?.completeCount==2 }
        } finally { stop(engine); root.deleteRecursively() }
    }
    @Test fun `insufficient space pauses without requesting media and invalid ids cannot escape root`() {
        val root=temp(); val engine=OfflineDownloads(root,{ _,_,_,_-> error("Must not request media") },Long.MAX_VALUE)
        try {
            engine.start(); engine.enqueue(detail,720,PlaybackSource.AUTO)
            waitFor { engine.snapshot().series.firstOrNull()?.paused==true }
            assertTrue(engine.snapshot().series.single().error.contains("空间不足"))
            assertThrows(IllegalArgumentException::class.java) { engine.deleteSeries("../other") }
            assertThrows(IllegalArgumentException::class.java) { engine.deleteEpisode("100","../other") }
            assertThrows(IllegalArgumentException::class.java) { engine.enqueue(detail.copy(series=Series("..","invalid")),720,PlaybackSource.AUTO) }
        } finally { stop(engine); root.deleteRecursively() }
    }
    @Test fun `corrupt catalog remains intact rather than overwriting orphaned downloads`() {
        val root=temp(); File(root,"index.json").writeText("broken")
        val engine=OfflineDownloads(root,{ _,_,_,_-> error("Must not request media") },0)
        try {
            waitFor { !engine.snapshot().loading }
            assertTrue(engine.snapshot().storageError.isNotEmpty())
            engine.start(); engine.enqueue(detail,720,PlaybackSource.AUTO); engine.deleteSeries("100")
            stop(engine)
            assertEquals("broken",File(root,"index.json").readText())
        } finally { stop(engine); root.deleteRecursively() }
    }
}
