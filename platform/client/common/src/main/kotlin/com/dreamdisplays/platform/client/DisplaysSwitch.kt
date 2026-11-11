package com.dreamdisplays.platform.client

/**
 * Where "displays on / off" lives. The player's own switch is global ([Config.displaysEnabled]); what a server sets
 * (`SetDisplaysEnabled`, from `/display off` on it, by the player or an admin) is kept for that server only, so no
 * server can switch displays off on every other one.
 */
object DisplaysSwitch {
    /** Displays are on for [serverId] (null: not connected) when on globally and not switched off by that server. */
    fun enabledOn(globalEnabled: Boolean, disabledServers: Set<String>, serverId: String?): Boolean =
        globalEnabled && (serverId == null || key(serverId) !in disabledServers)

    /** The switch after server [serverId] (null: unknown) set displays [enabled]: the new global flag and server set. */
    fun afterServerSet(
        globalEnabled: Boolean, disabledServers: Set<String>, serverId: String?, enabled: Boolean,
    ): Pair<Boolean, Set<String>> = when {
        serverId == null -> globalEnabled to disabledServers
        // Turned back on: also lifts a global "off" older versions stored when a server switched it off
        enabled -> true to (disabledServers - key(serverId))
        else -> globalEnabled to (disabledServers + key(serverId))
    }

    fun parseServers(raw: String): Set<String> =
        raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun formatServers(servers: Set<String>): String = servers.sorted().joinToString(",")

    private fun key(serverId: String): String = serverId.trim().lowercase()
}
