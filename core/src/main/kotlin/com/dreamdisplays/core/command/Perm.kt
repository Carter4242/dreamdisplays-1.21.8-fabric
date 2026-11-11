package com.dreamdisplays.core.command

/**
 * Every permission node Dream Displays checks: one per `/display` action, plus the display menu's own. All of them are
 * operator-only by default; [ADMIN] grants every one. Node names are fixed here (not remappable in `config.toml`),
 * and `paper-plugin.yml` declares exactly [ALL] plus [LEGACY].
 */
object Perm {
    const val ADMIN = "dreamdisplays.admin"

    const val DISPLAY_CREATE = "dreamdisplays.display.create"
    const val DISPLAY_CREATE_UNLIMITED = "dreamdisplays.display.create.unlimited"
    const val DISPLAY_DELETE = "dreamdisplays.display.delete"
    const val DISPLAY_NAME = "dreamdisplays.display.name"
    const val DISPLAY_INFO = "dreamdisplays.display.info"
    const val DISPLAY_LIST = "dreamdisplays.display.list"

    /** Act on displays someone else owns (delete, name, video, schedule, the menu's playback controls). */
    const val DISPLAY_OTHERS = "dreamdisplays.display.others"

    const val PLAYBACK_VIDEO = "dreamdisplays.playback.video"
    const val PLAYBACK_SPEED = "dreamdisplays.playback.speed"
    const val PLAYBACK_MODE = "dreamdisplays.playback.mode"
    const val PLAYBACK_SCHEDULE = "dreamdisplays.playback.schedule"

    const val QUEUE = "dreamdisplays.queue"
    const val KARAOKE_MANAGE = "dreamdisplays.karaoke.manage"
    const val KARAOKE_JOIN = "dreamdisplays.karaoke.join"
    const val PREPARE = "dreamdisplays.prepare"
    const val FULLSCREEN = "dreamdisplays.fullscreen"
    const val FULLSCREEN_NETWORK = "dreamdisplays.fullscreen.network"
    const val SHARE_MANAGE = "dreamdisplays.share.manage"
    const val VIEWING_TOGGLE = "dreamdisplays.viewing.toggle"
    const val VIEWING_TOGGLE_OTHERS = "dreamdisplays.viewing.toggle.others"
    const val ADMIN_CONFIG = "dreamdisplays.admin.config"
    const val ADMIN_POLICY = "dreamdisplays.admin.policy"
    const val DIAG = "dreamdisplays.diag"

    // Display menu (not commands)
    const val MENU_LOCAL = "dreamdisplays.local"
    const val MENU_SYNCED = "dreamdisplays.synced"
    const val MENU_BROADCAST = "dreamdisplays.broadcast"
    const val MENU_CUSTOM = "dreamdisplays.custom"
    const val MENU_LOCK = "dreamdisplays.lock"
    const val MENU_SHARE = "dreamdisplays.share"
    const val MENU_PREMIUM = "dreamdisplays.premium"
    const val MENU_UPDATES = "dreamdisplays.updates"

    /** Every node except [ADMIN], which grants all of them. */
    val ALL: List<String> = listOf(
        DISPLAY_CREATE, DISPLAY_CREATE_UNLIMITED, DISPLAY_DELETE, DISPLAY_NAME, DISPLAY_INFO, DISPLAY_LIST, DISPLAY_OTHERS,
        PLAYBACK_VIDEO, PLAYBACK_SPEED, PLAYBACK_MODE, PLAYBACK_SCHEDULE,
        QUEUE, KARAOKE_MANAGE, KARAOKE_JOIN, PREPARE, FULLSCREEN, FULLSCREEN_NETWORK, SHARE_MANAGE,
        VIEWING_TOGGLE, VIEWING_TOGGLE_OTHERS, ADMIN_CONFIG, ADMIN_POLICY, DIAG,
        MENU_LOCAL, MENU_SYNCED, MENU_BROADCAST, MENU_CUSTOM, MENU_LOCK, MENU_SHARE, MENU_PREMIUM,
        MENU_UPDATES,
    )

    /**
     * Nodes of the previous release and the nodes they now grant. Declared with these as children for one release so
     * existing grants keep working; drop afterwards.
     */
    val LEGACY: Map<String, List<String>> = linkedMapOf(
        "dreamdisplays.create" to listOf(DISPLAY_CREATE),
        "dreamdisplays.create.bypass" to listOf(DISPLAY_CREATE_UNLIMITED),
        "dreamdisplays.delete" to listOf(DISPLAY_DELETE),
        "dreamdisplays.delete.others" to listOf(DISPLAY_OTHERS),
        "dreamdisplays.name" to listOf(DISPLAY_NAME),
        "dreamdisplays.name.others" to listOf(DISPLAY_OTHERS),
        "dreamdisplays.info" to listOf(DISPLAY_INFO),
        "dreamdisplays.list" to listOf(DISPLAY_LIST),
        "dreamdisplays.video" to listOf(PLAYBACK_VIDEO),
        "dreamdisplays.speed" to listOf(PLAYBACK_SPEED),
        "dreamdisplays.mode" to listOf(PLAYBACK_MODE),
        "dreamdisplays.schedule" to listOf(PLAYBACK_SCHEDULE),
        "dreamdisplays.schedule.others" to listOf(DISPLAY_OTHERS),
        "dreamdisplays.karaoke" to listOf(KARAOKE_MANAGE),
        "dreamdisplays.fullscreen.start" to listOf(FULLSCREEN, PREPARE),
        "dreamdisplays.fullscreen.stop" to listOf(FULLSCREEN),
        "dreamdisplays.fullscreen.list" to listOf(FULLSCREEN),
        "dreamdisplays.share.admin" to listOf(SHARE_MANAGE),
        "dreamdisplays.help" to listOf(VIEWING_TOGGLE),
        "dreamdisplays.toggle.others" to listOf(VIEWING_TOGGLE_OTHERS),
        "dreamdisplays.reload" to listOf(ADMIN_CONFIG, ADMIN_POLICY, DIAG),
        "dreamdisplays.stats" to listOf(DIAG),
    )

    /**
     * Resolves [node] on a platform without Bukkit's permission children (Fabric / NeoForge with LuckPerms): the node's
     * own setting first, then a [LEGACY] node that grants it, then [ADMIN]. [lookup] gives a node's explicit setting
     * (null when unset). Null when none of them is set, so the caller's default applies.
     */
    fun resolve(node: String, lookup: (String) -> Boolean?): Boolean? {
        lookup(node)?.let { return it }
        for ((legacy, grants) in LEGACY) {
            if (node in grants) lookup(legacy)?.let { return it }
        }
        if (node != ADMIN && (node in ALL || LEGACY.values.any { node in it })) lookup(ADMIN)?.let { return it }
        return null
    }
}
