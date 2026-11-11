package com.dreamdisplays.platform.client.displays

/**
 * Which world a display stands in, as far as the client can tell. A protocol-12 server names it (`DisplayInfo.world`,
 * empty for virtual displays). Older servers don't, so the client relies on what they do: an ordinary (not forced,
 * not virtual) description only ever goes to players in the display's own world and range, while a forced one (a
 * pinned PiP on join, a fullscreen broadcast target) can reach a player anywhere. An empty key means "not known yet":
 * such a display shows in its popouts but never in the world.
 */
internal object DisplayDimensions {
    /** The key a display created from a description (naming [serverWorld], empty when it doesn't) starts with. */
    fun keyForNewScreen(serverWorld: String, forced: Boolean, virtual: Boolean, currentKey: String): String = when {
        serverWorld.isNotEmpty() -> serverWorld
        forced || virtual -> ""
        else -> currentKey
    }

    /**
     * The key after another description for an existing display: the world the server names, or else (older server)
     * the current world when an ordinary description proves it.
     */
    fun learnedKey(existingKey: String, serverWorld: String, forced: Boolean, virtual: Boolean, currentKey: String): String =
        when {
            serverWorld.isNotEmpty() -> serverWorld
            !forced && !virtual && currentKey.isNotEmpty() -> currentKey
            else -> existingKey
        }

    /** True when the display stands in the world the player is in now: drawn, clickable and heard in place. */
    fun inWorldHere(virtual: Boolean, screenKey: String, currentKey: String): Boolean =
        !virtual && screenKey.isNotEmpty() && screenKey == currentKey

    /** A display whose world is unknown is not kept for a distance restore: it could come back in the wrong world. */
    fun cacheableForRestore(screenKey: String): Boolean = screenKey.isNotEmpty()
}
