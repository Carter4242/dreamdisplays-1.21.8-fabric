package com.dreamdisplays.platform.client.karaoke

import com.dreamdisplays.core.karaoke.VoiceTrackAligner
import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.protocol.common.packets.KaraokeClockPing
import com.dreamdisplays.core.protocol.common.packets.KaraokeClockPong
import com.dreamdisplays.core.protocol.common.packets.KaraokeLatencyReport
import com.dreamdisplays.core.protocol.common.packets.KaraokeMicMarkers
import com.dreamdisplays.core.protocol.common.packets.KaraokeOffsetReport
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.pipeline.PlayedPcmTap
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import com.dreamdisplays.shared.capability.ServerFeature
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The client side of karaoke timing. The server makes the voice of a singer land on the beat for every listener; for
 * that it needs latencies only clients can measure, so this:
 *
 * - reports this client's latencies every few seconds ([KaraokeLatencyReport]): display audio output (the OpenAL
 *   device's own figure, or an estimate under Java Sound), and with Simple Voice Chat installed, the mic capture time
 *   and voice chat's jitter buffer and output;
 * - answers the server's clock pings at once, so it knows this clock against its own;
 * - while this player sings (the server sent a timeline with [com.dreamdisplays.core.protocol.common.packets.DisplaySync.karaokeLeadMs])
 *   and the song plays: tells the server when voice chat handed over some mic frames ([KaraokeMicMarkers]), so it
 *   knows their exact capture -> arrival time, and listens to the singing against the track this client plays
 *   ([VoiceTrackAligner]) and reports how late the voice is ([KaraokeOffsetReport]); the server slowly trims by that.
 *
 * PRIVACY: microphone audio is only ever looked at on the singer's own client, only while they sing on a display
 * whose song plays, and only to reduce it to a coarse energy envelope in memory. It is never stored or sent anywhere;
 * the only thing that leaves this client is the resulting offset number (and the mic frames' sequence numbers and
 * times, which say nothing about their content). When not singing, the mic hook only counts frames.
 *
 * Voice chat calls [onMicPoll] / [onMicFrame] on its mic thread (via the platform's voice chat add-on, which exists
 * only when Simple Voice Chat is installed); everything else runs on the client thread, the correlation on one
 * background thread. Nothing on the mic or audio thread allocates.
 */
object KaraokeClient {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Set by the voice chat add-on once Simple Voice Chat initialised it. */
    @Volatile
    var voiceChatInstalled = false

    /** Displays this player sings on, with their lead (normally at most one). */
    private val leads = ConcurrentHashMap<UUID, Int>()

    /** The display this player sings on while its song plays; mic and track analysis only run while set. */
    @Volatile
    private var active: UUID? = null

    private val aligner = VoiceTrackAligner()

    // ---- Mic frames (voice chat's mic thread) ----

    @Volatile
    private var micThread: Thread? = null
    private var nextSeq = 0L
    private var hasSent = false
    private var sentSincePoll = false
    private var burstStartSeq = 0L
    private var burstAnchorNanos = Long.MAX_VALUE

    private val markerSeqs = LongArray(MARKER_SLOTS)
    private val markerNanos = LongArray(MARKER_SLOTS)
    private var markerCount = 0
    private val markerLock = Any()

    // ---- Track (display audio thread) ----

    @Volatile
    private var trackOutputNanos = DEFAULT_JAVA_SOUND_OUTPUT_MS * 1_000_000L
    private var tapped: MediaPlayer? = null
    private val tap = PlayedPcmTap { pcm, len, playAtNanos ->
        if (active != null) aligner.track.addStereo16(pcm, len, TRACK_RATE, playAtNanos + trackOutputNanos)
    }

    // ---- Client thread ----

    private var ticks = 0L
    private var lastReportTick = Long.MIN_VALUE / 2
    private var lastConfigTick = Long.MIN_VALUE / 2
    @Volatile
    private var voiceConfig = VoiceChatClientConfig.DEFAULTS
    private var lastTrackOutputMs = -1
    private var lastTrackMeasured = false
    private var lastQueuedMs = -1

    @Volatile
    private var executor: ScheduledExecutorService? = null
    private var lastOffsetSentNanos = 0L

    /** A timeline of [displayId] arrived; [leadMs] > 0 while this player sings there. Client thread. */
    fun onSync(displayId: UUID, leadMs: Int) {
        if (leadMs > 0) leads[displayId] = leadMs else leads.remove(displayId)
    }

    /** True while this player sings on [displayId] (its track then plays at full volume for them). */
    fun isSinging(displayId: UUID): Boolean = leads.containsKey(displayId)

    /** A clock probe from the server: answered at once. Client thread. */
    fun onClockPing(ping: KaraokeClockPing) {
        val received = System.nanoTime()
        Initializer.sendPacket(KaraokeClockPong(ping.seq, ping.serverNanos, received, System.nanoTime()))
    }

    /** Disconnected: forget everything about that server. */
    fun reset() {
        leads.clear()
        setActive(null)
        lastReportTick = Long.MIN_VALUE / 2
    }

    /** Every client tick. */
    fun tick() {
        ticks++
        val snapshot = ClientPacketManager.serverSnapshot
        if (!snapshot.hasFeature(ServerFeature.KARAOKE)) {
            if (active != null) setActive(null)
            return
        }
        val singing = leads.keys.firstNotNullOfOrNull { id -> DisplayRegistry.screens[id] }
        val playing = singing?.takeIf { voiceChatInstalled && it.videoStarted && !it.isPaused }
        if (playing?.uuid != active) setActive(playing?.uuid)
        // The track tap follows the display's current player (a new one with every video)
        val player = playing?.karaokePlayer
        if (player !== tapped) {
            tapped?.setPcmTap(null)
            player?.setPcmTap(tap)
            tapped = player
        }
        if (ticks - lastConfigTick >= CONFIG_TICKS) {
            lastConfigTick = ticks
            if (voiceChatInstalled) voiceConfig = VoiceChatClientConfig.read()
        }
        updateTrackOutput(singing)
        if (active != null && ticks % MARKER_TICKS == 0L) sendMarkers()
        if (ticks - lastReportTick >= REPORT_TICKS) {
            lastReportTick = ticks
            Initializer.sendPacket(latencyReport())
        }
    }

    private fun setActive(displayId: UUID?) {
        if (displayId == active) return
        active = displayId
        aligner.reset()
        synchronized(markerLock) { markerCount = 0 }
        if (displayId == null) {
            tapped?.setPcmTap(null)
            tapped = null
            return
        }
        logger.debug("Karaoke: singing on {}; timing analysis on.", displayId)
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "DreamDisplays-karaoke").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            }.also { it.scheduleWithFixedDelay(::estimate, ESTIMATE_MS, ESTIMATE_MS, TimeUnit.MILLISECONDS) }
        }
    }

    /** Background thread: one correlation over the last window, reported when it is confident enough. */
    private fun estimate() {
        val displayId = active ?: return
        val result = runCatching {
            aligner.estimate(System.nanoTime(), micSettleMs = captureMs() + MIC_SETTLE_EXTRA_MS)
        }.getOrNull() ?: return
        if (result.confidence < MIN_REPORT_CONFIDENCE) return
        val now = System.nanoTime()
        if (now - lastOffsetSentNanos < MIN_REPORT_INTERVAL_NANOS) return
        lastOffsetSentNanos = now
        val report = KaraokeOffsetReport(displayId, result.offsetMs.roundToInt(), result.confidence, aligner.windowMs)
        Minecraft.getInstance().execute {
            if (active == displayId) Initializer.sendPacket(report)
        }
    }

    private fun updateTrackOutput(singing: com.dreamdisplays.platform.client.displays.DisplayScreen?) {
        val latency = (singing?.audioOutputLatency()
            ?: DisplayRegistry.screens.values.firstNotNullOfOrNull { it.audioOutputLatency() }) ?: return
        val measured = latency.deviceNanos >= 0
        val outputMs = if (measured) (latency.deviceNanos / 1_000_000L).toInt() else DEFAULT_JAVA_SOUND_OUTPUT_MS
        lastTrackOutputMs = outputMs
        lastTrackMeasured = measured
        lastQueuedMs = (latency.queuedNanos / 1_000_000L).toInt()
        trackOutputNanos = outputMs * 1_000_000L
    }

    private fun latencyReport(): KaraokeLatencyReport {
        val voice = voiceChatInstalled
        val voiceOutput = if (voice) GameAudioLatency.deviceLatencyMs() else -1
        return KaraokeLatencyReport(
            trackOutputMs = if (lastTrackOutputMs >= 0) lastTrackOutputMs else DEFAULT_JAVA_SOUND_OUTPUT_MS,
            trackOutputMeasured = lastTrackMeasured,
            trackQueuedMs = lastQueuedMs,
            voiceChat = voice,
            captureMs = if (voice) captureMs() else -1,
            voiceBufferMs = if (voice) voiceConfig.outputBufferFrames * FRAME_MS else -1,
            voiceOutputMs = voiceOutput,
            voiceOutputMeasured = voiceOutput >= 0,
        )
    }

    /**
     * From a sound reaching the mic to voice chat handing over the frame holding it: the device's input buffering (an
     * estimate), the 20 ms frame itself, and the denoiser's look-ahead when it is on. The mic envelope is timed by
     * the same figure, so whatever it gets wrong shows up in the measured offset and is trimmed away.
     */
    private fun captureMs(): Int = MIC_INPUT_MS + FRAME_MS + if (voiceConfig.denoiser) DENOISER_MS else 0

    private fun sendMarkers() {
        val packet = synchronized(markerLock) {
            if (markerCount == 0) return
            KaraokeMicMarkers(markerSeqs.copyOf(markerCount).toList(), markerNanos.copyOf(markerCount).toList())
                .also { markerCount = 0 }
        }
        Initializer.sendPacket(packet)
    }

    // ---- Voice chat's mic thread ----

    /** Voice chat polled one mic frame (every 20 ms of mic audio, sent or not), before any [onMicFrame] for it. */
    fun onMicPoll() {
        checkMicThread()
        // The previous frame was not sent after some were: voice chat sent an end-of-burst packet, which takes a number
        if (!sentSincePoll && hasSent) {
            nextSeq++
            hasSent = false
        }
        sentSincePoll = false
    }

    /** Voice chat is about to encode and send [pcm] (48 kHz mono) as the next mic frame. */
    fun onMicFrame(pcm: ShortArray) {
        val now = System.nanoTime()
        checkMicThread()
        val seq = nextSeq++
        if (!hasSent) {
            burstStartSeq = seq
            burstAnchorNanos = Long.MAX_VALUE
        }
        hasSent = true
        sentSincePoll = true
        // Frames of a burst are 20 ms apart at capture; they can only be handed over late (a busy mic thread), so the
        // earliest time on that grid is the true one
        val k = seq - burstStartSeq
        val onGrid = now - k * FRAME_NANOS
        if (onGrid < burstAnchorNanos) burstAnchorNanos = onGrid
        if (active == null) return
        val handed = burstAnchorNanos + k * FRAME_NANOS
        aligner.mic.addMono16(pcm, pcm.size, MIC_RATE, handed - captureMs() * 1_000_000L)
        if (seq % MARKER_EVERY_FRAMES == 0L) synchronized(markerLock) {
            if (markerCount < MARKER_SLOTS) {
                markerSeqs[markerCount] = seq
                markerNanos[markerCount] = handed
                markerCount++
            }
        }
    }

    /** Voice chat (re)connected or disconnected: its mic numbering starts over. */
    fun onVoiceConnection() {
        micThread = null
    }

    /** A new mic thread (reconnect, other microphone) numbers its frames from 0 again. */
    private fun checkMicThread() {
        val thread = Thread.currentThread()
        if (thread === micThread) return
        micThread = thread
        nextSeq = 0L
        hasSent = false
        sentSincePoll = false
    }

    private const val FRAME_MS = 20
    private const val FRAME_NANOS = FRAME_MS * 1_000_000L
    private const val MIC_RATE = 48_000
    private const val TRACK_RATE = 44_100

    /** Mic device input buffering nobody can read through voice chat's API: a typical figure. */
    private const val MIC_INPUT_MS = 20

    /** RNNoise works on 10 ms frames. */
    private const val DENOISER_MS = 10

    /** Java Sound does not tell its device latency: a typical shared-mode figure. */
    private const val DEFAULT_JAVA_SOUND_OUTPUT_MS = 30
    private const val MIC_SETTLE_EXTRA_MS = 60
    private const val MIN_REPORT_CONFIDENCE = 0.2
    private const val MIN_REPORT_INTERVAL_NANOS = 1_500_000_000L
    private const val ESTIMATE_MS = 2_000L
    private const val REPORT_TICKS = 100L
    private const val CONFIG_TICKS = 600L
    private const val MARKER_TICKS = 20L
    private const val MARKER_EVERY_FRAMES = 10L
    private const val MARKER_SLOTS = 32
}
