package com.dreamdisplays.platform.client.render

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThumbnailBoundsTest {
    @Test
    fun anOrdinaryThumbnailPasses() {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB), "png", out)
        assertEquals(320 to 180, ThumbnailBounds.dimensions(out.toByteArray()))
        ThumbnailBounds.check(out.toByteArray())
    }

    @Test
    fun aTinyFileDeclaringAHugeImageIsRefusedFromItsHeader() {
        // 50 bytes that declare 50000 x 50000 RGBA (10 GB decoded); only the header is ever read
        val bomb = pngHeader(50_000, 50_000)
        assertEquals(50_000 to 50_000, ThumbnailBounds.dimensions(bomb))
        assertFailsWith<IOException> { ThumbnailBounds.check(bomb) }
    }

    @Test
    fun anUnknownFormatIsLeftToTheDecoder() {
        ThumbnailBounds.check("not an image".toByteArray())
    }

    private fun pngHeader(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val typed = type.toByteArray() + body
            data.write(typed)
            data.writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 6, 0, 0, 0)) }
        }.toByteArray()
        chunk("IHDR", ihdr)
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
