package com.dreamdisplays.platform.client.compat.flashback

import com.dreamdisplays.core.protocol.common.PacketRegistry
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.platform.client.replay.ReplayCard
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.*
import kotlin.math.roundToLong

/**
 * Where a display's timeline stands in a replay: [positionMs] (content ms) at replay time [anchorMs], advancing at
 * [rate] content ms per replay ms unless [paused]. Before [anchorMs] it holds [positionMs] (a start scheduled ahead).
 * In a written frame [anchorMs] is relative to the frame's own tick; [ReplayModel] makes it absolute.
 */
data class ReplaySample(
    val mediaGen: Long,
    val positionMs: Long,
    val anchorMs: Long,
    val rate: Double,
    val paused: Boolean,
    val loop: Boolean,
    val durationMs: Long,
) {
    /** The content position at replay time [replayMs]. */
    fun positionAt(replayMs: Long): Long {
        val raw = if (paused) positionMs else positionMs + ((replayMs - anchorMs).coerceAtLeast(0) * rate).roundToLong()
        return when {
            durationMs <= 0 -> raw.coerceAtLeast(0)
            loop -> Math.floorMod(raw, durationMs)
            else -> raw.coerceIn(0, durationMs)
        }
    }
}

/**
 * One display in a frame. [removed]: gone at this point. [info] null: the frame only carries a [sample]. [card]: the
 * display is shown as a card in replays ([info] then carries no URL).
 */
data class ReplayEntry(
    val id: UUID,
    val info: DisplayInfo? = null,
    val card: ReplayCard? = null,
    val sample: ReplaySample? = null,
    val removed: Boolean = false,
)

/**
 * What one replay action holds. A [full] frame lists every display there is (any other one is gone); an update only the
 * displays that changed.
 */
data class ReplayFrame(val full: Boolean, val entries: List<ReplayEntry>) {
    /** This frame written [delayMs] after it was made: its timelines' anchors are that much further back. */
    fun writtenLater(delayMs: Long): ReplayFrame =
        if (delayMs == 0L) this
        else copy(entries = entries.map { e -> e.copy(sample = e.sample?.let { it.copy(anchorMs = it.anchorMs - delayMs) }) })
}

/** The replay action's payload: a version byte, then the frame. Private to Dream Displays, versioned on its own. */
object ReplayFormat {
    const val VERSION = 1

    private const val F_INFO = 1
    private const val F_SAMPLE = 2
    private const val F_REMOVED = 4

    fun encode(frame: ReplayFrame): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            out.writeBoolean(frame.full)
            out.writeShort(frame.entries.size)
            for (e in frame.entries) {
                out.writeLong(e.id.mostSignificantBits)
                out.writeLong(e.id.leastSignificantBits)
                val flags = (if (e.info != null) F_INFO else 0) or (if (e.sample != null) F_SAMPLE else 0) or
                    (if (e.removed) F_REMOVED else 0)
                out.writeByte(flags)
                e.info?.let { info ->
                    out.writeByte(e.card?.let { it.ordinal + 1 } ?: 0)
                    val encoded = PacketRegistry.encode(info)
                    out.writeInt(encoded.size)
                    out.write(encoded)
                }
                e.sample?.let { s ->
                    out.writeLong(s.mediaGen)
                    out.writeLong(s.positionMs)
                    out.writeLong(s.anchorMs)
                    out.writeDouble(s.rate)
                    out.writeBoolean(s.paused)
                    out.writeBoolean(s.loop)
                    out.writeLong(s.durationMs)
                }
            }
        }
        return bytes.toByteArray()
    }

    /** The frame in [bytes], or null for a newer format this client does not read. */
    fun decode(bytes: ByteArray): ReplayFrame? {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readUnsignedByte() != VERSION) return null
            val full = input.readBoolean()
            val n = input.readUnsignedShort()
            val entries = ArrayList<ReplayEntry>(n)
            repeat(n) {
                val id = UUID(input.readLong(), input.readLong())
                val flags = input.readUnsignedByte()
                var info: DisplayInfo? = null
                var card: ReplayCard? = null
                if (flags and F_INFO != 0) {
                    card = ReplayCard.entries.getOrNull(input.readUnsignedByte() - 1)
                    val encoded = ByteArray(input.readInt()).also(input::readFully)
                    info = PacketRegistry.decode(encoded) as? DisplayInfo
                }
                val sample = if (flags and F_SAMPLE != 0) {
                    ReplaySample(
                        mediaGen = input.readLong(), positionMs = input.readLong(), anchorMs = input.readLong(),
                        rate = input.readDouble(), paused = input.readBoolean(), loop = input.readBoolean(),
                        durationMs = input.readLong(),
                    )
                } else {
                    null
                }
                entries += ReplayEntry(id, info, card, sample, removed = flags and F_REMOVED != 0)
            }
            return ReplayFrame(full, entries)
        }
    }
}
