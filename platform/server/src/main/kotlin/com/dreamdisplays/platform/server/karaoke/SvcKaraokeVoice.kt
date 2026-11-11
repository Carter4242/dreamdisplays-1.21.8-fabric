package com.dreamdisplays.platform.server.karaoke

import io.github.arnodoelinger.platformweaver.PaperOnly
import com.dreamdisplays.core.karaoke.BurstAnchor
import com.dreamdisplays.core.karaoke.KaraokeTiming
import de.maxhenkel.voicechat.api.BukkitVoicechatService
import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket
import org.bukkit.Bukkit
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Karaoke's Simple Voice Chat add-on. Loaded by name from [KaraokeManager] only when voice chat is installed.
 *
 * Every mic packet of an active singer is taken out of voice chat's normal routing (so nobody hears it raw, not even
 * players outside the display's range) and re-sent to each listener in the display's range as locational sound from
 * the display, at the time that lands it on the beat for that listener: see [KaraokeTiming] for the math and
 * [BurstAnchor] for how frames are put on a jitter-free grid. Sends are scheduled per listener on [VoiceEmitter].
 */
@PaperOnly
class SvcKaraokeVoice : KaraokeVoice {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val emitter = VoiceEmitter()
    private val voices = ConcurrentHashMap<UUID, SingerVoice>()
    private var service: BukkitVoicechatService? = null
    private val addon = Addon()

    @Volatile
    private var api: VoicechatServerApi? = null

    override fun start(): Boolean {
        val svc = Bukkit.getServicesManager().load(BukkitVoicechatService::class.java) ?: return false
        return try {
            svc.registerPlugin(addon)
            service = svc
            val hotLoad = VoicechatHotSwap.attach(svc, addon)
            emitter.start()
            logger.info(
                if (hotLoad) "Karaoke attached to the running Simple Voice Chat."
                else "Karaoke registered with Simple Voice Chat."
            )
            true
        } catch (e: Exception) {
            logger.error("Could not attach karaoke to Simple Voice Chat.", e)
            stop()
            false
        }
    }

    override fun stop() {
        service?.let { svc ->
            runCatching { VoicechatHotSwap.detach(svc, addon) }
                .onFailure { logger.error("Could not detach karaoke from Simple Voice Chat.", it) }
        }
        service = null
        api = null
        emitter.shutdown()
        voices.clear()
    }

    override fun isConnected(playerId: UUID): Boolean = api?.getConnectionOf(playerId)?.isConnected == true

    override fun stats(singerId: UUID): SingerVoiceStats? = voices[singerId]?.stats(System.nanoTime())

    override fun forget(singerId: UUID) {
        voices.remove(singerId)
    }

    private inner class Addon : VoicechatPlugin {
        override fun getPluginId(): String = PLUGIN_ID

        override fun initialize(api: VoicechatApi) {
            this@SvcKaraokeVoice.api = api as? VoicechatServerApi
        }

        override fun registerEvents(registration: EventRegistration) {
            registration.registerEvent(MicrophonePacketEvent::class.java, MicHandler(this@SvcKaraokeVoice))
        }
    }

    /** A named class (not a lambda) so the hot swap recognises it by package. */
    private class MicHandler(private val voice: SvcKaraokeVoice) : Consumer<MicrophonePacketEvent> {
        override fun accept(event: MicrophonePacketEvent) = voice.onMic(event)
    }

    private fun onMic(event: MicrophonePacketEvent) {
        val singerId = event.senderConnection?.player?.uuid ?: return
        val stage = KaraokeManager.stageOfSinger(singerId) ?: return
        val singer = stage.singers[singerId] ?: return
        // Nobody hears a singer's raw voice, wherever they are; only the aligned copy below
        event.cancel()
        val api = api ?: return
        val now = System.nanoTime()
        val mic = event.packet
        val packet = mic.locationalSoundPacketBuilder()
            .channelId(channelOf(singerId))
            .position(api.createPosition(stage.x, stage.y, stage.z))
            .distance(stage.distance.toFloat())
            .build()
        val seq = packet.sequenceNumber
        val voice = voices.computeIfAbsent(singerId) { SingerVoice() }
        val burst = voice.onFrame(now, seq, mic.opusEncodedData.isEmpty())
        if (burst < 0) return
        for (listener in stage.listeners) {
            val delayMs = KaraokeTiming.emitDelayMs(singer.leadMs, singer.pingMs, listener.pingMs, singer.trimMs, stage.latency)
            val shift = voice.shiftFor(burst, listener.playerId, delayMs * 1_000_000L)
            val provisional = voice.estimate(burst, seq, lock = false) ?: return
            emitter.schedule(provisional + shift - LOOKAHEAD_SLACK_NANOS) { nowNanos ->
                send(voice, burst, seq, shift, listener.playerId, packet, nowNanos)
            }
        }
    }

    /** Sends [packet] to [listenerId] once its target time is reached; returns a later time while it isn't. */
    private fun send(
        voice: SingerVoice, burst: Int, seq: Long, shift: Long, listenerId: UUID,
        packet: LocationalSoundPacket, nowNanos: Long,
    ): Long? {
        val target = (voice.estimate(burst, seq, lock = false) ?: return null) + shift
        if (target - nowNanos > EARLY_TOLERANCE_NANOS) return target
        // The first send of a burst fixes its anchor: from here the listener's client plays it at its own rate
        voice.estimate(burst, seq, lock = true)
        val api = api ?: return null
        val connection = api.getConnectionOf(listenerId) ?: return null
        api.sendLocationalSoundPacketTo(connection, packet)
        voice.onSent(listenerId, nowNanos - target, nowNanos)
        return null
    }

    /** One singer's anchor and per-burst listener delays, plus statistics. Thread-safe. */
    private class SingerVoice {
        private val anchor = BurstAnchor()
        private val shifts = LinkedHashMap<Int, HashMap<UUID, Long>>()
        private val lastLate = HashMap<UUID, Long>()
        private val worstLate = HashMap<UUID, Long>()
        private var frames = 0L
        private var sent = 0L
        private var late = 0L

        @Synchronized
        fun onFrame(nowNanos: Long, seq: Long, stop: Boolean): Int {
            frames++
            return anchor.onFrame(nowNanos, seq, stop)
        }

        @Synchronized
        fun estimate(burst: Int, seq: Long, lock: Boolean): Long? = anchor.estimate(burst, seq, lock)

        /**
         * The listener's delay is fixed for a whole burst: their client plays a burst at its own rate from the first
         * frame, so a ping update mid-burst must not reorder or bunch frames.
         */
        @Synchronized
        fun shiftFor(burst: Int, listenerId: UUID, nanos: Long): Long {
            val perListener = shifts.getOrPut(burst) { HashMap() }
            while (shifts.size > 2) shifts.remove(shifts.keys.first())
            return perListener.getOrPut(listenerId) { nanos }
        }

        @Synchronized
        fun onSent(listenerId: UUID, lateNanos: Long, nowNanos: Long) {
            sent++
            if (lateNanos <= LATE_TOLERANCE_NANOS) return
            late++
            val recent = lastLate[listenerId]?.let { nowNanos - it < LATE_WINDOW_NANOS } == true
            lastLate[listenerId] = nowNanos
            val ms = lateNanos / 1_000_000L
            worstLate[listenerId] = if (recent) maxOf(worstLate[listenerId] ?: 0L, ms) else ms
        }

        @Synchronized
        fun stats(nowNanos: Long): SingerVoiceStats {
            val recent = lastLate.filterValues { nowNanos - it < LATE_WINDOW_NANOS }.keys
            return SingerVoiceStats(
                frames = frames,
                bursts = anchor.bursts,
                reanchors = anchor.reanchors,
                sent = sent,
                late = late,
                lateListeners = recent.size,
                worstLateMs = recent.maxOfOrNull { worstLate[it] ?: 0L } ?: 0L,
            )
        }
    }

    private companion object {
        const val PLUGIN_ID = "dreamdisplays"

        /** Wake this much before a frame's provisional time: the anchor may still improve until it is sent. */
        const val LOOKAHEAD_SLACK_NANOS = 30_000_000L
        const val EARLY_TOLERANCE_NANOS = 300_000L
        const val LATE_TOLERANCE_NANOS = 5_000_000L
        const val LATE_WINDOW_NANOS = KaraokeVoice.LATE_WINDOW_MS * 1_000_000L

        /** Each singer's aligned voice gets its own channel, apart from their normal (now silent) one. */
        fun channelOf(singerId: UUID): UUID = UUID.nameUUIDFromBytes("dreamdisplays:karaoke:$singerId".toByteArray())
    }
}
