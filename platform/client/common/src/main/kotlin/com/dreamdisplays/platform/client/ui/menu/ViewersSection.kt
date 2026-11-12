package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.core.activity.ViewerStatus
import com.dreamdisplays.core.activity.ViewerVia
import com.dreamdisplays.core.protocol.common.packets.ViewerEntryWire
import com.dreamdisplays.platform.client.displays.DisplayPanels
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.ui.ClientMessage
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiText
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font

/**
 * The "Viewers" tab of the display menu's bottom panel: everyone who sees or hears the display right now (as far as
 * the server lets this player know), one row each: head, name, how they watch (nearby, picture-in-picture,
 * fullscreen) and what their client is doing when it is not simply playing. Players in range without the mod are
 * listed last, greyed.
 */
class ViewersSection(private val ds: DisplayScreen) : PanelRows() {
    private var rows: List<ViewerEntryWire> = emptyList()

    override fun rowCount(): Int {
        rows = DisplayPanels.viewersOf(ds.uuid)?.viewers.orEmpty()
        return rows.size
    }

    override fun emptyText(): String =
        if (DisplayPanels.viewersOf(ds.uuid) != null) ClientMessage.VIEWERS_EMPTY.text() else ClientMessage.SUGGESTIONS_LOADING.text()

    override fun drawRow(g: GuiGraphicsCompat, f: Font, index: Int, r: UiRect, hover: Boolean): List<String>? {
        val row = rows[index]
        val status = ViewerStatus.fromWire(row.status)
        val noMod = status == ViewerStatus.NO_MOD
        val textY = r.y + PanelRows.ROW_PAD + 1

        val via = viaText(ViewerVia.fromWire(row.via))
        val statusText = statusText(status)
        val meta = if (statusText == null) via else "$via · $statusText"
        val metaW = f.width(meta)
        g.drawText(f, meta, r.right - 4 - metaW, textY, statusColor(status), false)

        var x = r.x + 4
        val head = f.lineHeight
        QueueSection.drawPlayerHead(g, row.playerId, x, textY - 1, head - 1)
        if (noMod) g.fill(x, textY - 1, x + head - 1, textY + head - 2, 0x90000000.toInt())
        x += head + 3
        val me = Minecraft.getInstance().player?.uuid == row.playerId
        val name = if (me) "${row.name} ${ClientMessage.VIEWERS_YOU.text()}" else row.name
        val shown = UiText.trim(f, name, maxOf(0, r.right - 4 - metaW - 6 - x))
        g.drawText(f, shown, x, textY, if (noMod) UiTheme.TEXT_SECONDARY else UiTheme.TEXT_PRIMARY, false)
        return if (hover) [row.name, meta] else null
    }

    private fun viaText(via: ViewerVia): String = when (via) {
        ViewerVia.NEARBY -> ClientMessage.VIEWERS_VIA_NEARBY.text()
        ViewerVia.PIP -> ClientMessage.VIEWERS_VIA_PIP.text()
        ViewerVia.FULLSCREEN -> ClientMessage.VIEWERS_VIA_FULLSCREEN.text()
    }

    private fun statusText(status: ViewerStatus): String? = when (status) {
        ViewerStatus.WATCHING -> null
        ViewerStatus.LOADING -> ClientMessage.VIEWERS_STATUS_LOADING.text()
        ViewerStatus.BUFFERING -> ClientMessage.VIEWERS_STATUS_BUFFERING.text()
        ViewerStatus.ERROR -> ClientMessage.VIEWERS_STATUS_ERROR.text()
        ViewerStatus.NO_MOD -> ClientMessage.VIEWERS_STATUS_NO_MOD.text()
    }

    private fun statusColor(status: ViewerStatus): Int = when (status) {
        ViewerStatus.LOADING, ViewerStatus.BUFFERING -> 0xFFE8C860.toInt()
        ViewerStatus.ERROR -> 0xFFFF6060.toInt()
        else -> UiTheme.TEXT_SECONDARY
    }
}
