package com.dreamdisplays.core.command

import com.dreamdisplays.core.command.Kind.*

/**
 * The whole `/display` command surface. Execution, tab completion, help, usage errors and permissions are all built from
 * this list; the platforms only supply a handler per [CommandDef.id].
 *
 * Grammar: `/display <verb> [display] [args]`, or `/display <area> <action> [display] [args]` where an area has several
 * actions. The display always comes right after the action; it may be left off only when nothing follows it, and then
 * means the display the player is looking at.
 */
object DisplayCommands {
    val ALL: List<CommandDef> = CommandSpecBuilder().apply {
        cmd("help", "help", null, null, opt("area", WORD))
        cmd("on", "on", Area.VIEWING, Perm.VIEWING_TOGGLE, opt("player", PLAYER))
        cmd("off", "off", Area.VIEWING, Perm.VIEWING_TOGGLE, opt("player", PLAYER))

        cmd("create", "create", Area.DISPLAYS, Perm.DISPLAY_CREATE, playerOnly = true)
        cmd("delete", "delete", Area.DISPLAYS, Perm.DISPLAY_DELETE, opt("display", DISPLAY), playerOnly = true)
        cmd("info", "info", Area.DISPLAYS, Perm.DISPLAY_INFO, opt("display", DISPLAY), playerOnly = true)
        cmd(
            "name", "name", Area.DISPLAYS, Perm.DISPLAY_NAME,
            opt("display", DISPLAY), opt("name", WORD, "name|reset"), playerOnly = true,
        )
        cmd(
            "list", "list", Area.DISPLAYS, Perm.DISPLAY_LIST, opt("page", POSITIVE),
            usage = "[mine|world <world>|owner <player>|mode <mode>] [page]",
        )
        cmd("list.mine", "list mine", Area.DISPLAYS, Perm.DISPLAY_LIST, opt("page", POSITIVE), inHelp = false)
        cmd(
            "list.world", "list world", Area.DISPLAYS, Perm.DISPLAY_LIST,
            req("world", WORD), opt("page", POSITIVE), inHelp = false,
        )
        cmd(
            "list.owner", "list owner", Area.DISPLAYS, Perm.DISPLAY_LIST,
            req("owner", WORD, "player"), opt("page", POSITIVE), inHelp = false,
        )
        cmd(
            "list.mode", "list mode", Area.DISPLAYS, Perm.DISPLAY_LIST,
            req("mode", WORD, "synced|broadcast|local"), opt("page", POSITIVE), inHelp = false,
        )

        cmd(
            "video", "video", Area.PLAYBACK, Perm.PLAYBACK_VIDEO,
            req("display", DISPLAY), req("url", URL), opt("lang", LANG), playerOnly = true,
        )
        // `clear` in the url slot: parsed and run by `video` above
        cmd(
            "video.clear", "video", Area.PLAYBACK, Perm.PLAYBACK_VIDEO,
            req("display", DISPLAY), usage = "<display> clear", playerOnly = true, helpOnly = true,
        )
        cmd("speed", "speed", Area.PLAYBACK, Perm.PLAYBACK_SPEED, opt("display", DISPLAY), opt("rate", WORD, "0.1-10|reset"))
        cmd(
            "mode", "mode", Area.PLAYBACK, Perm.PLAYBACK_MODE,
            opt("display", DISPLAY), opt("mode", WORD, "synced|broadcast|local"),
        )
        cmd("stats", "stats", Area.PLAYBACK, Perm.DIAG, opt("display", DISPLAY_OR_ALL, "display|all"))

        cmd("schedule.show", "schedule show", Area.SCHEDULE, Perm.PLAYBACK_SCHEDULE, opt("display", DISPLAY), playerOnly = true)
        cmd(
            "schedule.play", "schedule play", Area.SCHEDULE, Perm.PLAYBACK_SCHEDULE,
            req("display", DISPLAY), opt("time", TIME, "HH:mm[:ss]"), playerOnly = true,
        )
        cmd(
            "schedule.pause", "schedule pause", Area.SCHEDULE, Perm.PLAYBACK_SCHEDULE,
            req("display", DISPLAY), opt("time", TIME, "HH:mm[:ss]"), playerOnly = true,
        )
        cmd("schedule.cancel", "schedule cancel", Area.SCHEDULE, Perm.PLAYBACK_SCHEDULE, opt("display", DISPLAY), playerOnly = true)

        cmd("queue.list", "queue list", Area.QUEUE, Perm.QUEUE, opt("display", DISPLAY))
        cmd("queue.add", "queue add", Area.QUEUE, Perm.QUEUE, req("display", DISPLAY), req("url", URL))
        cmd("queue.remove", "queue remove", Area.QUEUE, Perm.QUEUE, req("display", DISPLAY), req("n", POSITIVE))
        cmd(
            "queue.move", "queue move", Area.QUEUE, Perm.QUEUE,
            req("display", DISPLAY), req("n", POSITIVE), req("to", POSITIVE),
        )
        cmd("queue.skip", "queue skip", Area.QUEUE, Perm.QUEUE, opt("display", DISPLAY))
        cmd("queue.clear", "queue clear", Area.QUEUE, Perm.QUEUE, opt("display", DISPLAY))
        cmd("queue.loop", "queue loop", Area.QUEUE, Perm.QUEUE, req("display", DISPLAY), opt("state", WORD, "on|off"))

        cmd("karaoke.status", "karaoke status", Area.KARAOKE, Perm.KARAOKE_MANAGE, opt("display", DISPLAY))
        cmd("karaoke.stop", "karaoke stop", Area.KARAOKE, Perm.KARAOKE_MANAGE, opt("display", DISPLAY))
        cmd("karaoke.trim", "karaoke trim", Area.KARAOKE, Perm.KARAOKE_MANAGE, req("display", DISPLAY), req("ms", WORD))
        cmd(
            "karaoke.singer.add", "karaoke singer add", Area.KARAOKE, Perm.KARAOKE_MANAGE,
            req("display", DISPLAY), req("player", PLAYER), opt("duet", WORD), opt("lead", WORD, "lead_ms"),
            usage = "<display> <player> [duet] [lead_ms]",
        )
        cmd(
            "karaoke.singer.remove", "karaoke singer remove", Area.KARAOKE, Perm.KARAOKE_MANAGE,
            req("display", DISPLAY), req("singer", WORD, "player"),
        )
        cmd(
            "karaoke.singer.trim", "karaoke singer trim", Area.KARAOKE, Perm.KARAOKE_MANAGE,
            req("display", DISPLAY), req("singer", WORD, "player"), req("ms", WORD),
        )
        cmd(
            "karaoke.join", "karaoke join", Area.KARAOKE, Perm.KARAOKE_JOIN,
            req("display", DISPLAY), req("url", URL), playerOnly = true,
        )
        cmd("karaoke.leave", "karaoke leave", Area.KARAOKE, Perm.KARAOKE_JOIN, opt("display", DISPLAY), playerOnly = true)
        cmd("karaoke.line.list", "karaoke line list", Area.KARAOKE, Perm.KARAOKE_JOIN, opt("display", DISPLAY))
        cmd(
            "karaoke.line.add", "karaoke line add", Area.KARAOKE, Perm.KARAOKE_MANAGE,
            req("display", DISPLAY), req("player", PLAYER), req("url", URL),
        )
        cmd(
            "karaoke.line.remove", "karaoke line remove", Area.KARAOKE, Perm.KARAOKE_MANAGE,
            req("display", DISPLAY), req("singer", WORD, "player"),
        )
        cmd("karaoke.line.clear", "karaoke line clear", Area.KARAOKE, Perm.KARAOKE_MANAGE, opt("display", DISPLAY))

        cmd(
            "prepare.start", "prepare start", Area.PREPARE, Perm.PREPARE,
            req("source", SOURCE, "display|url"), opt("flags", PREPARE_FLAGS),
            usage = "<display|url> [target <players>] [radius <blocks> [x y z]] [quality <q>] [rewind]",
        )
        cmd("prepare.list", "prepare list", Area.PREPARE, Perm.PREPARE)
        cmd("prepare.cancel", "prepare cancel", Area.PREPARE, Perm.PREPARE, req("id", SESSION, "id|display|all"))

        cmd(
            "fullscreen.start", "fullscreen start", Area.FULLSCREEN, Perm.FULLSCREEN,
            req("source", SOURCE, "display|url"), opt("flags", START_FLAGS),
        )
        cmd("fullscreen.list", "fullscreen list", Area.FULLSCREEN, Perm.FULLSCREEN)
        cmd("fullscreen.stop", "fullscreen stop", Area.FULLSCREEN, Perm.FULLSCREEN, req("id", SESSION, "session|display|all"))

        cmd("share.stop", "share stop", Area.SHARE, Perm.SHARE_MANAGE, opt("display", DISPLAY))
        cmd("share.list", "share list", Area.SHARE, Perm.SHARE_MANAGE)

        cmd("config", "config", Area.ADMIN, Perm.ADMIN_CONFIG, opt("key", WORD), opt("value", REST, "value|reset"))
        cmd(
            "policy", "policy", Area.ADMIN, Perm.ADMIN_POLICY,
            opt("display", DISPLAY), opt("key", WORD, "key|reset"), opt("value", WORD, "value|reset"),
        )
        cmd("reload", "reload", Area.ADMIN, Perm.ADMIN_CONFIG)

        cmd("diag.clients", "diag clients", Area.DIAG, Perm.DIAG)
        cmd("diag.env", "diag env", Area.DIAG, Perm.DIAG, req("player", KNOWN_PLAYER))
        cmd(
            "diag.loglevel", "diag loglevel", Area.DIAG, Perm.DIAG,
            req("player", KNOWN_PLAYER), opt("level", WORD, "quiet|normal|verbose|ultra|reset"),
        )
        cmd(
            "diag.errors", "diag errors", Area.DIAG, Perm.DIAG,
            opt("who", WORD, "player|display|#n"), opt("type", WORD, "errors|hitches|shares|commands|all"),
            opt("count", WORD),
        )
    }.defs.toList()

    /** The word in `/display video <display> <url>`'s url slot that clears the display's video instead. */
    const val CLEAR_VIDEO = "clear"

    /** Words a display name may not be, because they mean something else where a display goes. */
    val RESERVED_NAMES: Set<String> = setOf("this", "all", "reset", "id", "url")

    /** The command with [id]. */
    fun byId(id: String): CommandDef = ALL.first { it.id == id }
}
