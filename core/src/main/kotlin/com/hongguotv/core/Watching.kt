// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

object PlaybackQuality {
    val options=listOf(480,720,1080)
    fun normalize(value: Int)=value.takeIf { it in options } ?: 1080
    fun lowerThan(value: Int)=options.lastOrNull { it<value }
}

class QualityRecovery {
    private var stalledSince: Long?=null
    private var decoderFailures=0

    fun resetBuffering() { stalledSince=null }
    fun reset() { resetBuffering(); decoderFailures=0 }
    fun afterDecoderFailure()=++decoderFailures>=2
    fun stalled(now: Long, buffering: Boolean): Boolean {
        if(!buffering) { resetBuffering(); return false }
        val started=stalledSince ?: now.also { stalledSince=it }
        return now-started>=10_000L
    }
}

enum class EpisodeWatchState(val label: String) {
    UNWATCHED("未看"), IN_PROGRESS("未看完"), WATCHED("已看")
}

data class EpisodeCheckpoint(val seriesId: String,val episodeId: String,val position: Long,val completed: Boolean,val updatedAt: Long) {
    val key get()=seriesId to episodeId
    val state get()=if(completed) EpisodeWatchState.WATCHED else EpisodeWatchState.IN_PROGRESS
    fun merge(other: EpisodeCheckpoint): EpisodeCheckpoint =
        (if(other.updatedAt>updatedAt) other else this).copy(completed=completed || other.completed)
}

internal fun BackupProgress.episodeCheckpoint() =
    EpisodeCheckpoint(series.id,episodeId,position,completed,updatedAt)

/** One automatic recovery budget per episode; only an explicit user retry resets it. */
class RecoveryBudget {
    var attempts=0; private set
    val remaining get()=attempts<3
    val delayMillis get()=listOf(1000L,2500L,5000L).getOrNull(attempts)
    fun consume(): Boolean { if(!remaining) return false; attempts++; return true }
    fun reset() { attempts=0 }
}

data class ResumeTarget(val index: Int,val position: Long)
object ResumePlayback {
    fun target(episodes: List<String>,episodeId: String?,position: Long,completed: Boolean): ResumeTarget {
        require(episodes.isNotEmpty())
        val index=episodes.indexOf(episodeId)
        if(index<0) return ResumeTarget(0,0)
        return if(completed) ResumeTarget((index+1).coerceAtMost(episodes.lastIndex),0)
            else ResumeTarget(index,position.coerceAtLeast(0))
    }
}

/** Seen count is a high-water mark, so temporary catalog shrinkage is not a new update. */
data class FavoriteUpdate(val total: Int,val seen: Int,val checkedAt: Long) {
    val added get()=(total-seen).coerceAtLeast(0)
    fun observe(count: Int,now: Long,acknowledge: Boolean=false): FavoriteUpdate {
        require(count>0)
        if(now<checkedAt) return this
        return FavoriteUpdate(count,if(acknowledge) maxOf(seen,count) else seen,now)
    }
    fun json()=JSONObject().put("total",total).put("seen",seen).put("checkedAt",checkedAt)
    companion object {
        fun first(count: Int,now: Long): FavoriteUpdate { require(count>0); return FavoriteUpdate(count,count,now) }
        fun decode(value: JSONObject?)=runCatching {
            require(value!=null)
            val total=value.getInt("total"); val seen=value.getInt("seen"); val time=value.getLong("checkedAt")
            require(total>0 && seen>0 && time>=0)
            FavoriteUpdate(total,seen,time)
        }.getOrNull()
    }
}
