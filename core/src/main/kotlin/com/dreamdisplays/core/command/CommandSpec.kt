package com.dreamdisplays.core.command

/**
 * A help area of `/display`. A [prefix] area is also the first word of its commands (`/display queue add ...`), and on
 * its own prints its help; the others only group top-level commands in `/display help`.
 */
enum class Area(val id: String, val prefix: Boolean) {
    DISPLAYS("displays", false),
    PLAYBACK("playback", false),
    SCHEDULE("schedule", true),
    QUEUE("queue", true),
    KARAOKE("karaoke", true),
    PREPARE("prepare", true),
    FULLSCREEN("fullscreen", true),
    SHARE("share", true),
    ADMIN("admin", false),
    DIAG("diag", true),
    VIEWING("viewing", false);

    /** Lang key of the area's one-line description. */
    val key: String get() = "cmd.area.$id"

    companion object {
        fun byId(id: String): Area? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** What an argument holds; decides its parser and what the platform suggests for it. */
enum class Kind {
    /** `this`, a display name, or a display id / unique id prefix. */
    DISPLAY,

    /** [DISPLAY], or `all`. */
    DISPLAY_OR_ALL,

    /** [DISPLAY], or a media URL (an ad-hoc display). */
    SOURCE,

    /** A fullscreen session id, a preparation id, a display, or `all`. */
    SESSION,

    /** An online player. */
    PLAYER,

    /** An online player, or one only known from client reports. */
    KNOWN_PLAYER,

    URL,

    /** Audio language code. */
    LANG,

    /** `HH:mm[:ss]` in the sender's time zone. */
    TIME,

    /** Positive whole number (pages, queue positions). */
    POSITIVE,

    /** One word; the platform suggests values by the argument's name. */
    WORD,

    /** The rest of the line; the platform suggests values by the argument's name. */
    REST,

    /** `/display fullscreen start` flags, any order. */
    START_FLAGS,

    /** `/display prepare start` flags, any order. */
    PREPARE_FLAGS,
}

/** One argument of a command. Optional arguments always come last. */
class ArgDef(val name: String, val kind: Kind, val optional: Boolean, val label: String = name) {
    val usage: String get() = if (optional) "[$label]" else "<$label>"
}

/**
 * One `/display` command: the literal [path] after `/display`, then [args]. [id] names its handler and its lang key
 * (`cmd.<id>`, also the tab tooltip). [usage] overrides the generated argument part of the usage line.
 */
class CommandDef(
    val id: String,
    val path: List<String>,
    val area: Area?,
    val permission: String?,
    val args: List<ArgDef>,
    val usage: String? = null,
    val inHelp: Boolean = true,
    val playerOnly: Boolean = false,
    /** Only a help line: another command with the same [path] parses and runs it (e.g. a reserved word in a slot). */
    val helpOnly: Boolean = false,
) {
    init {
        require(args.dropWhile { !it.optional }.all { it.optional }) { "$id: optional arguments must come last" }
        require(args.count { it.kind == Kind.REST || it.kind == Kind.START_FLAGS || it.kind == Kind.PREPARE_FLAGS } <= 1)
    }

    val key: String get() = "cmd.$id"

    /** The full command as `/display` text, without arguments. */
    val command: String get() = "/display " + path.joinToString(" ")

    /** The argument part of the usage line. */
    val argUsage: String get() = usage ?: args.joinToString(" ") { it.usage }

    /** The whole usage line. */
    val usageLine: String get() = listOf(command, argUsage).filter { it.isNotEmpty() }.joinToString(" ")

    val takesDisplay: Boolean
        get() = args.any { it.kind == Kind.DISPLAY || it.kind == Kind.DISPLAY_OR_ALL || it.kind == Kind.SOURCE }

    override fun toString(): String = usageLine
}

/** Builds [CommandDef]s with an area and permission in scope. */
class CommandSpecBuilder {
    val defs = mutableListOf<CommandDef>()

    fun cmd(
        id: String,
        path: String,
        area: Area?,
        permission: String?,
        vararg args: ArgDef,
        usage: String? = null,
        inHelp: Boolean = true,
        playerOnly: Boolean = false,
        helpOnly: Boolean = false,
    ) {
        defs += CommandDef(id, path.split(' '), area, permission, args.toList(), usage, inHelp, playerOnly, helpOnly)
    }
}

fun req(name: String, kind: Kind, label: String = name) = ArgDef(name, kind, optional = false, label = label)

fun opt(name: String, kind: Kind, label: String = name) = ArgDef(name, kind, optional = true, label = label)
