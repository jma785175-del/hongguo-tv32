// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

interface VideoInput : Closeable {
    val info: StreamInfo
    val total: Long
    fun read(start: Long,length: Int): ByteArray
}

class LocalVideo(file: File,expectedSize: Long,quality: Int) : VideoInput {
    private val input=RandomAccessFile(file,"r")
    override val total=input.length()
    override val info=StreamInfo("offline://video",null,"${quality}P","offline")
    init {
        if(total<=0 || total!=expectedSize) {
            input.close()
            throw IOException("下载文件不完整，请重新下载本集")
        }
    }
    @Synchronized override fun read(start: Long,length: Int): ByteArray {
        require(start>=0 && length in 1..1024*1024)
        if(start>=total) return byteArrayOf()
        val bytes=ByteArray(minOf(length.toLong(),total-start).toInt())
        input.seek(start)
        input.readFully(bytes)
        return bytes
    }
    @Synchronized override fun close() = input.close()
}
