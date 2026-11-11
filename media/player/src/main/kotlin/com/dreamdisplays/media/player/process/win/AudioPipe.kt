package com.dreamdisplays.media.player.process.win

import com.dreamdisplays.media.player.process.AudioTimeline
import com.dreamdisplays.media.player.process.ShareAudio
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A Windows named pipe that carries captured sound into the share's `FFmpeg` (its `-i`) as WAV, on one continuous
 * timeline: frame 0 is [startNanos] ([System.nanoTime] clock), each packet goes where its capture time says, and
 * silence fills whatever loopback did not deliver (see [AudioTimeline]). Capture runs before `FFmpeg` opens the pipe; that stretch
 * is queued and written at once on connect.
 */
class AudioPipe(private val format: ShareAudio.Format, private val startNanos: Long) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** The path `FFmpeg` opens. */
    val path: String = "\\\\.\\pipe\\dreamdisplays-share-" + UUID.randomUUID()

    private class Packet(val nanos: Long, val data: ByteArray)

    /** About 5 s of 10 ms packets; past that the oldest are dropped (the timeline turns the gap into silence). */
    private val queue = ArrayBlockingQueue<Packet>(512)

    @Volatile
    private var closed = false

    @Volatile
    private var connected = false

    private val handle: Pointer = WinApi.kernel32.CreateNamedPipeW(
        WString(path), PIPE_ACCESS_OUTBOUND, PIPE_TYPE_BYTE or PIPE_WAIT or PIPE_REJECT_REMOTE_CLIENTS, 1,
        OUT_BUFFER, 0, 0, null,
    )?.takeIf { Pointer.nativeValue(it) != INVALID_HANDLE } ?: throw IllegalStateException(
        "could not create the audio pipe (error ${Native.getLastError()})",
    )

    private val writer = Thread(::run, "DreamDisplays-share-audio-pipe").apply { isDaemon = true }

    /** Starts waiting for `FFmpeg` to open the pipe. */
    fun start() = writer.start()

    /** A captured packet: its first frame's time and its PCM. Never blocks. */
    fun offer(nanos: Long, data: ByteArray) {
        if (closed) return
        while (!queue.offer(Packet(nanos, data))) queue.poll()
    }

    /** Stops writing; `FFmpeg` then sees the end of its sound input. */
    fun close() {
        if (closed) return
        closed = true
        // Still waiting in ConnectNamedPipe (FFmpeg never opened it): connect once ourselves to release it
        if (!connected) runCatching { FileInputStream(path).close() }
    }

    private fun run() {
        try {
            val ok = WinApi.kernel32.ConnectNamedPipe(handle, null) || Native.getLastError() == ERROR_PIPE_CONNECTED
            connected = true
            if (!ok || closed) return
            if (!write(ShareAudio.wavHeader(format))) return
            val timeline = AudioTimeline(format.sampleRate, startNanos)
            val frameBytes = format.bytesPerFrame
            while (!closed) {
                val packet = queue.poll(10, TimeUnit.MILLISECONDS)
                if (packet != null) {
                    val frames = packet.data.size / frameBytes
                    val at = timeline.place(packet.nanos, frames)
                    if (!writeSilence(at.silenceBefore * frameBytes)) return
                    val skipBytes = at.skip * frameBytes
                    if (skipBytes < packet.data.size) {
                        val body = if (skipBytes == 0) packet.data else packet.data.copyOfRange(skipBytes, packet.data.size)
                        if (!write(body)) return
                    }
                }
                // Silence only once everything queued is placed: what waited for FFmpeg to open the pipe is older than
                // "now minus the grace" and would otherwise be overrun by it
                if (queue.isEmpty() && !writeSilence(timeline.silenceDue(System.nanoTime()) * frameBytes)) return
            }
        } catch (t: Throwable) {
            if (!closed) logger.warn("Share audio pipe stopped: {}", t.toString())
        } finally {
            closed = true
            WinApi.kernel32.CloseHandle(handle)
        }
    }

    private val zeros = ByteArray(SILENCE_CHUNK)

    private fun writeSilence(bytes: Long): Boolean {
        var left = bytes
        while (left > 0) {
            val n = minOf(left, zeros.size.toLong()).toInt()
            if (!write(if (n == zeros.size) zeros else zeros.copyOf(n))) return false
            left -= n
        }
        return true
    }

    private val written = IntByReference()

    /** False once `FFmpeg` is gone (broken pipe). */
    private fun write(data: ByteArray): Boolean =
        data.isEmpty() || WinApi.kernel32.WriteFile(handle, data, data.size, written, null)

    private companion object {
        const val PIPE_ACCESS_OUTBOUND = 0x2
        const val PIPE_TYPE_BYTE = 0x0
        const val PIPE_WAIT = 0x0
        const val PIPE_REJECT_REMOTE_CLIENTS = 0x8

        /** 64 KiB of pipe buffer: well over 100 ms of sound, so a write never waits on `FFmpeg` in normal running. */
        const val OUT_BUFFER = 65_536
        const val INVALID_HANDLE = -1L
        const val ERROR_PIPE_CONNECTED = 535
        const val SILENCE_CHUNK = 19_200
    }
}
