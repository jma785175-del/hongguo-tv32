// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Build

/** Keep 32-bit TVs off vendor codec paths while using the registered playback API. */
object PlaybackCompatibility {
    val softwareOnly = Build.SUPPORTED_ABIS.none { it=="arm64-v8a" || it=="x86_64" }
    val isolatedDecoder = softwareOnly && Build.VERSION.SDK_INT >= 26
    fun qualityLimit(requested: Int)=if(softwareOnly) minOf(requested,720) else requested
}
