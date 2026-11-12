package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.api.media.source.YouTubeUrls
import com.dreamdisplays.core.activity.ActivityKind
import com.dreamdisplays.core.activity.ActivityText
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import com.dreamdisplays.core.protocol.common.packets.ActivityEntryWire
import com.dreamdisplays.media.source.youtube.cache.VideoTitleCache
import com.dreamdisplays.platform.client.displays.DisplayPanels
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.ui.ClientMessage
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiText
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.utils.translatableText
import net.minecraft.client.gui.Font

/**
 * The "Activity" tab of the display menu's bottom panel: the last things done on the display, newest first, one row
 * each: who (head and name), what, and how long ago (the exact time on hover). Rows that happened by themselves
 * (the queue moved on, the video started over) are grey and have no player.
 */
class ActivitySection(private val ds: DisplayScreen) : PanelRows() {
    private var rows: List<ActivityEntryWire> = emptyList()
    private var clockOffsetMs = 0L

    override fun rowCount(): Int {
        val activity = DisplayPanels.activityOf(ds.uuid)
        // Rows of a kind this build does not know are skipped
        rows = activity?.rows.orEmpty().filter { ActivityKind.fromWire(it.kind) != null }
        clockOffsetMs = activity?.clockOffsetMs ?: 0L
        return rows.size
    }

    override fun emptyText(): String =
        if (DisplayPanels.activityLoaded(ds.uuid)) ClientMessage.ACTIVITY_EMPTY.text() else ClientMessage.SUGGESTIONS_LOADING.text()

    override fun drawRow(g: GuiGraphicsCompat, f: Font, index: Int, r: UiRect, hover: Boolean): List<String>? {
        val row = rows[index]
        val kind = ActivityKind.fromWire(row.kind) ?: return null
        val automatic = kind.automatic || (row.actorId == ZERO_UUID && !row.console && row.actorName.isEmpty())
        val textY = r.y + PanelRows.ROW_PAD + 1

        val ago = translate(ActivityText.ago(System.currentTimeMillis() + clockOffsetMs - row.atMs))
        val agoW = f.width(ago)
        g.drawText(f, ago, r.right - 4 - agoW, textY, UiTheme.TEXT_SECONDARY, false)
        val right = r.right - 4 - agoW - 6

        var x = r.x + 4
        if (!automatic) {
            val head = f.lineHeight
            if (row.console) {
                g.fill(x, textY - 1, x + head - 1, textY + head - 2, 0xFF505050.toInt())
            } else {
                QueueSection.drawPlayerHead(g, row.actorId, x, textY - 1, head - 1)
            }
            x += head + 3
            val name = if (row.console) ClientMessage.ACTIVITY_CONSOLE.text() else row.actorName.ifEmpty { "?" }
            val shownName = UiText.trim(f, name, maxOf(0, minOf(f.width(name), (right - x) / 2)))
            g.drawText(f, shownName, x, textY, UiTheme.TEXT_PRIMARY, false)
            x += f.width(shownName) + 4
        }
        val description = describe(row)
        val shown = UiText.trim(f, description, maxOf(0, right - x))
        g.drawText(f, shown, x, textY, if (automatic) UiTheme.TEXT_SECONDARY else UiTheme.TEXT_DIM, false)

        if (!hover) return null
        val who = when {
            automatic -> translatableText("dreamdisplays.activity.automatic").string
            row.console -> ClientMessage.ACTIVITY_CONSOLE.text()
            else -> row.actorName
        }
        return [if (automatic) description else "$who $description", ActivityText.exact(row.atMs)]
    }

    private fun describe(row: ActivityEntryWire): String {
        val line = ActivityText.describe(row) { url -> YouTubeUrls.extractVideoId(url)?.let(VideoTitleCache::get) } ?: return ""
        return translate(line)
    }

    companion object {
        /** [line] in the game's language; arguments with a key of their own are translated too. */
        fun translate(line: ActivityText.Line): String {
            val args = line.args.mapIndexed { i, arg ->
                line.argKeys.getOrNull(i)?.let { translatableText(it).string } ?: arg
            }
            return translatableText(line.key, *args.toTypedArray()).string
        }
    }
}
