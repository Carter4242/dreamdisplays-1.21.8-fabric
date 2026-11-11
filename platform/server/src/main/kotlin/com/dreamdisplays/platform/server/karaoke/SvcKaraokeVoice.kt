package com.dreamdisplays.platform.server.karaoke

import io.github.arnodoelinger.platformweaver.PaperOnly
import com.dreamdisplays.core.karaoke.BurstAnchor
import com.dreamdisplays.core.karaoke.KaraokeCoSinger
import com.dreamdisplays.core.karaoke.KaraokeRoute
import com.dreamdisplays.core.karaoke.KaraokeRouting
import com.dreamdisplays.core.karaoke.KaraokeTiming
import com.dreamdisplays.core.karaoke.KaraokeVoiceGain
import com.dreamdisplays.core.karaoke.KaraokeVoiceSend
import com.dreamdisplays.core.karaoke.VoiceGroup
import de.maxhenkel.voicechat.api.BukkitVoicechatService
import de.maxhenkel.voicechat.api.Group
import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent
import de.maxhenkel.voicechat.api.Position
import de.maxhenkel.voicechat.api.ServerPlayer
import de.maxhenkel.voicechat.api.opus.OpusDecoder
import de.maxhenkel.voicechat.api.opus.OpusEncoder
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket
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
 * says who hears it how): the other singers get it at once from the singer like normal voice chat (re-encoded per hearer at
 * `co_singer_volume` / `co_singer_idle_volume` while a song plays, see [KaraokeCoSinger]), and each listener gets the
 * singer's voice (with `voice_source = singer` as
 * entity sound from the singer on their own channel, so clients show their speaking icon; locational at the display
 * otherwise, or beyond voice chat's distance where the listener may not track the singer),
 * at the time that lands it on the beat for them: see [KaraokeTiming] for the math and [BurstAnchor] for how frames
 * are put on a jitter-free grid. Sends are scheduled per listener on [VoiceEmitter].
 *
 * Voice chat's privacy holds throughout: a whisper, or a singer in a group that isn't open, is never taken (voice chat
 * routes it to whom it was meant for); members of a singer's open group get voice chat's own group copy; players in
 * an isolated group hear no singer outside it ([KaraokeRouting.interceptsMic], [KaraokeRouting.withGroups]).
 */
@PaperOnly
class SvcKaraokeVoice : KaraokeVoice {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val emitter = VoiceEmitter()
    private val voices = ConcurrentHashMap<UUID, SingerVoice>()

    /** When each singer's last voice frame arrived ([System.nanoTime]): whether they sing now, for co-singer volume. */
    private val lastVoice = ConcurrentHashMap<UUID, Long>()

    /** Singers told that their private voice chat group keeps the audience from hearing them (once per singing). */
    private val toldPrivate: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
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
        lastVoice.clear()
    }

    override fun isConnected(playerId: UUID): Boolean = api?.getConnectionOf(playerId)?.isConnected == true

    override fun stats(singerId: UUID): SingerVoiceStats? = voices[singerId]?.stats(System.nanoTime())

    override fun forget(singerId: UUID) {
        voices.remove(singerId)?.close()
        lastVoice.remove(singerId)
        toldPrivate.remove(singerId)
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

    @Volatile
    private var lastMicErrorNanos = 0L

    /** Never lets one bad frame throw out of voice chat's event (it would just log and drop it every frame). */
    private fun onMic(event: MicrophonePacketEvent) {
        try {
            handleMic(event)
        } catch (e: Exception) {
            val now = System.nanoTime()
            if (now - lastMicErrorNanos > MIC_ERROR_LOG_NANOS) {
                lastMicErrorNanos = now
                logger.warn("Karaoke voice frame failed (logged at most once a minute).", e)
            }
        }
    }

    private fun handleMic(event: MicrophonePacketEvent) {
        val singerId = event.senderConnection?.player?.uuid ?: return
        val stage = KaraokeManager.stageOfSinger(singerId) ?: return
        val singer = stage.singers[singerId] ?: return
        if (event.packet.opusEncodedData.isNotEmpty()) lastVoice[singerId] = System.nanoTime()
        // Away from the display (or offline): voice chat routes them as usual
        if (!singer.present) return
        // Without the API (detached) nothing below can send: voice chat routes them as usual
        val api = api ?: return
        val senderConnection = event.senderConnection ?: return
        val senderGroup = senderConnection.group
        val mic = event.packet
        // Private (a whisper, a group that isn't open): voice chat keeps it to whom it is meant for
        if (!KaraokeRouting.interceptsMic(groupTypeOf(senderGroup), mic.isWhispering)) {
            if (senderGroup != null && !mic.isWhispering) tellPrivate(singerId)
            return
        }
        // Nobody hears a singer's raw voice; only the copies below
        event.cancel()
        val now = System.nanoTime()
        val sender = senderConnection.player
        // Their open group hears them as voice chat would: its copy, anywhere, at once
        val groupMembers = senderGroup?.let { membersOf(api, it.id, singerId) }.orEmpty()
        if (groupMembers.isNotEmpty()) {
            val packet = mic.staticSoundPacketBuilder().build()
            for (memberId in groupMembers) api.getConnectionOf(memberId)?.let { api.sendStaticSoundPacketTo(it, packet) }
        }
        val proximity = api.voiceChatDistance
        val singing = stage.singers.keys
        val voice = voices.computeIfAbsent(singerId) { SingerVoice() }
        val stop = mic.opusEncodedData.isEmpty()

        // Other singers: at once and from the singer, like normal voice chat, each at a volume faded by whether
        // they sing themselves right now
        voice.retainCoSingers(singing)
        for (otherId in singing) {
            if (otherId == singerId) continue
            val other = api.getConnectionOf(otherId) ?: continue
            val near = isNear(other.player, sender, proximity)
            val route = KaraokeRouting.withGroups(
                KaraokeRouting.route(singerId, otherId, singing, false, near, stage.fromSinger),
                otherId in groupMembers, groupTypeOf(other.group),
            )
            if (route != KaraokeRoute.NORMAL) continue
            val volume = KaraokeCoSinger.target(stage.songPlaying, lastVoice[otherId], now, stage.coSinger)
            val stream = voice.coSinger(otherId)
            val data = stream.faded(api, mic.opusEncodedData, volume, now, stage.coSinger) ?: continue
            val packet = entityPacket(mic, KaraokeVoiceSend.toCoSinger(singerId, mic.isWhispering, proximity), data)
            if (voice.startStream(otherId) && stage.debug) {
                logger.info(
                    "Karaoke voice {} -> {} (singer): entity sound, distance {}, volume {}, connected {}.",
                    singerId, otherId, proximity.toInt(), volume, other.isConnected,
                )
            }
            api.sendEntitySoundPacketTo(other, packet)
        }

        val seq = mic.locationalSoundPacketBuilder().position(api.createPosition(0.0, 0.0, 0.0)).build().sequenceNumber
        // For the singer's capture -> arrival time (matched with their client's capture markers)
        KaraokeMeasurements.onFrame(singerId, seq, now)
        val burst = voice.onFrame(now, seq, stop)
        if (burst < 0) return
        // Listeners' copies at singer_volume: decoded, boosted (soft-limited) and re-encoded once per frame, in order
        var listenerData: ByteArray? = null
        for (listener in stage.listeners) {
            val connection = api.getConnectionOf(listener.playerId) ?: continue
            val near = stage.fromSinger && isNear(connection.player, sender, proximity)
            val route = KaraokeRouting.withGroups(
                KaraokeRouting.route(singerId, listener.playerId, singing, listener.inRange, near, stage.fromSinger),
                listener.playerId in groupMembers, groupTypeOf(connection.group),
            )
            if (route != KaraokeRoute.ALIGNED) continue
            val delayMs = KaraokeTiming.emitDelayMs(
                singer.leadMs, singer.pingMs, singer.latency, listener.pingMs, listener.latency, singer.trimMs, stage.latency,
            )
            val data = listenerData ?: voice.listeners.scaled(api, mic.opusEncodedData, stage.singerVolume)
                .also { listenerData = it }
            val shift = voice.shiftFor(burst, listener.playerId, delayMs * 1_000_000L)
            val provisional = voice.estimate(burst, seq, lock = false) ?: return
            if (voice.startStream(listener.playerId) && stage.debug) {
                logger.info(
                    "Karaoke voice {} -> {} (listener): sound from the {}, distance >= {}, delay {} ms, " +
                            "in display range {}, near singer {}, connected {}.",
                    singerId, listener.playerId, if (stage.fromSinger) "singer" else "display", distanceFor(stage, null).toInt(),
                    delayMs, listener.inRange, near, connection.isConnected,
                )
            }
            emitter.schedule(provisional + shift - LOOKAHEAD_SLACK_NANOS) { nowNanos ->
                send(voice, burst, seq, shift, singerId, listener.playerId, stage, mic, data, nowNanos)
            }
        }
    }

    private fun groupTypeOf(group: Group?): VoiceGroup = when (group?.type) {
        null -> VoiceGroup.NONE
        Group.Type.OPEN -> VoiceGroup.OPEN
        Group.Type.ISOLATED -> VoiceGroup.ISOLATED
        else -> VoiceGroup.NORMAL
    }

    /** Online players in voice chat group [groupId], [except] the singer. */
    private fun membersOf(api: VoicechatServerApi, groupId: UUID, except: UUID): Set<UUID> =
        Bukkit.getOnlinePlayers().mapNotNullTo(HashSet()) { player ->
            player.uniqueId.takeIf { it != except && api.getConnectionOf(it)?.group?.id == groupId }
        }

    /** Tells [singerId] once per singing that the audience can't hear them while they talk in a private group. */
    private fun tellPrivate(singerId: UUID) {
        if (!toldPrivate.add(singerId)) return
        com.dreamdisplays.platform.server.meta.Scheduler.runSync {
            Bukkit.getPlayer(singerId)?.let {
                com.dreamdisplays.platform.server.utils.MessageUtil.sendColoredMessage(
                    it, com.dreamdisplays.platform.server.utils.MessageUtil.formatIndexed(it, "karaokePrivateGroup"),
                )
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
        stage: KaraokeStage, mic: MicrophonePacket, data: ByteArray, nowNanos: Long,
    ): Long? {
        val target = (voice.estimate(burst, seq, lock = false) ?: return null) + shift
        if (target - nowNanos > EARLY_TOLERANCE_NANOS) return target
        // The first send of a burst fixes its anchor: from here the listener's client plays it at its own rate
        voice.estimate(burst, seq, lock = true)
        val api = api ?: return null
        val connection = api.getConnectionOf(listenerId) ?: return null
        // From the singer: where they are now, so the voice follows them
        val singer = if (stage.fromSinger) api.getConnectionOf(singerId)?.player else null
        val singerPos = singer?.position
        val near = singer != null && isNear(connection.player, singer, api.voiceChatDistance)
        // Entity sound is on the singer's own channel; their raw mic frames are cancelled, so it is the only stream
        // there (same sequence numbers)
        when (val how = KaraokeVoiceSend.toListener(singerId, stage.fromSinger, singer != null, near, distanceFor(stage, singerPos))) {
            is KaraokeVoiceSend.Entity -> api.sendEntitySoundPacketTo(connection, entityPacket(mic, how, data))
            is KaraokeVoiceSend.Locational -> {
                val packet = mic.locationalSoundPacketBuilder()
                    .opusEncodedData(data)
                    .channelId(how.channel)
                    .position(singerPos?.takeIf { how.atSinger } ?: api.createPosition(stage.x, stage.y, stage.z))
                    .distance(how.distance.toFloat())
                    .build()
                api.sendLocationalSoundPacketTo(connection, packet)
            }
        }
        voice.onSent(listenerId, nowNanos - target, nowNanos)
        return null
    }

    /** The only place entity packets are built, so the entity UUID voice chat requires is always set. */
    private fun entityPacket(mic: MicrophonePacket, how: KaraokeVoiceSend.Entity, data: ByteArray?): EntitySoundPacket {
        val builder = mic.entitySoundPacketBuilder()
            .entityUuid(how.entity)
            .whispering(how.whispering)
            .distance(how.distance.toFloat())
        if (data != null) builder.opusEncodedData(data)
        return builder.build()
    }

    /** One stream of a singer's frames at a volume: decode -> [KaraokeVoiceGain] -> encode. Thread-safe. */
    private open class GainCodec {
        private var decoder: OpusDecoder? = null
        private var encoder: OpusEncoder? = null

        /** Closed for good: a frame still in flight passes through rather than opening codecs nobody closes. */
        private var closed = false

        /** [data] at [volume]; passes through at 1.0, and the empty end-of-burst frame passes and resets the codecs. */
        fun scaled(api: VoicechatServerApi, data: ByteArray, volume: Double): ByteArray =
            ramped(api, data, volume, volume)

        /** [data] at a gain moving from [from] to [to] across the frame; see [scaled]. */
        @Synchronized
        fun ramped(api: VoicechatServerApi, data: ByteArray, from: Double, to: Double): ByteArray {
            if (data.isEmpty()) {
                decoder?.resetState()
                encoder?.resetState()
                return data
            }
            if (closed || KaraokeVoiceGain.isUnity(from) && KaraokeVoiceGain.isUnity(to)) return data
            val dec = decoder ?: api.createDecoder().also { decoder = it }
            val enc = encoder ?: api.createEncoder().also { encoder = it }
            val pcm = dec.decode(data)
            KaraokeVoiceGain.applyRamp(pcm, from, to)
            return enc.encode(pcm)
        }

        @Synchronized
        fun close() {
            closed = true
            decoder?.close()
            encoder?.close()
            decoder = null
            encoder = null
        }
    }

    /** The copy one other singer hears, its gain faded per [KaraokeCoSinger.Ramp]. Thread-safe. */
    private class CoSingerStream : GainCodec() {
        private val ramp = KaraokeCoSinger.Ramp()

        /** [data] faded toward [volume]; null when it would be silent anyway (not sent). */
        @Synchronized
        fun faded(
            api: VoicechatServerApi, data: ByteArray, volume: Double, nowNanos: Long, levels: KaraokeCoSinger.Levels,
        ): ByteArray? {
            if (data.isEmpty()) return ramped(api, data, volume, volume)
            val (from, to) = ramp.next(volume, nowNanos, levels)
            if (from <= 0.0 && to <= 0.0) return null
            return ramped(api, data, from, to)
        }
    }

    /** One singer's anchor and per-burst listener delays, plus statistics. Thread-safe. */
    private class SingerVoice {
        private val anchor = BurstAnchor()
        private val shifts = LinkedHashMap<Int, HashMap<UUID, Long>>()
        private val lastLate = HashMap<UUID, Long>()
        private val worstLate = HashMap<UUID, Long>()
        private val streams = HashSet<UUID>()
        private var frames = 0L
        private var sent = 0L
        private var late = 0L

        /** True the first time [receiverId] gets this singer's voice (for the debug log). */
        @Synchronized
        fun startStream(receiverId: UUID): Boolean = streams.add(receiverId)

        /** Re-encodes the copy each other singer hears (`co_singer_volume` / `co_singer_idle_volume`). */
        private val coSingers = ConcurrentHashMap<UUID, CoSingerStream>()

        fun coSinger(hearerId: UUID): CoSingerStream = coSingers.computeIfAbsent(hearerId) { CoSingerStream() }

        /** Drops the streams of hearers who no longer sing on the stage. */
        fun retainCoSingers(singing: Set<UUID>) {
            if (coSingers.keys.all { it in singing }) return
            coSingers.keys.filter { it !in singing }.forEach { coSingers.remove(it)?.close() }
        }

        /** Re-encodes the copies listeners hear (`singer_volume`); its own codec state, apart from [coSinger]'s. */
        val listeners = GainCodec()

        fun close() {
            coSingers.values.forEach { it.close() }
            coSingers.clear()
            listeners.close()
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

        const val MIC_ERROR_LOG_NANOS = 60_000_000_000L
    }
}
