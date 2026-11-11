package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.api.media.source.CustomMediaUrls
import com.dreamdisplays.api.media.source.YouTubeUrls
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.core.protocol.common.packets.QueueItemWire
import com.dreamdisplays.media.source.youtube.cache.VideoTitleCache
import com.dreamdisplays.platform.client.displays.DisplayQueues
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.render.Thumbnails
import com.dreamdisplays.platform.client.ui.ClientMessage
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
//? if <1.21.6 {
import com.dreamdisplays.platform.client.ui.enableScissorPoseAware
//?}
import com.dreamdisplays.platform.client.ui.kit.*
import com.dreamdisplays.platform.client.ui.widgets.IconButton
import com.dreamdisplays.platform.client.utils.buttonClickSound
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
//? if >=1.21.6 <1.21.11 {
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.resources.DefaultPlayerSkin
//?}
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines
//?}
import net.minecraft.client.resources.sounds.SimpleSoundInstance
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
//? if >=1.21.6 {
import net.minecraft.util.ARGB
//?}
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The "Up next" tab of the display menu's bottom panel: the display's queue as a strip of cards in the suggestion
 * cards' style (position, thumbnail, duration, title, who added it). Hovering a card shows small remove / move
 * buttons for whoever may use them. Drawing and hit-testing only; the screen routes clicks and scrolls here and
 * draws the tab header over it.
 */
class QueueSection(private val ds: DisplayScreen) {
    private var scrollF = 0f
    private var targetScroll = 0
    private var lastFrameNanos = 0L
    private var maxScroll = 0
    private var strip: UiRect? = null
    private val actions = ArrayList<Pair<UiRect, () -> Unit>>()

    /** Lays the cards out vertically (wide screens put the panel in a side column). */
    var vertical = false

    /** Tooltip for whatever small button the mouse is over this frame, or null. */
    var tooltip: String? = null
        private set

    /** Draws the panel into [panel]; the header line is left to the screen's tabs. */
    fun render(g: GuiGraphicsCompat, panel: UiRect, mouseX: Int, mouseY: Int) {
        g.drawPanelSprite(panel)
        actions.clear()
        tooltip = null
        val f = Minecraft.getInstance().font
        val stripRect = UiRect(panel.x + 10, panel.y + 10 + HEADER_H + 8, panel.w - 20, panel.h - 10 - HEADER_H - 8 - 10)
        strip = stripRect
        val notice = when {
            ds.effectiveMode == PlaybackMode.WATCH_PARTY -> ClientMessage.QUEUE_WATCH_PARTY.text()
            !ds.queueRunsHere -> ClientMessage.QUEUE_NOT_RUNNING.text()
            DisplayQueues.items(ds.uuid).isEmpty() -> ClientMessage.QUEUE_EMPTY.text()
            else -> null
        }
        if (notice != null) {
            var y = stripRect.y + 6
            for (line in UiText.wrap(f, notice, stripRect.w, 3)) {
                g.drawText(f, line, stripRect.x, y, UiTheme.TEXT_SECONDARY, false)
                y += f.lineHeight + 2
            }
            maxScroll = 0
            if (ds.effectiveMode == PlaybackMode.WATCH_PARTY || !ds.queueRunsHere) return
        }
        val items = DisplayQueues.items(ds.uuid)
        if (items.isEmpty() || stripRect.h < 40) return

        val viewportMain = if (vertical) stripRect.h else stripRect.w
        val thumbH = if (vertical) max(THUMB_H, (stripRect.w * 180.0 / 320.0).toInt()) else dynThumbH(stripRect.h)
        val cardW = if (vertical) stripRect.w else max(80, (thumbH * CARD_W / THUMB_H.toDouble()).toInt())
        val cardH = thumbH + CARD_TEXT_H + 6
        val step = (if (vertical) cardH else cardW) + CARD_GAP
        maxScroll = max(0, items.size * step - CARD_GAP - viewportMain)
        targetScroll = targetScroll.coerceIn(0, maxScroll)
        scrollF = ease(scrollF.coerceIn(0f, maxScroll.toFloat()), targetScroll.toFloat())
        val scroll = scrollF.roundToInt()

        //? if >=1.21.6 {
        g.enableScissor(stripRect.x, stripRect.y, stripRect.right, stripRect.bottom)
        //?} else
        /*g.enableScissorPoseAware(stripRect.x, stripRect.y, stripRect.right, stripRect.bottom)*/
        var hovered: Pair<Int, UiRect>? = null
        val rowY = if (vertical) 0 else stripRect.y + max(0, (stripRect.h - cardH) / 2)
        items.forEachIndexed { i, item ->
            val pos = i * step - scroll
            val card = if (vertical) UiRect(stripRect.x, stripRect.y + pos, cardW, cardH)
            else UiRect(stripRect.x + pos, rowY, cardW, cardH)
            val onScreen = if (vertical) card.bottom >= stripRect.y && card.y <= stripRect.bottom
            else card.right >= stripRect.x && card.x <= stripRect.right
            if (!onScreen) return@forEachIndexed
            val hover = card.contains(mouseX, mouseY) && stripRect.contains(mouseX, mouseY)
            if (hover) hovered = i to card
            drawCard(g, f, item, i, card, thumbH, hover)
        }
        g.disableScissor()
        hovered?.let { (i, card) -> drawCardActions(g, items, i, card, stripRect, mouseX, mouseY) }
        drawScrollbar(g, stripRect, scroll, viewportMain)
    }

    /** Handles a left click; true when it hit one of the card buttons. */
    fun handleClick(mouseX: Int, mouseY: Int): Boolean {
        val hit = actions.firstOrNull { it.first.contains(mouseX, mouseY) } ?: return false
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(buttonClickSound(), 1.0f))
        hit.second()
        return true
    }

    /** Scrolls the strip; true when the mouse is over it. */
    fun handleScroll(mouseX: Int, mouseY: Int, dx: Double, dy: Double): Boolean {
        val r = strip ?: return false
        if (!r.contains(mouseX, mouseY)) return false
        val axis = if (vertical) dy else if (dx != 0.0) dx else dy
        targetScroll = (targetScroll - (axis * 32).toInt()).coerceIn(0, maxScroll)
        return true
    }

    private fun drawCard(g: GuiGraphicsCompat, f: Font, item: QueueItemWire, index: Int, r: UiRect, thumbH: Int, hover: Boolean) {
        g.fill(r.x, r.y, r.right, r.bottom, if (hover) UiTheme.CARD_BG_HOVER else UiTheme.CARD_BG)
        val thumbW = if (vertical) r.w else (thumbH.toDouble() * CARD_W / THUMB_H).roundToInt().coerceAtMost(r.w)
        val thumbX = r.x + (r.w - thumbW) / 2
        val thumb = thumbnail(item)
        if (thumb != null) {
            blit(g, thumb, thumbX, r.y, thumbW, thumbH)
        } else {
            g.fillVGradient(thumbX, r.y, thumbX + thumbW, r.y + thumbH, UiTheme.CUSTOM_ART_TOP, UiTheme.CUSTOM_ART_BOTTOM)
            val host = CustomMediaUrls.hostOf(item.url).orEmpty()
            if (host.isNotEmpty()) {
                val label = UiText.trim(f, host, thumbW - 8)
                g.drawText(f, label, thumbX + (thumbW - f.width(label)) / 2, r.y + (thumbH - f.lineHeight) / 2, UiTheme.TEXT_SECONDARY, true)
            }
        }

        // Position badge, top-left
        val badge = (index + 1).toString()
        val bw = f.width(badge) + 6
        g.fill(thumbX + 2, r.y + 2, thumbX + 2 + bw, r.y + 2 + f.lineHeight + 4, UiTheme.ACCENT)
        g.drawText(f, badge, thumbX + 5, r.y + 4, UiTheme.TEXT_PRIMARY, false)

        val dur = formatDuration(item.durationMs)
        if (dur.isNotEmpty()) {
            val scrimH = (thumbH * 2) / 5
            g.fillVGradient(thumbX, r.y + thumbH - scrimH, thumbX + thumbW, r.y + thumbH, 0x00000000, 0xB0000000.toInt())
            g.drawText(f, dur, thumbX + thumbW - f.width(dur) - 4, r.y + thumbH - f.lineHeight - 3, UiTheme.TEXT_PRIMARY, true)
        }
        if (hover) g.drawOutline(r, UiTheme.CARD_BORDER_HOVER)

        val textX = r.x + 4
        val textW = r.w - 8
        var textY = r.y + thumbH + 4
        for (line in UiText.wrap(f, titleOf(item), textW, 2)) {
            g.drawText(f, line, textX, textY, UiTheme.TEXT_PRIMARY, true)
            textY += f.lineHeight + 1
        }
        val metaY = r.bottom - f.lineHeight - 3
        val head = f.lineHeight
        drawPlayerHead(g, item.addedBy, textX, metaY - 1, head)
        g.drawText(f, UiText.trim(f, item.addedByName, textW - head - 3), textX + head + 3, metaY, UiTheme.TEXT_META, true)
    }

    private fun drawCardActions(
        g: GuiGraphicsCompat, items: List<QueueItemWire>, index: Int, card: UiRect, stripRect: UiRect,
        mouseX: Int, mouseY: Int,
    ) {
        val item = items[index]
        val buttons = ArrayList<Triple<String, String, () -> Unit>>()
        if (ds.canManageQueueHere) {
            if (index > 0) buttons += Triple("left", ClientMessage.QUEUE_MOVE_EARLIER.text()) {
                DisplayQueues.move(ds.uuid, item.itemId, index - 1)
            }
            if (index < items.size - 1) buttons += Triple("right", ClientMessage.QUEUE_MOVE_LATER.text()) {
                DisplayQueues.move(ds.uuid, item.itemId, index + 1)
            }
        }
        if (ds.canRemoveFromQueueHere(DisplayQueues.isMine(item))) {
            buttons += Triple("cross", ClientMessage.QUEUE_REMOVE.text()) { DisplayQueues.remove(ds.uuid, item.itemId) }
        }
        var x = card.right - 2 - MINI
        for ((icon, tip, action) in buttons.asReversed()) {
            val r = UiRect(x, card.y + 2, MINI, MINI)
            x -= MINI + 2
            // Partly scrolled out of the strip: no button rather than one hanging over the panel edge
            if (r.x < stripRect.x || r.right > stripRect.right || r.y < stripRect.y || r.bottom > stripRect.bottom) continue
            val hover = r.contains(mouseX, mouseY)
            drawMiniButton(g, r, icon, hover)
            if (hover) tooltip = tip
            actions += r to action
        }
    }

    private fun drawScrollbar(g: GuiGraphicsCompat, r: UiRect, scroll: Int, viewport: Int) {
        if (maxScroll <= 0) return
        val content = maxScroll + viewport
        if (vertical) {
            val barX = r.right + 1
            g.fill(barX, r.y, barX + 2, r.bottom, UiTheme.SCROLLBAR_TRACK)
            val len = max(20, (viewport.toFloat() / content * viewport).toInt())
            val pos = r.y + (scroll.toFloat() / maxScroll * (viewport - len)).toInt()
            g.fill(barX, pos, barX + 2, pos + len, UiTheme.SCROLLBAR_THUMB)
        } else {
            val barY = r.bottom + 1
            g.fill(r.x, barY, r.right, barY + 2, UiTheme.SCROLLBAR_TRACK)
            val len = max(20, (viewport.toFloat() / content * viewport).toInt())
            val pos = r.x + (scroll.toFloat() / maxScroll * (viewport - len)).toInt()
            g.fill(pos, barY, pos + len, barY + 2, UiTheme.SCROLLBAR_THUMB)
        }
    }

    private fun dynThumbH(stripH: Int): Int = max(30, min(THUMB_H, stripH - CARD_TEXT_H - 6 - 8))

    private fun ease(current: Float, target: Float): Float {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = now
        val diff = target - current
        if (abs(diff) < 0.05f) return target
        return current + diff * min(1f, dt * 8f)
    }

    private fun thumbnail(item: QueueItemWire): Identifier? {
        YouTubeUrls.extractVideoId(item.url)?.let { id ->
            return Thumbnails.get(id, Thumbnails.Quality.LOW) ?: run {
                Thumbnails.request(id, Thumbnails.Quality.LOW)
                null
            }
        }
        val url = item.thumbnailUrl.takeIf(::isTrustedThumbnail) ?: return null
        return Thumbnails.get(url) ?: run {
            Thumbnails.request(url, url)
            null
        }
    }

    companion object {
        private const val HEADER_H = 14
        private const val CARD_W = 152
        private const val THUMB_H = 86
        private const val CARD_TEXT_H = 32
        private const val CARD_GAP = 6
        private const val MINI = 14

        /**
         * Hosts a queued item's thumbnail may come from. The URL was typed by another player, so anything else is not
         * fetched (it would tell that player's server every viewer's address); the card shows the plain art instead.
         */
        private val THUMBNAIL_HOSTS = listOf(
            "ytimg.com", "ggpht.com", "googleusercontent.com", "jtvnw.net", "twitchcdn.net", "vimeocdn.com",
            "kick.com", "hdslb.com", "biliimg.com",
        )

        internal fun isTrustedThumbnail(url: String): Boolean {
            if (!url.startsWith("https://")) return false
            val host = CustomMediaUrls.hostOf(url)?.lowercase(Locale.ROOT) ?: return false
            return THUMBNAIL_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        /** What to call [item]: its title, else a cached YouTube title, else a name derived from its link. */
        fun titleOf(item: QueueItemWire): String = item.title.ifBlank {
            YouTubeUrls.extractVideoId(item.url)?.let(VideoTitleCache::get) ?: CustomMediaUrls.displayName(item.url)
        }

        fun formatDuration(ms: Long): String {
            val s = ms / 1_000
            if (s <= 0) return ""
            return if (s >= 3_600) "%d:%02d:%02d".format(s / 3_600, s % 3_600 / 60, s % 60)
            else "%d:%02d".format(s / 60, s % 60)
        }

        /** A small square button in the card style: dark plate, icon, light outline when hovered. */
        fun drawMiniButton(g: GuiGraphicsCompat, r: UiRect, icon: String, hover: Boolean) {
            g.fill(r.x, r.y, r.right, r.bottom, if (hover) 0xE0303030.toInt() else 0xC0000000.toInt())
            if (hover) g.drawOutline(r, UiTheme.CARD_BORDER_HOVER)
            val sprite = IconButton.modIcon(icon)
            //? if >=1.21.6 {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, r.x + 1, r.y + 1, r.w - 2, r.h - 2, ARGB.white(1f))
            //?} else
            /*g.blitSprite(sprite, r.x + 1, r.y + 1, r.w - 2, r.h - 2)*/
            if (hover) {
                //? if >=1.21.11 {
                g.requestCursor(CursorTypes.POINTING_HAND)
                //?}
                //? if >=1.21.6 <1.21.11 {
                LegacyCursor.POINTING_HAND.request()
                //?}
            }
        }

        /** The face of player [id] (their skin when they are online, the default skin otherwise). */
        fun drawPlayerHead(g: GuiGraphicsCompat, id: UUID, x: Int, y: Int, size: Int) {
            //? if >=1.21.6 <1.21.11 {
            val skin = Minecraft.getInstance().connection?.getPlayerInfo(id)?.skin ?: DefaultPlayerSkin.get(id)
            PlayerFaceRenderer.draw(g, skin, x, y, size)
            //?}
            //? if <1.21.6 {
            g.fill(x, y, x + size, y + size, 0xFF000000.toInt() or (id.hashCode() and 0x7F7F7F))
            //?}
            //? if >=1.21.11 {
            g.fill(x, y, x + size, y + size, 0xFF000000.toInt() or (id.hashCode() and 0x7F7F7F))
            //?}
        }

        private fun blit(g: GuiGraphicsCompat, id: Identifier, x: Int, y: Int, w: Int, h: Int) {
            //? if >=1.21.6 {
            g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, w, h)
            //?} else
            /*g.blit(id, x, y, 0f, 0f, w, h, w, h)*/
        }
    }
}
