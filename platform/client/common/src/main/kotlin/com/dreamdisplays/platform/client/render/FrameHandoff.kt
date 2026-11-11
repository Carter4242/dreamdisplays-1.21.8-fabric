package com.dreamdisplays.platform.client.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hands decoded frames from the decode thread to the render thread without either side touching a buffer the other
 * is using. The producer copies each frame into a buffer that is neither the newest published one nor the one the
 * consumer has borrowed (three buffers are always enough), then publishes it together with its [M] description.
 * The consumer borrows the newest frame, uploads it, and releases it; until then the producer never writes into it.
 */
class FrameHandoff<M : Any> {
    /** A published frame: [buffer] holds exactly the frame's bytes (position 0), described by [meta]. */
    class Frame<M>(val buffer: ByteBuffer, val meta: M, val version: Long)

    private val lock = Any()
    private val pool = arrayOfNulls<ByteBuffer>(SLOTS)
    private var newest = -1
    private var borrowed = -1

    /** Slots a producer is copying into (one bit each): never handed to another producer or the consumer. */
    private var writing = 0
    private var newestMeta: M? = null
    private var version = 0L

    /** Bumped by [clear]: a producer that reserved its slot before it may not publish. */
    private var epoch = 0L

    /** A slot reserved by one producer for one frame. */
    internal class Reservation(val slot: Int, val epoch: Long)

    /**
     * Copies [size] bytes of [src] (from its position; [src]'s position and limit are left as they were) and publishes
     * them as the newest frame. Producer thread; several producers may call it at once (an old player still stopping
     * while its successor starts). Returns false when [src] holds fewer than [size] bytes, when every buffer is in use
     * (that frame is skipped), or when [clear] ran while it was copying.
     */
    fun publish(src: ByteBuffer, size: Int, meta: M): Boolean {
        if (size <= 0 || src.remaining() < size) return false
        val reservation = reserve() ?: return false
        return commit(reservation, fill(reservation, src, size), meta)
    }

    /** Reserves a buffer that is not the newest, not borrowed and not being written by another producer. */
    internal fun reserve(): Reservation? = synchronized(lock) {
        val free = (0 until SLOTS).firstOrNull { it != newest && it != borrowed && (writing and (1 shl it)) == 0 }
            ?: return null
        writing = writing or (1 shl free)
        Reservation(free, epoch)
    }

    /** Copies the frame into the reserved buffer (allocating it when missing or too small); no lock held. */
    internal fun fill(reservation: Reservation, src: ByteBuffer, size: Int): ByteBuffer {
        var buf = synchronized(lock) { pool[reservation.slot] }
        if (buf == null || buf.capacity() < size) {
            buf = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
        buf.clear()
        val view = src.duplicate()
        view.limit(view.position() + size)
        buf.put(view)
        buf.flip()
        return buf
    }

    /** Publishes the filled buffer unless [clear] ran since [reservation] was taken. */
    internal fun commit(reservation: Reservation, buf: ByteBuffer, meta: M): Boolean = synchronized(lock) {
        writing = writing and (1 shl reservation.slot).inv()
        if (reservation.epoch != epoch) return false // Cleared meanwhile: a picture from before must not come back
        pool[reservation.slot] = buf
        newest = reservation.slot
        newestMeta = meta
        version++
        true
    }

    /**
     * Borrows the newest frame if it is newer than [seenVersion], or returns null. Consumer thread. The frame stays
     * valid until [release]; a new borrow gives the previous one back.
     */
    fun borrowNewest(seenVersion: Long): Frame<M>? = synchronized(lock) {
        if (newest < 0 || version == seenVersion) return null
        val buf = pool[newest] ?: return null
        val meta = newestMeta ?: return null
        borrowed = newest
        Frame(buf.duplicate(), meta, version)
    }

    /** Returns the borrowed frame: the producer may write into its buffer again. */
    fun release() {
        synchronized(lock) { borrowed = -1 }
    }

    /** The version of the newest published frame (0 before any). */
    val latestVersion: Long get() = synchronized(lock) { version }

    /** The newest frame's description, or null before any (or after [clear]). */
    val latestMeta: M? get() = synchronized(lock) { newestMeta }

    /**
     * Forgets every held frame: nothing published so far can be borrowed again. A frame still borrowed stays valid
     * until released; a frame being written when this runs is dropped, and its buffer stays out of reach of new
     * producers until that copy is done. Any thread.
     */
    fun clear() {
        synchronized(lock) {
            for (i in 0 until SLOTS) if (i != borrowed) pool[i] = null
            newest = -1
            newestMeta = null
            version++
            epoch++
        }
    }

    private companion object {
        const val SLOTS = 3
    }
}

/** What an overlay needs to know about a decoded frame, published together with its pixels. */
data class OverlayFrame(val width: Int, val height: Int, val format: UploadPixelFormat, val aspect: Double)
