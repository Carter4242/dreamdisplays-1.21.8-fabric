package com.dreamdisplays.core.activity

import com.dreamdisplays.core.protocol.common.packets.ActivityEntryWire
import java.util.*

/**
 * How an Activity row reads: a translation key plus its arguments (the client translates them), and the small
 * formatting the rows share (positions, speeds, relative times, short links). Pure, so it is tested without a game.
 */
object ActivityText {
    private const val PREFIX = "dreamdisplays.activity."

    /**
     * A translation [key] with its [args] (already formatted strings). [argKeys]: per argument, a translation key to
     * show instead of it (a mode or access word), or null to show it as it is.
     */
    data class Line(val key: String, val args: List<String> = emptyList(), val argKeys: List<String?> = emptyList())

    /**
     * What [e] says, or null for a kind this build does not know. [titleOf] may name a video by its link (a cached
     * title); without one a row uses its own title, else a shortened link.
     */
    fun describe(e: ActivityEntryWire, titleOf: (String) -> String? = { null }): Line? {
        val kind = ActivityKind.fromWire(e.kind) ?: return null
        val video by lazy { videoName(e.title, e.url, titleOf) }
        return when (kind) {
            ActivityKind.PLAY -> Line(PREFIX + "play")
            ActivityKind.PAUSE -> Line(PREFIX + "pause")
            ActivityKind.SEEK -> Line(PREFIX + "seek", [position(e.fromMs), position(e.toMs)])
            ActivityKind.RESTART -> Line(PREFIX + "restart")
            ActivityKind.SPEED -> Line(PREFIX + "speed", [speed(e.rate)])
            ActivityKind.VIDEO_SET -> Line(PREFIX + "video", [video])
            ActivityKind.VIDEO_CLEAR -> Line(PREFIX + "clear")
            ActivityKind.QUEUE_ADD -> Line(PREFIX + "queue.add", [video])
            ActivityKind.QUEUE_ADD_PLAYLIST -> Line(PREFIX + "queue.playlist", [e.count.toString()])
            ActivityKind.QUEUE_REMOVE -> Line(PREFIX + "queue.remove", [video])
            ActivityKind.QUEUE_MOVE -> when (e.toMs) {
                e.fromMs - 1 -> Line(PREFIX + "queue.move.up", [video])
                e.fromMs + 1 -> Line(PREFIX + "queue.move.down", [video])
                else -> Line(PREFIX + "queue.move.to", [video, e.toMs.toString()])
            }
            ActivityKind.QUEUE_SKIP -> Line(PREFIX + "queue.skip", [video])
            ActivityKind.QUEUE_CLEAR -> Line(PREFIX + "queue.clear")
            ActivityKind.QUEUE_LOOP -> Line(PREFIX + if (e.flag) "queue.loop.on" else "queue.loop.off")
            ActivityKind.MODE -> Line(PREFIX + "mode", [e.text], [tokenKey(e.text)])
            ActivityKind.ACCESS -> Line(PREFIX + "access", [e.text], [tokenKey(e.text)])
            ActivityKind.RENAME -> if (e.text.isEmpty()) Line(PREFIX + "rename.clear") else Line(PREFIX + "rename", [e.text])
            ActivityKind.SHARE_START -> Line(PREFIX + "share.start")
            ActivityKind.SHARE_STOP -> Line(PREFIX + "share.stop")
            ActivityKind.KARAOKE_JOIN -> Line(PREFIX + "karaoke.join")
            ActivityKind.KARAOKE_LEAVE -> Line(PREFIX + "karaoke.leave")
            ActivityKind.KARAOKE_STAGE -> Line(PREFIX + if (e.flag) "karaoke.sing" else "karaoke.stop")
            ActivityKind.FULLSCREEN_START -> Line(PREFIX + "fullscreen.start")
            ActivityKind.FULLSCREEN_END -> Line(PREFIX + "fullscreen.end")
            ActivityKind.PREPARE -> Line(PREFIX + "prepare")
            ActivityKind.SCHEDULE_SET -> Line(
                PREFIX + if (e.text == "pause") "schedule.pause" else "schedule.play", [clock(e.toMs)],
            )
            ActivityKind.SCHEDULE_CANCEL -> Line(PREFIX + "schedule.cancel")
            ActivityKind.SCHEDULE_FIRED -> Line(PREFIX + if (e.text == "pause") "schedule.fired.pause" else "schedule.fired.play")
            ActivityKind.POLICY -> when {
                e.text.isEmpty() -> Line(PREFIX + "policy.reset_all")
                e.text2.isEmpty() -> Line(PREFIX + "policy.reset", [e.text])
                else -> Line(PREFIX + "policy", [e.text, e.text2])
            }
            ActivityKind.CREATED -> Line(PREFIX + "created")
            ActivityKind.RESIZED -> Line(PREFIX + "resized", [e.count.toString(), e.toMs.toString()])
            ActivityKind.MOVED -> Line(PREFIX + "moved")
            ActivityKind.DELETED -> Line(PREFIX + "deleted")
            ActivityKind.QUEUE_NEXT -> Line(PREFIX + "auto.next", [video])
            ActivityKind.LOOPED -> Line(PREFIX + "auto.loop")
            ActivityKind.AUTO_PAUSED -> Line(PREFIX + "auto.pause")
            ActivityKind.RESTORED -> Line(PREFIX + "auto.restored")
            ActivityKind.FULLSCREEN_ENDED -> Line(PREFIX + "auto.fullscreen_end")
        }
    }

    /** Translation key of a mode / access token inside a row, or null when it has none (shown as is). */
    fun tokenKey(t: String): String? = if (t in TOKENS) PREFIX + "value." + t else null

    private val TOKENS = setOf("local", "synced", "broadcast", "everyone", "region", "locked")

    /** [title], else a title for [url] from [titleOf], else [url] shortened. */
    fun videoName(title: String, url: String, titleOf: (String) -> String? = { null }): String =
        title.ifBlank { titleOf(url)?.takeIf { it.isNotBlank() } ?: shortUrl(url) }

    /** [url] without its scheme and `www.`, cut to [max] characters. */
    fun shortUrl(url: String, max: Int = 48): String {
        val bare = url.trim().replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "").removePrefix("www.")
        if (bare.isEmpty()) return "?"
        return if (bare.length <= max) bare else bare.take(max - 1) + "…"
    }

    /** A video position: `3:26`, `1:02:10`. */
    fun position(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) + 500L) / 1_000L
        return if (s >= 3_600) "%d:%02d:%02d".format(Locale.ROOT, s / 3_600, s % 3_600 / 60, s % 60)
        else "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
    }

    /** A playback speed with its × sign: `1.5×`, `2×`, `0.75×`. */
    fun speed(rate: Double): String {
        val r = Math.round(rate * 100.0) / 100.0
        val s = if (r == Math.floor(r)) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
        return "$s×"
    }

    /** Wall time [epochMs] in the local time zone, `HH:mm` (for a scheduled time). */
    fun clock(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        if (epochMs <= 0L) return "?"
        val fmt = java.text.SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = zone }
        return fmt.format(Date(epochMs))
    }

    /** Exact local date and time of [epochMs], for the hover on a row's relative time. */
    fun exact(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = zone }
        return fmt.format(Date(epochMs))
    }

    /** "2m ago" for something [agoMs] ago, as a key and its number. */
    fun ago(agoMs: Long): Line {
        val s = agoMs.coerceAtLeast(0L) / 1_000L
        return when {
            s < 10 -> Line(PREFIX + "ago.now")
            s < 60 -> Line(PREFIX + "ago.seconds", [s.toString()])
            s < 3_600 -> Line(PREFIX + "ago.minutes", [(s / 60).toString()])
            s < 86_400 -> Line(PREFIX + "ago.hours", [(s / 3_600).toString()])
            else -> Line(PREFIX + "ago.days", [(s / 86_400).toString()])
        }
    }

    /** Every key the rows can use (the language files must have them all). */
    val allKeys: List<String> = [
        "play", "pause", "seek", "restart", "speed", "video", "clear", "queue.add", "queue.playlist", "queue.remove",
        "queue.move.up", "queue.move.down", "queue.move.to", "queue.skip", "queue.clear", "queue.loop.on",
        "queue.loop.off", "mode", "access", "rename", "rename.clear", "share.start", "share.stop", "karaoke.join",
        "karaoke.leave", "karaoke.sing", "karaoke.stop", "fullscreen.start", "fullscreen.end", "prepare", "schedule.play",
        "schedule.pause", "schedule.cancel", "schedule.fired.play", "schedule.fired.pause", "policy", "policy.reset", "policy.reset_all",
        "created", "resized", "moved", "deleted", "auto.next", "auto.loop", "auto.pause", "auto.restored", "auto.fullscreen_end",
        "ago.now", "ago.seconds", "ago.minutes", "ago.hours", "ago.days",
    ].map { PREFIX + it } + TOKENS.map { PREFIX + "value." + it }
}
