package com.dreamdisplays.platform.server.karaoke

import io.github.arnodoelinger.platformweaver.PaperOnly
import de.maxhenkel.voicechat.api.BukkitVoicechatService
import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.events.Event
import de.maxhenkel.voicechat.api.events.EventRegistration
import java.lang.reflect.Field
import java.util.function.Consumer

/**
 * Lets the karaoke voice add-on attach to and detach from a Simple Voice Chat that keeps running, so DreamDisplays
 * can be reloaded with PlugManX.
 *
 * Voice chat collects its add-ons' event handlers exactly once, in `PluginManager.init()` on its first tick. An add-on
 * registered later is never initialised or sent events, and an unloaded one keeps getting events in its dead class
 * loader. This edits voice chat's `PluginManager` through reflection: on a hot load it initialises the add-on and
 * adds its handlers to a fresh copy of the `events` map, on unload it swaps in a copy without them. Voice chat
 * dispatches from network threads without locking, so a list it may be iterating is never modified, only replaced.
 *
 * Same approach as the hot swap of our voicechat-discord fork. Written against voice chat 2.5.36 (bukkit); re-check
 * the field names if it is ever updated.
 */
@PaperOnly
internal class VoicechatHotSwap private constructor(service: BukkitVoicechatService) {
    private val svcLoader: ClassLoader = service.javaClass.classLoader

    // The same list PluginManager iterates (init() keeps it as is)
    @Suppress("UNCHECKED_CAST")
    private val plugins = service.javaClass.getMethod("getPlugins").invoke(service) as MutableList<VoicechatPlugin>
    private val pluginManager: Any
    private val eventsField: Field

    init {
        val managerClass = Class.forName("de.maxhenkel.voicechat.plugins.PluginManager", true, svcLoader)
        pluginManager = managerClass.getMethod("instance").invoke(null)
        eventsField = managerClass.getDeclaredField("events").apply { isAccessible = true }
    }

    @Suppress("UNCHECKED_CAST")
    private fun events(): Map<Class<out Event>, List<Consumer<out Event>>>? =
        eventsField.get(pluginManager) as Map<Class<out Event>, List<Consumer<out Event>>>?

    private fun setEvents(events: Map<Class<out Event>, List<Consumer<out Event>>>) = eventsField.set(pluginManager, events)

    companion object {
        /** Handlers of ours (from this or an older, unloaded class loader) are classes in this package. */
        private val OWN_PREFIX = VoicechatHotSwap::class.java.name.substringBeforeLast('.') + "."

        /**
         * Call right after `registerPlugin`. On a normal boot voice chat hasn't initialised add-ons yet and this does
         * nothing (returns false). On a hot load it initialises [plugin], drops any stale instance of it and its
         * handlers, adds the new handlers, and returns true.
         */
        fun attach(service: BukkitVoicechatService, plugin: VoicechatPlugin): Boolean {
            val svc = VoicechatHotSwap(service)
            val current = svc.events() ?: return false
            svc.plugins.removeIf { it !== plugin && it.pluginId == plugin.pluginId }
            val events = withoutOwnHandlers(current)
            val serverApi = Class.forName("de.maxhenkel.voicechat.plugins.impl.VoicechatServerApiImpl", true, svc.svcLoader)
                .getMethod("instance").invoke(null) as VoicechatApi
            plugin.initialize(serverApi)
            // Priorities can't be honoured against handlers voice chat already sorted; appended after everyone
            // else's, ours cancels a singer's mic packet only after the other add-ons saw it.
            plugin.registerEvents(object : EventRegistration {
                override fun <T : Event> registerEvent(eventClass: Class<T>, onPacket: Consumer<T>, priority: Int) {
                    events.getOrPut(eventClass) { ArrayList() }.add(onPacket)
                }
            })
            svc.setEvents(events)
            return true
        }

        /** Stops voice chat from calling into this (soon unloaded) add-on. */
        fun detach(service: BukkitVoicechatService, plugin: VoicechatPlugin) {
            val svc = VoicechatHotSwap(service)
            svc.plugins.remove(plugin)
            svc.events()?.let { svc.setEvents(withoutOwnHandlers(it)) }
        }

        private fun withoutOwnHandlers(
            events: Map<Class<out Event>, List<Consumer<out Event>>>,
        ): HashMap<Class<out Event>, MutableList<Consumer<out Event>>> {
            val copy = HashMap<Class<out Event>, MutableList<Consumer<out Event>>>()
            for ((eventClass, handlers) in events) {
                copy[eventClass] = handlers.filterTo(ArrayList()) { !it.javaClass.name.startsWith(OWN_PREFIX) }
            }
            return copy
        }
    }
}
