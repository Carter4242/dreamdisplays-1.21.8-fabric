package com.dreamdisplays.platform.server.utils

import java.util.*

/**
 * Tells [onRecalculated] whose permissions `LuckPerms` recalculated (a grant, a revocation, a group or track change),
 * on `LuckPerms`' own threads. `LuckPerms` is optional: [subscribe] returns null when it is not installed or not
 * running, and its classes are only touched through [Api], which is loaded only once `LuckPerms` is known to be there.
 */
object LuckPermsRecalc {
    /**
     * Subscribes; close the result to unsubscribe. [owner] is the `Bukkit` plugin on `Paper` (`LuckPerms` then also drops
     * the subscription when that plugin is disabled), null on the mod loaders.
     */
    fun subscribe(owner: Any?, onRecalculated: (UUID) -> Unit): AutoCloseable? {
        try {
            Class.forName("net.luckperms.api.LuckPermsProvider")
        } catch (_: ClassNotFoundException) {
            return null
        }
        return Api.subscribe(owner, onRecalculated)
    }

    private object Api {
        fun subscribe(owner: Any?, onRecalculated: (UUID) -> Unit): AutoCloseable? {
            val bus = try {
                net.luckperms.api.LuckPermsProvider.get().eventBus
            } catch (_: IllegalStateException) {
                // Installed but not loaded (yet)
                return null
            }
            val type = net.luckperms.api.event.user.UserDataRecalculateEvent::class.java
            val handler = java.util.function.Consumer<net.luckperms.api.event.user.UserDataRecalculateEvent> {
                onRecalculated(it.user.uniqueId)
            }
            return if (owner != null) bus.subscribe(owner, type, handler) else bus.subscribe(type, handler)
        }
    }
}
