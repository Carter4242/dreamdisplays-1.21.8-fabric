package com.dreamdisplays.platform.server.karaoke

import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.platform.server.playback.DisplayActivity
import com.dreamdisplays.core.activity.ActivityKind
import com.dreamdisplays.core.karaoke.KaraokeLatency
import com.dreamdisplays.core.karaoke.KaraokeTiming
import com.dreamdisplays.core.karaoke.KaraokeCoSinger
import com.dreamdisplays.core.karaoke.KaraokeTrackDuck
import com.dreamdisplays.core.karaoke.LeadResync
import com.dreamdisplays.core.protocol.common.packets.KaraokeOffsetReport
import com.dreamdisplays.core.protocol.common.toSync
import com.dreamdisplays.core.queue.QueueItem
import com.dreamdisplays.core.queue.QueueTiming
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.playback.PaperPlaybackTransport
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.QueueManager
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.policy.DisplayPolicies
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.PlatformUtil
import com.dreamdisplays.platform.server.utils.net.PaperV2Networking
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import com.dreamdisplays.shared.playback.PlaybackContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Karaoke on synced / broadcast displays: who sings where, their lead, the singer line tied to the display's queue,
 * and the [KaraokeStage] snapshots the voice side ([KaraokeVoice]) reads.
 *
 * There is no on / off: a display is in karaoke exactly while someone sings on it (added by hand, or their line turn
 * came up). When the last singer stops (removed, left the line, logged out, their song ended) everything is back to
 * normal: normal voice chat routing and the normal timeline. `stop` ends it at once and clears the line.
 *
 * Timing is automatic: clients report what they measured ([KaraokeMeasurements]): the singer's audio output, mic
 * capture and (clock-synced) capture -> server time, each listener's voice chat buffer and output, and the singer's
 * own client listens to their singing against the track and reports how far off it is, which slowly moves their
 * automatic trim. What is not reported falls back to the `[karaoke]` constants and pings.
 *
 * - Singers get the display's timeline [Singer.leadMs] ahead ([TimelineManager.playerLeadsMs]), so they hear the
 *   track and see the lyrics early; their voice is re-timed per listener by the voice side.
 * - The line: a player joins with a link, which is queued on the display tagged with them. When that entry becomes
 *   the display's video ([QueueManager.beforeStart], just before the switch), they become its singer, so even the
 *   first timeline of their song carries their lead; when it ends (next entry, a hand-set video, or the end of the
 *   video with nothing queued) they stop.
 *
 * Everything runs on the main thread except [stageOfSinger] (voice chat's network thread). State is in memory only:
 * a reload or restart ends karaoke (queued songs stay queued but lose their singer).
 */
@PaperOnly
object KaraokeManager {
    data class Settings(
        val defaultLeadMs: Long = 0L,
        val leadMarginMs: Long = 40L,
        val minLeadMs: Long = 260L,
        val maxLeadMs: Long = 1_500L,
        val latency: KaraokeLatency = KaraokeLatency(),
        val skipAbsent: Boolean = true,
        val fromSinger: Boolean = true,
        val coSinger: KaraokeCoSinger.Levels = KaraokeCoSinger.Levels(),
        val trackVolume: Double = KaraokeTrackDuck.DEFAULT_TRACK_VOLUME,
        val singerTrackVolume: Double = KaraokeTrackDuck.DEFAULT_SINGER_TRACK_VOLUME,
        val singerVolume: Double = 1.4,
        val debug: Boolean = false,
    )

    /**
     * A singer on a display. [itemId]: the queue entry they sing, when they came from the line. [autoLead]: their lead
     * is the automatic one and follows what is needed ([KaraokeTiming.autoLeadChange]); a lead set by hand stays.
     */
    data class Singer(
        val playerId: UUID, val name: String, val leadMs: Long, val trimMs: Long, val itemId: String?,
        val autoLead: Boolean = false,
    )

    /** A line entry: [playerId] sings queue entry [itemId] when it comes up. */
    data class LineEntry(val playerId: UUID, val name: String, val itemId: String)

    private class State {
        @Volatile
        var trimMs = 0L
        val singers = ConcurrentHashMap<UUID, Singer>()
        val line = ConcurrentHashMap<String, LineEntry>()
    }

    private val logger = LoggerFactory.getLogger(javaClass)
    private val states = ConcurrentHashMap<UUID, State>()
    private val singerDisplay = ConcurrentHashMap<UUID, UUID>()
    private val duck = KaraokeTrackDuck()

    @Volatile
    private var leads: Map<UUID, Map<UUID, Long>> = emptyMap()

    @Volatile
    private var stagesBySinger: Map<UUID, KaraokeStage> = emptyMap()
    private var ticks = 0L

    /** The voice chat side, or null when Simple Voice Chat is not installed (karaoke is then unavailable). */
    @Volatile
    var voice: KaraokeVoice? = null
        private set

    val available: Boolean get() = voice != null

    /** Where the `[karaoke]` settings come from. */
    var settings: () -> Settings = { Settings() }

    /** Hooks karaoke into the timeline and queue, and into Simple Voice Chat when it is installed. */
    fun init() {
        voice = loadVoice()
        TimelineManager.playerLeadsMs = { leads[it] ?: emptyMap() }
        QueueManager.beforeStart = ::beforeStart
        TimelineManager.onLoopRestart = ::onLoopRestart
    }

    /** Detaches from voice chat and forgets every session (plugin disable). */
    fun shutdown() {
        TimelineManager.playerLeadsMs = { emptyMap() }
        QueueManager.beforeStart = { _, _ -> true }
        voice?.stop()
        voice = null
        states.clear()
        singerDisplay.clear()
        leads = emptyMap()
        stagesBySinger = emptyMap()
        duck.clear()
        KaraokeMeasurements.clear()
    }

    private fun loadVoice(): KaraokeVoice? {
        if (Bukkit.getPluginManager().getPlugin("voicechat")?.isEnabled != true) return null
        if (PlatformUtil.isFolia) {
            // Stages read player positions from one thread, which Folia does not allow
            logger.info("Karaoke is not available on Folia.")
            return null
        }
        // By name: its class refers to voice chat's API, which only exists when voice chat is installed
        val voice = runCatching {
            Class.forName("com.dreamdisplays.platform.server.karaoke.SvcKaraokeVoice")
                .getDeclaredConstructor().newInstance() as KaraokeVoice
        }.onFailure { logger.warn("Karaoke could not load its Simple Voice Chat support.", it) }.getOrNull()
        return voice?.takeIf { it.start() }
    }

    /** The stage [singerId] sings on, for the voice side; null when they are not singing. Any thread. */
    fun stageOfSinger(singerId: UUID): KaraokeStage? = stagesBySinger[singerId]

    /** True while someone sings on [display] (it is in karaoke). */
    fun isActive(display: DisplayData): Boolean = states[display.id]?.singers?.isNotEmpty() == true

    /**
     * Whether [playerId] sings on display [displayId] now. A singer keeps a display tied to a region wherever they stand
     * (and hears its track unfaded): they sing to it.
     */
    fun isSinging(displayId: UUID, playerId: UUID): Boolean =
        states[displayId]?.singers?.containsKey(playerId) == true

    /** Waiting line entries of [display]. */
    fun lineSize(display: DisplayData): Int = states[display.id]?.line?.size ?: 0

    fun trimOf(display: DisplayData): Long = states[display.id]?.trimMs ?: 0L

    fun singersOf(display: DisplayData): List<Singer> = states[display.id]?.singers?.values?.sortedBy { it.name }.orEmpty()

    /** Karaoke needs a server timeline: synced or broadcast displays only. */
    fun runsOn(display: DisplayData): Boolean =
        !display.virtual && (display.mode == PlaybackMode.SYNCED || display.mode == PlaybackMode.BROADCAST)

    /**
     * Ends karaoke on [display] at once: every singer stops (and gets the normal timeline) and the line is emptied,
     * its songs dropped from the queue. Returns how many singers and line entries there were.
     */
    fun stop(display: DisplayData): Pair<Int, Int> {
        val state = states[display.id] ?: return 0 to 0
        val singers = state.singers.keys.toList()
        singers.forEach { removeSinger(display, it) }
        val line = clearLine(display)
        refresh()
        return singers.size to line
    }

    /**
     * [display]'s video was cleared: everyone singing there stops (there is no song). The line stays, with its songs
     * queued: the next one to play brings its singer up as usual.
     */
    fun endSinging(display: DisplayData) {
        val state = states[display.id] ?: return
        state.singers.keys.toList().forEach { removeSinger(display, it, resend = false) }
    }

    /**
     * Makes [playerId] a singer on [display] with [leadMs] (null = the automatic lead), taking them off any other
     * display. Unless [duet], they replace the display's current singers. [resend]: send the changed singers their
     * timeline now (false when a new video is about to go out anyway). Returns the lead.
     */
    fun addSinger(
        display: DisplayData, playerId: UUID, name: String, leadMs: Long?,
        itemId: String? = null, resend: Boolean = true, duet: Boolean = false,
    ): Long {
        singerDisplay[playerId]?.takeIf { it != display.id }?.let { other ->
            DisplayManager.getDisplayData(other)?.let { removeSinger(it, playerId) }
        }
        val state = states.computeIfAbsent(display.id) { State() }
        if (!duet) state.singers.keys.filter { it != playerId }.forEach { removeSinger(display, it, resend) }
        val previous = state.singers[playerId]
        // A new singer's offset reports start afresh (the trim they learned this session stays)
        if (previous == null) KaraokeMeasurements.onSingingStarted(playerId)
        val wanted = leadMs ?: autoLeadMs(display, playerId)
        // Already singing with about this lead: keep it, and spare them a paused resync
        val unchanged = previous != null && abs(wanted - previous.leadMs) < KaraokeTiming.LEAD_DEADBAND_MS
        val lead = if (unchanged) previous!!.leadMs else wanted
        state.singers[playerId] = Singer(playerId, name, lead, previous?.trimMs ?: 0L, itemId, autoLead = leadMs == null)
        if (previous == null) DisplayActivity.record(display, ActivityKind.KARAOKE_STAGE, playerId, name, flag = true)
        singerDisplay[playerId] = display.id
        refresh()
        if (resend && !unchanged) resendTimeline(display, playerId)
        return lead
    }

    /** Stops [playerId] singing on [display]; [resend] sends them the normal timeline again. False if they weren't. */
    fun removeSinger(display: DisplayData, playerId: UUID, resend: Boolean = true): Boolean {
        val state = states[display.id] ?: return false
        val singer = state.singers.remove(playerId) ?: return false
        DisplayActivity.record(display, ActivityKind.KARAOKE_STAGE, playerId, singer.name, flag = false)
        singerDisplay.remove(playerId, display.id)
        // Off the stage first, so no new voice frame of theirs makes fresh codecs after the old ones are closed
        refresh()
        voice?.forget(playerId)
        if (resend) resendTimeline(display, playerId)
        return true
    }

    fun setTrim(display: DisplayData, ms: Long) {
        states.computeIfAbsent(display.id) { State() }.trimMs = ms
        refresh()
    }

    /** Sets a singer's own trim; false when [playerId] is not singing on [display]. */
    fun setSingerTrim(display: DisplayData, playerId: UUID, ms: Long): Boolean {
        val state = states[display.id] ?: return false
        state.singers.computeIfPresent(playerId) { _, s -> s.copy(trimMs = ms) } ?: return false
        refresh()
        return true
    }

    /**
     * The automatic lead for [playerId] on [display] now: their measured singer side plus the worst measured listener
     * side in range, room for a negative trim, and the margin (see [KaraokeTiming.autoLeadMs]); `default_lead_ms`
     * when that is set. Read-only.
     */
    fun autoLeadMs(display: DisplayData, playerId: UUID): Long {
        val s = settings()
        if (s.defaultLeadMs > 0L) return s.defaultLeadMs
        val singerPing = Bukkit.getPlayer(playerId)?.ping?.toLong() ?: 0L
        val singerSide = KaraokeTiming.singerSideMs(
            singerPing, KaraokeMeasurements.singerLatency(playerId, System.nanoTime()), s.latency,
        )
        val state = states[display.id]
        val singers = state?.singers?.keys.orEmpty() + playerId
        val worst = (display as? PaperDisplayData)?.let { listenersOf(it, singers) }
            ?.maxOfOrNull { KaraokeTiming.listenerSideMs(it.pingMs.coerceAtMost(MAX_PING_FOR_LEAD_MS), it.latency, s.latency) }
            ?: KaraokeTiming.listenerSideMs(singerPing, null, s.latency)
        val trim = (state?.trimMs ?: 0L) + (state?.singers?.get(playerId)?.trimMs ?: 0L) +
                KaraokeMeasurements.autoTrimMs(playerId)
        return KaraokeTiming.autoLeadMs(singerSide, worst, trim, s.leadMarginMs, s.minLeadMs, s.maxLeadMs)
    }

    /**
     * A singer's client reports how late their voice is against the track they heard. Taken only from a current
     * singer of that display while its song plays; it slowly moves their automatic trim.
     */
    fun onOffsetReport(player: Player, report: KaraokeOffsetReport) {
        val state = states[report.id] ?: return
        if (!state.singers.containsKey(player.uniqueId)) return
        val stage = stagesBySinger[player.uniqueId]?.takeIf { it.displayId == report.id && it.songPlaying } ?: return
        if (stage.singers[player.uniqueId]?.present != true) return
        val moved = KaraokeMeasurements.onOffset(
            player.uniqueId, report.offsetMs.toDouble(), report.confidence, PaperPlaybackTransport.nowMs(),
        )
        if (settings().debug) {
            logger.info(
                "Karaoke on display {}: {} sings {} ms {} (confidence {}); automatic trim {} ms.",
                report.id, player.name, kotlin.math.abs(report.offsetMs), if (report.offsetMs >= 0) "late" else "early",
                "%.2f".format(report.confidence), KaraokeMeasurements.autoTrimMs(player.uniqueId),
            )
        }
        if (moved) refresh()
    }

    /** [playerId] logged out: they stop singing wherever they did, and their measurements are forgotten. */
    fun onQuit(playerId: UUID) {
        singerDisplay[playerId]?.let { displayId ->
            DisplayManager.getDisplayData(displayId)?.let { removeSinger(it, playerId, resend = false) }
        }
        KaraokeMeasurements.forget(playerId)
    }

    /** The current stage of [display] (listeners, positions), built now; null when nobody sings there. */
    fun stageOf(display: DisplayData): KaraokeStage? {
        val paper = display as? PaperDisplayData ?: return null
        val state = states[display.id] ?: return null
        return buildStage(paper, state)
    }

    // ---- The line ----

    enum class LineResult { OK, ALREADY, NOT_IN_LINE, QUEUE_REFUSED }

    data class JoinOutcome(val result: LineResult, val queueResult: QueueManager.Result? = null, val position: Int = 0)

    /** Queues [url] on [display] for [playerId] and puts them in the line for it. [url] must already be validated. */
    fun join(display: DisplayData, playerId: UUID, name: String, url: String): JoinOutcome {
        val state = states.computeIfAbsent(display.id) { State() }
        if (state.line.values.any { it.playerId == playerId }) return JoinOutcome(LineResult.ALREADY)
        val result = QueueManager.add(display, adminContext(display), playerId, name, url, "", "", 0L, "", throttled = false)
        if (result != QueueManager.Result.OK) return JoinOutcome(LineResult.QUEUE_REFUSED, result)
        val queue = QueueManager.snapshotOf(display.id) ?: return JoinOutcome(LineResult.QUEUE_REFUSED, QueueManager.Result.REFUSED)
        val taken = state.line.keys + state.singers.values.mapNotNull { it.itemId }
        val item = (queue.items + listOfNotNull(queue.current))
            .filter { it.addedBy == playerId && it.url == url && it.itemId !in taken }
            .maxByOrNull { it.addedAtMs }
            ?: return JoinOutcome(LineResult.QUEUE_REFUSED, QueueManager.Result.REFUSED)
        if (queue.current?.itemId == item.itemId) {
            // The display was empty, so the queue started this video at once: its singer starts now
            Bukkit.getPlayer(playerId)?.takeIf { isPresent(display, it) }?.let {
                addSinger(display, playerId, it.name, null, item.itemId)
            }
            return JoinOutcome(LineResult.OK, position = 0)
        }
        state.line[item.itemId] = LineEntry(playerId, name, item.itemId)
        DisplayActivity.record(display, ActivityKind.KARAOKE_JOIN, playerId, name)
        return JoinOutcome(LineResult.OK, position = lineOf(display).indexOfFirst { it.second.playerId == playerId } + 1)
    }

    /** Takes [playerId] out of [display]'s line (their queued song too) and stops them if they sing from it. */
    fun leave(display: DisplayData, playerId: UUID): LineResult {
        val state = states[display.id] ?: return LineResult.NOT_IN_LINE
        val entries = state.line.values.filter { it.playerId == playerId }
        val singing = state.singers[playerId]?.takeIf { it.itemId != null }
        if (entries.isEmpty() && singing == null) return LineResult.NOT_IN_LINE
        entries.forEach { dropEntry(display, state, it) }
        if (singing != null) removeSinger(display, playerId)
        DisplayActivity.record(display, ActivityKind.KARAOKE_LEAVE, playerId)
        return LineResult.OK
    }

    /** Empties [display]'s line, dropping its songs from the queue; returns how many there were. */
    fun clearLine(display: DisplayData): Int {
        val state = states[display.id] ?: return 0
        val entries = state.line.values.toList()
        entries.forEach { dropEntry(display, state, it) }
        return entries.size
    }

    /** The line in play order: each waiting queue entry with its singer. */
    fun lineOf(display: DisplayData): List<Pair<QueueItem, LineEntry>> {
        val state = states[display.id] ?: return emptyList()
        val queue = QueueManager.snapshotOf(display.id) ?: return emptyList()
        return queue.items.mapNotNull { item -> state.line[item.itemId]?.let { item to it } }
    }

    /** The line entry of [nameOrId] on [display] (name match first, so offline players can be removed). */
    fun lineEntryNamed(display: DisplayData, nameOrId: String): LineEntry? =
        states[display.id]?.line?.values?.firstOrNull {
            it.name.equals(nameOrId, ignoreCase = true) || it.playerId.toString() == nameOrId
        }

    /** The singer of [display] called [nameOrId], online or not. */
    fun singerNamed(display: DisplayData, nameOrId: String): Singer? =
        states[display.id]?.singers?.values?.firstOrNull {
            it.name.equals(nameOrId, ignoreCase = true) || it.playerId.toString() == nameOrId
        }

    private fun dropEntry(display: DisplayData, state: State, entry: LineEntry) {
        state.line.remove(entry.itemId)
        // Karaoke's own bookkeeping, not a player's edit: never refused by the edit throttle
        QueueManager.removeEntry(display, entry.itemId)
    }

    /**
     * Queue hook, just before [display] switches to [item]: the previous line singer is done (the new video's timeline
     * reaches them unshifted), and [item]'s singer, if any, starts singing now. A singer who is offline or away skips
     * their song, or it plays without them (`absent_singer`).
     */
    private fun beforeStart(display: DisplayData, item: QueueItem): Boolean {
        val state = states[display.id] ?: return true
        state.singers.values.filter { it.itemId != null }.forEach { removeSinger(display, it.playerId, resend = false) }
        // Singers who stay (added by hand) start the new song with the automatic lead it needs now
        followAutoLeads(display, state, playing = false, resend = false)
        val entry = state.line.remove(item.itemId) ?: return true
        val player = Bukkit.getPlayer(entry.playerId)
        if (player == null || !isPresent(display, player)) {
            val skip = settings().skipAbsent
            logger.info(
                "Karaoke on display {}: {} is not here for their song; {}.",
                display.id, entry.name, if (skip) "skipping it" else "playing it without a singer",
            )
            return !skip
        }
        val lead = addSinger(display, entry.playerId, player.name, null, item.itemId, resend = false)
        logger.info("Karaoke on display {}: {} is up, {} ms ahead.", display.id, player.name, lead)
        MessageUtil.sendColoredMessage(player, MessageUtil.formatIndexed(player, "karaokeYouAreUp", display.shortLabel))
        return true
    }

    /** Online, with the mod and voice chat, in the display's world and audio range. */
    private fun isPresent(display: DisplayData, player: Player): Boolean {
        val paper = display as? PaperDisplayData ?: return false
        if (player.world != paper.pos1.world || !V2PlayerTracker.isV2(player.uniqueId)) return false
        if (voice?.isConnected(player.uniqueId) != true) return false
        return distanceToBox(player.location.x, player.location.y, player.location.z, paper.box) <= audioRadius(paper)
    }

    // ---- Ticking and stages ----

    /**
     * Every couple of ticks: ends finished line songs and singers who left, prunes the line, forgets displays nobody
     * sings or waits on, pings singers' clocks, and rebuilds the stages.
     */
    fun tick() {
        if (states.isEmpty()) {
            if (stagesBySinger.isNotEmpty()) stagesBySinger = emptyMap()
            voice?.retain(emptySet())
            syncTrackVolume()
            return
        }
        val slow = ++ticks % SLOW_TICKS == 0L
        for ((displayId, state) in states) {
            val display = DisplayManager.getDisplayData(displayId)
            if (display == null) {
                state.singers.keys.forEach { singerDisplay.remove(it, displayId); voice?.forget(it) }
                states.remove(displayId)
                continue
            }
            endFinishedSongs(display, state)
            state.singers.keys.filter { Bukkit.getPlayer(it) == null }.forEach { removeSinger(display, it, resend = false) }
            if (slow) {
                pruneLine(display, state)
                // Not while a start is held for readiness: singers are waited for at their lead's position
                if (!TimelineManager.isHolding(displayId)) followAutoLeads(display, state, songPlaying(displayId), resend = true)
            }
            if (state.singers.isEmpty() && state.line.isEmpty() && state.trimMs == 0L) states.remove(displayId)
        }
        refresh()
        KaraokeMeasurements.pingSingers(
            singerDisplay.keys,
            capable = { V2PlayerTracker.helloOf(it)?.supportsKaraoke == true },
            send = { id, ping -> Bukkit.getPlayer(id)?.let { PaperV2Networking.send([it], ping) } },
        )
    }

    /**
     * A line singer stops when the display no longer plays their entry (hand-set video), or their song reached its end
     * with nothing queued after it (the display then just loops it).
     */
    private fun endFinishedSongs(display: DisplayData, state: State, restartedAtEnd: Boolean = false) {
        val fromLine = state.singers.values.filter { it.itemId != null }
        if (fromLine.isEmpty()) return
        val current = QueueManager.snapshotOf(display.id)?.current?.itemId
        val timeline = TimelineManager.timelineOf(display.id)
        val untilEnd = timeline?.let { QueueTiming.msUntilEnd(it, PaperPlaybackTransport.nowMs()) }
        for (singer in fromLine) {
            if (!KaraokeTiming.lineSongOver(singer.itemId, current, untilEnd, SONG_END_MS, restartedAtEnd)) continue
            // At a restart the restarted timeline is sent right after, without their lead
            removeSinger(display, singer.playerId, resend = !restartedAtEnd)
        }
    }

    /** [display]'s video reached its end with nothing queued and starts over for everyone: a line song there is done. */
    private fun onLoopRestart(display: DisplayData) {
        val state = states[display.id] ?: return
        endFinishedSongs(display, state, restartedAtEnd = true)
    }

    /**
     * Moves automatic leads to what [autoLeadMs] needs now (a learned trim, measured latencies and listeners in range
     * change it), within [KaraokeTiming.autoLeadChange]'s limits; [resend] sends a changed singer their timeline.
     */
    private fun followAutoLeads(display: DisplayData, state: State, playing: Boolean, resend: Boolean) {
        for (singer in state.singers.values.toList()) {
            if (!singer.autoLead) continue
            val lead = KaraokeTiming.autoLeadChange(singer.leadMs, autoLeadMs(display, singer.playerId), playing) ?: continue
            state.singers.computeIfPresent(singer.playerId) { _, s -> s.copy(leadMs = lead) } ?: continue
            if (settings().debug) {
                logger.info(
                    "Karaoke on display {}: {}'s automatic lead {} -> {} ms{}.",
                    display.id, singer.name, singer.leadMs, lead, if (playing) " (mid-song)" else "",
                )
            }
            refresh()
            if (resend) resendTimeline(display, singer.playerId)
        }
    }

    /** [displayId]'s timeline runs (not paused, started, not held for readiness). */
    private fun songPlaying(displayId: UUID): Boolean {
        val timeline = TimelineManager.timelineOf(displayId) ?: return false
        return !timeline.paused && timeline.serverTimeMs <= PaperPlaybackTransport.nowMs() && !TimelineManager.isHolding(displayId)
    }

    private fun pruneLine(display: DisplayData, state: State) {
        if (state.line.isEmpty()) return
        val queued = QueueManager.snapshotOf(display.id)?.items?.mapTo(HashSet()) { it.itemId }.orEmpty()
        state.line.keys.removeIf { it !in queued }
    }

    /** Rebuilds the lead map and the stages from the current state. */
    private fun refresh() {
        val newLeads = HashMap<UUID, Map<UUID, Long>>()
        val stages = HashMap<UUID, KaraokeStage>()
        for ((displayId, state) in states) {
            if (state.singers.isEmpty()) continue
            newLeads[displayId] = state.singers.values.associate { it.playerId to it.leadMs }
            val display = DisplayManager.getDisplayData(displayId) as? PaperDisplayData ?: continue
            val stage = buildStage(display, state) ?: continue
            for (singerId in stage.singers.keys) stages[singerId] = stage
        }
        leads = newLeads
        stagesBySinger = stages
        // A voice frame in flight while a singer was removed can have made fresh voice state after it was dropped
        voice?.retain(stages.keys)
        syncTrackVolume()
    }

    /** Re-sends the displays that gained their first singer or lost their last, so `audio.gain` follows. */
    private fun syncTrackVolume() {
        val active = states.filterValues { it.singers.isNotEmpty() }.keys
        val changed = duck.update(active)
        // At 1.0 nothing changes, so there is nothing to re-send
        if (settings().trackVolume >= 1.0 && settings().singerTrackVolume == 1.0) return
        for (displayId in changed) {
            (DisplayManager.getDisplayData(displayId) as? PaperDisplayData)?.let(DisplayManager::broadcastUpdate)
        }
    }

    private fun buildStage(display: PaperDisplayData, state: State): KaraokeStage? {
        if (state.singers.isEmpty()) return null
        val box = display.box
        val radius = audioRadius(display)
        val s = settings()
        val nowNanos = System.nanoTime()
        val singers = state.singers.values.associate {
            val player = Bukkit.getPlayer(it.playerId)
            val present = player != null && player.world == display.pos1.world &&
                    distanceToBox(player.location.x, player.location.y, player.location.z, box) <= radius
            val trim = state.trimMs + it.trimMs + KaraokeMeasurements.autoTrimMs(it.playerId)
            it.playerId to StageSinger(
                it.leadMs, trim, player?.ping?.toLong() ?: 0L, present,
                KaraokeMeasurements.singerLatency(it.playerId, nowNanos),
            )
        }
        val halfDiagonal = sqrt(box.widthX * box.widthX + box.height * box.height + box.widthZ * box.widthZ) / 2.0
        return KaraokeStage(
            displayId = display.id,
            displayLabel = display.shortLabel,
            x = box.centerX, y = box.centerY, z = box.centerZ,
            distance = radius + halfDiagonal,
            latency = s.latency,
            singers = singers,
            listeners = listenersOf(display, singers.keys, everyone = s.fromSinger),
            fromSinger = s.fromSinger,
            songPlaying = songPlaying(display.id),
            coSinger = s.coSinger,
            singerVolume = s.singerVolume,
            debug = s.debug,
            world = display.pos1.world,
        )
    }

    /** Players in [display]'s audio range who are not singing on it. */
    fun listenersNear(display: DisplayData): List<StageListener> =
        (display as? PaperDisplayData)?.let { listenersOf(it, states[display.id]?.singers?.keys.orEmpty()) }.orEmpty()

    /** Players in [display]'s audio range (with [everyone], all in its world, flagged), except [singers]. */
    private fun listenersOf(display: PaperDisplayData, singers: Set<UUID>, everyone: Boolean = false): List<StageListener> {
        val world = display.pos1.world ?: return emptyList()
        val radius = audioRadius(display)
        return world.players.mapNotNull {
            if (it.uniqueId in singers) return@mapNotNull null
            // A display tied to a region: its singers are heard where the display is (in the region or its fade band)
            val inRange = distanceToBox(it.location.x, it.location.y, it.location.z, display.box) <= radius &&
                    DisplayManager.hearsDisplay(it, display)
            if (inRange || everyone) {
                StageListener(it.uniqueId, it.ping.toLong(), inRange, KaraokeMeasurements.listenerLatency(it.uniqueId))
            } else null
        }
    }

    /** Where the display's sound fades out: its `audio.radius_blocks` policy, else the client default. */
    fun audioRadius(display: DisplayData): Double =
        DisplayPolicies.effective(display).audio?.radiusBlocks?.takeIf { it > 0.0 } ?: DEFAULT_AUDIO_RADIUS

    private fun distanceToBox(x: Double, y: Double, z: Double, box: BoundingBox): Double {
        val dx = maxOf(box.minX - x, 0.0, x - box.maxX)
        val dy = maxOf(box.minY - y, 0.0, y - box.maxY)
        val dz = maxOf(box.minZ - z, 0.0, z - box.maxZ)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Sends [playerId] alone the display's timeline after their lead changed ([LeadResync]): one packet, in the
     * display's own state (a playing display stays playing for them), shifted by their new lead. Never paused first:
     * that restarts their player and leaves them without sound for seconds. Nobody else's timeline changed.
     */
    private fun resendTimeline(display: DisplayData, playerId: UUID) {
        val transport = PaperPlaybackTransport
        val timeline = TimelineManager.timelineOf(display.id)
        val now = transport.nowMs()
        val lead = leads[display.id]?.get(playerId) ?: 0L
        val packets = LeadResync.packetsFor(
            playerId, transport.nearbyPlayerIds(display), timeline?.toSync(display.id, display.mode, now, display.mediaGen), lead,
        )
        for ((target, sync) in packets) TimelineManager.sendShifted(target, display.id, lead, sync)
    }

    private fun adminContext(display: DisplayData): PlaybackContext =
        PlaybackContexts.of(display, display.ownerId, isAdmin = true).copy(isOwner = false, isAdmin = true, isLocked = false)

    /** Reads `[karaoke]` from the live config. */
    fun settingsFromConfig(): Settings = PaperServer.config.settings.karaoke.let {
        Settings(
            defaultLeadMs = it.default_lead_ms.toLong(),
            leadMarginMs = it.lead_margin_ms.toLong(),
            minLeadMs = it.min_lead_ms.toLong(),
            maxLeadMs = it.max_lead_ms.toLong(),
            latency = KaraokeLatency(it.singer_output_capture_ms.toLong(), it.listener_buffer_output_ms.toLong()),
            skipAbsent = it.absent_singer != "play",
            fromSinger = it.voice_source != "display",
            coSinger = KaraokeCoSinger.Levels(
                singing = it.co_singer_volume,
                idle = it.co_singer_idle_volume,
                idleAfterMs = it.co_singer_idle_after_ms.toLong(),
                fadeDownMs = it.co_singer_fade_down_ms.toLong(),
                fadeUpMs = it.co_singer_fade_up_ms.toLong(),
            ),
            trackVolume = it.track_volume,
            singerTrackVolume = it.singer_track_volume,
            singerVolume = it.singer_volume,
            debug = it.debug,
        )
    }

    private const val DEFAULT_AUDIO_RADIUS = 96.0
    private const val MAX_PING_FOR_LEAD_MS = 400L
    /** Under the queue's own early advance (250 ms), so a queued next song takes over first. */
    private const val SONG_END_MS = 100L
    private const val SLOW_TICKS = 10L
}
