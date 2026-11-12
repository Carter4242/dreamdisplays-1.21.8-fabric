package com.dreamdisplays.platform.client.compat.flashback

import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.platform.client.displays.DisplayRegistry
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
import java.util.*

/**
 * One Flashback export: every display's picture and sound, frame by frame at the export's time instead of in real time
 * (see [ExportPicture], [ExportSound]). Driven once per drawn frame, before the displays are drawn; a frame drawn again
 * (Flashback warms up and waits on screens) is not taken twice. Render thread only. [close] when the export ends.
 */
internal class ReplayExport(
    private val framerate: Double,
    private val recordAudio: Boolean,
    private val trace: ((String) -> Unit)? = null,
) {
    private val pictures = HashMap<UUID, ExportPicture>()
    private val sounds = HashMap<UUID, ExportSound>()
    private val shown = HashMap<UUID, RenderType>()
    private val previous = HashMap<UUID, Long>() // each display's content position (ns) in the frame before
    private var lastFrameMs = Double.NaN
    private var sampleCarry = 0.0

    /** The frame at replay time [replayMs]: decodes each playing display's picture there and queues its sound. */
    fun frame(replayMs: Double, views: Map<UUID, ReplayView>) {
        if (replayMs == lastFrameMs) return
        lastFrameMs = replayMs
        val (samples, carry) = samplesForFrame(sampleCarry, framerate)
        sampleCarry = carry
        val frameMs = 1000.0 / framerate
        shown.clear()
        for ((id, view) in views) {
            val sample = view.sample ?: continue
            if (view.card != null || view.info.url.isEmpty()) continue
            val screen = DisplayRegistry.screens[id] ?: continue
            // The player's resolved streams only while it holds this video (a description may have changed since)
            val ownVideo = screen.videoUrl == view.info.url
            val pictureUrl = (if (ownVideo) screen.scrubPreviewRawUrl else null) ?: view.info.url
            val audioUrl = (if (ownVideo) screen.currentAudioTrackUrl.ifEmpty { null } else null) ?: pictureUrl
            val fromNanos = sample.positionNanosAt(replayMs)
            trace?.invoke("export frame T=${replayMs.toLong()} ${id.toString().take(8)} content=${fromNanos / 1_000_000L}")
            val w = screen.textureWidth.takeIf { it > 0 } ?: DEFAULT_W
            val h = screen.textureHeight.takeIf { it > 0 } ?: DEFAULT_H
            pictures.getOrPut(id) { ExportPicture(id) }.frameAt(pictureUrl, w, h, fromNanos)?.let { shown[id] = it }
            val before = previous.put(id, fromNanos)
            if (!recordAudio) continue
            val toNanos = endOfFrame(sample, fromNanos, before, frameMs)
            val c = centre(view)
            sounds.getOrPut(id) { ExportSound(id) }.frame(
                audioUrl = audioUrl, playing = !sample.paused, fromNanos = fromNanos, toNanos = toNanos, outSamples = samples,
                x = c[0], y = c[1], z = c[2], gain = if (screen.muted) 0f else screen.volume, maxDistance = MAX_DISTANCE,
            )
        }
        previous.keys.retainAll(views.keys)
    }

    /** What display [id] shows in the frame being exported, or null to leave it to its player. */
    fun picture(id: UUID): RenderType? = shown[id]

    /** Ends the export's pictures and sounds. Render thread, while the export's sound device is still the game's. */
    fun close() {
        pictures.values.forEach { runCatching { it.dispose() } }
        sounds.values.forEach { runCatching { it.close() } }
        pictures.clear()
        sounds.clear()
        shown.clear()
    }

    private fun centre(view: ReplayView): DoubleArray {
        val i = view.info
        val alongX = DisplayFacing.entries.getOrNull(i.facing)?.let { it == DisplayFacing.NORTH || it == DisplayFacing.SOUTH } ?: true
        val half = i.width / 2.0
        return doubleArrayOf(
            i.x + 0.5 + if (alongX) half - 0.5 else 0.0,
            i.y + i.height / 2.0,
            i.z + 0.5 + if (alongX) 0.0 else half - 0.5,
        )
    }

    companion object {
        /** Exactly the samples Flashback renders for one frame, carrying the fraction the same way it does. */
        fun samplesForFrame(carry: Double, framerate: Double): Pair<Int, Double> {
            val total = carry + ExportSound.RATE / framerate
            val n = total.toInt()
            return n to (total - n)
        }

        /**
         * Where the content one export frame covers ends, from where it starts ([fromNanos]): as far as the frame before
         * moved ([before], its start), which follows the replay's own pace (speed keyframes); a frame's length at the
         * timeline's rate for the first frame or after a jump; nowhere while paused.
         */
        fun endOfFrame(sample: ReplaySample, fromNanos: Long, before: Long?, frameMs: Double): Long {
            if (sample.paused) return fromNanos
            val step = before?.let { fromNanos - it }?.takeIf { it in 1..MAX_FRAME_STEP_NANOS }
                ?: (frameMs * 1_000_000.0 * sample.rate).toLong()
            return fromNanos + step
        }

        /** A frame-to-frame move larger than this is a jump, not the replay's pace. */
        private const val MAX_FRAME_STEP_NANOS = 1_000_000_000L

        private const val DEFAULT_W = 854
        private const val DEFAULT_H = 480

        /** Blocks over which a display's sound fades out in an export. */
        private const val MAX_DISTANCE = 48f
    }
}
