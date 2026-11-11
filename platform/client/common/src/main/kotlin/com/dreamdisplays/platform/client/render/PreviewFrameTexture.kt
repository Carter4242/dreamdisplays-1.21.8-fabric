package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.utils.identifier
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplays.platform.client.Initializer
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import java.nio.ByteBuffer
import java.util.*

/**
 * Holds the display menu's RGBA preview texture for one display, fed by [updateFrame] (decode thread) and uploaded to the
 * GPU on the render thread.
 */
class PreviewFrameTexture(private val uuid: UUID) {
    /** Decoded frames from the decode thread, handed over without sharing a buffer either side is using. */
    private val frames = FrameHandoff<OverlayFrame>()
    private var uploadedVersion = 0L
    private var dynamicTexture: DynamicTexture? = null
    var textureId: Identifier? = null
        private set
    var texW = 0
        private set
    var texH = 0
        private set
    private var uploader: AsyncTextureUploader? = null
    private var rgbaUploadBuffer: ByteBuffer? = null

    fun updateFrame(buf: ByteBuffer, w: Int, h: Int, format: UploadPixelFormat) {
        // Copied into a buffer the render thread is not reading; published together with its description
        frames.publish(buf, w * h * format.bytesPerPixel, OverlayFrame(w, h, format, 0.0))
    }

    fun uploadFrame() {
        // Borrowed: the decode thread cannot write into it until it is released after the upload
        val frame = frames.borrowNewest(uploadedVersion) ?: return
        try {
            upload(frame)
        } finally {
            frames.release()
        }
    }

    private fun upload(frame: FrameHandoff.Frame<OverlayFrame>) {
        val fw = frame.meta.width
        val fh = frame.meta.height
        val buf = frame.buffer
        val format = frame.meta.format
        if (fw <= 0 || fh <= 0) return

        val mc = Minecraft.getInstance()
        var tex = dynamicTexture
        if (tex == null || texW != fw || texH != fh) {
            tex?.close()
            textureId?.let { mc.textureManager.release(it) }
            val img = NativeImage(NativeImage.Format.RGBA, fw, fh, false)
            //? if >=1.21.5 {
            tex = DynamicTexture({ "dreamdisplays:preview" }, img)
            //?} else
            /*tex = DynamicTexture(img)*/
            textureId = identifier(
                Initializer.MOD_ID,
                "preview/${uuid}-${UUID.randomUUID()}",
            )
            mc.textureManager.register(textureId!!, tex)
            TextureUploadUtil.applyBilinearFilter(tex)
            dynamicTexture = tex
            texW = fw
            texH = fh
        }

        TextureUploadUtil.uploadDynamicTexture(
            texture = tex,
            src = buf,
            w = fw,
            h = fh,
            format = format,
            glUploader = { uploader ?: AsyncTextureUploader(stateCache = true).also { uploader = it } },
            rgbaScratch = rgbaUploadBuffer,
            setRgbaScratch = { rgbaUploadBuffer = it },
        )
        uploadedVersion = frame.version
    }

    /**
     * Forgets the held frames and releases the GPU texture on the render thread: nothing of the last
     * picture can be shown again. Any thread. The next [updateFrame] starts over.
     */
    fun clear() {
        frames.clear()
        closeAsync()
    }

    /** Releases the GPU texture / uploader immediately. GL calls — render thread only. */
    fun close() {
        uploader?.close()
        uploader = null
        val mc = Minecraft.getInstance()
        dynamicTexture?.close()
        textureId?.let { mc.textureManager.release(it) }
        dynamicTexture = null
        textureId = null
    }

    /**
     * Releases the GPU texture asynchronously on the render thread; safe to call from teardown paths
     * that don't run on it (e.g. `DisplayScreen.unregister()` can fire from a Netty disconnect handler
     * — calling GL functions there aborts the JVM outright, not just throws).
     */
    fun closeAsync() {
        Minecraft.getInstance().execute { close() }
    }

}
