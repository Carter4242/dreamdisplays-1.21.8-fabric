package com.dreamdisplays.platform.client.compat.flashback

import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.render.DisplayUnlitRenderTypes
import com.dreamdisplays.platform.client.utils.identifier
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Export only: one display's picture for a Flashback export, decoded frame-exactly instead of played in real time. For
 * each export frame [frameAt] gives the video frame on screen at a content time (the latest frame starting at or before
 * it), read from its own in-process libav session: forward by decoding on, back or far ahead by reopening a little
 * before it and decoding up to it. The size is fixed for a source once chosen (the display's own texture size can step
 * with distance during an export).
 *
 * Decoding runs on a thread of its own; the render thread, which an export waits on anyway, waits for it at most
 * [DEADLINE_MS] per frame. A source that stalls or fails is given up for the rest of the export (the display then shows
 * what its player shows). Render thread only; [close] when the export ends.
 */
internal class ExportPicture(private val displayId: UUID) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "DreamDisplays-ExportPicture").apply { isDaemon = true } }
    private var url: String? = null
    private var w = 0
    private var h = 0
    @Volatile private var handle = 0L
    private var frameBytes = 0

    /** Raw pts of the video's first frame: the stream's pts start where its content does not (B-frame delay, edits). */
    private var startPts = Long.MIN_VALUE
    private var current: Frame? = null
    private var next: Frame? = null
    private var ended = false
    private var texture: DynamicTexture? = null
    private var textureId: Identifier? = null
    private var renderType: RenderType? = null
    private var uploaded: Frame? = null
    private var uploadedPts = Long.MIN_VALUE
    private var failed = false

    private class Frame(val data: ByteBuffer) {
        var pts = 0L
    }

    /**
     * The render type showing the frame on screen at content time [contentNanos] of [sourceUrl] (decoded at [width] x
     * [height] the first time this source is seen); null when it can't be decoded here.
     */
    fun frameAt(sourceUrl: String, width: Int, height: Int, contentNanos: Long): RenderType? {
        if (sourceUrl != url) {
            close()
            url = sourceUrl
            w = (width.coerceIn(16, 1920) / 2) * 2
            h = (height.coerceIn(16, 1080) / 2) * 2
            failed = false
            startPts = Long.MIN_VALUE
        }
        if (failed) return null
        val t = contentNanos.coerceAtLeast(0L)
        val decoded = timed {
            val cur = current
            if (handle == 0L || cur == null || t < cur.pts - REOPEN_BACK_NANOS || t > (next?.pts ?: cur.pts) + REOPEN_AHEAD_NANOS) {
                if (!open(t)) return@timed false
            }
            advanceTo(t)
            true
        }
        if (decoded != true) {
            failed = true
            if (decoded == null) logger.warn("Export: decoding the video of display {} stalled; it shows its player's picture.", displayId)
            closeSession()
            return null
        }
        val shown = current ?: return null
        upload(shown)
        return renderType
    }

    /** Runs [body] on the decoding thread; null when it did not finish within [DEADLINE_MS] (the session is interrupted). */
    private fun timed(body: () -> Boolean): Boolean? {
        val f = worker.submit(body)
        return try {
            f.get(DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            handle.takeIf { it != 0L }?.let { runCatching { NativeMedia.lavKill(it) } }
            runCatching { f.get(KILL_WAIT_MS, TimeUnit.MILLISECONDS) }
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            logger.warn("Export: decoding the video of display {} failed.", displayId, e.cause)
            false
        }
    }

    /** Makes [current] the latest frame starting at or before [t] and [next] the one after it. Decoding thread. */
    private fun advanceTo(t: Long) {
        while (true) {
            val n = next ?: break
            if (n.pts > t) break
            // The next frame has started by t: it becomes the one on screen, and its buffer takes the frame after it
            val old = current ?: break
            current = n
            if (old === uploaded) uploaded = null
            readInto(old)
            next = if (ended) null else old
        }
    }

    /** Opens the video a little before [atNanos], so the frame on screen at it is decoded too. Decoding thread. */
    private fun open(atNanos: Long): Boolean {
        closeSession()
        if (!NativeMedia.lavAvailable) return false
        frameBytes = w * h + 2 * (((w + 1) / 2) * ((h + 1) / 2))
        if (startPts == Long.MIN_VALUE) {
            // Once per video: where its pts start, from its first frame
            handle = NativeMedia.lavOpen(url ?: return false, w, h, 0L, 0)
            if (handle == 0L) return false
            startPts = 0L
            val probe = Frame(ByteBuffer.allocateDirect(frameBytes))
            readInto(probe)
            startPts = if (ended) 0L else probe.pts
            closeSession()
        }
        val from = (atNanos - PREROLL_NANOS).coerceAtLeast(0L)
        handle = NativeMedia.lavOpen(url ?: return false, w, h, from / 1_000L, 0)
        if (handle == 0L) {
            logger.warn("Export: could not open the video of display {} for decoding.", displayId)
            return false
        }
        ended = false
        val first = Frame(ByteBuffer.allocateDirect(frameBytes))
        readInto(first)
        if (ended) return false
        current = first
        next = Frame(ByteBuffer.allocateDirect(frameBytes)).also { readInto(it) }.takeIf { !ended }
        return true
    }

    /** Reads the next shown frame into [f], with its content pts; sets [ended] at the end or on an error. */
    private fun readInto(f: Frame) {
        while (true) {
            f.data.clear()
            val read = NativeMedia.lavReadFrameI420WithPts(handle, f.data, frameBytes)
            when (read.code) {
                NativeMedia.READ_PREVIEW -> continue // the keyframe before a seek target: never the frame at it
                NativeMedia.READ_OK -> {
                    val raw = if (read.ptsNanos == NativeMedia.LAV_NO_PTS_NANOS) (current?.pts ?: 0L) + FALLBACK_FRAME_NANOS
                    else read.ptsNanos - startPts.coerceAtLeast(0L)
                    f.pts = raw
                    return
                }
                else -> {
                    ended = true
                    return
                }
            }
        }
    }

    private fun upload(f: Frame) {
        if (f === uploaded && f.pts == uploadedPts && texture != null) return
        val tex = texture ?: createTexture()
        val image = tex.pixels ?: return
        I420.toImage(f.data, w, h, image)
        tex.upload()
        uploaded = f
        uploadedPts = f.pts
    }

    private fun createTexture(): DynamicTexture {
        //? if >=1.21.5 {
        val tex = DynamicTexture({ "dreamdisplays-export-$displayId" }, NativeImage(NativeImage.Format.RGBA, w, h, false))
        //?} else
        /*val tex = DynamicTexture(NativeImage(NativeImage.Format.RGBA, w, h, false))*/
        val id = identifier(Initializer.MOD_ID, "replay-export/${displayId}-${w}x$h-${UUID.randomUUID()}")
        Minecraft.getInstance().textureManager.register(id, tex)
        texture = tex
        textureId = id
        renderType = DisplayUnlitRenderTypes.create("dream-displays-export", id)
        return tex
    }

    private fun closeSession() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { worker.submit { NativeMedia.lavClose(h) }.get(KILL_WAIT_MS, TimeUnit.MILLISECONDS) }
        current = null
        next = null
        uploaded = null
        ended = false
    }

    /** Ends this source: the session, and the texture. Render thread. */
    fun close() {
        closeSession()
        textureId?.let { id -> runCatching { Minecraft.getInstance().textureManager.release(id) } }
        texture = null
        textureId = null
        renderType = null
        uploadedPts = Long.MIN_VALUE
        url = null
    }

    /** Ends for good (the export is over). */
    fun dispose() {
        close()
        worker.shutdownNow()
    }

    companion object {
        private val logger = LoggerFactory.getLogger("DreamDisplays/Flashback")

        /** A step back further than this reopens the video (forward is decoded on). */
        private const val REOPEN_BACK_NANOS = 1_000_000L

        /** A jump further ahead than this reopens rather than decodes every frame in between. */
        private const val REOPEN_AHEAD_NANOS = 3_000_000_000L

        /** Opened this far before the target, so the frame on screen at it is decoded rather than skipped. */
        private const val PREROLL_NANOS = 500_000_000L

        private const val FALLBACK_FRAME_NANOS = 33_333_333L

        /** Longest the render thread waits for one frame of one display. */
        private const val DEADLINE_MS = 10_000L

        private const val KILL_WAIT_MS = 2_000L
    }
}

/** I420 to the RGBA of a [NativeImage] (BT.709 limited range, as the display's own shader). */
internal object I420 {
    fun toImage(src: ByteBuffer, w: Int, h: Int, image: NativeImage) {
        if (image.width != w || image.height != h) return
        val cw = (w + 1) / 2
        val ySize = w * h
        val cSize = cw * ((h + 1) / 2)
        for (y in 0 until h) {
            val yRow = y * w
            val cRow = (y / 2) * cw
            for (x in 0 until w) {
                val yy = (src.get(yRow + x).toInt() and 0xFF) - 16
                val u = (src.get(ySize + cRow + x / 2).toInt() and 0xFF) - 128
                val v = (src.get(ySize + cSize + cRow + x / 2).toInt() and 0xFF) - 128
                val c = 1.164 * yy
                val r = (c + 1.793 * v).toInt().coerceIn(0, 255)
                val g = (c - 0.213 * u - 0.533 * v).toInt().coerceIn(0, 255)
                val b = (c + 2.112 * u).toInt().coerceIn(0, 255)
                image.setPixel(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
    }
}
