package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.shared.playback.FullscreenAckAction
import com.dreamdisplays.shared.playback.FullscreenMode
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.shared.playback.FullscreenSession
import com.dreamdisplays.shared.playback.FullscreenPrepareRoster
import com.dreamdisplays.shared.playback.FullscreenPrepareState
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.FullscreenPrepare
import com.dreamdisplays.core.protocol.common.packets.FullscreenState
import com.dreamdisplays.core.protocol.common.toSync
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.storage.FullscreenSessionStore
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** A player is targeted for radius-based fullscreen sessions when within [blocks] of (x, y, z) in [world]. */
data class FullscreenRadiusTarget(val world: String, val x: Double, val y: Double, val z: Double, val blocks: Double)

/** Manages server-forced fullscreen broadcast sessions targeting players by name or radius. */
object FullscreenBroadcastManager {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val TICK_MS = 1_000L
    private const val TIMELINE_RESEND_MS = 5_000L
    private const val REDELIVER_UNCONFIRMED_MS = 1_500L
    private const val DELIVERY_CONFIRM_WINDOW_MS = 20_000L
    private const val PREPARE_REDELIVER_MS = 5_000L
    private const val PREPARE_FIRST_REPORT_MS = 10_000L
    private const val PREPARE_LAST_REPORT_MS = 60_000L
    private lateinit var transport: PlaybackTransport
    private val sessions = ConcurrentHashMap<String, Session>()

    /** Live preparations by display id; a display holds at most one. */
    private val preparations = ConcurrentHashMap<UUID, Preparation>()

    /** How long a preparation lives before it lets go; the platform points this at `fullscreen.prepare_timeout_seconds`. */
    var prepareTimeoutMs: () -> Long = { 600_000L }

    /**
     * A `/display fullscreen prepare`: the targeted clients load [display] ahead of the broadcast and hold it hidden
     * and ready, so `start` on the same display (or URL) shows it at once. [timeline] backs a URL-only (virtual)
     * display, paused at its start; a real display keeps following its own [TimelineManager] timeline.
     */
    private class Preparation(
        val roster: FullscreenPrepareRoster,
        val display: DisplayData,
        val virtual: Boolean,
        val quality: String,
        val namedTargets: Set<UUID>?,
        val radius: FullscreenRadiusTarget?,
        val timeline: Timeline?,
        val createdAtMs: Long,
        var reporter: ((FullscreenPrepareInfo) -> Unit)?,
        var reportedOnce: Boolean = false,
        var lastTimelineResend: Long = 0,
    ) {
        val prepareId: String get() = roster.prepareId
    }

    private class Session(
        val sessionId: String,
        var display: DisplayData,
        val virtual: Boolean,
        val transientSession: Boolean,
        val ownerId: UUID,
        var mode: FullscreenMode,
        var forced: Boolean,
        var volume: Float,
        var loop: Boolean,
        var quality: String,
        var title: String,
        var namedTargets: Set<UUID>?,
        var radius: FullscreenRadiusTarget?,
        var timeline: Timeline?,
        val shownTo: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
        val dismissedBy: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
        val minimizedBy: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
        val confirmedBy: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
        val deliveredAtMs: MutableMap<UUID, Long> = ConcurrentHashMap(),
        val retryUntilMs: MutableMap<UUID, Long> = ConcurrentHashMap(),
        var lastTick: Long = 0,
        var lastTimelineResend: Long = 0,
    )

    /** Wires the platform transport. */
    fun init(transport: PlaybackTransport) {
        this.transport = transport
        TimelineManager.extraReceivers = ::timelineFollowers
    }

    /**
     * Far-away players who follow real display [displayId] anyway: its fullscreen viewers and prepared clients. They
     * hold it loaded at any distance and get its timeline, description and queue like players in range.
     */
    fun timelineFollowers(displayId: UUID): Collection<UUID> {
        val prepared = preparations[displayId]?.roster?.delivered
        val shown = sessions.values.firstOrNull { !it.virtual && it.display.id == displayId }?.shownTo
        if (prepared.isNullOrEmpty()) return shown?.toSet() ?: emptySet()
        if (shown.isNullOrEmpty()) return prepared
        return prepared + shown
    }

    /** True if [displayId] is shown or prepared by a fullscreen session (virtual displays included). */
    fun knowsDisplay(displayId: UUID): Boolean =
        preparations.containsKey(displayId) || sessions.values.any { it.display.id == displayId }

    /**
     * Real display [displayId] reached the end of its video: a fullscreen session of it set not to loop is over (its
     * viewers' clients closed the overlay at their end of stream already; the server forgets it and says so, or a
     * rejoin would show it again).
     */
    fun endNonLoopingSessions(displayId: UUID) {
        sessions.values.filter { !it.virtual && !it.loop && it.display.id == displayId }.map { it.sessionId }.forEach { stop(it) }
    }

    /**
     * Real display [displayId]'s video was cleared: every fullscreen session of it is over, looping or not (its viewers
     * would keep an empty overlay, a forced one closable only by `/display fullscreen stop`). Call after the display's
     * empty description went out, so far viewers drop the video before the overlay closes.
     */
    fun endSessionsOf(displayId: UUID) {
        sessions.values.filter { !it.virtual && it.display.id == displayId }.map { it.sessionId }.forEach { stop(it) }
    }

    /** True if a session with [sessionId] is currently live. */
    fun hasSession(sessionId: String): Boolean = sessions.containsKey(sessionId)

    /** The live session ids targeting a given real display, if any (a display can only host one). */
    fun sessionIdForDisplay(displayId: UUID): String? =
        sessions.values.firstOrNull { !it.virtual && it.display.id == displayId }?.sessionId

    /** Resolves a display by id/prefix or creates a virtual one if the string looks like a URL. */
    fun resolveOrCreateDisplay(
        idOrUrl: String,
        ownerId: UUID,
        virtualDisplayId: UUID? = null,
    ): Pair<DisplayData, Boolean>? {
        resolveDisplayByIdOrPrefix(idOrUrl)?.let { return it to false }
        if (!looksLikeUrl(idOrUrl)) return null
        val url = MediaSource.from(idOrUrl).toResolvableUrl() ?: idOrUrl
        // A URL prepared earlier: reuse its display, which the prepared clients already hold under that id
        if (virtualDisplayId == null) {
            preparations.values.firstOrNull { it.virtual && it.display.url == url }?.let { return it.display to true }
        }
        val virtual = transport.createVirtualDisplay(virtualDisplayId ?: UUID.randomUUID(), ownerId) ?: return null
        virtual.url = url
        return virtual to true
    }

    /** Resolves a network fullscreen target to its URL for broadcasting. */
    fun resolveNetworkFullscreenUrl(idOrUrl: String): String? {
        displayUrlByIdOrPrefix(idOrUrl)?.let { return it }
        if (!looksLikeUrl(idOrUrl)) return null
        return MediaSource.from(idOrUrl).toResolvableUrl() ?: idOrUrl
    }

    /** Gets the video URL of a display by full id or unambiguous prefix. */
    fun displayUrlByIdOrPrefix(idOrPrefix: String): String? =
        resolveDisplayByIdOrPrefix(idOrPrefix)?.url?.takeIf(String::isNotBlank)

    private fun resolveDisplayByIdOrPrefix(idOrPrefix: String): DisplayData? =
        DisplayManager.resolveByIdOrPrefix(idOrPrefix)

    private fun looksLikeUrl(value: String): Boolean =
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(value) ||
                Regex("""^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)+(?:[/?#].*)?$""")
                    .matches(value.trim())

    /** Sends the current timeline to a player if a live session owns the display. */
    fun sendCurrentTo(displayId: UUID, playerId: UUID): Boolean {
        val session = sessions.values.firstOrNull { it.display.id == displayId }
        if (session != null) {
            sendTimeline(session, playerId, transport.nowMs())
            return true
        }
        val preparation = preparations[displayId]?.takeIf { it.roster.isDelivered(playerId) } ?: return false
        sendPrepareTimeline(preparation, playerId, transport.nowMs())
        return true
    }

    /**
     * Starts a new session. [namedTargets] and [radius] are combined by OR - at least one must be
     * given. Returns the started session's id, or null if neither targeting condition was set, a
     * session with [sessionId] already exists, or [display] already hosts a session.
     */
    fun start(
        sessionId: String,
        display: DisplayData,
        virtual: Boolean,
        transientSession: Boolean,
        ownerId: UUID,
        mode: FullscreenMode,
        forced: Boolean,
        volume: Float,
        loop: Boolean,
        quality: String,
        title: String,
        namedTargets: Set<UUID>?,
        radius: FullscreenRadiusTarget?,
        timelineAnchorMs: Long? = null,
    ): String? {
        if (namedTargets.isNullOrEmpty() && radius == null) return null
        if (sessions.containsKey(sessionId)) return null
        if (!virtual && sessionIdForDisplay(display.id) != null) return null

        // Clients holding this display ready play it the moment the session reaches them
        val preparation = preparations.remove(display.id)
        if (preparation != null && !virtual) TimelineManager.playNow(display)

        val now = transport.nowMs()
        val session = Session(
            sessionId = sessionId,
            display = display,
            virtual = virtual,
            transientSession = transientSession,
            ownerId = ownerId,
            mode = mode,
            forced = forced,
            volume = volume,
            loop = loop,
            quality = quality,
            title = title,
            namedTargets = namedTargets,
            radius = radius,
            // Prepared clients hold the start frame: schedule it a moment ahead so they all begin together
            timeline = if (virtual) Timeline.start(timelineAnchorMs ?: (now + preparedLead(display, preparation)), loop = true) else null,
        )
        sessions[sessionId] = session
        deliverToAll(session)
        if (preparation != null) {
            // Prepared players the broadcast does not reach let go of the display
            for (playerId in preparation.roster.delivered) {
                if (playerId !in session.shownTo) withdrawPrepare(preparation, playerId)
            }
            val tally = preparation.roster.tally()
            logger.info(
                "Fullscreen session $sessionId took over preparation ${preparation.prepareId} " +
                        "(${tally.ready}/${tally.delivered} ready).",
            )
        }
        persist()
        return sessionId
    }

    private fun preparedLead(display: DisplayData, preparation: Preparation?): Long =
        if (preparation == null) 0L else ReadinessTracker.leadMs(display.id, preparation.roster.delivered)

    /** Stops [sessionId], notifying every player it was shown to. Returns false if it doesn't exist. */
    fun stop(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return false
        teardown(session)
        persist()
        return true
    }

    /** Snapshot of live sessions for the `list` subcommand: (sessionId, display id, title, reach count). */
    fun list(): List<FullscreenSessionInfo> = sessions.values.map { session ->
        FullscreenSessionInfo(
            session.sessionId,
            session.display.id,
            session.virtual,
            session.title,
            session.shownTo.size
        )
    }

    /**
     * Applies a client's [FullscreenAckAction] for [sessionId]. Dismissing an unforced session (Esc)
     * drops the player from its targets and means stop.
     */
    fun handleAck(sessionId: String, playerId: UUID, action: FullscreenAckAction) {
        val session = sessions[sessionId] ?: return
        when (action) {
            FullscreenAckAction.SHOWN -> {
                session.confirmedBy.add(playerId)
                session.minimizedBy.remove(playerId)
                sendTimeline(session, playerId, transport.nowMs())
                onMinimizedChanged?.invoke(session.sessionId, playerId, false)
            }

            // Only from a player it is shown to: anyone else's would stop a session nobody is watching yet
            FullscreenAckAction.DISMISSED -> if (!session.forced && session.shownTo.remove(playerId)) {
                session.dismissedBy.add(playerId)
                if (session.shownTo.isEmpty()) stop(sessionId)
            }

            FullscreenAckAction.MINIMIZED -> {
                session.confirmedBy.add(playerId)
                session.minimizedBy.add(playerId)
                sendTimeline(session, playerId, transport.nowMs())
                onMinimizedChanged?.invoke(session.sessionId, playerId, true)
            }
        }
    }

    /**
     * Fired when a viewer collapses a session to PiP or restores it, so
     * [com.dreamdisplays.platform.server.proxy.NetworkFullscreenManager]-style listeners can track reach.
     */
    var onMinimizedChanged: ((sessionId: String, playerId: UUID, minimized: Boolean) -> Unit)? = null

    /** Forgets sessions for a player who left, so a rejoin is treated as a fresh delivery (a past dismissal doesn't carry over either). */
    fun onPlayerQuit(playerId: UUID) {
        sessions.values.forEach {
            forget(it, playerId)
            it.dismissedBy.remove(playerId)
        }
        preparations.values.forEach { it.roster.forget(playerId) }
    }

    private fun forget(session: Session, playerId: UUID) {
        session.shownTo.remove(playerId)
        session.confirmedBy.remove(playerId)
        session.deliveredAtMs.remove(playerId)
        session.retryUntilMs.remove(playerId)
    }

    /** Re-sends every live session's state to [playerId] on join, in case they're still within a radius/selector. */
    fun onPlayerJoin(playerId: UUID) {
        for (session in sessions.values) {
            if (isTargeted(session, playerId)) deliverTo(session, playerId)
        }
        val now = transport.nowMs()
        for (preparation in preparations.values) {
            if (isTargeted(preparation, playerId) && supportsPrepare(playerId)) deliverPrepare(preparation, playerId, now)
        }
    }

    /**
     * Adds [playerId] to [sessionId]'s frozen `named targets` and delivers immediately if they're online —
     * `named` sessions don't re-scan for new targets.
     */
    fun addTarget(sessionId: String, playerId: UUID, minimized: Boolean = false): Boolean {
        val session = sessions[sessionId] ?: return false
        session.namedTargets = (session.namedTargets ?: emptySet()) + playerId
        session.dismissedBy.remove(playerId)
        if (minimized) session.minimizedBy.add(playerId) else session.minimizedBy.remove(playerId)
        if (playerId in transport.onlinePlayerIds()) deliverTo(session, playerId)
        return true
    }

    /**
     * Ends a removed display's sessions: every viewer is told (a far one is outside the deletion broadcast and would
     * keep a forced overlay of a display nobody can stop any more).
     */
    fun onDisplayRemoved(displayId: UUID) {
        sessions.values.filter { !it.virtual && it.display.id == displayId }.map { it.sessionId }.forEach { id ->
            sessions.remove(id)?.let(::teardown)
        }
        preparations.remove(displayId)?.let { preparation ->
            // Far prepared clients hold the display outside the deletion broadcast's range
            for (playerId in preparation.roster.delivered) {
                withdrawPrepare(preparation, playerId)
                transport.sendTo(playerId, DisplayDelete(displayId))
            }
        }
        persist()
    }

    /**
     * Plugin disable: transient sessions are not saved, so the next plugin instance could never end them; their viewers
     * are told they are over now. Saved sessions carry on after the reload.
     */
    fun endTransientSessions() {
        sessions.values.filter { it.transientSession }.map { it.sessionId }.forEach { id ->
            sessions.remove(id)?.let(::teardown)
        }
    }

    /** Re-evaluates radius membership and refreshes drifting clients. Called once per second. */
    fun tick() {
        if (sessions.isEmpty() && preparations.isEmpty()) return
        val now = transport.nowMs()
        for (session in sessions.values) {
            if (now - session.lastTick < TICK_MS) continue
            session.lastTick = now
            reconcileTargets(session, now)
        }
        for (preparation in preparations.values) reconcilePreparation(preparation, now)
    }

    /**
     * Starts preparing [display] for a broadcast: every targeted client that supports it loads the display, keeps it
     * hidden, muted and loaded at any distance, and holds it ready to play at once. [rewind] first pauses a real
     * display at the start of its video (a URL is always held at its start). A previous preparation of the same
     * display is replaced in place: it keeps its id, so clients that stay targeted keep what they loaded. [reporter]
     * hears once when every client settled, or after a few seconds, whichever is first, and again when a partial first
     * report later settles.
     *
     * Returns null when [display] already hosts a running broadcast.
     */
    fun prepare(
        prepareId: String,
        display: DisplayData,
        virtual: Boolean,
        quality: String,
        namedTargets: Set<UUID>?,
        radius: FullscreenRadiusTarget?,
        rewind: Boolean,
        reporter: ((FullscreenPrepareInfo) -> Unit)?,
    ): FullscreenPrepareInfo? {
        if (!virtual && sessionIdForDisplay(display.id) != null) return null
        val previous = preparations.remove(display.id)
        if (rewind && !virtual) TimelineManager.parkAtStart(display)
        val now = transport.nowMs()
        val preparation = Preparation(
            roster = FullscreenPrepareRoster(
                previous?.prepareId ?: prepareId, now + prepareTimeoutMs().coerceAtLeast(10_000L),
            ),
            display = display,
            virtual = virtual,
            quality = quality,
            namedTargets = namedTargets,
            radius = radius,
            timeline = if (virtual) Timeline.start(now, paused = true, loop = true) else null,
            createdAtMs = now,
            reporter = reporter,
        )
        preparations[display.id] = preparation
        reconcilePreparation(preparation, now)
        previous?.roster?.delivered?.forEach { if (!preparation.roster.isDelivered(it)) withdrawPrepare(previous, it) }
        logger.info("Preparing display ${display.id} for a fullscreen broadcast (${preparation.prepareId}).")
        return infoOf(preparation, now)
    }

    /** The quality [displayId] is prepared at, so a start that names none keeps the streams the clients hold open. */
    fun preparedQuality(displayId: UUID): String? = preparations[displayId]?.quality

    /** Cancels the preparation with [prepareId] or of the display [displayId]; false when there is none. */
    fun cancelPreparation(prepareId: String, displayId: UUID? = null): Boolean {
        val preparation = preparations.values.firstOrNull { it.prepareId == prepareId }
            ?: displayId?.let { preparations[it] }
            ?: return false
        cancelPreparation(preparation)
        return true
    }

    /** Live preparations for the `list` subcommand. */
    fun listPreparations(): List<FullscreenPrepareInfo> {
        val now = transport.nowMs()
        return preparations.values.map { infoOf(it, now) }
    }

    /** Applies a client's progress report on a preparation. */
    fun onPrepareStatus(playerId: UUID, prepareId: String, displayId: UUID, state: FullscreenPrepareState) {
        val preparation = preparations[displayId] ?: return
        if (!preparation.roster.onStatus(playerId, prepareId, state)) return
        if (state == FullscreenPrepareState.FAILED) {
            logger.info("Player $playerId could not load display $displayId for preparation $prepareId.")
        }
    }

    private fun cancelPreparation(preparation: Preparation) {
        if (!preparations.remove(preparation.display.id, preparation)) return
        for (playerId in preparation.roster.delivered) withdrawPrepare(preparation, playerId)
        logger.info("Cancelled fullscreen preparation ${preparation.prepareId}.")
    }

    private fun reconcilePreparation(preparation: Preparation, now: Long) {
        if (preparation.roster.isExpired(now)) {
            logger.info("Fullscreen preparation ${preparation.prepareId} expired.")
            cancelPreparation(preparation)
            return
        }
        val roster = preparation.roster
        for (playerId in transport.onlinePlayerIds()) {
            val targeted = isTargeted(preparation, playerId)
            when {
                targeted && supportsPrepare(playerId) -> if (!roster.isDelivered(playerId)) {
                    deliverPrepare(preparation, playerId, now)
                }

                targeted -> if (V2PlayerTracker.isV2(playerId)) roster.onUnsupported(playerId)
                roster.isDelivered(playerId) -> withdrawPrepare(preparation, playerId)
                else -> roster.forget(playerId)
            }
        }
        for (playerId in roster.dueForRedelivery(now, PREPARE_REDELIVER_MS)) deliverPrepare(preparation, playerId, now)
        if (preparation.virtual && now - preparation.lastTimelineResend >= TIMELINE_RESEND_MS) {
            preparation.lastTimelineResend = now
            for (playerId in roster.delivered) sendPrepareTimeline(preparation, playerId, now)
        }
        reportIfDue(preparation, now)
    }

    private fun reportIfDue(preparation: Preparation, now: Long) {
        val reporter = preparation.reporter ?: return
        val age = now - preparation.createdAtMs
        val tally = preparation.roster.tally()
        val settled = tally.settled && tally.delivered > 0
        val due = if (!preparation.reportedOnce) settled || age >= PREPARE_FIRST_REPORT_MS
        else settled || age >= PREPARE_LAST_REPORT_MS
        if (!due) return
        if (settled || preparation.reportedOnce) preparation.reporter = null
        preparation.reportedOnce = true
        runCatching { reporter(infoOf(preparation, now)) }.onFailure { logger.debug("Prepare report failed.", it) }
    }

    private fun deliverPrepare(preparation: Preparation, playerId: UUID, now: Long) {
        val display = preparation.display
        transport.sendTo(
            playerId,
            FullscreenPrepare(
                prepareId = preparation.prepareId,
                displayId = display.id,
                active = true,
                quality = preparation.quality,
                ttlMs = (preparation.roster.expiresAtMs - now).coerceAtLeast(1L),
            ),
        )
        transport.sendDisplayInfo(playerId, display, forced = true)
        sendPrepareTimeline(preparation, playerId, now)
        preparation.roster.onDelivered(playerId, now)
    }

    private fun withdrawPrepare(preparation: Preparation, playerId: UUID) {
        transport.sendTo(playerId, FullscreenPrepare(preparation.prepareId, preparation.display.id, active = false))
        if (preparation.virtual) transport.sendTo(playerId, DisplayDelete(preparation.display.id))
        preparation.roster.forget(playerId)
    }

    private fun sendPrepareTimeline(preparation: Preparation, playerId: UUID, now: Long) {
        val timeline = preparation.timeline
        if (timeline != null) {
            val sync = timeline.toSync(preparation.display.id, PlaybackMode.BROADCAST, now, preparation.display.mediaGen)
            transport.sendTo(playerId, TimelineManager.shiftedFor(preparation.display.id, playerId, sync))
        } else {
            TimelineManager.sendCurrent(preparation.display, playerId)
        }
    }

    private fun isTargeted(preparation: Preparation, playerId: UUID): Boolean {
        // A world display is only prepared for players in its world; others load it the usual way on start
        if (!preparation.virtual && !transport.isInDisplayWorld(playerId, preparation.display)) return false
        if (preparation.namedTargets?.contains(playerId) == true) return true
        val radius = preparation.radius ?: return false
        val distSq = transport.playerDistanceSq(playerId, radius.world, radius.x, radius.y, radius.z) ?: return false
        return distSq <= radius.blocks * radius.blocks
    }

    private fun supportsPrepare(playerId: UUID): Boolean =
        V2PlayerTracker.helloOf(playerId)?.supportsFullscreenPrepare == true

    private fun infoOf(preparation: Preparation, now: Long): FullscreenPrepareInfo = FullscreenPrepareInfo(
        prepareId = preparation.prepareId,
        displayId = preparation.display.id,
        virtual = preparation.virtual,
        tally = preparation.roster.tally(),
        expiresInMs = (preparation.roster.expiresAtMs - now).coerceAtLeast(0L),
    )

    /** Restores persisted (non-transient) sessions from disk; call once worlds/displays are loaded. */
    fun restore() {
        val records = FullscreenSessionStore.load()
        if (records.isEmpty()) return
        var restored = 0
        for (record in records) {
            // One bad record (a malformed id) is skipped, never fatal to the enable
            val ok = runCatching {
                val display = resolveRecordDisplay(record) ?: run {
                    logger.warn("Dropping persisted fullscreen session ${record.sessionId}: display unavailable.")
                    return@runCatching false
                }
                val ownerId = runCatching { UUID.fromString(record.ownerId) }.getOrNull() ?: return@runCatching false
                val now = transport.nowMs()
                val session = Session(
                    sessionId = record.sessionId,
                    display = display,
                    virtual = record.virtual,
                    transientSession = false,
                    ownerId = ownerId,
                    mode = FullscreenMode.entries.getOrElse(record.mode) { FullscreenMode.STANDARD },
                    forced = record.forced,
                    volume = record.volume,
                    loop = record.loop,
                    quality = record.quality,
                    title = record.title,
                    namedTargets = record.namedTargets?.map(UUID::fromString)?.toSet(),
                    radius = record.radiusWorld?.let { world ->
                        FullscreenRadiusTarget(world, record.radiusX, record.radiusY, record.radiusZ, record.radiusBlocks)
                    },
                    timeline = if (record.virtual) Timeline.start(now, loop = true) else null,
                )
                sessions[session.sessionId] = session
                true
            }.onFailure { logger.warn("Dropping persisted fullscreen session ${record.sessionId}: unreadable.", it) }
            if (ok.getOrDefault(false)) restored++
        }
        if (restored > 0) logger.info("Restored $restored persisted fullscreen session(s).")
    }

    private fun resolveRecordDisplay(record: FullscreenSession): DisplayData? {
        val id = runCatching { UUID.fromString(record.displayId) }.getOrNull() ?: return null
        if (!record.virtual) return DisplayManager.getDisplayData(id)
        val ownerId = runCatching { UUID.fromString(record.ownerId) }.getOrNull() ?: return null
        val display = transport.createVirtualDisplay(id, ownerId) ?: return null
        display.url = record.url
        display.lang = record.lang
        return display
    }

    private fun persist() {
        val records = sessions.values.filter { !it.transientSession }.map { session ->
            FullscreenSession(
                sessionId = session.sessionId,
                displayId = session.display.id.toString(),
                virtual = session.virtual,
                url = if (session.virtual) session.display.url else "",
                lang = if (session.virtual) session.display.lang else "",
                ownerId = session.ownerId.toString(),
                mode = session.mode.wire,
                forced = session.forced,
                volume = session.volume,
                loop = session.loop,
                quality = session.quality,
                title = session.title,
                namedTargets = session.namedTargets?.map(UUID::toString),
                radiusWorld = session.radius?.world,
                radiusX = session.radius?.x ?: 0.0,
                radiusY = session.radius?.y ?: 0.0,
                radiusZ = session.radius?.z ?: 0.0,
                radiusBlocks = session.radius?.blocks ?: 0.0,
            )
        }
        FullscreenSessionStore.save(records)
    }

    private fun isTargeted(session: Session, playerId: UUID): Boolean {
        if (playerId in session.dismissedBy) return false
        if (session.namedTargets?.contains(playerId) == true) return true
        val radius = session.radius ?: return false
        val distSq = transport.playerDistanceSq(playerId, radius.world, radius.x, radius.y, radius.z) ?: return false
        return distSq <= radius.blocks * radius.blocks
    }

    private fun deliverToAll(session: Session) {
        for (playerId in transport.onlinePlayerIds()) {
            if (isTargeted(session, playerId)) deliverTo(session, playerId)
        }
    }

    private fun deliverTo(session: Session, playerId: UUID) {
        // Always forced (load at any distance): the session's own `forced` only means it cannot be dismissed, and an
        // unforced description of a far display is only cached, so the fullscreen would never open
        transport.sendDisplayInfo(playerId, session.display, forced = true)
        transport.sendTo(
            playerId,
            FullscreenState(
                sessionId = session.sessionId,
                displayId = session.display.id,
                active = true,
                mode = session.mode.wire,
                forced = session.forced,
                volume = session.volume,
                title = session.title,
                loop = session.loop,
                quality = session.quality,
                minimized = playerId in session.minimizedBy,
            ),
        )
        val now = transport.nowMs()
        sendTimeline(session, playerId, now)
        if (session.shownTo.add(playerId)) session.retryUntilMs[playerId] = now + DELIVERY_CONFIRM_WINDOW_MS
        session.confirmedBy.remove(playerId)
        session.deliveredAtMs[playerId] = now
    }

    private fun sendTimeline(session: Session, playerId: UUID, now: Long) {
        val timeline = session.timeline
        if (timeline != null) {
            val sync = timeline.toSync(session.display.id, PlaybackMode.BROADCAST, now, session.display.mediaGen)
            transport.sendTo(playerId, TimelineManager.shiftedFor(session.display.id, playerId, sync))
        } else {
            TimelineManager.sendCurrent(session.display, playerId)
        }
    }

    private fun reconcileTargets(session: Session, now: Long) {
        val resendDue = now - session.lastTimelineResend >= TIMELINE_RESEND_MS
        for (playerId in transport.onlinePlayerIds()) {
            val targeted = isTargeted(session, playerId)
            val shown = playerId in session.shownTo
            if (targeted && !shown) {
                deliverTo(session, playerId)
            } else if (!targeted && shown) {
                transport.sendTo(playerId, FullscreenState(sessionId = session.sessionId, displayId = session.display.id, active = false))
                forget(session, playerId)
            } else if (targeted) {
                val unconfirmed = playerId !in session.confirmedBy &&
                        now < (session.retryUntilMs[playerId] ?: 0L) &&
                        now - (session.deliveredAtMs[playerId] ?: now) >= REDELIVER_UNCONFIRMED_MS
                if (unconfirmed) deliverTo(session, playerId) else if (resendDue) sendTimeline(session, playerId, now)
            }
        }
        if (resendDue) session.lastTimelineResend = now
    }

    private fun teardown(session: Session) {
        val now = transport.nowMs()
        for (playerId in session.shownTo) {
            transport.sendTo(playerId, FullscreenState(sessionId = session.sessionId, displayId = session.display.id, active = false))
            if (session.virtual) transport.sendTo(playerId, DisplayDelete(session.display.id))
        }
        logger.info("Stopped fullscreen session ${session.sessionId} (reached ${session.shownTo.size} player(s)) at $now.")
    }
}

/** Snapshot of a live fullscreen preparation for the `list` subcommand and the prepare reply. */
data class FullscreenPrepareInfo(
    val prepareId: String,
    val displayId: UUID,
    val virtual: Boolean,
    val tally: FullscreenPrepareRoster.Tally,
    val expiresInMs: Long,
)

/** Snapshot of a live fullscreen session for the `list` subcommand. */
data class FullscreenSessionInfo(
    val sessionId: String,
    val displayId: UUID,
    val virtual: Boolean,
    val title: String,
    val reach: Int
)
