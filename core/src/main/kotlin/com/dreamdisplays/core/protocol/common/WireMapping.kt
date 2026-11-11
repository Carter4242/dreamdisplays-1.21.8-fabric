package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.shared.capability.ClientFeature
import com.dreamdisplays.shared.capability.ServerFeature
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.PlaybackSpeed
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.core.protocol.common.packets.DisplaySync
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import kotlinx.serialization.Serializable
import java.util.*

/** True if this server snapshot advertises [feature]. */
fun ServerHello.hasFeature(feature: ServerFeature): Boolean =
    feature.wire in allowedFeatures

/** True if this client says it does [feature]; false for every client that predates `ClientHello.features`. */
fun ClientHello.hasFeature(feature: ClientFeature): Boolean =
    feature.wire in features

/**
 * Builds the wire [DisplaySync] for [id] in [mode], stamped with the current [nowMs] anchor; [mediaGen] is the
 * display's [com.dreamdisplays.core.protocol.common.packets.DisplayInfo.mediaGen] (0 = not stamped).
 */
fun Timeline.toSync(
    id: @Serializable(UuidSerializer::class) UUID, mode: PlaybackMode, nowMs: Long, mediaGen: Long = 0L,
): DisplaySync {
    // A start scheduled ahead of now: say where it starts and how long until then
    if (!paused && serverTimeMs > nowMs) {
        return DisplaySync(
            id = id,
            isSync = mode == PlaybackMode.SYNCED,
            isPaused = false,
            currentTimeMs = positionMs,
            durationMs = durationMs,
            serverTimeMs = nowMs,
            loop = loop,
            mode = mode.wire,
            rate = rate,
            startInMs = (serverTimeMs - nowMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            mediaGen = mediaGen,
        )
    }
    val anchored = anchoredAt(nowMs)
    return DisplaySync(
        id = id,
        isSync = mode == PlaybackMode.SYNCED,
        isPaused = anchored.paused,
        currentTimeMs = anchored.positionMs,
        durationMs = anchored.durationMs,
        serverTimeMs = anchored.serverTimeMs,
        loop = anchored.loop,
        mode = mode.wire,
        rate = anchored.rate,
        mediaGen = mediaGen,
    )
}

/** Reconstructs a core [Timeline] from an incoming wire [DisplaySync] packet. */
fun DisplaySync.toTimeline(): Timeline = Timeline(
    positionMs = currentTimeMs,
    serverTimeMs = serverTimeMs,
    paused = isPaused,
    durationMs = durationMs,
    loop = loop,
    rate = PlaybackSpeed.clamp(rate),
)
