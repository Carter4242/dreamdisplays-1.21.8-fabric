package com.dreamdisplays.media.player.util

import kotlin.math.abs
import kotlin.math.tanh

/** Media buffer effects applied in place to raw audio buffers before uploading to the GPU. */
object MediaBufferEffects {
    /**
     * Apply volume in place to an interleaved S16LE buffer. `len` is the number of valid bytes (must be a multiple of 2).
     */
    fun applyVolumeS16LE(buf: ByteArray, len: Int, volume: Double) {
        if (abs(volume - 1.0) < 1e-5) return
        var i = 0
        while (i + 1 < len) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val s = (hi shl 8) or lo
            val scaled = if (volume > 1.0) softLimit(s * volume) else (s * volume).toInt().coerceIn(-32768, 32767)
            buf[i] = (scaled and 0xFF).toByte()
            buf[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private const val SOFT_KNEE = 0.7 * 32767.0

    /** A boosted sample without hard clipping: exact below 70 % of full scale, louder peaks bent (tanh) toward it. */
    private fun softLimit(y: Double): Int {
        val a = abs(y)
        if (a <= SOFT_KNEE) return y.toInt()
        val limited = SOFT_KNEE + (32767.0 - SOFT_KNEE) * tanh((a - SOFT_KNEE) / (32767.0 - SOFT_KNEE))
        return (if (y < 0) -limited else limited).toInt().coerceIn(-32768, 32767)
    }
}
