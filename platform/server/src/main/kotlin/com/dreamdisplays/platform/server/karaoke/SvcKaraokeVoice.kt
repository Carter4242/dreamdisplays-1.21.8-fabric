package com.dreamdisplays.platform.server.karaoke

import io.github.arnodoelinger.platformweaver.PaperOnly
import com.dreamdisplays.core.karaoke.BurstAnchor
import com.dreamdisplays.core.karaoke.KaraokeRoute
import com.dreamdisplays.core.karaoke.KaraokeRouting
import com.dreamdisplays.core.karaoke.KaraokeTiming
import de.maxhenkel.voicechat.api.BukkitVoicechatService
import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent
import de.maxhenkel.voicechat.api.Position
import de.maxhenkel.voicechat.api.ServerPlayer
import de.maxhenkel.voicechat.api.opus.OpusDecoder
import de.maxhenkel.voicechat.api.opus.OpusEncoder
import de.maxhenkel.voicechat.api.packets.MicrophonePacket
import org.bukkit.Bukkit
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kotlin.math.sqrt

/**
 * Karaoke's Simple Voice Chat add-on. Loaded by name from [KaraokeManager] only when voice chat is installed.
 *
 * Every mic packet of a singer in the display's range is taken out of voice chat's normal routing ([KaraokeRouting]
 * says who hears it how): the other singers get it at once from the singer like normal voice chat (re-encoded at
 * `co_singer_volume` while a song plays), and each listener gets locational sound from the singer (or the display),
 * at the time that lands it on the beat for them: see [KaraokeTiming] for the math and [BurstAnchor] for how frames
 * are put on a jitter-free grid. Sends are scheduled per listener on [VoiceEmitter].
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
        voices.values.forEach { it.close() }
        voices.clear()
    }

    override fun isConnected(playerId: UUID): Boolean = api?.getConnectionOf(playerId)?.isConnected == true

    override fun stats(singerId: UUID): SingerVoiceStats? = voices[singerId]?.stats(System.nanoTime())

    override fun forget(singerId: UUID) {
        voices.remove(singerId)?.close()
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
        // Away from the display (or offline): voice chat routes them as usual
        if (!singer.present) return
        // Nobody hears a singer's raw voice; only the copies below
        event.cancel()
        val api = api ?: return
        val now = System.nanoTime()
        val mic = event.packet
        val sender = event.senderConnection?.player ?: return
        val proximity = api.voiceChatDistance
        val singing = stage.singers.keys
        val voice = voices.computeIfAbsent(singerId) { SingerVoice() }
        val stop = mic.opusEncodedData.isEmpty()

        // Other singers: at once and from the singer, like normal voice chat
        val volume = KaraokeRouting.coSingerVolume(stage.songPlaying, stage.coSingerVolume)
        var coSingerData: ByteArray? = null
        for (otherId in singing) {
            if (otherId == singerId || volume <= 0.0) continue
            val other = api.getConnectionOf(otherId) ?: continue
            val near = isNear(other.player, sender, proximity)
            if (KaraokeRouting.route(singerId, otherId, singing, false, near, stage.fromSinger) != KaraokeRoute.NORMAL) continue
            val data = coSingerData ?: voice.scaled(api, mic.opusEncodedData, volume).also { coSingerData = it }
            val packet = mic.entitySoundPacketBuilder()
                .opusEncodedData(data)
                .whispering(mic.isWhispering)
                .distance(proximity.toFloat())
                .build()
            if (voice.startStream(otherId) && stage.debug) {
                logger.info(
                    "Karaoke voice {} -> {} (singer): entity sound, distance {}, volume {}, connected {}.",
                    singerId, otherId, proximity.toInt(), volume, other.isConnected,
                )
            }
            api.sendEntitySoundPacketTo(other, packet)
        }

        val seq = mic.locationalSoundPacketBuilder().position(api.createPosition(0.0, 0.0, 0.0)).build().sequenceNumber
        val burst = voice.onFrame(now, seq, stop)
        if (burst < 0) return
        for (listener in stage.listeners) {
            val connection = api.getConnectionOf(listener.playerId) ?: continue
            val near = stage.fromSinger && isNear(connection.player, sender, proximity)
            val route = KaraokeRouting.route(singerId, listener.playerId, singing, listener.inRange, near, stage.fromSinger)
            if (route != KaraokeRoute.ALIGNED) continue
            val delayMs = KaraokeTiming.emitDelayMs(singer.leadMs, singer.pingMs, listener.pingMs, singer.trimMs, stage.latency)
            val shift = voice.shiftFor(burst, listener.playerId, delayMs * 1_000_000L)
            val provisional = voice.estimate(burst, seq, lock = false) ?: return
            if (voice.startStream(listener.playerId) && stage.debug) {
                logger.info(
                    "Karaoke voice {} -> {} (listener): locational sound from the {}, distance >= {}, delay {} ms, " +
                            "in display range {}, near singer {}, connected {}.",
                    singerId, listener.playerId, if (stage.fromSinger) "singer" else "display", distanceFor(stage, null).toInt(),
                    delayMs, listener.inRange, near, connection.isConnected,
                )
            }
            emitter.schedule(provisional + shift - LOOKAHEAD_SLACK_NANOS) { nowNanos ->
                send(voice, burst, seq, shift, singerId, listener.playerId, stage, mic, nowNanos)
            }
        }
    }

    /** Same world and within [distance] of [other]. */
    private fun isNear(player: ServerPlayer, other: ServerPlayer, distance: Double): Boolean {
        if (player.serverLevel.serverLevel != other.serverLevel.serverLevel) return false
        return distanceOf(player.position, other.position) <= distance
    }

    private fun distanceOf(a: Position, b: Position): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * How far a listener copy carries: the display's range from its centre, or from the singer at [singerPos] far
     * enough to still reach the whole display range, and never less than voice chat's own distance.
     */
    private fun distanceFor(stage: KaraokeStage, singerPos: Position?): Double {
        if (!stage.fromSinger || singerPos == null) return stage.distance
        val api = api ?: return stage.distance
        val toCentre = distanceOf(singerPos, api.createPosition(stage.x, stage.y, stage.z))
        return maxOf(api.voiceChatDistance, stage.distance + toCentre)
    }

    /** Sends the frame to [listenerId] once its target time is reached; returns a later time while it isn't. */
    private fun send(
        voice: SingerVoice, burst: Int, seq: Long, shift: Long, singerId: UUID, listenerId: UUID,
        stage: KaraokeStage, mic: MicrophonePacket, nowNanos: Long,
    ): Long? {
        val target = (voice.estimate(burst, seq, lock = false) ?: return null) + shift
        if (target - nowNanos > EARLY_TOLERANCE_NANOS) return target
        // The first send of a burst fixes its anchor: from here the listener's client plays it at its own rate
        voice.estimate(burst, seq, lock = true)
        val api = api ?: return null
        val connection = api.getConnectionOf(listenerId) ?: return null
        // From the singer: where they are now, so the voice follows them
        val singerPos = if (stage.fromSinger) api.getConnectionOf(singerId)?.player?.position else null
        val position = singerPos ?: api.createPosition(stage.x, stage.y, stage.z)
        val packet = mic.locationalSoundPacketBuilder()
            .channelId(channelOf(singerId))
            .position(position)
            .distance(distanceFor(stage, singerPos).toFloat())
            .build()
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
        private val streams = HashSet<UUID>()
        private var decoder: OpusDecoder? = null
        private var encoder: OpusEncoder? = null
        private var frames = 0L
        private var sent = 0L
        private var late = 0L

        /** True the first time [receiverId] gets this singer's voice (for the debug log). */
        @Synchronized
        fun startStream(receiverId: UUID): Boolean = streams.add(receiverId)

        /** [data] re-encoded at [volume] (0..1); the empty end-of-burst frame passes through and resets the codecs. */
        @Synchronized
        fun scaled(api: VoicechatServerApi, data: ByteArray, volume: Double): ByteArray {
            if (volume >= 1.0) return data
            if (data.isEmpty()) {
                decoder?.resetState()
                encoder?.resetState()
                return data
            }
            val dec = decoder ?: api.createDecoder().also { decoder = it }
            val enc = encoder ?: api.createEncoder().also { encoder = it }
            val pcm = dec.decode(data)
            for (i in pcm.indices) pcm[i] = (pcm[i] * volume).toInt().toShort()
            return enc.encode(pcm)
        }

        @Synchronized
        fun close() {
            decoder?.close()
            encoder?.close()
            decoder = null
            encoder = null
        }

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
