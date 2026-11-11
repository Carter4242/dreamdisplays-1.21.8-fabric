package com.dreamdisplays.core.karaoke

import com.dreamdisplays.core.protocol.common.packets.DisplaySync

/**
 * Latency fallbacks for what a client did not measure (or for clients too old to report), in ms (`[karaoke]` in the
 * server config).
 *
 * @property singerOutputCaptureMs the singer's audio output (hearing the track) plus mic capture and encoding.
 * @property listenerBufferOutputMs a listener's voice chat jitter buffer plus audio output (Simple Voice Chat's
 *   client buffers 5 frames = 100 ms by default, plus ~20 ms of output).
 */
data class KaraokeLatency(
    val singerOutputCaptureMs: Long = 250L,
    val listenerBufferOutputMs: Long = 120L,
)

/**
 * What a singer's client measured, in ms; null where it reported nothing (the [KaraokeLatency] / ping fallback
 * applies).
 *
 * @property outputMs the display's audio: from where the player's clock says a moment is to when it leaves the
 *   speakers (the output device's latency).
 * @property captureMs the mic: from a sound to Simple Voice Chat handing over the frame that holds it (device input,
 *   the 20 ms frame, denoiser).
 * @property uplinkMs from that hand-over to the frame's arrival at the server, measured with clock-synced capture
 *   markers (the jitter-free low end, like the burst anchor).
 */
data class SingerLatency(
    val outputMs: Long? = null,
    val captureMs: Long? = null,
    val uplinkMs: Long? = null,
)

/**
 * What a listener's client measured, in ms; null where it reported nothing.
 *
 * @property voiceBufferMs Simple Voice Chat's jitter buffer (`output_buffer_size` frames of 20 ms).
 * @property voiceOutputMs voice chat's audio output device latency.
 * @property trackOutputMs the display audio's output device latency: the track is heard that much after the
 *   timeline says, so the voice may come that much later too.
 */
data class ListenerLatency(
    val voiceBufferMs: Long? = null,
    val voiceOutputMs: Long? = null,
    val trackOutputMs: Long? = null,
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
 * `s_out + s_cap + s_up` is [singerSideMs]: measured by the singer's client where it can ([SingerLatency]; `s_up` from
 * clock-synced capture markers), else [KaraokeLatency.singerOutputCaptureMs] plus half the singer's ping.
 * `l_down_i + l_buf + l_out` is [listenerSideMs]: half the listener's ping plus their reported voice chat buffer and
 * output, less their display audio's output latency (they hear the track that much late too), else
 * [KaraokeLatency.listenerBufferOutputMs]. `trim` is the manual trim (display + singer, by ear) plus the singer's
 * automatic trim ([AutoTrimController]). A negative [emitDelayMs] means the lead is too small for that listener: the
 * frame can only be sent on arrival and is heard late by that much.
 */
object KaraokeTiming {
    /** Simple Voice Chat's frame length. */
    const val FRAME_MS = 20L

    /** An audio output latency nobody reported. */
    const val DEFAULT_OUTPUT_MS = 20L

    /**
     * The furthest ahead of now a voice frame is ever sent: several times the largest lead (`karaoke.max_lead_ms` stops
     * at 3 s) plus every latency term. The grid takes the frame number from the singer's client, so a client that jumps
     * it far ahead would otherwise park frames for days in the queue every singer's frames share.
     */
    const val MAX_EMIT_AHEAD_MS = 10_000L

    /** True when a frame due at [atNanos] is further than [MAX_EMIT_AHEAD_MS] after [nowNanos]: it is dropped. */
    fun emitTooFar(atNanos: Long, nowNanos: Long): Boolean = atNanos - nowNanos > MAX_EMIT_AHEAD_MS * 1_000_000L

    /** `E_i - A`: how long after a frame's (jitter-free) arrival it is sent to a listener (constants and pings only). */
    fun emitDelayMs(leadMs: Long, singerPingMs: Long, listenerPingMs: Long, trimMs: Long, latency: KaraokeLatency): Long =
        emitDelayMs(leadMs, singerPingMs, null, listenerPingMs, null, trimMs, latency)

    /** `E_i - A` with what the singer's and the listener's clients measured (null = fallbacks). */
    fun emitDelayMs(
        leadMs: Long, singerPingMs: Long, singer: SingerLatency?, listenerPingMs: Long, listener: ListenerLatency?,
        trimMs: Long, latency: KaraokeLatency,
    ): Long = leadMs - singerSideMs(singerPingMs, singer, latency) - listenerSideMs(listenerPingMs, listener, latency) + trimMs

    /** `s_out + s_cap + s_up`: from hearing a moment to its voice frame reaching the server. */
    fun singerSideMs(singerPingMs: Long, singer: SingerLatency?, latency: KaraokeLatency): Long {
        val output = singer?.outputMs
        val capture = singer?.captureMs
        val outputCapture = if (output != null && capture != null) output + capture else latency.singerOutputCaptureMs
        return outputCapture + (singer?.uplinkMs ?: (singerPingMs.coerceAtLeast(0) / 2))
    }

    /** `l_down + l_buf + l_out - l_track`: from sending a voice frame to the listener hearing it, net of their track. */
    fun listenerSideMs(listenerPingMs: Long, listener: ListenerLatency?, latency: KaraokeLatency): Long {
        val down = listenerPingMs.coerceAtLeast(0) / 2
        val buffer = listener?.voiceBufferMs ?: return down + latency.listenerBufferOutputMs
        // An output not reported counts the same on both sides: voice and track usually share the device
        val track = listener.trackOutputMs ?: DEFAULT_OUTPUT_MS
        val voice = listener.voiceOutputMs ?: listener.trackOutputMs ?: DEFAULT_OUTPUT_MS
        return down + buffer + voice - track
    }

    /**
     * The automatic lead for a singer: just enough for [worstListenerPingMs] (the slowest listener in range, or the
     * singer's own ping when nobody is there yet) plus [marginMs] for network jitter, kept within [minMs]..[maxMs].
     */
    fun autoLeadMs(
        singerPingMs: Long, worstListenerPingMs: Long, latency: KaraokeLatency,
        marginMs: Long, minMs: Long, maxMs: Long,
    ): Long = autoLeadMs(
        singerSideMs(singerPingMs, null, latency), listenerSideMs(worstListenerPingMs, null, latency), 0L,
        marginMs, minMs, maxMs,
    )

    /**
     * The automatic lead from the singer's side ([singerSideMs]) and the worst listener side ([listenerSideMs]) in
     * range, plus room for a negative [trimMs] (an earlier voice needs more lead) and [marginMs] for network jitter,
     * kept within [minMs]..[maxMs].
     */
    fun autoLeadMs(
        singerSideMs: Long, worstListenerSideMs: Long, trimMs: Long,
        marginMs: Long, minMs: Long, maxMs: Long,
    ): Long {
        val needed = singerSideMs + worstListenerSideMs + (-trimMs).coerceAtLeast(0) + marginMs
        return needed.coerceIn(minMs, maxOf(minMs, maxMs))
    }

    /**
     * The next value of a singer's automatic lead, now at [currentMs], when [autoLeadMs] now says [wantedMs] (their
     * learned trim, their measured latencies and the listeners in range move it); null = keep it. While the song plays
     * a change makes the singer's client jump its track in place, so the lead only goes up there, and only once the
     * voice would be late by [MID_SONG_RAISE_MS] or more less the margin: below that, keeping the singer's track
     * undisturbed is better. Otherwise (paused, between songs) any change of [LEAD_DEADBAND_MS] or more is taken.
     */
    fun autoLeadChange(currentMs: Long, wantedMs: Long, playing: Boolean): Long? {
        val change = wantedMs - currentMs
        return when {
            playing -> wantedMs.takeIf { change >= MID_SONG_RAISE_MS }
            kotlin.math.abs(change) >= LEAD_DEADBAND_MS -> wantedMs
            else -> null
        }
    }

    /** A singer's lead changes by at least this much, or not at all. */
    const val LEAD_DEADBAND_MS = 30L

    /** An automatic lead is raised mid-song once it is this far under what is needed (see [autoLeadChange]). */
    const val MID_SONG_RAISE_MS = 100L

    /**
     * [sync] as a singer with [leadMs] gets it: the position moved ahead by the lead (see [leadPosition]). Pause state,
     * server time, speed and a scheduled start stay, so a paused display shows the singer the paused frame plus the
     * lead, and a scheduled start starts them that far ahead at the same moment. [DisplaySync.karaokeLeadMs] tells the
     * client it sings here (and lets it jump straight to a changed lead).
     */
    fun withLead(sync: DisplaySync, leadMs: Long): DisplaySync {
        if (leadMs == 0L) return sync
        return sync.copy(
            currentTimeMs = leadPosition(sync.currentTimeMs, leadMs, sync.rate, sync.durationMs, sync.loop),
            karaokeLeadMs = leadMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
        )
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

    /**
     * Where a client that preloaded a display's next video counts as held ready when that video starts held at 0: a
     * singer with [leadMs] is waited for at their lead position ([leadPosition]), while their client keeps the preloaded
     * stream on its first frame and jumps the lead in place once it plays; at 0 they would hold everyone for the whole
     * start hold. Everyone else ([leadMs] 0) at 0.
     */
    fun heldReadyPositionMs(leadMs: Long, rate: Double, durationMs: Long): Long =
        if (leadMs <= 0L) 0L else leadPosition(0L, leadMs, rate, durationMs, loop = true)

    /**
     * True when a karaoke line singer's song is over: the display no longer plays their queue entry ([currentItemId]
     * differs from [singerItemId]), its play-through is within [endMs] of its end ([msUntilEnd]), or it was just
     * started over at its end for everyone ([restartedAtEnd]). The restart is decided up to a couple hundred ms before
     * the end and re-anchors the clock at 0, so [msUntilEnd] alone never sees that end.
     */
    fun lineSongOver(
        singerItemId: String?, currentItemId: String?, msUntilEnd: Long?, endMs: Long, restartedAtEnd: Boolean,
    ): Boolean = restartedAtEnd || singerItemId != currentItemId || (msUntilEnd != null && msUntilEnd <= endMs)
}
