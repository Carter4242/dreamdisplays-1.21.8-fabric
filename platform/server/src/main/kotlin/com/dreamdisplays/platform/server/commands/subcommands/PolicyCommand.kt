package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.display.policy.PolicyPaths
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.policy.DisplayPolicies
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/display policy <display> [<key> [<value>|reset] | reset]`: shows or edits one display's policy override.
 * Keys are the `[policy.*]` config names (`audio.radius_blocks`); changes are saved and reach viewers at once.
 */
@PaperOnly
object PolicyCommand {
    const val RESET = "reset"

    /** Entry point; [key] and [value] are null when not typed. */
    fun execute(sender: CommandSender, token: String, key: String?, value: String?) {
        val display = resolve(sender, token) ?: return
        when {
            key == null -> show(sender, display)
            key.equals(RESET, ignoreCase = true) && value == null -> {
                DisplayPolicies.clearOverride(display)
                ServerMessage.POLICY_RESET_ALL.sendTo(sender, display.shortLabel)
            }

            else -> {
                val path = PolicyPaths.find(key) ?: run {
                    ServerMessage.POLICY_UNKNOWN_KEY.sendTo(sender, key, perDisplay.joinToString(", ") { it.name })
                    return
                }
                if (path.serverWideOnly) {
                    ServerMessage.POLICY_SERVER_WIDE_ONLY.sendTo(sender, path.name, path.name)
                    return
                }
                if (value == null) {
                    showOne(sender, display, path)
                    return
                }
                val raw = if (value.equals(RESET, ignoreCase = true)) null else value
                val updated = PolicyPaths.apply(DisplayPolicies.override(display), path, raw)
                val applied = updated != null && DisplayPolicies.setOverride(display, updated)
                when {
                    !applied -> ServerMessage.POLICY_BAD_VALUE.sendTo(sender, path.name, path.hint)
                    raw == null -> ServerMessage.POLICY_RESET.sendTo(sender, path.name, display.shortLabel)
                    else -> ServerMessage.POLICY_SET.sendTo(
                        sender, path.name, PolicyPaths.format(path.get(updated)), display.shortLabel,
                    )
                }
            }
        }
    }

    private fun show(sender: CommandSender, display: DisplayData) {
        ServerMessage.POLICY_HEADER.sendTo(sender, display.shortLabel)
        perDisplay.forEach { showOne(sender, display, it, header = false) }
    }

    private fun showOne(sender: CommandSender, display: DisplayData, path: PolicyPaths.Path, header: Boolean = true) {
        if (header) ServerMessage.POLICY_HEADER.sendTo(sender, display.shortLabel)
        val override = DisplayPolicies.override(display)?.let(path.get)
        val effective = path.get(DisplayPolicies.effective(display))
        ServerMessage.POLICY_LINE.sendTo(
            sender, path.name, PolicyPaths.format(effective), PolicyPaths.format(override),
        )
    }

    private fun resolve(sender: CommandSender, token: String): DisplayData? {
        if (sender is Player) return resolvePaperDisplayTarget(sender, sender, token)
        return DisplayManager.resolveByIdOrPrefix(token) ?: run {
            ServerMessage.NO_DISPLAY.sendTo(sender)
            null
        }
    }

    /** Suggestions for the key slot: every policy key plus `reset`. */
    fun keySuggestions(): List<String> = perDisplay.map { it.name } + RESET

    /** The keys a single display can override. */
    private val perDisplay: List<PolicyPaths.Path> get() = PolicyPaths.all.filter { !it.serverWideOnly }

    /** Suggestions for the value slot of [key]: its choices (or true / false) plus `reset`. */
    fun valueSuggestions(key: String): List<String> {
        val path = PolicyPaths.find(key) ?: return []
        return when (val kind = path.kind) {
            PolicyPaths.Kind.Bool -> ["true", "false"]
            is PolicyPaths.Kind.Choice -> kind.values
            else -> []
        } + RESET
    }

    /** Whether [sender] may use this command: the reload permission (op by default). */
    fun allowed(sender: CommandSender): Boolean = sender.hasPermission(PaperServer.config.permissions.reload)
}
