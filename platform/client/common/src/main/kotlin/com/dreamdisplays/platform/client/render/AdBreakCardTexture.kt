package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.Initializer
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger

/**
 * The card a display shows while a Twitch ad is skipped: a title and a countdown line, drawn in the style of the replay
 * cards (the PNGs under `textures/replay`: dark panel, thin frame, white title, grey line under it). One per display, redrawn only
 * when its text changes (once a second while counting down).
 */
class AdBreakCardTexture {
    private var lastKey: String? = null
    private var identifier: Identifier? = null
    private var texture: DynamicTexture? = null
    private var cachedRenderType: RenderType? = null

    /** The card for [title] over [subtitle], ready to draw. Render thread only. */
    fun renderType(title: String, subtitle: String): RenderType? {
        val key = "$title\n$subtitle"
        if (key != lastKey) {
            lastKey = key
            val image = rasterize(title, subtitle)
            val existing = texture
            val pixels = existing?.pixels
            if (existing != null && pixels != null) {
                // Same size every time: redraw in place, no new texture to register
                SubtitleOverlayTexture.writePixels(pixels, image)
                existing.upload()
            } else {
                val id = identifier ?: com.dreamdisplays.platform.client.utils.identifier(
                    Initializer.MOD_ID, "dynamic/ad_break_${INSTANCE_ID.incrementAndGet()}",
                ).also { identifier = it }
                val native = SubtitleOverlayTexture.toNativeImage(image)
                val dynamic =
                    //? if >=1.21.5 {
                    DynamicTexture({ "dreamdisplays-ad-break" }, native)
                //?} else
                /*DynamicTexture(native)*/
                dynamic.upload()
                Minecraft.getInstance().textureManager.register(id, dynamic)
                texture = dynamic
            }
        }
        val id = identifier ?: return null
        if (texture == null) return null
        return cachedRenderType ?: DisplayUnlitRenderTypes.create("dream-displays-ad-break", id).also { cachedRenderType = it }
    }

    /** Releases the GPU texture. Call once when the owning display is unregistered. */
    fun dispose() {
        texture?.close()
        identifier?.let { Minecraft.getInstance().textureManager.release(it) }
        identifier = null
        texture = null
        cachedRenderType = null
        lastKey = null
    }

    companion object {
        private val INSTANCE_ID = AtomicInteger(0)
        private const val W = 640
        private const val H = 360
        private val BACKGROUND = Color(16, 18, 24)
        private val FRAME = Color(60, 66, 84)
        private val TITLE = Color(235, 238, 245)
        private val SUBTITLE = Color(150, 156, 172)

        internal fun rasterize(title: String, subtitle: String): BufferedImage {
            val image = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                g.color = BACKGROUND
                g.fillRect(0, 0, W, H)
                g.color = FRAME
                g.stroke = BasicStroke(2f)
                g.drawRect(10, 10, W - 21, H - 21)
                drawCentered(g, title, Font(Font.SANS_SERIF, Font.BOLD, 52), TITLE, 178)
                drawCentered(g, subtitle, Font(Font.SANS_SERIF, Font.PLAIN, 30), SUBTITLE, 236)
            } finally {
                g.dispose()
            }
            return image
        }

        /** Draws [text] centred on [baselineY], shrunk to fit inside the frame. */
        private fun drawCentered(g: java.awt.Graphics2D, text: String, font: Font, color: Color, baselineY: Int) {
            var f = font
            var width = g.getFontMetrics(f).stringWidth(text)
            val max = W - 60
            if (width > max) {
                f = f.deriveFont(f.size2D * max / width)
                width = g.getFontMetrics(f).stringWidth(text)
            }
            g.font = f
            g.color = color
            g.drawString(text, (W - width) / 2, baselineY)
        }
    }
}
