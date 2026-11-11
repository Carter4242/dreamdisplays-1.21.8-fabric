package com.dreamdisplays.core.karaoke

import com.dreamdisplays.core.protocol.common.packets.DisplaySync

/**
 * Latency constants the karaoke voice alignment cannot measure, in ms (`[karaoke]` in the server config).
 *
 * @property singerOutputCaptureMs the singer's audio output (hearing the track) plus mic capture and encoding.
 * @property listenerBufferOutputMs a listener's voice chat jitter buffer plus audio output (Simple Voice Chat's
 *   client buffers 5 frames = 100 ms by default, plus ~20 ms of output).
 */
data class KaraokeLatency(
    val singerOutputCaptureMs: Long = 60L,
    val listenerBufferOutputMs: Long = 120L,
)

/**
 * The karaoke timing model.
 *
 * Every client follows the display's server timeline with latency compensation, so track moment `m` plays for
 * every listener at the same server time `R(m)`. A singer is sent the timeline shifted ahead by their `lead`, so they
 * hear `m` at `R(m) - lead` and sing it right then; the voice frame for `m` leaves their client `s_out + s_cap` later
 * and reaches the server after `s_up`:
 *
 * ```
 * A   = R(m) - lead + s_out + s_cap + s_up                     (arrival, without network jitter)
 * E_i = R(m) - (l_down_i + l_buf + l_out)                      (emit so it plays at R(m) for listener i)
 *     = A + lead - (s_out + s_cap + s_up) - (l_down_i + l_buf + l_out) + trim
 * ```
 *
 * `s_up` and `l_down_i` are half the singer's / listener's ping; `trim` (display + singer, by ear) absorbs what the
 * constants get wrong. A negative [emitDelayMs] means the lead is too small for that listener: the frame can only be
 * sent on arrival and is heard late by that much.
 */
object KaraokeTiming {
    /** Simple Voice Chat's frame length. */
    const val FRAME_MS = 20L

    /** `E_i - A`: how long after a frame's (jitter-free) arrival it is sent to a listener. */
    fun emitDelayMs(leadMs: Long, singerPingMs: Long, listenerPingMs: Long, trimMs: Long, latency: KaraokeLatency): Long =
        leadMs - (latency.singerOutputCaptureMs + singerPingMs / 2) -
                (listenerPingMs / 2 + latency.listenerBufferOutputMs) + trimMs

    /**
     * The automatic lead for a singer: just enough for [worstListenerPingMs] (the slowest listener in range, or the
     * singer's own ping when nobody is there yet) plus [marginMs] for network jitter, kept within [minMs]..[maxMs].
     */
    fun autoLeadMs(
        singerPingMs: Long, worstListenerPingMs: Long, latency: KaraokeLatency,
        marginMs: Long, minMs: Long, maxMs: Long,
    ): Long {
        val needed = singerPingMs.coerceAtLeast(0) / 2 + latency.singerOutputCaptureMs +
                worstListenerPingMs.coerceAtLeast(0) / 2 + latency.listenerBufferOutputMs + marginMs
        return needed.coerceIn(minMs, maxOf(minMs, maxMs))
    }

    /**
     * [sync] as a singer with [leadMs] gets it: the position moved ahead by the lead (see [leadPosition]). Pause state,
     * server time, speed and a scheduled start stay, so a paused display shows the singer the paused frame plus the
     * lead, and a scheduled start starts them that far ahead at the same moment.
     */
    fun withLead(sync: DisplaySync, leadMs: Long): DisplaySync {
        if (leadMs == 0L) return sync
        return sync.copy(currentTimeMs = leadPosition(sync.currentTimeMs, leadMs, sync.rate, sync.durationMs, sync.loop))
    }

    /**
     * [positionMs] moved [leadMs] of wall time ahead at playback speed [rate]: wrapped around a looping video, kept
     * within a finite one.
     */
    fun leadPosition(positionMs: Long, leadMs: Long, rate: Double, durationMs: Long, loop: Boolean): Long {
        val raw = positionMs + Math.round(leadMs * (if (rate > 0.0) rate else 1.0))
        return when {
            durationMs <= 0L -> raw.coerceAtLeast(0L)
            loop -> Math.floorMod(raw, durationMs)
            else -> raw.coerceIn(0L, durationMs)
        }
    }
}
