package com.dreamdisplays.media.player.process

/**
 * The sound side of "Share screen": what the sharer can pick, what that becomes on each OS (with its fallbacks), and
 * the `FFmpeg` input it feeds. Pure: the capture itself lives with the client (WASAPI through JNA on Windows), and
 * `FFmpeg` captures PulseAudio / PipeWire itself on Linux.
 */
object ShareAudio {
    /** The OS the share runs on, as far as audio is concerned. */
    enum class Os { WINDOWS, MAC, LINUX }

    /** What the sharer picked. */
    sealed interface Choice {
        /** Everything this PC plays except Minecraft (game sounds, and the voice chat it plays). The default. */
        data object ExceptGame : Choice

        /** Everything this PC plays, Minecraft included. */
        data object WholePc : Choice

        /** One app: process [pid] and the processes it started (a browser's audio runs in a child process). */
        data class App(val pid: Long, val name: String) : Choice

        /** Video only. */
        data object Off : Choice
    }

    /** How sound is really captured, best first in [plans]. */
    sealed interface Plan {
        /** WASAPI process loopback (Windows 10 build 20348+): [pid]'s tree only ([include]) or all but it. */
        data class ProcessLoopback(val pid: Long, val include: Boolean) : Plan

        /** WASAPI loopback of the default output device: everything. */
        data object DeviceLoopback : Plan

        /** `FFmpeg`'s PulseAudio input on the default output's monitor (PipeWire serves it too). */
        data class Pulse(val device: String = PULSE_DEFAULT_MONITOR) : Plan
    }

    /** PulseAudio's name for the monitor of whatever the default output is. */
    const val PULSE_DEFAULT_MONITOR = "@DEFAULT_MONITOR@"

    /**
     * What to try for [choice] on [os], in order, given this game's [selfPid]; empty = no sound. Neither "Except
     * Minecraft" nor "Just one app" ever widens to the whole PC on its own: that would stream the game, its voice chat
     * (with this share's own playback, an echo) or a call the sharer did not mean to share. Older Windows (no process
     * loopback) is never offered either; it gets "Whole PC" instead.
     */
    fun plans(choice: Choice, os: Os, selfPid: Long): List<Plan> = when (os) {
        Os.MAC -> emptyList()
        Os.LINUX -> if (choice == Choice.Off) emptyList() else listOf(Plan.Pulse())
        Os.WINDOWS -> when (choice) {
            Choice.Off -> emptyList()
            Choice.WholePc -> listOf(Plan.DeviceLoopback)
            Choice.ExceptGame -> listOf(Plan.ProcessLoopback(selfPid, include = false))
            is Choice.App -> listOf(Plan.ProcessLoopback(choice.pid, include = true))
        }
    }

    /**
     * The choices the picker offers on [os], in order: per-app ones only where process loopback exists, and [apps]
     * (processes playing sound now) each as "Just <app>". [window] is the app owning a picked window, offered first.
     */
    fun options(os: Os, processLoopback: Boolean, apps: List<Choice.App>, window: Choice.App? = null): List<Choice> =
        when (os) {
            Os.MAC -> emptyList()
            Os.LINUX -> listOf(Choice.WholePc, Choice.Off)
            Os.WINDOWS -> buildList {
                val perApp = processLoopback
                if (perApp && window != null) add(window)
                add(if (perApp) Choice.ExceptGame else Choice.WholePc)
                if (perApp) add(Choice.WholePc)
                if (perApp) apps.filter { a -> window == null || a.pid != window.pid }.forEach(::add)
                add(Choice.Off)
            }
        }

    /** The choice selected when the audio step opens: a picked window's own app, else everything but the game. */
    fun defaultChoice(os: Os, processLoopback: Boolean, window: Choice.App? = null): Choice = when {
        os == Os.MAC -> Choice.Off
        os == Os.LINUX -> Choice.WholePc
        !processLoopback -> Choice.WholePc
        window != null -> window
        else -> Choice.ExceptGame
    }

    /** PCM layout of a pipe input. */
    data class Format(val sampleRate: Int, val channels: Int, val float: Boolean) {
        val bytesPerFrame: Int get() = channels * (if (float) 4 else 2)

        /** Bytes in 10 ms: the packet size `FFmpeg` reads the pipe in. */
        val tenMillisBytes: Int get() = sampleRate / 100 * bytesPerFrame
    }

    /**
     * The WAV header that opens a pipe of [format] PCM, lengths "unknown" (`-ignore_length`). WAV rather than raw
     * PCM because `FFmpeg`'s raw PCM reader takes 85 ms (4096-sample) packets, and the muxer, which keeps picture and
     * sound interleaved, then released both in 85 ms bursts; the WAV reader's packet size is an option (`-max_size`).
     */
    fun wavHeader(format: Format): ByteArray {
        val bits = if (format.float) 32 else 16
        val block = format.channels * bits / 8
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(-1).put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        b.putShort((if (format.float) 3 else 1).toShort()).putShort(format.channels.toShort())
        b.putInt(format.sampleRate).putInt(format.sampleRate * block).putShort(block.toShort()).putShort(bits.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(-1)
        return b.array()
    }

    /** Where `FFmpeg` reads the sound from. */
    sealed interface Input {
        /**
         * WAV ([wavHeader], then PCM) the client writes to [path] (a Windows named pipe); sample 0 is at the share's
         * clock base.
         */
        data class Pipe(val path: String, val format: Format) : Input

        /** `FFmpeg`'s own PulseAudio capture of [device]. */
        data class Pulse(val device: String) : Input

        /**
         * A silent track `FFmpeg` makes itself: a window shared without sound carries one, so its stream never goes
         * quiet while the window draws nothing. Never counts as sound being shared.
         */
        data object Silence : Input
    }

    /** The silent track's rate (encoded like any share sound). */
    const val SILENCE_RATE = 96_000

    /** What the stream carries: AAC at this rate, stereo 48 kHz. */
    const val AAC_BITRATE = "160k"
    const val OUTPUT_RATE = 48_000
}

/**
 * Lays captured audio packets onto one continuous PCM stream whose frame `n` is wall time `startNanos + n / rate`, so
 * `FFmpeg` can stamp it by sample count and still meet the video's wall-clock timestamps. Loopback delivers nothing
 * while nothing plays: those stretches become silence ([silenceDue]), written a little after the fact so a packet
 * still in flight is not overrun. A packet that lands off by more than [toleranceFrames] (clock drift, a hitch) is
 * pushed to its true place with silence, or has its already-covered start skipped. Times are in the
 * [System.nanoTime] clock. Not thread-safe: one writer.
 */
class AudioTimeline(
    private val rate: Int,
    private val startNanos: Long,
    private val toleranceFrames: Int = rate / 100,
    private val graceNanos: Long = 60_000_000L,
) {
    /** Frames written so far: the stream's next frame is at wall time [startNanos] + written / rate. */
    var written: Long = 0
        private set

    /** Where a packet goes: [silenceBefore] frames of silence first, then its frames from [skip] on. */
    data class Placement(val silenceBefore: Long, val skip: Int)

    private fun frameAt(nanos: Long): Long = Math.floorDiv((nanos - startNanos) * rate, 1_000_000_000L)

    /** Places a packet of [frames] frames whose first one was captured at [packetNanos]; counts what is written. */
    fun place(packetNanos: Long, frames: Int): Placement {
        val at = frameAt(packetNanos)
        val placement = when {
            at > written + toleranceFrames -> Placement(at - written, 0)
            at < written - toleranceFrames -> Placement(0, (written - at).coerceAtMost(frames.toLong()).toInt())
            else -> Placement(0, 0)
        }
        written += placement.silenceBefore + (frames - placement.skip)
        return placement
    }

    /** Silence frames to write at [nowNanos] so the stream never falls more than the grace period behind real time. */
    fun silenceDue(nowNanos: Long): Long {
        val due = frameAt(nowNanos - graceNanos) - written
        if (due <= 0) return 0
        written += due
        return due
    }
}
