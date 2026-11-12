package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.command.Perm
import com.dreamdisplays.platform.server.playback.DisplayActivity
import com.dreamdisplays.core.activity.ActivityKind
import com.dreamdisplays.core.display.policy.PlayerPolicies
import com.dreamdisplays.core.display.policy.PolicyPaths
import com.dreamdisplays.core.display.region.RegionVisibility
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.DisplayRegions
import com.dreamdisplays.platform.server.policy.ClientErrorReports
import com.dreamdisplays.platform.server.policy.DisplayPolicies
import com.dreamdisplays.platform.server.policy.PlayerPolicyOverrides
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.WorldGuardRegions
import com.dreamdisplays.platform.server.utils.sendTo
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
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
                DisplayActivity.record(display, ActivityKind.POLICY)
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
                if (path.field == REGION_FIELD && raw != null && !regionUsable(sender, display, raw.trim())) return
                val updated = PolicyPaths.apply(DisplayPolicies.override(display), path, raw)
                val applied = updated != null && DisplayPolicies.setOverride(display, updated)
                if (applied) DisplayActivity.record(display, ActivityKind.POLICY, text = path.name, text2 = raw?.let { PolicyPaths.format(path.get(updated!!)) }.orEmpty())
                when {
                    !applied -> ServerMessage.POLICY_BAD_VALUE.sendTo(sender, path.name, path.hint)
                    raw == null -> ServerMessage.POLICY_RESET.sendTo(sender, path.name, display.shortLabel)
                    else -> ServerMessage.POLICY_SET.sendTo(
                        sender, path.name, PolicyPaths.format(path.get(updated)), display.shortLabel,
                    )
                }
                // What the region now does (or why nobody sees the display)
                if (applied && path.field.startsWith(REGION_FIELD)) {
                    (display as? PaperDisplayData)?.let { RegionStatus.send(sender, it) }
                }
            }
        }
    }

    /**
     * `/display policy player <player> [<key> [<value>|reset] | reset]`: shows or edits one player's own client policy
     * ([PlayerPolicies]): the server-wide-only keys, on top of the `[policy.*]` defaults, for that player's client only.
     */
    fun executePlayer(sender: CommandSender, name: String, key: String?, value: String?) {
        val (id, playerName) = Bukkit.getPlayerExact(name)?.let { it.uniqueId to it.name }
            ?: ClientErrorReports.findPlayer(name)
            ?: Bukkit.getOfflinePlayerIfCached(name)?.let { it.uniqueId to (it.name ?: name) }
            ?: run {
                ServerMessage.POLICY_PLAYER_UNKNOWN.sendTo(sender, name)
                return
            }
        val override = PlayerPolicyOverrides.get(id)
        when {
            key == null -> {
                ServerMessage.POLICY_PLAYER_HEADER.sendTo(sender, playerName)
                val effective = PlayerPolicyOverrides.applyTo(DisplayPolicies.defaults().serverWide(), id)
                PlayerPolicies.keys.forEach {
                    ServerMessage.POLICY_LINE.sendTo(
                        sender, it.name, PolicyPaths.format(it.get(effective)), PolicyPaths.format(override?.let(it.get)),
                    )
                }
            }

            key.equals(RESET, ignoreCase = true) && value == null -> {
                PlayerPolicyOverrides.set(id, null)
                ServerMessage.POLICY_PLAYER_RESET_ALL.sendTo(sender, playerName)
            }

            else -> {
                val path = PolicyPaths.find(key)?.takeIf(PlayerPolicies::isPlayerKey) ?: run {
                    ServerMessage.POLICY_PLAYER_KEY_ONLY_DISPLAY.sendTo(sender, key, playerKeySuggestions().dropLast(1).joinToString(", "))
                    return
                }
                if (value == null) {
                    ServerMessage.POLICY_LINE.sendTo(
                        sender, path.name,
                        PolicyPaths.format(path.get(PlayerPolicyOverrides.applyTo(DisplayPolicies.defaults().serverWide(), id))),
                        PolicyPaths.format(override?.let(path.get)),
                    )
                    return
                }
                val raw = if (value.equals(RESET, ignoreCase = true)) null else value
                val updated = PlayerPolicies.withKey(override, path, raw) ?: run {
                    ServerMessage.POLICY_BAD_VALUE.sendTo(sender, path.name, path.hint)
                    return
                }
                PlayerPolicyOverrides.set(id, updated)
                if (raw == null) ServerMessage.POLICY_PLAYER_RESET.sendTo(sender, path.name, playerName)
                else ServerMessage.POLICY_PLAYER_SET.sendTo(sender, path.name, PolicyPaths.format(path.get(updated)), playerName)
            }
        }
    }

    private const val REGION_FIELD = "visibility.region"

    /**
     * Whether [display] may be tied to `WorldGuard` region [regionId]; says why not when it may not. Refused: no
     * `WorldGuard`, a virtual display, a region that is neither a cuboid nor a polygon. A region that does not exist
     * (yet) is accepted: the display is then visible to nobody until it does, which the status line after it says.
     */
    private fun regionUsable(sender: CommandSender, display: DisplayData, regionId: String): Boolean {
        if (!WorldGuardRegions.isAvailable()) {
            ServerMessage.POLICY_REGION_NO_WORLDGUARD.sendTo(sender)
            return false
        }
        val paper = display as? PaperDisplayData ?: return false
        val preview = DisplayRegions.preview(paper, regionId)
        if (preview is RegionVisibility.State.Unavailable && preview.reason in REFUSED) {
            ServerMessage.POLICY_REGION_REFUSED.sendTo(
                sender, display.shortLabel, regionId, RegionStatus.reasonText(sender, preview.reason, paper),
            )
            return false
        }
        return true
    }

    private val REFUSED = setOf(
        RegionVisibility.Reason.NO_WORLDGUARD, RegionVisibility.Reason.UNSUPPORTED_SHAPE, RegionVisibility.Reason.VIRTUAL,
    )

    /** Suggestions for the key slot of `policy player`: the keys one player may have, plus `reset`. */
    fun playerKeySuggestions(): List<String> = PlayerPolicies.keys.map { it.name } + RESET

    private fun show(sender: CommandSender, display: DisplayData) {
        ServerMessage.POLICY_HEADER.sendTo(sender, display.shortLabel)
        perDisplay.forEach { showOne(sender, display, it, header = false) }
        (display as? PaperDisplayData)?.let { RegionStatus.send(sender, it) }
    }

    private fun showOne(sender: CommandSender, display: DisplayData, path: PolicyPaths.Path, header: Boolean = true) {
        if (header) ServerMessage.POLICY_HEADER.sendTo(sender, display.shortLabel)
        val override = DisplayPolicies.override(display)?.let(path.get)
        val effective = path.get(DisplayPolicies.effective(display))
        ServerMessage.POLICY_LINE.sendTo(
            sender, path.name, PolicyPaths.format(effective), PolicyPaths.format(override),
        )
        if (header && path.field.startsWith(REGION_FIELD)) (display as? PaperDisplayData)?.let { RegionStatus.send(sender, it) }
    }

    private fun resolve(sender: CommandSender, token: String): DisplayData? = resolvePaperDisplay(sender, token)

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

    /** Whether [sender] may use this command (op by default). */
    fun allowed(sender: CommandSender): Boolean = sender.hasPermission(Perm.ADMIN_POLICY)
}
