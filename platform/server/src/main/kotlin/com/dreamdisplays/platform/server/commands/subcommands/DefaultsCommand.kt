package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.command.DisplayCommands
import com.dreamdisplays.core.display.policy.PushedDefaults
import com.dreamdisplays.core.display.policy.PushedSetting
import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.core.sync.DisplayFollowers
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.PaperPlaybackTransport
import com.dreamdisplays.platform.server.playback.PipPinManager
import com.dreamdisplays.platform.server.policy.DisplayDefaults
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import com.dreamdisplays.shared.capability.ClientFeature
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.slf4j.LoggerFactory

/**
 * `/display defaults <display> [<setting> <value> | clear [setting]]`: sets every player's own volume, brightness,
 * quality, 3D audio tier or mute for one display once ([PushedDefaults]); each player may change it afterwards, until the
 * next push. Without a setting it lists what is pushed. Same permission as `/display policy`, whose levers still win.
 */
@PaperOnly
object DefaultsCommand {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Entry point; [setting] and [value] are null when not typed. */
    fun execute(sender: CommandSender, token: String, setting: String?, value: String?) {
        val display = resolvePaperDisplay(sender, token) ?: return
        when {
            setting == null -> show(sender, display, always = true)
            setting.equals(DisplayCommands.CLEAR_DEFAULTS, ignoreCase = true) -> clear(sender, display, value)
            else -> {
                val which = find(sender, setting) ?: return
                if (value == null) {
                    show(sender, display, always = true, only = which)
                    return
                }
                push(sender, display, which, value)
            }
        }
    }

    private fun push(sender: CommandSender, display: DisplayData, setting: PushedSetting, raw: String) {
        val canonical = setting.parse(raw) ?: run {
            ServerMessage.POLICY_BAD_VALUE.sendTo(sender, setting.wire, setting.hint)
            return
        }
        val pushed = DisplayDefaults.push(display, setting, canonical) ?: run {
            ServerMessage.DEFAULTS_NO_ROOM.sendTo(sender, display.shortLabel)
            return
        }
        val shown = setting.show(canonical)
        val (capable, old) = viewerSplit(display)
        ServerMessage.DEFAULTS_PUSHED.sendTo(sender, setting.wire, shown, display.shortLabel, capable.toString())
        if (old > 0) ServerMessage.DEFAULTS_OLD_CLIENTS.sendTo(sender, old.toString())
        logger.info(
            "{} pushed {} = {} on display {} ({}), revision {}: {} viewer(s) get it now, {} on older clients don't.",
            sender.name, setting.wire, shown, display.shortLabel, display.id, pushed.rev, capable, old,
        )
        // Activity log hook (fix27): DisplayActivity.record(display, ActivityKind.POLICY, text = "defaults.${setting.wire}", text2 = shown)
    }

    private fun clear(sender: CommandSender, display: DisplayData, raw: String?) {
        val setting = raw?.let { find(sender, it) ?: return }
        val changed = DisplayDefaults.clear(display, setting)
        if (setting == null) ServerMessage.DEFAULTS_CLEARED_ALL.sendTo(sender, display.shortLabel)
        else ServerMessage.DEFAULTS_CLEARED.sendTo(sender, setting.wire, display.shortLabel)
        if (changed) {
            logger.info("{} stopped pushing {} on display {} ({}).", sender.name, setting?.wire ?: "every default", display.shortLabel, display.id)
            // Activity log hook (fix27): DisplayActivity.record(display, ActivityKind.POLICY, text = "defaults.${setting?.wire ?: "all"}", text2 = "")
        }
    }

    private fun find(sender: CommandSender, name: String): PushedSetting? = PushedSetting.fromWire(name) ?: run {
        ServerMessage.DEFAULTS_UNKNOWN_SETTING.sendTo(sender, name, settingNames)
        null
    }

    /**
     * Lists what is pushed on [display] (only [only] when given). With [always] false (the `/display info` and
     * `/display policy` views) it says nothing when nothing is pushed.
     */
    fun show(sender: CommandSender, display: DisplayData, always: Boolean = false, only: PushedSetting? = null) {
        val pushed = DisplayDefaults.of(display)
        val lines = PushedSetting.entries.filter { only == null || it == only }.mapNotNull { s -> pushed[s]?.let { s to it } }
        if (lines.isEmpty()) {
            if (always) ServerMessage.DEFAULTS_NONE.sendTo(sender, display.shortLabel, settingNames)
            return
        }
        ServerMessage.DEFAULTS_HEADER.sendTo(sender, display.shortLabel)
        val now = System.currentTimeMillis()
        for ((setting, entry) in lines) {
            ServerMessage.DEFAULTS_LINE.sendTo(sender, setting.wire, setting.show(entry.value), ago(now - entry.rev))
        }
    }

    /**
     * Current viewers of [display] (in range, fullscreen followers, PiP pinners) with the mod: how many apply a push
     * now, and how many run a build without `ClientFeature.PUSHED_DEFAULTS` (they keep their own setting).
     */
    private fun viewerSplit(display: DisplayData): Pair<Int, Int> {
        val nearby = PaperPlaybackTransport.nearbyPlayerIds(display)
        val viewers = nearby + DisplayFollowers.farReceivers(
            nearby, FullscreenBroadcastManager.timelineFollowers(display.id), PipPinManager.pinnersOf(display.id),
        )
        val hellos = viewers.distinct().mapNotNull { V2PlayerTracker.helloOf(it) }
        val capable = hellos.count { it.hasFeature(ClientFeature.PUSHED_DEFAULTS) }
        return capable to hellos.size - capable
    }

    /** A revision is the push time in epoch millis (or just above the one before it): how long ago, roughly. */
    private fun ago(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            else -> "${s / 86_400}d"
        }
    }

    private val settingNames: String get() = PushedSetting.entries.joinToString(", ") { it.wire }

    /** Suggestions for the setting slot: every setting plus `clear`. */
    fun settingSuggestions(): List<String> = PushedSetting.entries.map { it.wire } + DisplayCommands.CLEAR_DEFAULTS

    /** Suggestions for the value slot: [setting]'s values, or after `clear` the settings. */
    fun valueSuggestions(setting: String): List<String> {
        if (setting.equals(DisplayCommands.CLEAR_DEFAULTS, ignoreCase = true)) return PushedSetting.entries.map { it.wire }
        return PushedSetting.fromWire(setting)?.suggestions ?: emptyList()
    }
}
