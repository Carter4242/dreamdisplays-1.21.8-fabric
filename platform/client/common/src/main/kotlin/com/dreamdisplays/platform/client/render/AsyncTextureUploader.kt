package com.dreamdisplays.platform.client.render

//? if >=1.21.5 <26.3 {
import com.mojang.blaze3d.opengl.GlStateManager
//?}
//? if <1.21.5 {
import com.mojang.blaze3d.platform.GlStateManager
//?}
import com.dreamdisplays.api.media.sink.DecodedVideoFrame
import com.dreamdisplays.platform.client.displays.ServerOverrides
import com.dreamdisplays.core.render.TextureHandle
import com.dreamdisplays.core.render.TextureUploaderService
import com.dreamdisplays.platform.client.render.AsyncTextureUploader.Companion.PBO_COUNT
import org.lwjgl.opengl.*
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * Async, low-stall texture uploader based on a ring buffer of `Pixel Buffer Objects` (`PBO`).
 *
 * Workflow:
 * 1. Data is copied from system memory to `PBO`.
 * 2. GPU asynchronously copies data from `PBO` to texture (`PBO` -> Texture).
 * 3. Using multiple PBOs (ring) avoids stalls: while the GPU reads from one buffer, the CPU can write to another.
 *
 * Each ring slot is guarded by a fence sync ([GL32.glFenceSync]) placed right after the PBO -> texture
 * copy is issued; before the CPU writes into a slot again the fence is checked, so a slot is never
 * overwritten while the GPU may still be reading from it (no reliance on ring depth alone).
 *
 * When `GL_ARB_buffer_storage` (core GL 4.4) is available, slots use immutable persistently-mapped
 * storage: the buffer is mapped exactly once and frames are memcpy'd straight into the coherent
 * mapping, skipping the per-frame `glMapBufferRange`/`glUnmapBuffer` driver round-trip.
 *
 * On contexts without it (e.g. macOS GL 4.1) the classic map-with-`GL_MAP_UNSYNCHRONIZED_BIT` path is used,
 * which the fences also make safe.
 */
class AsyncTextureUploader(private val stateCache: Boolean) : TextureUploaderService {
    private class Slot {
        /** GL buffer id (0 until created). */
        var pbo: Int = 0

        /** Allocated size in bytes so steady-state uploads do not reallocate every frame. */
        var capacity: Int = 0

        /** Fence placed after the last PBO->texture copy from this slot (0 when none pending). */
        var fence: Long = 0L

        /** Persistent coherent mapping of the whole buffer, when buffer-storage is supported. */
        var persistent: ByteBuffer? = null

        /** The buffer has immutable storage ([GL44.glBufferStorage]): it can only be replaced, never reallocated. */
        var immutable: Boolean = false
    }

    private val slots: Array<Slot> = Array(PBO_COUNT) { Slot().apply { pbo = GL15.glGenBuffers() } }
    private var ringIndex: Int = 0
    private var managedTexId: Int = -1
    private var managedTexW: Int = -1
    private var managedTexH: Int = -1

    /**
     * Persistently mapped slots: the driver offers buffer storage and the server has not asked for plain uploads
     * (`client.textureUpload = basic`). Fixed for this uploader's life, so no slot ever mixes the two kinds of storage.
     */
    private val persistentMapSupported: Boolean by lazy {
        ServerOverrides.settings.persistentUploads && runCatching {
            val caps = GL.getCapabilities()
            caps.OpenGL44 || caps.GL_ARB_buffer_storage
        }.getOrDefault(false)
    }

    /**
     * `glMemoryBarrier` is GL 4.2 (or `ARB_shader_image_load_store`); buffer storage can come without it as an
     * extension, and calling a function the driver does not have is a native crash.
     */
    private val memoryBarrierSupported: Boolean by lazy {
        runCatching {
            val caps = GL.getCapabilities()
            caps.OpenGL42 || caps.GL_ARB_shader_image_load_store
        }.getOrDefault(false)
    }

    override val supportsAsync: Boolean = true

    override val maxTextureSize: Int = 8192

    override fun upload(frame: DecodedVideoFrame): TextureHandle {
        if (managedTexId == -1) {
            managedTexId = GL11.glGenTextures()
            bindTexture(managedTexId)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            bindTexture(0)
        }
        val w = frame.width
        val h = frame.height
        val buf = ByteBuffer.wrap(frame.data)
        if (w != managedTexW || h != managedTexH) {
            bindTexture(managedTexId)
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0,
                GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf,
            )
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
            bindTexture(0)
            managedTexW = w; managedTexH = h
        } else {
            upload(managedTexId, buf, w, h, UploadPixelFormat.RGB24)
        }
        return TextureHandle(managedTexId)
    }

    override fun release(handle: TextureHandle) {
        if (handle.id == managedTexId && managedTexId != -1) {
            GL11.glDeleteTextures(managedTexId)
            managedTexId = -1; managedTexW = -1; managedTexH = -1
        }
    }

    override fun close() {
        if (managedTexId != -1) {
            GL11.glDeleteTextures(managedTexId)
            managedTexId = -1
        }
        cleanup()
    }

    /**
     * Uploads one decoded frame from [src] into the next `PBO` in the ring, then schedules an async copy from the PBO
     * to the texture.
     */
    fun upload(textureId: Int, src: ByteBuffer, w: Int, h: Int, format: UploadPixelFormat = UploadPixelFormat.RGB24) {
        val size = w * h * format.bytesPerPixel
        if (size <= 0 || src.remaining() < size) return

        // Pick the next slot and advance the ring
        val slot = slots[ringIndex]
        ringIndex = (ringIndex + 1) % PBO_COUNT

        // Make sure the GPU is done reading this slot's PBO before we overwrite it; still busy: this frame is dropped
        if (!waitSlotFence(slot)) return

        bindBuffer(id = slot.pbo)
        ensureCapacity(slot, size)

        val persistent = slot.persistent
        if (persistent != null) {
            copyIntoPersistentPbo(src, persistent, size)
        } else {
            copyIntoMappedPbo(src, size)
        }

        bindTexture(textureId)

        pixelStore(GL11.GL_UNPACK_ALIGNMENT, format.unpackAlignment)
        pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)

        texSubImage2DFromPbo(w, h, format.glFormat, 0L)

        // Guard this slot until the GPU has consumed the copy we just issued
        slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)

        // Restore default alignment (4 bytes) and unbind the buffer.
        pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
        bindBuffer(id = 0)
    }

    /** Uploads one packed I420 frame (Y plane, then U, then V) from [src] into three plane textures in a single PBO. */
    fun uploadPlanar(
        yId: Int, yW: Int, yH: Int,
        uId: Int, uW: Int, uH: Int,
        vId: Int, vW: Int, vH: Int,
        src: ByteBuffer,
    ) {
        val ySize = yW * yH
        val uSize = uW * uH
        val vSize = vW * vH
        val total = ySize + uSize + vSize
        if (total <= 0 || src.remaining() < total) return

        val slot = slots[ringIndex]
        ringIndex = (ringIndex + 1) % PBO_COUNT

        if (!waitSlotFence(slot)) return

        bindBuffer(id = slot.pbo)
        ensureCapacity(slot, total)

        val persistent = slot.persistent
        if (persistent != null) {
            copyIntoPersistentPbo(src, persistent, total)
        } else {
            copyIntoMappedPbo(src, total)
        }

        pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1)
        pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)

        val glFormat = UploadPixelFormat.R8.glFormat
        bindTexture(yId)
        texSubImage2DFromPbo(yW, yH, glFormat, 0L)
        bindTexture(uId)
        texSubImage2DFromPbo(uW, uH, glFormat, ySize.toLong())
        bindTexture(vId)
        texSubImage2DFromPbo(vW, vH, glFormat, (ySize + uSize).toLong())

        // Guard this slot until the GPU has consumed all three plane copies
        slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)

        pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
        bindBuffer(id = 0)
    }

    /** Releases `PBO` IDs. Must be called in the same OpenGL context where they were created. */
    fun cleanup() {
        for (slot in slots) {
            if (slot.fence != 0L) {
                GL32.glDeleteSync(slot.fence)
                slot.fence = 0L
            }
            if (slot.persistent != null) {
                bindBuffer(id = slot.pbo)
                GL30.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER)
                bindBuffer(id = 0)
                slot.persistent = null
            }
            if (slot.pbo != 0) GL15.glDeleteBuffers(slot.pbo)
            slot.pbo = 0
            slot.capacity = 0
            slot.immutable = false
        }
    }

    /**
     * Waits (bounded) until the GPU has finished reading [slot]'s buffer. False when it still has not: the slot keeps
     * its fence and must not be written (that would change pixels a pending copy is still reading).
     */
    private fun waitSlotFence(slot: Slot): Boolean {
        val fence = slot.fence
        if (fence == 0L) return true
        // Cheap non-blocking poll first; only flush + wait if the GPU is actually behind
        var status = GL32.glClientWaitSync(fence, 0, 0L)
        if (status == GL32.GL_TIMEOUT_EXPIRED) {
            status = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_TIMEOUT_NS)
        }
        if (status == GL32.GL_TIMEOUT_EXPIRED) return false
        // Signalled (or the wait failed, which leaves nothing to wait for): the slot is free
        GL32.glDeleteSync(fence)
        slot.fence = 0L
        return true
    }

    private fun ensureCapacity(slot: Slot, size: Int) {
        if (size <= slot.capacity) return

        // Immutable storage can't be resized or reallocated: unmap it and replace the buffer object
        if (slot.immutable) replaceBuffer(slot)

        if (persistentMapSupported) {
            val flags = GL44.GL_MAP_WRITE_BIT or GL44.GL_MAP_PERSISTENT_BIT or GL44.GL_MAP_COHERENT_BIT
            if (GL.getCapabilities().OpenGL44) {
                GL44.glBufferStorage(GL21.GL_PIXEL_UNPACK_BUFFER, size.toLong(), flags)
            } else {
                ARBBufferStorage.glBufferStorage(GL21.GL_PIXEL_UNPACK_BUFFER, size.toLong(), flags)
            }
            slot.immutable = true
            slot.persistent = GL30.glMapBufferRange(GL21.GL_PIXEL_UNPACK_BUFFER, 0L, size.toLong(), flags)
            // If mapping unexpectedly failed, fall back to plain mutable storage for this slot, in a new buffer object
            // (the immutable one cannot take glBufferData, nor glBufferSubData without the dynamic-storage bit)
            if (slot.persistent == null) {
                replaceBuffer(slot)
                GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, size.toLong(), GL15.GL_STREAM_DRAW)
            }
        } else {
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, size.toLong(), GL15.GL_STREAM_DRAW)
        }
        slot.capacity = size
    }

    /** Drops [slot]'s buffer object (unmapping it first) and binds a fresh one with no storage yet. */
    private fun replaceBuffer(slot: Slot) {
        if (slot.persistent != null) {
            GL30.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER)
            slot.persistent = null
        }
        bindBuffer(id = 0)
        GL15.glDeleteBuffers(slot.pbo)
        slot.pbo = GL15.glGenBuffers()
        slot.immutable = false
        bindBuffer(id = slot.pbo)
    }

    private fun bindBuffer(target: Int = GL21.GL_PIXEL_UNPACK_BUFFER, id: Int) {
        if (stateCache) GlStateManager._glBindBuffer(target, id) else GL15.glBindBuffer(target, id)
    }

    private fun bindTexture(id: Int) = bindTexture2D(stateCache, id)

    private fun pixelStore(name: Int, value: Int) {
        if (stateCache) GlStateManager._pixelStore(name, value) else GL11.glPixelStorei(name, value)
    }

    private fun copyIntoPersistentPbo(src: ByteBuffer, dst: ByteBuffer, size: Int) {
        if (src.isDirect) {
            MemoryUtil.memCopy(
                MemoryUtil.memAddress(src, src.position()),
                MemoryUtil.memAddress0(dst),
                size.toLong(),
            )
        } else {
            val savedLimit = src.limit()
            val savedPos = src.position()
            val view = src.duplicate()
            view.limit(savedPos + size)
            val out = dst.duplicate()
            out.clear()
            out.put(view)
            src.limit(savedLimit)
            src.position(savedPos)
        }
        // https://github.com/arnodoelinger/dreamdisplays/issues/199
        if (memoryBarrierSupported) GL42.glMemoryBarrier(GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT)
    }

    private fun copyIntoMappedPbo(src: ByteBuffer, size: Int) {
        val savedLimit = src.limit()
        val savedPos = src.position()
        val view = src.duplicate()
        view.limit(savedPos + size)
        view.position(savedPos)

        val mapped = GL30.glMapBufferRange(
            GL21.GL_PIXEL_UNPACK_BUFFER,
            0L,
            size.toLong(),
            GL30.GL_MAP_WRITE_BIT or GL30.GL_MAP_INVALIDATE_BUFFER_BIT or GL30.GL_MAP_UNSYNCHRONIZED_BIT,
        )
        if (mapped != null) {
            mapped.limit(size)
            if (view.isDirect) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(view), MemoryUtil.memAddress(mapped), size.toLong())
            } else {
                mapped.put(view)
            }
            GL30.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER)
        } else {
            GL15.glBufferSubData(GL21.GL_PIXEL_UNPACK_BUFFER, 0L, view)
        }

        src.limit(savedLimit)
        src.position(savedPos)
    }

    private fun texSubImage2DFromPbo(w: Int, h: Int, glFormat: Int, offset: Long) {
        if (stateCache) {
            GlStateManager._texSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, glFormat, GL11.GL_UNSIGNED_BYTE, offset,
            )
        } else {
            GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, glFormat, GL11.GL_UNSIGNED_BYTE, offset,
            )
        }
    }

    companion object {
        private const val PBO_COUNT = 3
        private const val FENCE_TIMEOUT_NS = 100_000_000L
    }
}
