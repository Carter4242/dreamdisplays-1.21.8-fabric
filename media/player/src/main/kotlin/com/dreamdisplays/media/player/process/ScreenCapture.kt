package com.dreamdisplays.media.player.process

import com.dreamdisplays.api.media.source.LiveStreamUrls
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import com.dreamdisplays.util.OsInfo
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Builds and probes the `FFmpeg` screen-capture + encode process behind the built-in "Share screen": one monitor,
 * captured at [Settings.fps], scaled to fit [Settings.maxHeight], encoded for the lowest latency the machine offers
 * (hardware H.264 when it works, else x264 `ultrafast` / `zerolatency`) and pushed to a live relay as MPEG-TS over SRT
 * (FLV for RTMP). On Windows a single window can be shared instead (Windows Graphics Capture). Sound ([ShareAudio])
 * joins as another input of the same process, picture and sound both on the wall clock, so they leave in step.
 *
 * On Windows each screen gets its own [Plan]: the first capture source and encoder that run that screen's real
 * pipeline (Desktop Duplication on the screen's own graphics adapter with that GPU's encoder, then the other hardware
 * encoders, then x264; GDI capture if Desktop Duplication cannot open the screen at all).
 */
object ScreenCapture {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Where the picture comes from. [GDIGRAB] is the Windows fallback when Desktop Duplication cannot open a screen
     * (and when a build lacks [GFXCAPTURE], which captures one window).
     */
    enum class Source(val ffmpegName: String) {
        DDAGRAB("ddagrab"), GDIGRAB("gdigrab"), AVFOUNDATION("avfoundation"), X11GRAB("x11grab"), GFXCAPTURE("gfxcapture")
    }

    /** Something to share: a whole [Monitor] or one [Window]. */
    sealed interface Target

    /** One top-level window ([hwnd]) of process [pid]: [app] is the program's friendly name, [title] the window's. */
    data class Window(val hwnd: Long, val pid: Long, val app: String, val title: String) : Target

    /**
     * One monitor. [index] is its position in GLFW's list (the primary first), or for Desktop Duplication the output
     * index on graphics adapter [adapter] (-1: the default adapter). [vendorId] is that adapter's PCI vendor (0:
     * unknown); with [byVendor] `FFmpeg` opens the adapter by that vendor rather than by index (immune to a process
     * seeing the adapters in another order). [deviceName] is the Windows screen (`\\.\DISPLAY1`) when known;
     * [x], [y], [width], [height] are its desktop rectangle unless [rectKnown] is false. [key] is the physical screen
     * (see [sameScreen]): on Windows its monitors' device interface names, on macOS `cg:<CGDirectDisplayID>`, on
     * Linux GLFW's name for the output; empty when unknown.
     */
    data class Monitor(
        val index: Int, val name: String, val x: Int, val y: Int, val width: Int, val height: Int, val adapter: Int = -1,
        val vendorId: Int = 0, val byVendor: Boolean = false, val deviceName: String = "", val rectKnown: Boolean = true,
        /** PCI `vvvv:dddd` of [adapter] and its name, when known. */
        val adapterPciId: String = "", val adapterName: String = "",
        val key: String = "",
        /**
         * `FFmpeg`'s index for [adapter] when it differs from the game's (-1: the same): DXGI orders adapters per
         * process, and a GPU preference set for the game (common on laptops) puts its GPU first only for the game.
         */
        val ffmpegAdapter: Int = -1,
    ) : Target

    /** H.264 encoders tried in order; the first that encodes a test picture here wins. */
    enum class Encoder(val ffmpegName: String, val hardware: Boolean) {
        NVENC("h264_nvenc", true),
        AMF("h264_amf", true),
        QSV("h264_qsv", true),
        VIDEOTOOLBOX("h264_videotoolbox", true),
        X264("libx264", false),
    }

    /** What to capture and how hard to encode it. */
    data class Settings(
        val fps: Int = 30,
        val bitrateKbps: Int = DEFAULT_KBPS_30,
        val maxWidth: Int = 1920,
        val maxHeight: Int = 1080,
        /** SRT latency (ms) for the publisher's leg when the publish URL sets none. */
        val latencyMs: Int = 60,
    ) {
        /** These settings within a server's limits (0: no limit): fewer frames, a smaller picture, the bitrate scaled. */
        fun limitedTo(maxFps: Int, maxHeight: Int): Settings {
            val f = if (maxFps > 0) fps.coerceAtMost(maxFps.coerceAtLeast(1)) else fps
            val h = if (maxHeight > 0) this.maxHeight.coerceAtMost(maxHeight.coerceAtLeast(144)) else this.maxHeight
            val w = (maxWidth.toLong() * h / this.maxHeight).toInt() and 1.inv()
            val kbps = (bitrateKbps.toLong() * f * h / (fps.toLong() * this.maxHeight)).toInt().coerceAtLeast(500)
            return copy(fps = f, maxWidth = w, maxHeight = h and 1.inv(), bitrateKbps = kbps.coerceAtMost(bitrateKbps))
        }

        /** These settings at the server's bitrate [kbps] (0: keep), kept within [MIN_KBPS]..[MAX_KBPS]. */
        fun withServerBitrate(kbps: Int): Settings =
            if (kbps <= 0) this else copy(bitrateKbps = kbps.coerceIn(MIN_KBPS, MAX_KBPS))

        companion object {
            /** The defaults at [fps]: [DEFAULT_KBPS_60] from 60 fps up, else [DEFAULT_KBPS_30]. */
            fun forFps(fps: Int): Settings = Settings(fps = fps, bitrateKbps = if (fps >= 60) DEFAULT_KBPS_60 else DEFAULT_KBPS_30)
        }
    }

    // ---- 60 fps ----

    /** Encode time per frame (ms) at or below which a plan's encoder is trusted with 60 fps (a frame lasts 16.7). */
    const val SIXTY_BUDGET_MS = 8.0

    private val sixty = ConcurrentHashMap<String, Double>()

    /**
     * How long [encoder] takes per frame here at 60 fps and [settings]' size (ms), measured once on a synthetic moving
     * picture: the run with the encoder less the same run without it. Blocks ~2 s the first time; off the render thread.
     */
    fun encodeMsPerFrame(ffmpeg: String, encoder: Encoder, settings: Settings): Double =
        sixty.getOrPut("$ffmpeg|$encoder|${settings.maxWidth}x${settings.maxHeight}") {
            val frames = 120
            val pixFmt = if (encoder.hardware) "nv12" else "yuv420p"
            val src = listOf(ffmpeg, "-hide_banner", "-nostats", "-benchmark", "-f", "lavfi", "-i",
                "testsrc2=s=${settings.maxWidth}x${settings.maxHeight}:r=60", "-frames:v", frames.toString(), "-pix_fmt", pixFmt)
            val bare = exec(src + listOf("-f", "null", "-"), 20)
            val coded = exec(src + encoderArgs(encoder, settings.copy(fps = 60)) + listOf("-g", "60", "-bf", "0", "-f", "null", "-"), 20)
            val a = benchRealSeconds(bare.output)
            val b = benchRealSeconds(coded.output)
            val ms = if (!bare.ok || !coded.ok || a == null || b == null) Double.MAX_VALUE else (b - a).coerceAtLeast(0.0) * 1000.0 / frames
            logger.info("Screen share: {} encodes a {}x{} frame in {} ms.", encoder.ffmpegName, settings.maxWidth, settings.maxHeight,
                if (ms == Double.MAX_VALUE) "?" else String.format(Locale.ROOT, "%.2f", ms))
            ms
        }

    /** `-benchmark`'s `rtime=` (wall seconds of the run), or null. */
    internal fun benchRealSeconds(output: String): Double? =
        Regex("""bench: .*rtime=([0-9.]+)s""").find(output)?.groupValues?.get(1)?.toDoubleOrNull()

    /**
     * The frame rate to share at: 60 where the source keeps up with it, [encoder] is a hardware one that measured
     * within [SIXTY_BUDGET_MS] a frame, and the server allows it ([serverMaxFps], 0 = no cap); else 30. x264 stays at
     * 30: its benchmark measures throughput on every core, which in game it shares with Minecraft's threads.
     */
    fun chooseFps(ffmpeg: String, source: Source, encoder: Encoder, maxHeight: Int, serverMaxFps: Int): Int {
        val cap = if (serverMaxFps > 0) serverMaxFps else 60
        if (!sixtyPossible(cap, source, encoder)) return minOf(30, cap)
        val probe = Settings.forFps(60).limitedTo(0, maxHeight)
        return if (encodeMsPerFrame(ffmpeg, encoder, probe) <= SIXTY_BUDGET_MS) 60 else 30
    }

    /** 60 fps can be considered at all: allowed by [cap], a source that keeps up, a hardware encoder. */
    internal fun sixtyPossible(cap: Int, source: Source, encoder: Encoder): Boolean =
        cap >= 60 && source != Source.GDIGRAB && source != Source.X11GRAB && encoder.hardware

    /** How one screen is shared: where the picture comes from and what encodes it. */
    data class Plan(val source: Source, val encoder: Encoder) {
        override fun toString() = "${source.ffmpegName}+${encoder.ffmpegName}"
    }

    /** One pipeline test while choosing a [Plan]: [adapter] as `FFmpeg` was told to open it, [error] when it failed. */
    data class Attempt(val plan: Plan, val adapter: String, val ok: Boolean, val error: String = "", val elapsedMs: Long = 0)

    /** The [Plan] chosen for a screen, the exact [monitor] variant it runs on, and the tests behind it. */
    data class Choice(val monitor: Monitor, val plan: Plan, val attempts: List<Attempt>)

    /** The capture source on this OS, or null where there is none (Linux under Wayland: see [linuxSource]). */
    fun sourceForThisOs(): Source? = when {
        OsInfo.isWindows -> Source.DDAGRAB
        OsInfo.isMac -> Source.AVFOUNDATION
        OsInfo.isLinux -> linuxSource(System::getenv)
        else -> null
    }

    /**
     * Linux's capture source given the environment [env]: x11grab under X11 only. A Wayland session sets `DISPLAY`
     * too (for XWayland, which the game itself runs on), but x11grab there sees only X11 windows: every native
     * Wayland window would be shared as black.
     */
    internal fun linuxSource(env: (String) -> String?): Source? = when {
        !env("WAYLAND_DISPLAY").isNullOrBlank() -> null
        env("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true) -> null
        !env("DISPLAY").isNullOrBlank() -> Source.X11GRAB
        else -> null
    }

    /** Encoders worth probing on this OS, best first; [Encoder.X264] always comes last. */
    fun candidates(): List<Encoder> = when {
        OsInfo.isWindows -> [Encoder.NVENC, Encoder.AMF, Encoder.QSV, Encoder.X264]
        OsInfo.isMac -> [Encoder.VIDEOTOOLBOX, Encoder.X264]
        else -> [Encoder.NVENC, Encoder.X264]
    }

    @Volatile
    private var usable: Pair<String, List<Encoder>>? = null

    /**
     * True when the server-supplied [publishUrl] is a relay this client may stream the screen to: a well-formed live
     * relay URL (`srt` / `rtmp(s)` / `rtsp`) on a public host. Without it `FFmpeg` takes any output: a local file
     * path, `file:`, `http://` or `tcp://` to a host on the player's LAN. Resolves the host (call off the render thread).
     */
    fun publishTargetAllowed(publishUrl: String): Boolean =
        LiveStreamUrls.isValid(publishUrl) && MediaHostGuard.isAllowed(publishUrl)

    /**
     * The encoders in [candidates] that open at all on this machine, best first, [Encoder.X264] last (a build lists
     * `h264_nvenc` whether or not there is an NVIDIA GPU, or a driver new enough for it). Blocks for a few short
     * `FFmpeg` runs the first time; call off the render thread.
     */
    fun usableEncoders(ffmpeg: String): List<Encoder> {
        usable?.takeIf { it.first == ffmpeg }?.let { return it.second }
        val list = candidates().filter { it == Encoder.X264 || works(ffmpeg, it) }
        logger.info("Screen share encoders that open here: {}.", list.joinToString { it.ffmpegName })
        usable = ffmpeg to list
        return list
    }

    /** The best encoder that opens here, wherever the picture comes from (macOS / Linux). */
    fun pickEncoder(ffmpeg: String): Encoder = usableEncoders(ffmpeg).first()

    private fun works(ffmpeg: String, encoder: Encoder): Boolean = exec(
        listOf(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-nostats",
            "-f", "lavfi", "-i", "color=c=black:s=640x360:r=30", "-frames:v", "3",
            "-pix_fmt", "nv12", "-c:v", encoder.ffmpegName, "-f", "null", "-",
        )
    ).also { if (!it.ok) logger.debug("Encoder {} unusable here: {}", encoder.ffmpegName, it.tail) }.ok

    // ---- Per-screen plans ----

    private val choices = ConcurrentHashMap<String, Choice>()

    private fun choiceKey(ffmpeg: String, monitor: Monitor, softwareOnly: Boolean) =
        "$ffmpeg|$softwareOnly|" + monitor.deviceName.ifEmpty { "${monitor.adapter}:${monitor.index}@${monitor.x},${monitor.y}" }

    /** The plan already chosen for [monitor], or null before [choose] ran for it. */
    fun knownChoice(ffmpeg: String, monitor: Monitor, softwareOnly: Boolean = false): Choice? =
        choices[choiceKey(ffmpeg, monitor, softwareOnly)]

    /** The share of [monitor] ran fine with [choice] (e.g. a fallback after the first plan failed): keep it. */
    fun rememberChoice(ffmpeg: String, monitor: Monitor, softwareOnly: Boolean, choice: Choice) {
        choices[choiceKey(ffmpeg, monitor, softwareOnly)] = choice
    }

    /**
     * How to share [monitor]: the first source and encoder that encode two frames of its real pipeline. On macOS and
     * Linux that is the OS source with the best encoder. On Windows: Desktop Duplication on the screen's own adapter
     * (or a duplicate of the screen on another adapter) with that GPU's encoder, the other hardware encoders, then
     * x264; then GDI capture. [softwareOnly] (the server asked for it) tries x264 only. Cached per screen; blocks for
     * a few `FFmpeg` runs the first time, so call off the render thread. Null when DXGI no longer lists that screen
     * (Windows): nothing else is ever captured in its place.
     */
    fun choose(ffmpeg: String, monitor: Monitor, softwareOnly: Boolean = false): Choice? {
        val source = sourceForThisOs() ?: Source.DDAGRAB
        // Nothing to work out per screen there: the screen is the one picked now, found again at each launch
        if (source != Source.DDAGRAB) return Choice(monitor, Plan(source, if (softwareOnly) Encoder.X264 else pickEncoder(ffmpeg)), [])
        // A plan cached under this screen name for another monitor (the name went to it since) is not this screen's
        knownChoice(ffmpeg, monitor, softwareOnly)?.takeIf { it.monitor.key == monitor.key }?.let { cached ->
            // The screen as it is now: monitors sleep, get plugged, rearranged, resized while the game stays open
            val fresh = when (val l = locateOnWindows(cached.monitor)) {
                Located.Unknown -> return cached
                Located.Gone -> []
                is Located.Here -> l.ways
            }
            rebind(cached, fresh)?.let { now ->
                if (now != cached) {
                    logger.info("Screen share plan for {}: {} now at output {} of {}, {},{} {}x{}.", monitor.deviceName,
                        now.plan, now.monitor.index, adapterLabel(now.monitor), now.monitor.x, now.monitor.y,
                        now.monitor.width, now.monitor.height)
                    choices[choiceKey(ffmpeg, monitor, softwareOnly)] = now
                }
                return now
            }
            // Its adapter does not show the screen any more: work the plan out again
            choices.remove(choiceKey(ffmpeg, monitor, softwareOnly))
        }
        val choice = chooseOnWindows(ffmpeg, monitor, softwareOnly) ?: return null
        logger.info(
            "Screen share plan for {}: {} on {} (tried {}).", monitor.deviceName.ifEmpty { monitor.name },
            choice.plan, adapterLabel(choice.monitor), choice.attempts.joinToString { "${it.plan}@${it.adapter}=${if (it.ok) "ok" else "fail"}" },
        )
        choices[choiceKey(ffmpeg, monitor, softwareOnly)] = choice
        return choice
    }

    /**
     * [choice] for its screen as DXGI lists it now ([fresh]: every way to reach that screen): its plan on the same
     * adapter, opened the same way, at that adapter's current output index and the screen's current rectangle. Null
     * when that adapter no longer shows the screen. A screen not known by its Windows name cannot be checked and is
     * kept as it is.
     */
    fun rebind(choice: Choice, fresh: List<Monitor>): Choice? {
        val m = choice.monitor
        if (m.deviceName.isEmpty()) return choice
        val sameWay = fresh.filter { sameScreen(m, it) && it.byVendor == m.byVendor && it.adapterPciId == m.adapterPciId }
        // Adapter indexes follow the primary screen's adapter: the same index first, else that GPU wherever it is now
        val now = sameWay.firstOrNull { it.adapter == m.adapter } ?: sameWay.firstOrNull() ?: return null
        val rebound = now.copy(name = m.name, ffmpegAdapter = m.ffmpegAdapter)
        return if (rebound == m) choice else choice.copy(monitor = rebound)
    }

    // ---- Which screen is which ----

    /**
     * True when [a] and [b] are the same physical screen: the same Windows screen name (when known) showing the same
     * monitors, or the same macOS display / Linux output. A screen known by neither cannot be told apart from another.
     */
    fun sameScreen(a: Monitor, b: Monitor): Boolean =
        identifiable(a) && a.deviceName == b.deviceName && a.key == b.key

    /** True when [monitor] carries something that names its screen (see [sameScreen]). */
    fun identifiable(monitor: Monitor): Boolean = monitor.deviceName.isNotEmpty() || monitor.key.isNotEmpty()

    /** A Windows screen's [Monitor.key] from its monitors' device interface names. */
    fun monitorKey(interfaceNames: List<String>): String = interfaceNames.map { it.lowercase(Locale.ROOT) }.sorted().joinToString("|")

    /** Where the screen a share was picked as is now. */
    sealed interface Located {
        /** Found: [ways] reach it now (on Windows one per adapter way, see [dxgiGroups]; elsewhere the one entry). */
        data class Here(val ways: List<Monitor>) : Located

        /** The screen list was read and that screen is not on it: unplugged, switched off, or another monitor now. */
        data object Gone : Located

        /** Cannot be told now: the list could not be read, or the screen carries no identity. */
        data object Unknown : Located
    }

    /** Finds [picked] in [screens] (every screen as listed now; null when the list could not be read). */
    fun locate(picked: Monitor, screens: List<Monitor>?): Located {
        if (!identifiable(picked) || screens == null) return Located.Unknown
        val ways = screens.filter { sameScreen(picked, it) }.map { it.copy(name = picked.name) }
        return if (ways.isEmpty()) Located.Gone else Located.Here(ways)
    }

    /** [monitor]'s screen as DXGI lists it now (Windows; [Located.Unknown] elsewhere or when DXGI cannot be read). */
    fun locateOnWindows(monitor: Monitor): Located =
        if (sourceForThisOs() != Source.DDAGRAB || monitor.deviceName.isEmpty()) Located.Unknown
        else locate(monitor, dxgiGroups()?.flatten())

    /**
     * [choice] on its screen as it is now (see [rebind]), without probing anything; null when the screen cannot be
     * positively found where its plan reaches it.
     */
    fun current(choice: Choice): Choice? =
        (locateOnWindows(choice.monitor) as? Located.Here)?.let { rebind(choice, it.ways) }

    /**
     * macOS: avfoundation's `Capture screen` index of [picked] given the active display list [activeIds] (see
     * [MacDisplays]); null when that display is not active any more or [picked] names none.
     */
    fun avfoundationIndex(picked: Monitor, activeIds: List<Int>): Int? {
        val id = picked.key.removePrefix(MAC_KEY).takeIf { picked.key.startsWith(MAC_KEY) }?.toLongOrNull() ?: return null
        return activeIds.indexOfFirst { Integer.toUnsignedLong(it) == id }.takeIf { it >= 0 }
    }

    /** [Monitor.key] prefix of a macOS display (its CGDirectDisplayID follows, unsigned). */
    const val MAC_KEY = "cg:"

    /**
     * True while a rectangle capture of [picked] ([baseline]: the screen that covered its rectangle when the capture
     * started, from the same source as [now]) still shows that screen: the screen covering it now is the same one,
     * at the same place and size. Anything else (another screen moved in, the screen moved, resized, went away)
     * means the fixed rectangle no longer holds what was picked.
     */
    fun rectStillShows(picked: Monitor, baseline: Monitor?, now: Monitor?): Boolean =
        baseline != null && now != null && sameScreen(picked, baseline) && sameScreen(picked, now) &&
                now.x == baseline.x && now.y == baseline.y && now.width == baseline.width && now.height == baseline.height

    /**
     * True when the screen [picked] still sits exactly at its rectangle in [screens] (every screen listed now, as
     * [picked] was): what a fixed-rectangle capture (x11grab) of it needs.
     */
    fun stillAt(picked: Monitor, screens: List<Monitor>): Boolean =
        (locate(picked, screens) as? Located.Here)?.ways?.any {
            it.x == picked.x && it.y == picked.y && it.width == picked.width && it.height == picked.height
        } == true

    /**
     * [monitor] as `FFmpeg` reaches it now: itself when [confirm] says so, else on the adapter index `FFmpeg` gives
     * its GPU now (the adapter order changed, e.g. the primary screen moved to the other GPU), confirmed; null when
     * neither opens that screen. For Desktop Duplication by adapter index; off the render thread.
     */
    fun confirmed(ffmpeg: String, monitor: Monitor): Monitor? {
        if (confirm(ffmpeg, monitor)) return monitor
        if (monitor.adapterPciId.isEmpty()) return null
        val j = ffmpegAdapterOf(ffmpeg, monitor.adapterPciId, skip = adapterIndexFor(monitor)) ?: return null
        return monitor.copy(ffmpegAdapter = j).takeIf { confirm(ffmpeg, it) }
    }

    /** The adapter index `FFmpeg` is told for [monitor]. */
    internal fun adapterIndexFor(monitor: Monitor): Int = if (monitor.ffmpegAdapter >= 0) monitor.ffmpegAdapter else monitor.adapter

    /**
     * `FFmpeg`'s index for the GPU with PCI id [pciId], trying each adapter index but [skip] (one short run each, the
     * device only); null when none (or two identical GPUs, which cannot be told apart this way, both match).
     */
    private fun ffmpegAdapterOf(ffmpeg: String, pciId: String, skip: Int): Int? {
        val count = runCatching { Dxgi.enumerate().adapters.size }.getOrDefault(0)
        val matches = (0 until count).filter { j ->
            j != skip && probeScreen(ffmpeg, Monitor(0, "", 0, 0, 0, 0, adapter = j, rectKnown = false))?.pciId == pciId
        }
        return matches.singleOrNull()
    }

    /**
     * Opens [monitor] with Desktop Duplication the way its plan does (one frame) and checks `FFmpeg` got the
     * adapter ([Monitor.adapterPciId]) and a screen of its size: DXGI numbers adapters per process (a GPU preference
     * set for the game reorders them), so an index the game read can name another GPU in `FFmpeg`. Blocks up to a
     * few seconds; off the render thread.
     */
    fun confirm(ffmpeg: String, monitor: Monitor): Boolean {
        val r = probeScreen(ffmpeg, monitor) ?: return false
        val size = r.size ?: return false
        val pciOk = monitor.adapterPciId.isEmpty() || r.pciId == null || r.pciId == monitor.adapterPciId
        return pciOk && size.first == monitor.width && size.second == monitor.height
    }

    /**
     * True when `FFmpeg`'s [output] says Desktop Duplication lost the screen after capturing it: a secure desktop
     * (UAC, Ctrl+Alt+Del), a mode or HDR change, a hot-plug, a fullscreen app taking the output. The screen comes back.
     */
    fun isCaptureLost(output: String): Boolean = CAPTURE_LOST.containsMatchIn(output)

    private val CAPTURE_LOST = Regex("(?i)AcquireNextFrame failed|887a0026|ACCESS_LOST")

    private fun chooseOnWindows(ffmpeg: String, monitor: Monitor, softwareOnly: Boolean): Choice? {
        val attempts = ArrayList<Attempt>()
        val variants = variantsOf(monitor)
        if (variants.isEmpty()) {
            logger.info("Screen share: {} is no longer connected.", monitor.deviceName)
            return null
        }
        // Desktop Duplication, on each adapter that shows this screen
        for (variant in variants) {
            var m = variant
            for (encoder in encodersFor(ffmpeg, m, softwareOnly)) {
                val plan = Plan(Source.DDAGRAB, encoder)
                var r = probePipeline(ffmpeg, plan, m)
                var wrongAdapter = r.pciId != null && m.adapterPciId.isNotEmpty() && r.pciId != m.adapterPciId
                if (wrongAdapter && !m.byVendor && m.ffmpegAdapter < 0) {
                    // FFmpeg numbers this GPU differently (two GPUs of one vendor, a GPU preference set for the game)
                    ffmpegAdapterOf(ffmpeg, m.adapterPciId, skip = m.adapter)?.let { j ->
                        m = m.copy(ffmpegAdapter = j)
                        r = probePipeline(ffmpeg, plan, m)
                        wrongAdapter = r.pciId != null && r.pciId != m.adapterPciId
                    }
                }
                val ok = r.ok && !wrongAdapter
                val error = if (wrongAdapter) "opened ${r.pciId}, not ${m.adapterPciId}" else r.tail
                attempts += Attempt(plan, adapterLabel(m), ok, if (ok) "" else error, r.elapsedMs)
                if (ok) return Choice(m, plan, attempts)
                // The capture itself (or the adapter) failed: no other encoder will help on this variant
                if (wrongAdapter || !isEncoderOpenFailure(r.tail)) break
            }
        }
        // GDI: copies the desktop rectangle, any adapter; hardware encoders then open a device of their own
        val first = variants.first()
        if (first.rectKnown && first.width > 0 && first.height > 0) {
            for (encoder in encodersFor(ffmpeg, first, softwareOnly)) {
                val plan = Plan(Source.GDIGRAB, encoder)
                val r = probePipeline(ffmpeg, plan, first)
                attempts += Attempt(plan, "gdi", r.ok, if (r.ok) "" else r.tail, r.elapsedMs)
                if (r.ok) return Choice(first, plan, attempts)
                if (!isEncoderOpenFailure(r.tail)) break
            }
            return Choice(first, Plan(Source.GDIGRAB, Encoder.X264), attempts)
        }
        return Choice(first, Plan(Source.DDAGRAB, Encoder.X264), attempts)
    }

    /** Hardware encoders for [monitor]'s GPU first (on another GPU's device they fail), then the others, then x264. */
    private fun encodersFor(ffmpeg: String, monitor: Monitor, softwareOnly: Boolean): List<Encoder> {
        if (softwareOnly) return [Encoder.X264]
        val own = when (monitor.vendorId) {
            0x10DE -> Encoder.NVENC
            0x1002, 0x1022 -> Encoder.AMF
            0x8086 -> Encoder.QSV
            else -> null
        }
        return usableEncoders(ffmpeg).sortedBy { if (it == own) 0 else if (it.hardware) 1 else 2 }
    }

    /** The safe plan to retry with after [plan] failed at once: x264, and GDI capture too unless only the encoder failed. */
    fun safePlan(plan: Plan, monitor: Monitor, encoderFailed: Boolean): Plan? {
        val gdi = plan.source == Source.DDAGRAB && monitor.rectKnown && monitor.width > 0
        return when {
            plan.encoder != Encoder.X264 && (encoderFailed || !gdi) -> Plan(plan.source, Encoder.X264)
            gdi -> Plan(Source.GDIGRAB, Encoder.X264)
            plan.encoder != Encoder.X264 -> Plan(plan.source, Encoder.X264)
            else -> null
        }
    }

    internal data class ProbeResult(val ok: Boolean, val pciId: String?, val tail: String, val elapsedMs: Long)

    /** Encodes two frames of [plan] on [monitor] to nowhere. */
    internal fun probePipeline(ffmpeg: String, plan: Plan, monitor: Monitor, settings: Settings = Settings()): ProbeResult {
        val args: List<String> = listOf(ffmpeg, "-hide_banner", "-loglevel", "verbose", "-nostats") +
                inputArgs(plan.source, monitor, plan.encoder, settings) + listOf("-an") + encoderArgs(plan.encoder, settings) +
                listOf("-frames:v", "2", "-f", "null", "-")
        val r = exec(args)
        val pci = USING_DEVICE.find(r.output)?.let { "${it.groupValues[1]}:${it.groupValues[2]}".lowercase() }
        return ProbeResult(r.ok, pci, r.tail, r.elapsedMs)
    }

    private class RunResult(val ok: Boolean, val output: String, val tail: String, val elapsedMs: Long)

    private fun exec(args: List<String>, timeoutSeconds: Long = 10): RunResult {
        val t0 = System.nanoTime()
        return runCatching {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            // A capture probe dies with the game too (a stuck one would otherwise run on after a crash)
            CaptureLifetime.adopt(p)
            runCatching { p.outputStream.close() }
            val out = StringBuilder()
            val reader = Thread({
                runCatching { p.inputStream.bufferedReader().forEachLine { if (out.length < 64_000) out.appendLine(it) } }
            }, "DreamDisplays-ffmpeg-probe-out").apply { isDaemon = true; start() }
            val done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!done) p.destroyForcibly()
            reader.join(1_000)
            val text = out.toString()
            val ms = (System.nanoTime() - t0) / 1_000_000
            RunResult(done && p.exitValue() == 0, text, if (done) errorTail(text) else "timed out after ${timeoutSeconds}s", ms)
        }.getOrElse { RunResult(false, "", it.message ?: it.javaClass.simpleName, (System.nanoTime() - t0) / 1_000_000) }
    }

    /** The lines of `FFmpeg` output that say what went wrong (at most the last 4). */
    fun errorTail(output: String): String = output.lineSequence()
        .filter { ERROR_LINE.containsMatchIn(it) }
        .toList().takeLast(4).joinToString("\n") { it.trim().take(300) }

    private val ERROR_LINE = Regex("(?i)error|fail|unable|cannot|could not|not support|no such|invalid|denied|unsupported")
    private val USING_DEVICE = Regex("""Using device ([0-9a-fA-F]{4}):([0-9a-fA-F]{4})""")

    /**
     * True when `FFmpeg`'s [output] says the encoder would not open (or its device), as opposed to the capture or the
     * relay failing.
     */
    fun isEncoderOpenFailure(output: String): Boolean = ENCODER_OPEN_FAILURE.containsMatchIn(output)

    private val ENCODER_OPEN_FAILURE = Regex(
        "Error while opening encoder|Could not open encoder|Failed to create derived|AMF failed to initiali[sz]e|" +
                "nvenc API version|OpenEncodeSessionEx failed|No NVENC capable devices|Error creating a MFX session|" +
                "Cannot load nvcuda|Cannot load amfrt|\\[(?:h264_(?:nvenc|amf|qsv|videotoolbox)|libx264)[^\\]]*\\][^\\n]*(?:error|fail)",
        RegexOption.IGNORE_CASE,
    )

    /** How a capture's publish to the relay failed (see [relayFailure]). */
    enum class RelayFailure {
        /** The relay turned this publisher away (its key or credentials, an ended share): retrying cannot help. */
        REFUSED,

        /** The relay could not be reached, or dropped the connection: may be passing. */
        UNREACHABLE,
    }

    /**
     * What `FFmpeg`'s [output] says about the relay, or null when the relay is not what failed (the capture or the
     * encoder did, which a different plan can fix). Neither relay case says anything about the screen's plan.
     */
    fun relayFailure(output: String): RelayFailure? = when {
        isEncoderOpenFailure(output) || CAPTURE_FAILURE.containsMatchIn(output) -> null
        RELAY_REFUSED.containsMatchIn(output) -> RelayFailure.REFUSED
        RELAY_UNREACHABLE.containsMatchIn(output) -> RelayFailure.UNREACHABLE
        else -> null
    }

    /** A capture source's own error line (`[Parsed_ddagrab_0 @ ...]`, `[gdigrab @ ...]`, ...). */
    private val CAPTURE_FAILURE = Regex("""(?i)\[(?:Parsed_)?(?:ddagrab|gfxcapture|gdigrab|x11grab|avfoundation)""")

    /** The relay said no: an RTMP status error (MediaMTX rejecting the publish), HTTP-style auth codes, SRT rejection. */
    private val RELAY_REFUSED = Regex(
        """(?i)Server error|unauthori[sz]ed|authentication failed|auth(?:entication)? error|\b401\b|\b403\b|forbidden|""" +
            """Connect\.Rejected|Publish\.BadName|connection rejected|rejected by the server""",
    )

    private val RELAY_UNREACHABLE = Regex(
        """(?i)Connection refused|Connection reset|Connection timed out|timed out|Network is unreachable|No route to host|""" +
            """Failed to resolve|Name or service not known|getaddrinfo|Broken pipe|Input/output error|End of file|""" +
            """Error opening output|Error writing trailer|Cannot open connection|handshake|\btls\b""",
    )

    // ---- Windows ----

    /**
     * How [ffmpeg] captures a single window: Windows Graphics Capture (`gfxcapture`, FFmpeg 8+: GPU frames, covered
     * windows included) when it has it, else GDI (`gdigrab`, which shows hardware-drawn windows black). [dupFrames]:
     * the build repeats the last frame of a window that draws nothing, rather than stalling the stream.
     */
    data class WindowCapture(val source: Source, val dupFrames: Boolean)

    @Volatile
    private var windowCapture: Pair<String, WindowCapture>? = null

    /** [WindowCapture] for [ffmpeg]; one short `FFmpeg` run the first time, call off the render thread. */
    fun windowCapture(ffmpeg: String): WindowCapture {
        windowCapture?.takeIf { it.first == ffmpeg }?.let { return it.second }
        val found = parseWindowCapture(exec(listOf(ffmpeg, "-hide_banner", "-h", "filter=gfxcapture")).output)
        logger.info("Screen share window capture: {}{}.", found.source.ffmpegName, if (found.dupFrames) " (dup_frames)" else "")
        windowCapture = ffmpeg to found
        return found
    }

    /** Reads `ffmpeg -h filter=gfxcapture`: an unknown filter prints no option table. */
    internal fun parseWindowCapture(help: String): WindowCapture =
        if ("hwnd" in help && "gfxcapture AVOptions" in help) WindowCapture(Source.GFXCAPTURE, "dup_frames" in help)
        else WindowCapture(Source.GDIGRAB, dupFrames = true)

    /**
     * How to share [window]: its capture source with the best encoder that opens here (the capture hands frames over
     * on the CPU, so any encoder takes them), x264 only when [softwareOnly].
     */
    fun windowPlan(ffmpeg: String, softwareOnly: Boolean = false): Plan =
        Plan(windowCapture(ffmpeg).source, if (softwareOnly) Encoder.X264 else pickEncoder(ffmpeg))

    /**
     * The wall clock (microseconds since the epoch, the clock `FFmpeg`'s `av_gettime` and `RTCTIME` read) as of now:
     * the share's clock base when it carries sound, see [args].
     */
    fun wallClockMicros(): Long {
        val now = java.time.Instant.now()
        return now.epochSecond * 1_000_000L + now.nano / 1_000
    }

    // ---- Arguments ----

    /**
     * Full `FFmpeg` argv capturing [target] with [source], encoding with [encoder], publishing to [publishUrl], with
     * sound from [audio] when set. With a [clockBaseMicros] (or sound) every timestamp is the wall clock less that base:
     * each capture stamps its own frames, so neither input waits for the other to start, and the stream's PTS says
     * when each frame was captured (see `ShareTimeline`). [ShareAudio.Input.Pipe]'s first sample stands for the wall
     * clock instant [audioStartMicros].
     */
    fun args(
        ffmpeg: String, source: Source, target: Target, encoder: Encoder, publishUrl: String,
        settings: Settings = Settings(), audio: ShareAudio.Input? = null, clockBaseMicros: Long = 0,
        windowCapture: WindowCapture = WindowCapture(Source.GFXCAPTURE, dupFrames = false),
        audioStartMicros: Long = clockBaseMicros,
    ): List<String> = buildList {
        add(ffmpeg)
        addAll(["-hide_banner", "-loglevel", "error", "-nostats"])
        // A window without sound still carries a silent track: a still window sends no frames, and without any packets
        // the viewers' connection times out and reconnects (a screen keeps sending frames, and skips the audio delay)
        val sound = audio ?: if (target is Window) ShareAudio.Input.Silence else null
        val clocked = sound != null || clockBaseMicros > 0
        val video = videoArgs(source, target, encoder, settings, if (clocked) clockBaseMicros else null, windowCapture)
        addAll(video.inputs)
        // Inputs first, then everything about the output
        if (sound != null) audioInput(sound, clockBaseMicros, audioStartMicros)
        addAll(video.output)
        val fps = settings.fps.toString()
        if (sound == null) {
            add("-an")
        } else {
            if (video.inputCount > 0) addAll(["-map", "0:v:0"])
            addAll(["-map", "${video.inputCount}:a:0"])
        }
        if (clocked) addAll(["-copyts", "-fps_mode:v", "passthrough", "-enc_time_base:v", "1:1000"])
        // The silent track is encoded like any share sound (AAC at 96 kHz)
        if (sound != null) audioOutput()
        addAll(encoderArgs(encoder, settings))
        // A keyframe every second (a viewer joining waits at most that long) and no B-frames (each one is a frame
        // of delay at the encoder)
        addAll(["-g", fps, "-keyint_min", fps, "-bf", "0"])
        // A window sends frames only as it draws: keyframes by time, so a viewer joining still waits at most a second
        // (relative to the last one: t is the capture clock, not zero at the start)
        if (target is Window) addAll(["-force_key_frames", KEYFRAME_EVERY_SECOND])
        val dest = LiveStreamUrls.publishUrl(publishUrl, settings.latencyMs)
        val muxer = publishMuxer(dest)
        addAll(["-flush_packets", "1", "-muxdelay", "0", "-muxpreload", "0"])
        // Picture and sound leave interleaved (so a viewer gets them together), but a stream that stalls (a window
        // minimized without dup_frames) holds the other back at most this long rather than the default 10 s
        if (sound != null) addAll(["-max_interleave_delta", MAX_INTERLEAVE_MICROS])
        // MPEG-TS: a video PES with its length written goes out only once the frame is complete (~30 ms later)
        if (muxer == "mpegts") addAll(["-omit_video_pes_length", "0"])
        // RTSP publishes through its own muxer (an RTSP URL is no output protocol), over TCP: no lost UDP packets
        if (muxer == "rtsp") addAll(["-rtsp_transport", "tcp"])
        addAll(["-f", muxer, dest])
    }

    /** The muxer for publishing to [dest]: FLV for RTMP(S), RTSP's own for RTSP, MPEG-TS (SRT) otherwise. */
    internal fun publishMuxer(dest: String): String = when (LiveStreamUrls.schemeOf(dest)) {
        "rtmp", "rtmps" -> "flv"
        "rtsp" -> "rtsp"
        else -> "mpegts"
    }

    /** How long one stream may run ahead of a stalled other at the muxer (microseconds). */
    internal const val MAX_INTERLEAVE_MICROS = "150000"

    /** The capture half of [args] without sound: where the picture comes from, scaled and converted for [encoder]. */
    internal fun inputArgs(source: Source, target: Target, encoder: Encoder, settings: Settings): List<String> =
        videoArgs(source, target, encoder, settings, null, WindowCapture(Source.GFXCAPTURE, false)).let { it.inputs + it.output }

    /**
     * The picture: [inputs] (options and `-i` inputs, [inputCount] of them) and [output] (filters, mapping). Sources
     * inside `-filter_complex` are no input at all.
     */
    private class VideoArgs(val inputs: List<String>, val output: List<String>, val inputCount: Int)

    /**
     * The picture's arguments; with a [clockBase] (sound on) its timestamps are the wall clock less that base: by
     * `setpts` on `RTCTIME` for filter sources (which count from their first frame), by `-itsoffset` for the
     * devices that already stamp the wall clock (gdigrab, x11grab).
     */
    private fun videoArgs(
        source: Source, target: Target, encoder: Encoder, settings: Settings, clockBase: Long?,
        windowCapture: WindowCapture,
    ): VideoArgs {
        val inputs = ArrayList<String>()
        val output = ArrayList<String>()
        val fps = settings.fps.toString()
        val fit = "scale=w='min(${settings.maxWidth},iw)':h='min(${settings.maxHeight},ih)'" +
                ":force_original_aspect_ratio=decrease:force_divisible_by=2:flags=fast_bilinear"
        val pixFmt = if (encoder.hardware) "nv12" else "yuv420p"
        val wallClock = clockBase?.let { ",setpts=(RTCTIME-$it)/(TB*1000000)" }.orEmpty()
        val offset = clockBase?.let { listOf("-itsoffset", "-" + seconds(it)) }.orEmpty()
        val monitor = target as? Monitor
        val window = target as? Window
        when (source) {
            Source.DDAGRAB -> {
                // Desktop Duplication: the GPU hands over each new desktop frame (repeating the last one while the
                // screen is still, so the stream never pauses); downloaded once, scaled on the CPU. It only sees the
                // screens of one graphics adapter, so a screen on another one names its adapter. The source has to sit
                // in -filter_complex: as `-f lavfi -i ddagrab` it never gets -filter_hw_device and always opens the
                // default adapter, whichever screen was asked for.
                val m = requireNotNull(monitor) { "ddagrab captures a monitor" }
                inputs.addAll(adapterArgs(m))
                output.addAll(["-filter_complex",
                    "ddagrab=output_idx=${m.index}:framerate=$fps:draw_mouse=1$wallClock,hwdownload,format=bgra,$fit,format=$pixFmt[v]",
                    "-map", "[v]"])
            }

            Source.GFXCAPTURE -> {
                // Windows Graphics Capture of one window, covered or not, on a GPU device of its own. Its frames keep
                // the window's size at the start; a window resized later is scaled into them with its aspect kept
                // (black bars). A window that draws nothing (minimized) sends nothing unless the build repeats frames.
                val w = requireNotNull(window) { "gfxcapture captures a window" }
                val dup = if (windowCapture.dupFrames) ":dup_frames=1" else ""
                output.addAll(["-filter_complex",
                    "gfxcapture=hwnd=${java.lang.Long.toUnsignedString(w.hwnd)}:max_framerate=$fps:capture_cursor=1" +
                            ":capture_border=0:display_border=0:resize_mode=scale_aspect$dup$wallClock," +
                            "hwdownload,format=bgra,$fit,format=$pixFmt[v]",
                    "-map", "[v]"])
            }

            Source.GDIGRAB -> {
                // GDI: copies the screen's rectangle of the desktop (primary screen's top left is 0,0), or one
                // window; slower than Desktop Duplication but works where it cannot (old drivers, remote sessions)
                inputs.addAll(offset)
                inputs.addAll(["-f", "gdigrab", "-draw_mouse", "1", "-framerate", fps])
                if (window != null) {
                    inputs.addAll(["-i", "hwnd=${java.lang.Long.toUnsignedString(window.hwnd)}"])
                } else {
                    val m = requireNotNull(monitor)
                    inputs.addAll(["-offset_x", m.x.toString(), "-offset_y", m.y.toString()])
                    inputs.addAll(["-video_size", "${m.width}x${m.height}", "-i", "desktop"])
                }
                output.addAll(["-vf", "$fit,format=$pixFmt"])
            }

            Source.AVFOUNDATION -> {
                val m = requireNotNull(monitor) { "avfoundation captures a monitor" }
                inputs.addAll(["-f", "avfoundation", "-capture_cursor", "1", "-framerate", fps])
                inputs.addAll(["-i", "Capture screen ${m.index}:none"])
                output.addAll(["-vf", "$fit,format=$pixFmt"])
            }

            Source.X11GRAB -> {
                val m = requireNotNull(monitor) { "x11grab captures a monitor" }
                val display = System.getenv("DISPLAY").orEmpty().ifBlank { ":0" }
                inputs.addAll(offset)
                inputs.addAll(["-f", "x11grab", "-draw_mouse", "1", "-framerate", fps])
                inputs.addAll(["-video_size", "${m.width}x${m.height}"])
                inputs.addAll(["-i", "$display+${m.x},${m.y}"])
                output.addAll(["-vf", "$fit,format=$pixFmt"])
            }
        }
        return VideoArgs(inputs, output, inputs.count { it == "-i" })
    }

    /**
     * The sound input (after the picture's); [audioOutput] is its encode. Timestamps are copied as they come (all
     * inputs already on the shifted wall clock), so `FFmpeg` does not start each input at zero on its own; the picture
     * keeps its exact capture times (millisecond encoder clock, no frame-rate grid to round onto).
     */
    private fun MutableList<String>.audioInput(audio: ShareAudio.Input, clockBase: Long, audioStart: Long) {
        when (audio) {
            is ShareAudio.Input.Pipe -> {
                // PCM counts its own time from sample 0, which the client lays at [audioStart]; read in 10 ms packets
                // (raw PCM would come in 85 ms ones, and the muxer would hold the picture back for each)
                addAll(["-f", "wav", "-ignore_length", "1", "-max_size", audio.format.tenMillisBytes.toString()])
                if (audioStart != clockBase) addAll(["-itsoffset", seconds(audioStart - clockBase)])
                addAll(["-probesize", "32", "-analyzeduration", "0", "-i", audio.path])
            }

            ShareAudio.Input.Silence -> {
                // Counts from 0 at FFmpeg's start ([audioStart]): shifted onto the share's clock like the pipe's sound,
                // or it would sit up to a timeline period away from the picture's server-clock stamps
                if (audioStart != clockBase) addAll(["-itsoffset", seconds(audioStart - clockBase)])
                // Made in real time (-re: a generator would otherwise run as fast as it can)
                addAll(["-re", "-f", "lavfi", "-i", "anullsrc=r=${ShareAudio.SILENCE_RATE}:cl=stereo"])
            }

            is ShareAudio.Input.Pulse -> {
                // 10 ms fragments (PulseAudio's default buffers far more); stamped with the wall clock
                addAll(["-f", "pulse", "-sample_rate", ShareAudio.OUTPUT_RATE.toString(), "-channels", "2"])
                addAll(["-fragment_size", "1920", "-wallclock", "1", "-itsoffset", "-" + seconds(clockBase), "-i", audio.device])
            }
        }
    }

    private fun MutableList<String>.audioOutput() {
        addAll(["-c:a", "aac", "-aac_coder", "fast", "-b:a", ShareAudio.AAC_BITRATE])
        addAll(["-ar", ShareAudio.AAC_RATE.toString(), "-ac", "2"])
    }

    /** `-force_key_frames`: the first frame, then one a second after the last forced one (any clock origin). */
    internal const val KEYFRAME_EVERY_SECOND = "expr:if(isnan(prev_forced_t),1,gte(t-prev_forced_t,1))"

    /** Default bitrates (kbps): measured on scrolling text, 4000 -> 6000 lifts x264's SSIM 0.946 -> 0.959. */
    const val DEFAULT_KBPS_30 = 6_000
    const val DEFAULT_KBPS_60 = 8_000

    /** Bounds for a server-set bitrate (kbps). */
    const val MIN_KBPS = 1_000
    const val MAX_KBPS = 20_000

    /** [micros] as `FFmpeg` seconds, exact to the microsecond. */
    internal fun seconds(micros: Long): String =
        String.format(Locale.ROOT, "%d.%06d", micros / 1_000_000L, Math.abs(micros % 1_000_000L))

    /** `FFmpeg`'s D3D11 device on [monitor]'s adapter, by vendor or index; none for the default adapter. */
    private fun adapterArgs(monitor: Monitor): List<String> {
        val device = when {
            monitor.byVendor && monitor.vendorId != 0 -> String.format(Locale.ROOT, "dd:,vendor_id=0x%04x", monitor.vendorId)
            monitor.adapter >= 0 -> "dd:${adapterIndexFor(monitor)}"
            else -> return []
        }
        return ["-init_hw_device", "d3d11va=$device", "-filter_hw_device", "dd"]
    }

    private fun adapterLabel(monitor: Monitor): String = when {
        monitor.byVendor && monitor.vendorId != 0 -> String.format(Locale.ROOT, "vendor 0x%04x", monitor.vendorId)
        monitor.adapter >= 0 -> "#${monitor.adapter}" + if (monitor.ffmpegAdapter >= 0) " (FFmpeg #${monitor.ffmpegAdapter})" else ""
        else -> "default"
    }

    /** Rate control and latency options for [encoder]: constant bitrate, no lookahead, no frame reordering. */
    internal fun encoderArgs(encoder: Encoder, settings: Settings): List<String> {
        val rate = "${settings.bitrateKbps}k"
        // A quarter second of VBV: keyframes cannot balloon into a burst that the relay has to queue
        val buffer = "${settings.bitrateKbps / 4}k"
        val cbr = ["-b:v", rate, "-maxrate", rate, "-bufsize", buffer]
        return when (encoder) {
            Encoder.NVENC -> ["-c:v", "h264_nvenc", "-preset", "p1", "-tune", "ull", "-zerolatency", "1", "-delay", "0",
                "-rc", "cbr", "-profile:v", "main"] + cbr

            Encoder.AMF -> ["-c:v", "h264_amf", "-usage", "ultralowlatency", "-quality", "speed", "-rc", "cbr",
                "-profile:v", "main"] + cbr

            Encoder.QSV -> ["-c:v", "h264_qsv", "-preset", "veryfast", "-async_depth", "1", "-profile:v", "main"] + cbr

            Encoder.VIDEOTOOLBOX -> ["-c:v", "h264_videotoolbox", "-realtime", "1", "-prio_speed", "1",
                "-profile:v", "main"] + cbr

            Encoder.X264 -> ["-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-profile:v", "main",
                "-x264-params", "nal-hrd=cbr"] + cbr
        }
    }

    // ---- Windows screens ----

    /**
     * Every screen on the desktop, from DXGI (see [Dxgi]): one per Windows screen, named by where it sits now ("Screen
     * 1 (left)", ordered left to right), on the adapter whose [Plan] worked when [choose] already ran for it. Cheap
     * (no `FFmpeg`): call whenever the picker opens. Null when DXGI cannot be read here.
     */
    fun windowsScreens(ffmpeg: String?): List<Monitor>? {
        val groups = dxgiGroups() ?: return null
        val screens = groups.map { variants ->
            // Where the screen is now (its labels and capture follow the current layout), on the adapter its plan uses
            val chosen = ffmpeg?.let { f -> knownChoice(f, variants.first())?.let { rebind(it, variants)?.monitor } }
            chosen ?: variants.first()
        }
        return named(screens)
    }

    /** GPU names DXGI lists (software adapters left out), for reports. */
    fun gpuNames(): List<String> = runCatching {
        Dxgi.enumerate().adapters.filterNot { it.software }.map { "${it.description} (${it.pciId})" }
    }.getOrDefault([])

    /** One line per screen DXGI lists, `adapter#:output vendor DeviceName rect`, for reports. */
    fun screensSummary(): List<String> = runCatching {
        Dxgi.enumerate().outputs.map { o ->
            "${o.adapter.index}:${o.index} ${o.adapter.pciId} ${o.deviceName} ${o.left},${o.top} ${o.width}x${o.height}" +
                    if (o.attachedToDesktop) "" else " detached"
        }
    }.getOrDefault([])

    /**
     * Each attached screen's ways to reach it: one entry per adapter that lists it (DXGI order), opened by vendor
     * when that vendor has a single adapter (then by index as a second try), else by index.
     */
    private fun dxgiGroups(): List<List<Monitor>>? {
        val snap = runCatching { Dxgi.enumerate() }
            .onFailure { logger.debug("DXGI screen list unavailable: {}", it.toString()) }.getOrNull() ?: return null
        val perVendor = snap.adapters.filterNot { it.software }.groupingBy { it.vendorId }.eachCount()
        val keys = HashMap<String, String>()
        fun keyOf(deviceName: String) = keys.getOrPut(deviceName) {
            runCatching { com.dreamdisplays.media.player.process.win.WinScreens.monitorKey(deviceName) }
                .onFailure { logger.debug("Monitor ids of {} unavailable: {}", deviceName, it.toString()) }.getOrDefault("")
        }
        val groups = snap.outputs
            .filter { it.attachedToDesktop && it.width > 0 && it.height > 0 }
            .groupBy { it.deviceName.ifEmpty { "${it.adapter.index}:${it.index}" } }
        return groups.values.map { outputs ->
            outputs.flatMap { o ->
                val base = Monitor(
                    index = o.index, name = "", x = o.left, y = o.top, width = o.width, height = o.height,
                    adapter = o.adapter.index, vendorId = o.adapter.vendorId, deviceName = o.deviceName,
                    adapterPciId = o.adapter.pciId, adapterName = o.adapter.description, key = keyOf(o.deviceName),
                )
                if (perVendor[o.adapter.vendorId] == 1) [base.copy(byVendor = true), base] else [base]
            }
        }
    }

    /**
     * Every way to reach [monitor]'s screen as DXGI lists it now; empty when it is not listed (never the picker's own
     * copy, whose output index and rectangle may be another screen's by now). [monitor] itself only when DXGI cannot
     * be read (screens found by [probeWindowsScreens]).
     */
    private fun variantsOf(monitor: Monitor): List<Monitor> = when (val l = locateOnWindows(monitor)) {
        is Located.Here -> l.ways
        Located.Gone -> []
        Located.Unknown -> [monitor]
    }

    /** Names screens by their place on the desktop, left to right (top to bottom for a column of two). */
    internal fun named(screens: List<Monitor>): List<Monitor> {
        val sorted = screens.sortedWith(compareBy({ it.x }, { it.y }))
        val places: List<String>? = when {
            !sorted.all { it.rectKnown } || sorted.size < 2 -> null
            sorted.size == 2 -> {
                val (a, b) = sorted
                if (b.x >= a.x + a.width) ["left", "right"]
                else if (a.y + a.height <= b.y) ["top", "bottom"]
                else if (b.y + b.height <= a.y) ["bottom", "top"]
                else null
            }

            sorted.size == 3 && sorted.zipWithNext().all { (a, b) -> b.x >= a.x + a.width } -> ["left", "middle", "right"]
            else -> null
        }
        val main = sorted.singleOrNull { it.rectKnown && it.x == 0 && it.y == 0 }?.takeIf { sorted.size > 1 }
        return sorted.mapIndexed { i, m ->
            val tags = listOfNotNull(places?.get(i), if (m === main) "main" else null)
            m.copy(name = "Screen ${i + 1}" + if (tags.isEmpty()) "" else tags.joinToString(", ", " (", ")"))
        }
    }

    @Volatile
    private var probedScreens: Pair<String, List<Monitor>>? = null

    /** Screens found by [probeWindowsScreens] for [ffmpeg], or null before a probe finished. */
    fun knownWindowsScreens(ffmpeg: String?): List<Monitor>? = probedScreens?.takeIf { it.first == ffmpeg }?.second

    /**
     * Fallback when DXGI cannot be read here: finds the screens by asking `FFmpeg` for each adapter's outputs in turn,
     * until it reports no such adapter (it then silently falls back to the default one). Slow; off the render thread.
     */
    fun probeWindowsScreens(ffmpeg: String): List<Monitor> {
        knownWindowsScreens(ffmpeg)?.let { return it }
        val found = ArrayList<Monitor>()
        var adapter = 0
        var silent = 0
        adapters@ while (silent < 2) {
            var output = 0
            while (true) {
                val m = Monitor(output, "", 0, 0, 0, 0, adapter, rectKnown = false)
                val r = probeScreen(ffmpeg, m)
                if (r == null) {
                    silent++; break
                }
                silent = 0
                if (r.pciId == null) break@adapters // No such adapter
                if (r.size == null) break
                found += m.copy(width = r.size.first, height = r.size.second, adapterPciId = r.pciId,
                    vendorId = r.pciId.substringBefore(':').toIntOrNull(16) ?: 0)
                output++
            }
            adapter++
        }
        val named = found.mapIndexed { i, m -> m.copy(name = "Screen ${i + 1}") }
        logger.info("Screen share: {} screen(s) found by probing: {}.", named.size, named.joinToString { "${it.adapter}:${it.index} ${it.width}x${it.height}" })
        probedScreens = ffmpeg to named
        return named
    }

    internal class ScreenProbe(val pciId: String?, val size: Pair<Int, Int>?)

    private val OPENED = Regex("""Opened dxgi output \d+ with dimensions (\d+)x(\d+)""")

    /** Opens [monitor] with Desktop Duplication for one frame; null when `FFmpeg` did not answer in time. */
    private fun probeScreen(ffmpeg: String, monitor: Monitor): ScreenProbe? {
        val args: List<String> = listOf(ffmpeg, "-hide_banner", "-nostats", "-loglevel", "verbose") + adapterArgs(monitor) +
                listOf("-filter_complex", "ddagrab=output_idx=${monitor.index}:framerate=5[v]", "-map", "[v]",
                    "-frames:v", "1", "-f", "null", "-")
        val r = exec(args, timeoutSeconds = 8)
        if (r.tail.startsWith("timed out") || r.output.isEmpty()) return null
        val pci = USING_DEVICE.find(r.output)?.let { "${it.groupValues[1]}:${it.groupValues[2]}".lowercase() }
        val size = if (r.ok) OPENED.find(r.output)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() } else null
        return ScreenProbe(pci, size)
    }

    /** Short label for [window] in the share picker: the program, then the window title. */
    fun label(window: Window): String {
        val app = window.app.ifBlank { "Window" }
        val title = window.title.trim()
        return if (title.isEmpty() || title.equals(app, ignoreCase = true)) app else "$app - $title"
    }

    /** Short label for [monitor] in the share picker. */
    fun label(monitor: Monitor): String {
        if (monitor.adapter >= 0 || monitor.deviceName.isNotEmpty()) {
            return String.format(Locale.ROOT, "%s %dx%d", monitor.name, monitor.width, monitor.height)
        }
        val name = monitor.name.ifBlank { "Monitor" }.let { if (it.length > 16) it.take(15) + "…" else it }
        return String.format(Locale.ROOT, "%d: %s %dx%d", monitor.index + 1, name, monitor.width, monitor.height)
    }
}
