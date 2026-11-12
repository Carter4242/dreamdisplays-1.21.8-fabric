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

        /** Everything this PC plays, Minecraft and the voice chat it plays included. */
        data object WholePc : Choice

        /**
         * One app, a shared window's own: process [pid] and the processes it started (a browser's audio runs in a
         * child process). The game's own window is one too: the game's sound and the voice chat it plays.
         */
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
     * Minecraft" nor a window's own app ever widens to the whole PC on its own: that would stream the game, its voice
     * chat or a call the sharer did not mean to share. Older Windows (no process loopback) is never offered either; it
     * gets "Whole PC" instead. A window's app is captured with its whole process tree whatever it is, the game
     * included: the sharer's own client keeps its playback of the share silent, so nothing loops.
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
     * The sound choices for a shared screen on [os], in order. Windows with process loopback: everything but the
     * game, the whole PC, or none; Windows without it and Linux: the whole PC or none; macOS: no sound step.
     */
    fun options(os: Os, processLoopback: Boolean): List<Choice> = when (os) {
        Os.MAC -> emptyList()
        Os.LINUX -> listOf(Choice.WholePc, Choice.Off)
        Os.WINDOWS -> if (processLoopback) listOf(Choice.ExceptGame, Choice.WholePc, Choice.Off) else listOf(Choice.WholePc, Choice.Off)
    }

    /**
     * The screen sound selected when the sound step opens: everything but the game. Where only the whole PC can be
     * captured (Linux, Windows before process loopback) no sound: the whole PC carries the game and the voice chat it
     * plays, which the people at the display would hear again as an echo.
     */
    fun defaultChoice(os: Os, processLoopback: Boolean): Choice =
        if (os == Os.WINDOWS && processLoopback) Choice.ExceptGame else Choice.Off

    /** Why a shared window goes without sound. */
    enum class WindowSilence {
        /** This Windows cannot capture one app's sound (before build 20348). */
        NO_PROCESS_LOOPBACK,

        /** Its app was not found: a Store app's frame whose app process could not be found (minimized). */
        APP_NOT_FOUND,
    }

    /** What a shared window sounds like: [choice], and when that is no sound on Windows, [silence] says why. */
    data class WindowSound(val choice: Choice, val silence: WindowSilence? = null)

    /**
     * A shared window's sound, picked without asking: its own app's ([app], null when it was not found, see
     * [windowApp]), never anything wider. Only Windows shares windows.
     */
    fun windowSound(os: Os, processLoopback: Boolean, app: Choice.App?): WindowSound = when {
        os != Os.WINDOWS -> WindowSound(Choice.Off)
        !processLoopback -> WindowSound(Choice.Off, WindowSilence.NO_PROCESS_LOOPBACK)
        app == null -> WindowSound(Choice.Off, WindowSilence.APP_NOT_FOUND)
        else -> WindowSound(app)
    }

    /**
     * The app whose sound a shared window carries: its program's topmost process [root] (see `WinAudio.programRoot`)
     * and every process under it, whatever the program is: the game's own window, a launcher the game runs under (the
     * game is in its tree) or any other Java app alike. Null for a Store app's frame ([frameHost]: the app inside it
     * was not found, and the frame itself plays nothing).
     */
    fun windowApp(root: Long, name: String, frameHost: Boolean): Choice.App? =
        if (frameHost) null else Choice.App(root, name)

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

    /** What the stream carries: AAC at [AAC_BITRATE], stereo, [AAC_RATE]. */
    const val AAC_BITRATE = "160k"

    /** The rate sound is captured at (PulseAudio). */
    const val OUTPUT_RATE = 48_000

    /**
     * AAC's sample rate. An AAC frame is 1024 samples and the encoder looks one frame ahead, and the muxer holds the
     * picture until the sound of the same moment is encoded: at 96 kHz a frame is 10.7 ms instead of 21.3, which
     * measured 121 -> 84 ms glass-to-decode for the picture (and 68 -> 39 ms for the sound) through MediaMTX.
     */
    const val AAC_RATE = 96_000

    /**
     * The silent track's rate: made at the rate every share sound is encoded at ([AAC_RATE]), so nothing resamples it.
     */
    const val SILENCE_RATE = AAC_RATE
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
