// SPDX-License-Identifier: GPL-3.0-only
// Kotlin adaptation of kadaliao/openhongguo bed1459, internal/protocol/sign/playback.go (Apache-2.0).
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.SM3Digest

/** Playback-only request envelope. Catalog requests retain their existing signature. */
internal object PlaybackSignature {
    private const val license="1611921764"
    private val random=SecureRandom()
    private val signKey=hex("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304")
    private val aesKey=hex("d31e3718288a1027baab59f146a09a9c")
    private val aesIv=hex("ea180a0336ed352fcd24e4d50018ae54")
    private val mask=hex("f2f7fcfff2f7fcff")
    private val tail=hex("eafb2cfe8568519154")
    private val simonKeys=LongArray(72).apply {
        val seed=buffer(signKey)
        for(index in 0..3) this[index]=seed.long
        val sequence=0x3dc94c3a046d678bL
        for(index in 4 until size) {
            var next=java.lang.Long.rotateRight(this[index-1],3) xor this[index-3]
            next=next xor java.lang.Long.rotateRight(next,1)
            this[index]=this[index-4].inv() xor next xor ((sequence ushr ((index-4)%62)) and 1L) xor 3L
        }
    }

    fun headers(request: Signer.Request): Map<String,String> {
        val query=request.url.substringAfter('?',"")
        val ts=request.headers.getValue("x-khronos").toLong()
        val aid=values(query)["aid"] ?: "1967"
        return request.headers.toMutableMap().apply {
            remove("x-medusa")
            put("x-argus",argus(query,request.body,ts,random.nextInt().toLong() and 0xffffffffL))
            put("x-ladon",ladon(ts,random.nextInt().toLong() and 0xffffffffL,aid))
        }
    }

    internal fun argus(query: String,body: ByteArray?,timestamp: Long,nonce: Long): String {
        val fields=values(query)
        val bodyHash=sm3(if(body==null || body.isEmpty()) ByteArray(16) else md5(body))
        val queryHash=sm3(if(query.isEmpty()) ByteArray(16) else query.toByteArray(Charsets.UTF_8))
        val counters=Proto().apply { number(1,1); number(2,1); number(3,1); number(7,0xc792ecccL) }.bytes()
        val device=Proto().apply { string(1,"NX551J"); number(2,8196); number(4,0x80e0d800L) }.bytes()
        val bean=Proto().apply {
            number(1,1077940818); number(2,2); number(3,nonce)
            string(4,fields["aid"] ?: "1967"); string(5,fields["device_id"].orEmpty())
            string(6,license); string(7,fields["version_name"].orEmpty()); string(8,"v04.04.05-ov-android")
            number(9,134744640); data(10,ByteArray(8)); number(11,0)
            number(12,(timestamp shl 1) and 0xffffffffL)
            data(13,bodyHash.copyOf(6)); data(14,queryHash.copyOf(6)); data(15,counters)
            string(16,fields["iid"].orEmpty()); string(20,"none"); number(21,738); data(23,device); number(25,2)
        }.bytes()
        val plain=padded(bean)
        val words=buffer(plain)
        for(offset in plain.indices step 16) {
            var first=words.getLong(offset)
            var second=words.getLong(offset+8)
            for(key in simonKeys) {
                val next=first xor (java.lang.Long.rotateLeft(second,1) and java.lang.Long.rotateLeft(second,8)) xor
                    java.lang.Long.rotateLeft(second,2) xor key
                first=second
                second=next
            }
            words.putLong(offset,first)
            words.putLong(offset+8,second)
        }
        val frame=mask+plain+tail
        for(index in mask.size until frame.size) frame[index]=(frame[index].toInt() xor mask[index%mask.size].toInt()).toByte()
        frame.reverse()
        val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(aesKey,"AES"),IvParameterSpec(aesIv))
        return Base64.getEncoder().encodeToString(byteArrayOf(0xf2.toByte(),0x81.toByte())+
            cipher.doFinal(frame+byteArrayOf(0x61,0x6f)))
    }

    internal fun ladon(timestamp: Long,nonce: Long,aid: String): String {
        val seed=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(nonce.toInt()).array()
        val digest=md5(seed+aid.toByteArray(Charsets.UTF_8))
        val hexKey=digest.joinToString("") { "%02x".format(it.toInt() and 255) }.toByteArray(Charsets.US_ASCII)
        val words=buffer(hexKey)
        var key=words.long
        val pending=longArrayOf(words.long,words.long,words.long)
        val keys=LongArray(34)
        for(index in keys.indices) {
            keys[index]=key
            val next=(java.lang.Long.rotateRight(pending[0],8)+key) xor index.toLong()
            pending[0]=pending[1]; pending[1]=pending[2]; pending[2]=next
            key=next xor java.lang.Long.rotateLeft(key,3)
        }
        val plain=padded("$timestamp-$license-$aid".toByteArray(Charsets.UTF_8))
        val data=buffer(plain)
        for(offset in plain.indices step 16) {
            var first=data.getLong(offset)
            var second=data.getLong(offset+8)
            for(round in keys) {
                second=round xor (first+java.lang.Long.rotateRight(second,8))
                first=second xor java.lang.Long.rotateLeft(first,3)
            }
            data.putLong(offset,first); data.putLong(offset+8,second)
        }
        return Base64.getEncoder().encodeToString(seed+plain)
    }

    private fun values(query: String)=query.split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='),"UTF-8") to URLDecoder.decode(it.substringAfter('='),"UTF-8")
    }
    private fun buffer(bytes: ByteArray)=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun hex(value: String)=value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun md5(bytes: ByteArray)=MessageDigest.getInstance("MD5").digest(bytes)
    private fun sm3(bytes: ByteArray)=SM3Digest().run {
        update(bytes,0,bytes.size)
        ByteArray(digestSize).also { doFinal(it,0) }
    }
    private fun padded(bytes: ByteArray): ByteArray {
        val count=16-bytes.size%16
        return bytes+ByteArray(count) { count.toByte() }
    }
    private class Proto {
        private val out=ByteArrayOutputStream()
        private fun varint(value: Long) {
            var rest=value
            while(rest ushr 7!=0L) {
                out.write(((rest and 127) or 128).toInt())
                rest=rest ushr 7
            }
            out.write(rest.toInt())
        }
        fun number(tag: Int,value: Long) { varint(tag.toLong() shl 3); varint(value) }
        fun string(tag: Int,value: String)=data(tag,value.toByteArray(Charsets.UTF_8))
        fun data(tag: Int,value: ByteArray) {
            varint((tag.toLong() shl 3) or 2)
            varint(value.size.toLong())
            out.write(value)
        }
        fun bytes()=out.toByteArray()
    }
}
