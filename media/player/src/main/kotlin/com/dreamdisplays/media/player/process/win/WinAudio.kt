package com.dreamdisplays.media.player.process.win

import com.dreamdisplays.media.player.process.ShareAudio
import com.dreamdisplays.media.player.process.ShareWindows
import com.dreamdisplays.media.player.process.win.WinApi.call
import com.dreamdisplays.media.player.process.win.WinApi.check
import com.dreamdisplays.media.player.process.win.WinApi.release
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What the PC plays, through WASAPI (Windows only): loopback capture of the default output device or, on Windows 10
 * build 20348 and later, of one process tree (or all but one), plus the list of apps playing sound right now.
 */
object WinAudio {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Process loopback (`AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK`) appeared in Windows build 20348. */
    val processLoopback: Boolean get() = WinApi.buildNumber >= 20348

    /** What every capture asks the audio engine for: it converts whatever plays into this. */
    val FORMAT = ShareAudio.Format(48_000, 2, float = false)

    // IAudioClient::Initialize stream flags
    private const val LOOPBACK = 0x00020000
    private const val EVENTCALLBACK = 0x00040000
    private const val AUTOCONVERTPCM = 0x80000000.toInt()
    private const val SRC_DEFAULT_QUALITY = 0x08000000

    // AUDCLNT_BUFFERFLAGS_SILENT
    private const val SILENT = 0x2

    /** 200 ms of engine-side buffer (hundreds of ns): room for a hitch, no added delay since it is drained at once. */
    private const val BUFFER_HNS = 2_000_000L

    /**
     * A running loopback capture. [sink] gets each packet: its first frame's capture time ([System.nanoTime] clock)
     * and its PCM in [format]. Runs on its own thread until [stop].
     */
    class Capture internal constructor(
        val plan: ShareAudio.Plan, val format: ShareAudio.Format, private val thread: Thread,
        private val stopFlag: java.util.concurrent.atomic.AtomicBoolean,
        private val endedWhy: java.util.concurrent.atomic.AtomicReference<String?>,
    ) {
        /** Ends the capture; its thread notices within 10 ms. */
        fun stop() = stopFlag.set(true)

        /**
         * Why the capture ended on its own (its device went away, or the default output changed under a whole-device
         * capture), or null while it runs or after [stop].
         */
        val ended: String? get() = endedWhy.get()
    }

    /**
     * Starts capturing for [plan] (a WASAPI one); throws if this Windows cannot (no process loopback, no output
     * device), after at most a couple of seconds. [sink] runs on the capture thread and must not block.
     */
    fun start(plan: ShareAudio.Plan, sink: (Long, ByteArray) -> Unit): Capture {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val ended = java.util.concurrent.atomic.AtomicReference<String?>()
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        var format = FORMAT
        val thread = Thread({
            val com = WinApi.comInit()
            var client: Pointer? = null
            var capture: Pointer? = null
            var event: Pointer? = null
            try {
                client = when (plan) {
                    is ShareAudio.Plan.ProcessLoopback -> activateProcessLoopback(plan.pid, plan.include)
                    ShareAudio.Plan.DeviceLoopback -> activateDefaultRender(WinApi.IID_IAUDIO_CLIENT)
                    else -> throw IllegalArgumentException("not a WASAPI plan: $plan")
                }
                // A whole-device capture stays on the device it opened: it watches for the default output to move
                val device = if (plan == ShareAudio.Plan.DeviceLoopback) runCatching { defaultRenderId() }.getOrNull() else null
                event = WinApi.kernel32.CreateEventW(null, false, false, null)
                format = initialize(client, event)
                val out = PointerByReference()
                check(call(client, 14, WinApi.IID_IAUDIO_CAPTURE_CLIENT, out), "IAudioClient.GetService")
                capture = out.value
                check(call(client, 10), "IAudioClient.Start")
                ready.countDown()
                pump(capture!!, format, event, stop, sink, device)
                call(client, 11)
            } catch (t: Throwable) {
                if (ready.count > 0L) failure = t else {
                    logger.warn("Share audio capture stopped: {}", t.toString())
                    if (!stop.get()) ended.set(t.message ?: t.toString())
                }
            } finally {
                ready.countDown()
                release(capture)
                release(client)
                event?.let { WinApi.kernel32.CloseHandle(it) }
                if (com) runCatching { Native.load("ole32", CoUninit::class.java).CoUninitialize() }
            }
        }, "DreamDisplays-share-audio").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
        thread.start()
        if (!ready.await(5, TimeUnit.SECONDS)) {
            stop.set(true)
            throw IllegalStateException("audio capture did not start")
        }
        failure?.let { throw it }
        if (!thread.isAlive) throw IllegalStateException("audio capture ended at once")
        return Capture(plan, format, thread, stop, ended)
    }

    /**
     * Initializes [client] for loopback capture and returns the PCM layout it delivers: [FORMAT], converted by the
     * audio engine, or else the engine's own mix format (float or 16-bit, which `FFmpeg` converts). Event-driven
     * loopback is missing on some older builds; polling every 10 ms works the same.
     */
    private fun initialize(client: Pointer, event: Pointer?): ShareAudio.Format {
        fun init(flags: Int, wfx: Pointer): Boolean {
            if (call(client, 3, 0, flags or EVENTCALLBACK, BUFFER_HNS, 0L, wfx, null) >= 0) {
                if (event != null) call(client, 13, event)
                return true
            }
            return call(client, 3, 0, flags, BUFFER_HNS, 0L, wfx, null) >= 0
        }
        if (init(LOOPBACK or AUTOCONVERTPCM or SRC_DEFAULT_QUALITY, waveFormat(FORMAT))) return FORMAT
        val out = PointerByReference()
        check(call(client, 8, out), "IAudioClient.GetMixFormat")
        val mix = out.value
        try {
            val tag = mix.getShort(0).toInt() and 0xFFFF
            val channels = mix.getShort(2).toInt()
            val rate = mix.getInt(4)
            val bits = mix.getShort(14).toInt()
            // WAVE_FORMAT_EXTENSIBLE: the real tag is the first two bytes of the SubFormat GUID (offset 24)
            val real = if (tag == 0xFFFE) mix.getShort(24).toInt() and 0xFFFF else tag
            val format = when {
                real == 3 && bits == 32 -> ShareAudio.Format(rate, channels, float = true)
                real == 1 && bits == 16 -> ShareAudio.Format(rate, channels, float = false)
                else -> throw IllegalStateException("unsupported mix format (tag $real, $bits bit)")
            }
            check(if (init(LOOPBACK, mix)) 0 else -1, "IAudioClient.Initialize")
            return format
        } finally {
            WinApi.ole32.CoTaskMemFree(mix)
        }
    }

    private interface CoUninit : com.sun.jna.win32.StdCallLibrary {
        fun CoUninitialize()
    }

    private fun pump(
        capture: Pointer, format: ShareAudio.Format, event: Pointer?, stop: java.util.concurrent.atomic.AtomicBoolean,
        sink: (Long, ByteArray) -> Unit, device: String?,
    ) {
        val frameBytes = format.bytesPerFrame
        var deviceCheckNanos = System.nanoTime()
        val size = IntByReference()
        val data = PointerByReference()
        val frames = IntByReference()
        val flags = IntByReference()
        val devicePos = LongByReference()
        val qpcPos = LongByReference()
        while (!stop.get()) {
            if (event != null) WinApi.kernel32.WaitForSingleObject(event, 10) else Thread.sleep(10)
            if (device != null && System.nanoTime() - deviceCheckNanos >= DEVICE_CHECK_NANOS) {
                deviceCheckNanos = System.nanoTime()
                val now = runCatching { defaultRenderId() }.getOrNull()
                if (now != null && now != device) throw IllegalStateException("the default output device changed")
            }
            while (!stop.get()) {
                check(call(capture, 5, size), "IAudioCaptureClient.GetNextPacketSize")
                if (size.value == 0) break
                check(call(capture, 3, data, frames, flags, devicePos, qpcPos), "IAudioCaptureClient.GetBuffer")
                val n = frames.value
                val bytes = if (flags.value and SILENT != 0 || data.value == null) ByteArray(n * frameBytes)
                else data.value.getByteArray(0, n * frameBytes)
                call(capture, 4, n)
                val now = System.nanoTime()
                // QPC position, 100 ns units: the same counter as System.nanoTime on Windows. Fall back to the
                // arrival time should it ever be absent or nonsense.
                val stamped = qpcPos.value * 100L
                val age = now - stamped
                val at = if (qpcPos.value > 0 && age in -20_000_000L..1_000_000_000L) stamped
                else now - n * 1_000_000_000L / format.sampleRate
                sink(at, bytes)
            }
        }
    }

    /** WAVEFORMATEX for plain PCM / IEEE float [f]. */
    private fun waveFormat(f: ShareAudio.Format): Memory {
        val bits = if (f.float) 32 else 16
        val block = f.channels * bits / 8
        return Memory(18).apply {
            clear()
            setShort(0, (if (f.float) 3 else 1).toShort())
            setShort(2, f.channels.toShort())
            setInt(4, f.sampleRate)
            setInt(8, f.sampleRate * block)
            setShort(12, block.toShort())
            setShort(14, bits.toShort())
            setShort(16, 0)
        }
    }

    /** Activates [iid] on the default output device (console role); the caller releases it. */
    private fun activateDefaultRender(iid: Memory): Pointer {
        val out = PointerByReference()
        check(
            WinApi.ole32.CoCreateInstance(WinApi.CLSID_MM_DEVICE_ENUMERATOR, null, WinApi.CLSCTX_ALL, WinApi.IID_IMM_DEVICE_ENUMERATOR, out),
            "CoCreateInstance(MMDeviceEnumerator)",
        )
        val enumerator = out.value
        try {
            check(call(enumerator, 4, 0 /* eRender */, 1 /* eMultimedia */, out), "GetDefaultAudioEndpoint")
            val device = out.value
            try {
                check(call(device, 3, iid, WinApi.CLSCTX_ALL, null, out), "IMMDevice.Activate")
                return out.value
            } finally {
                release(device)
            }
        } finally {
            release(enumerator)
        }
    }

    /** How often a whole-device capture checks that it still records the default output. */
    private const val DEVICE_CHECK_NANOS = 2_000_000_000L

    /** The default output device's endpoint id (multimedia role, as [activateDefaultRender]), or null when there is none. */
    private fun defaultRenderId(): String? {
        val out = PointerByReference()
        check(
            WinApi.ole32.CoCreateInstance(WinApi.CLSID_MM_DEVICE_ENUMERATOR, null, WinApi.CLSCTX_ALL, WinApi.IID_IMM_DEVICE_ENUMERATOR, out),
            "CoCreateInstance(MMDeviceEnumerator)",
        )
        val enumerator = out.value
        try {
            if (call(enumerator, 4, 0 /* eRender */, 1 /* eMultimedia */, out) < 0) return null
            val device = out.value
            try {
                // IMMDevice::GetId: a CoTaskMem string
                if (call(device, 5, out) < 0) return null
                val id = out.value ?: return null
                try {
                    return id.getWideString(0)
                } finally {
                    WinApi.ole32.CoTaskMemFree(id)
                }
            } finally {
                release(device)
            }
        } finally {
            release(enumerator)
        }
    }

    /** An IAudioClient capturing process [pid]'s tree ([include]) or everything but it. */
    private fun activateProcessLoopback(pid: Long, include: Boolean): Pointer {
        if (!processLoopback) throw IllegalStateException("process loopback needs Windows build 20348+")
        // AUDIOCLIENT_ACTIVATION_PARAMS { PROCESS_LOOPBACK, { pid, INCLUDE (0) / EXCLUDE (1) TARGET_PROCESS_TREE } }
        val params = Memory(12).apply {
            setInt(0, 1)
            setInt(4, pid.toInt())
            setInt(8, if (include) 0 else 1)
        }
        // PROPVARIANT { VT_BLOB, { cbSize, pBlobData } }
        val variant = Memory(if (Native.POINTER_SIZE == 8) 24L else 16L).apply {
            clear()
            setShort(0, 65)
            setInt(8, 12)
            setPointer(if (Native.POINTER_SIZE == 8) 16L else 12L, params)
        }
        val done = CountDownLatch(1)
        // WAITING -> COMPLETED (the callback took the operation) or ABANDONED (the caller gave up first): a completion
        // after the caller gave up takes no reference, so nothing is leaked and nothing is used after this returns
        val state = java.util.concurrent.atomic.AtomicInteger(WAITING)
        val operation = java.util.concurrent.atomic.AtomicReference<Pointer?>()
        val handler = WinApi.CompletionHandler { op ->
            if (state.compareAndSet(WAITING, COMPLETED)) {
                call(op, 1) // AddRef: kept past the callback
                operation.set(op)
                done.countDown()
            }
        }
        handler.keep(params, variant)
        val opOut = PointerByReference()
        try {
            check(
                WinApi.mmdevapi.ActivateAudioInterfaceAsync(
                    WString("VAD\\Process_Loopback"), WinApi.IID_IAUDIO_CLIENT, variant, handler.pointer, opOut,
                ),
                "ActivateAudioInterfaceAsync",
            )
            return finishActivation(done, state, operation::get, opOut)
        } finally {
            // Windows holds its own reference until it is done with the handler; this was only ours
            handler.releaseOwn()
        }
    }

    private const val WAITING = 0
    private const val COMPLETED = 1
    private const val ABANDONED = 2

    private fun finishActivation(
        done: CountDownLatch, state: java.util.concurrent.atomic.AtomicInteger, operation: () -> Pointer?,
        opOut: PointerByReference,
    ): Pointer {
        try {
            if (!done.await(3, TimeUnit.SECONDS)) {
                if (state.compareAndSet(WAITING, ABANDONED)) throw IllegalStateException("process loopback activation timed out")
                // Completed just now: the callback is about to count down
                done.await(1, TimeUnit.SECONDS)
            }
            val op = operation() ?: throw IllegalStateException("process loopback gave no operation")
            try {
                val hr = IntByReference()
                val unk = PointerByReference()
                check(call(op, 3, hr, unk), "GetActivateResult")
                check(hr.value, "process loopback activation")
                return unk.value ?: throw IllegalStateException("process loopback gave no client")
            } finally {
                release(op)
            }
        } finally {
            release(opOut.value)
        }
    }

    /**
     * Processes with an active (playing) audio session on the default output, as share choices: one per program,
     * each named by its file description and pointing at the topmost process of its own program (so "this app"
     * includes the helper processes a browser plays through). Excludes [selfPid] and system sounds.
     */
    fun playingApps(selfPid: Long): List<ShareAudio.Choice.App> {
        val pids = LinkedHashSet<Long>()
        val com = WinApi.comInit()
        try {
            val manager = activateDefaultRender(WinApi.IID_IAUDIO_SESSION_MANAGER2)
            try {
                val out = PointerByReference()
                check(call(manager, 5, out), "GetSessionEnumerator")
                val sessions = out.value
                try {
                    val count = IntByReference()
                    check(call(sessions, 3, count), "IAudioSessionEnumerator.GetCount")
                    for (i in 0 until count.value) {
                        if (call(sessions, 4, i, out) < 0) continue
                        val control = out.value
                        try {
                            if (call(control, 0, WinApi.IID_IAUDIO_SESSION_CONTROL2, out) < 0) continue
                            val control2 = out.value
                            try {
                                val state = IntByReference()
                                val pid = IntByReference()
                                val active = call(control2, 3, state) >= 0 && state.value == 1
                                // IsSystemSoundsSession: S_OK (0) for the system sounds session
                                val system = call(control2, 15) == WinApi.S_OK
                                if (active && !system && call(control2, 14, pid) >= 0 && pid.value != 0) {
                                    pids += pid.value.toLong() and 0xFFFFFFFFL
                                }
                            } finally {
                                release(control2)
                            }
                        } finally {
                            release(control)
                        }
                    }
                } finally {
                    release(sessions)
                }
            } finally {
                release(manager)
            }
        } finally {
            if (com) runCatching { Native.load("ole32", CoUninit::class.java).CoUninitialize() }
        }
        val apps = LinkedHashMap<Long, ShareAudio.Choice.App>()
        for (pid in pids) {
            val root = programRoot(pid)
            if (root == selfPid || isAncestor(selfPid, root)) continue
            apps.getOrPut(root) { ShareAudio.Choice.App(root, appName(root)) }
        }
        return apps.values.sortedBy { it.name.lowercase() }
    }

    private fun exeOf(pid: Long): String? =
        ProcessHandle.of(pid).flatMap { it.info().command() }.orElse(null)

    /** Walks up from [pid] while the parent runs the same executable: a browser's main process for its audio helper. */
    fun programRoot(pid: Long): Long {
        val exe = exeOf(pid) ?: return pid
        var current = ProcessHandle.of(pid).orElse(null) ?: return pid
        repeat(16) {
            val parent = current.parent().orElse(null) ?: return current.pid()
            if (parent.info().command().orElse(null)?.equals(exe, ignoreCase = true) != true) return current.pid()
            current = parent
        }
        return current.pid()
    }

    private fun isAncestor(pid: Long, ancestor: Long): Boolean {
        var current = ProcessHandle.of(pid).orElse(null) ?: return false
        repeat(32) {
            current = current.parent().orElse(null) ?: return false
            if (current.pid() == ancestor) return true
        }
        return false
    }

    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [pid]'s program name: its file description ("Google Chrome"), else the executable's name. */
    fun appName(pid: Long): String {
        val exe = exeOf(pid)
        if (exe == null) return "PID $pid"
        return names.getOrPut(exe.lowercase()) { ShareWindows.appName(runCatching { fileDescription(exe) }.getOrNull(), exe) }
    }

    private fun fileDescription(path: String): String? {
        val file = WString(path)
        val size = WinApi.version.GetFileVersionInfoSizeW(file, null)
        if (size <= 0) return null
        val block = Memory(size.toLong())
        if (!WinApi.version.GetFileVersionInfoW(file, 0, size, block)) return null
        val ptr = PointerByReference()
        val len = IntByReference()
        val languages = ArrayList<String>()
        if (WinApi.version.VerQueryValueW(block, WString("\\VarFileInfo\\Translation"), ptr, len) && len.value >= 4) {
            for (i in 0 until len.value / 4) {
                val lang = ptr.value.getShort(i * 4L).toInt() and 0xFFFF
                val page = ptr.value.getShort(i * 4L + 2).toInt() and 0xFFFF
                languages += String.format("%04x%04x", lang, page)
            }
        }
        languages.addAll(listOf("040904b0", "040904e4", "04090000"))
        for (l in languages) {
            if (WinApi.version.VerQueryValueW(block, WString("\\StringFileInfo\\$l\\FileDescription"), ptr, len) && len.value > 1) {
                return ptr.value.getWideString(0).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}
