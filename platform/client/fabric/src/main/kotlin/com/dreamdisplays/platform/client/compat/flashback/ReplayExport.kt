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
    private var lastFrameMs = Long.MIN_VALUE
    private var sampleCarry = 0.0

    /** The frame at replay time [replayMs]: decodes each playing display's picture there and queues its sound. */
    fun frame(replayMs: Long, views: Map<UUID, ReplayView>) {
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
            val url = screen.scrubPreviewRawUrl ?: view.info.url
            val fromMs = sample.positionAt(replayMs)
            trace?.invoke("export frame T=$replayMs ${id.toString().take(8)} content=$fromMs")
            val w = screen.textureWidth.takeIf { it > 0 } ?: DEFAULT_W
            val h = screen.textureHeight.takeIf { it > 0 } ?: DEFAULT_H
            pictures.getOrPut(id) { ExportPicture(id) }.frameAt(url, w, h, fromMs * 1_000_000L)?.let { shown[id] = it }
            if (!recordAudio) continue
            val (fromNanos, toNanos) = contentOfFrame(sample, replayMs, frameMs)
            val c = centre(view)
            sounds.getOrPut(id) { ExportSound(id) }.frame(
                sourceUrl = url, playing = !sample.paused, fromNanos = fromNanos, toNanos = toNanos, outSamples = samples,
                x = c[0], y = c[1], z = c[2], gain = if (screen.muted) 0f else screen.volume, maxDistance = MAX_DISTANCE,
            )
        }
    }

    /** What display [id] shows in the frame being exported, or null to leave it to its player. */
    fun picture(id: UUID): RenderType? = shown[id]

    fun close() {
        pictures.values.forEach { it.close() }
        sounds.values.forEach { it.close() }
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

        /** The content (ns) one export frame from [replayMs] covers: none while paused, its length times the speed. */
        fun contentOfFrame(sample: ReplaySample, replayMs: Long, frameMs: Double): Pair<Long, Long> {
            val from = sample.positionAt(replayMs) * 1_000_000L
            return from to (if (sample.paused) from else from + (frameMs * 1_000_000.0 * sample.rate).toLong())
        }

        private const val DEFAULT_W = 854
        private const val DEFAULT_H = 480

        /** Blocks over which a display's sound fades out in an export. */
        private const val MAX_DISTANCE = 48f
    }
}
