package com.dreamdisplays.media.source.direct

/**
 * Reads the first video track's codec and alpha signalling out of the leading bytes of a Matroska / WebM file.
 *
 * WebM carries VP8 / VP9 alpha as a side channel, announced by the track's `AlphaMode` element (`alpha_mode=1` in
 * FFmpeg's terms). It sits in the `Tracks` element, which every muxer writes ahead of the first cluster, so the
 * header fetch the duration probe already makes is enough to find it.
 */
internal object MatroskaTracks {
    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_VIDEO = 0xE0L
    private const val ID_ALPHA_MODE = 0x53C0L
    private const val TRACK_TYPE_VIDEO = 1L

    /** The facts read from one video track entry. */
    data class Video(val codecId: String?, val hasAlpha: Boolean) {
        /** FFmpeg codec name for [codecId], or null when it is not one this player names. */
        val codec: String?
            get() = when (codecId) {
                "V_VP9" -> "vp9"
                "V_VP8" -> "vp8"
                "V_AV1" -> "av1"
                "V_MPEG4/ISO/AVC" -> "h264"
                "V_MPEGH/ISO/HEVC" -> "hevc"
                else -> null
            }
    }

    private class Element(val id: Long, val dataStart: Int, val dataEnd: Int)

    /** The first video track found in [head], or null when the bytes are not Matroska or end before its tracks. */
    fun firstVideo(head: ByteArray): Video? = runCatching {
        var cursor = 0
        var segment: Element? = null
        while (cursor < head.size) {
            val e = element(head, cursor, head.size) ?: return null
            if (cursor == 0 && e.id != ID_EBML) return null
            if (e.id == ID_SEGMENT) {
                segment = e; break
            }
            cursor = e.dataEnd
        }
        val seg = segment ?: return null
        cursor = seg.dataStart
        while (cursor < seg.dataEnd) {
            val e = element(head, cursor, seg.dataEnd) ?: return null
            when (e.id) {
                ID_TRACKS -> return tracks(head, e)
                // Media data starts here: a file with tracks after its first cluster is not a valid WebM
                ID_CLUSTER -> return null
            }
            cursor = e.dataEnd
        }
        null
    }.getOrNull()

    private fun tracks(head: ByteArray, tracks: Element): Video? {
        var cursor = tracks.dataStart
        while (cursor < tracks.dataEnd) {
            val e = element(head, cursor, tracks.dataEnd) ?: return null
            if (e.id == ID_TRACK_ENTRY) trackEntry(head, e)?.let { return it }
            cursor = e.dataEnd
        }
        return null
    }

    private fun trackEntry(head: ByteArray, entry: Element): Video? {
        var type = -1L
        var codecId: String? = null
        var alpha = false
        var cursor = entry.dataStart
        while (cursor < entry.dataEnd) {
            val e = element(head, cursor, entry.dataEnd) ?: break
            when (e.id) {
                ID_TRACK_TYPE -> type = unsigned(head, e)
                ID_CODEC_ID -> codecId = String(head, e.dataStart, e.dataEnd - e.dataStart, Charsets.US_ASCII)
                    .trimEnd('\u0000')
                ID_VIDEO -> alpha = alphaMode(head, e)
            }
            cursor = e.dataEnd
        }
        return if (type == TRACK_TYPE_VIDEO) Video(codecId, alpha) else null
    }

    private fun alphaMode(head: ByteArray, video: Element): Boolean {
        var cursor = video.dataStart
        while (cursor < video.dataEnd) {
            val e = element(head, cursor, video.dataEnd) ?: return false
            if (e.id == ID_ALPHA_MODE) return unsigned(head, e) == 1L
            cursor = e.dataEnd
        }
        return false
    }

    /** Reads the element header at [offset]. An unknown or overlong size is clamped to [limit]. */
    private fun element(data: ByteArray, offset: Int, limit: Int): Element? {
        val id = vint(data, offset, stripMarker = false) ?: return null
        val size = vint(data, offset + id.width, stripMarker = true) ?: return null
        val dataStart = offset + id.width + size.width
        if (dataStart > limit) return null
        val unknown = size.value == (1L shl (7 * size.width)) - 1
        val dataEnd = if (unknown || size.value > limit - dataStart) limit else dataStart + size.value.toInt()
        return Element(id.value, dataStart, dataEnd)
    }

    private class Vint(val value: Long, val width: Int)

    private fun vint(data: ByteArray, offset: Int, stripMarker: Boolean): Vint? {
        if (offset >= data.size) return null
        val first = data[offset].toInt() and 0xFF
        if (first == 0) return null
        var width = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            width++
        }
        if (offset + width > data.size) return null
        var value = (if (stripMarker) first and (mask - 1) else first).toLong()
        for (i in 1 until width) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return Vint(value, width)
    }

    private fun unsigned(data: ByteArray, e: Element): Long {
        var value = 0L
        for (i in e.dataStart until minOf(e.dataEnd, e.dataStart + 8)) value = (value shl 8) or (data[i].toLong() and 0xFF)
        return value
    }
}
