package com.dreamdisplays.platform.client.replay

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.render.DisplayUnlitRenderTypes
import com.dreamdisplays.platform.client.utils.identifier
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
//? if >=1.20.5 {
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//?} else
/*import com.dreamdisplays.platform.client.net.compat.CustomPacketPayload*/
import java.util.*

/**
 * The only way normal code reaches replay support (Flashback; see the Fabric `compat.flashback` package). Every hook
 * is a no-op while [bridge] is null, which it always is unless a replay mod's compat set it up.
 */
object ReplayHooks {
    /** Set once at startup by a replay mod's compat; never set when no replay mod is installed. */
    @Volatile
    @JvmField
    var bridge: ReplayBridge? = null

    /** True while the client is in a replay (watching or exporting). */
    fun inReplay(): Boolean = bridge?.inReplay() == true

    /** An incoming `dreamdisplays:v2` packet: true when it must not reach the client (it is part of a replay). */
    fun dropServerPacket(bytes: ByteArray): Boolean = bridge?.onServerPacket(bytes) == true

    /** An outgoing payload: true when the replay took it instead of the network. */
    fun interceptSend(payload: CustomPacketPayload): Boolean {
        val b = bridge ?: return false
        return b.inReplay() && b.onClientPayload(payload)
    }

    /** What a display shows instead of its media in a replay (a card saying it was not saved), or null. */
    fun card(displayId: UUID): RenderType? {
        val kind = bridge?.card(displayId) ?: return null
        return cards.getOrPut(kind) {
            val id = identifier(Initializer.MOD_ID, kind.texture)
            // Loaded now, before any draw: loading it on first use would upload it while the draw's render pass is open
            Minecraft.getInstance().textureManager.getTexture(id)
            DisplayUnlitRenderTypes.create("dream-displays-replay-card", id)
        }
    }

    private val cards = EnumMap<ReplayCard, RenderType>(ReplayCard::class.java)
}

/** Why a display shows a card in a replay instead of its media. */
enum class ReplayCard(val texture: String) {
    /** A screen share: never stored in replays. */
    SHARE("textures/replay/share_not_saved.png"),

    /** A display in local mode: each player's own playback, not recorded. */
    LOCAL("textures/replay/local_not_saved.png"),
}

/** Implemented by a replay mod's compat. */
interface ReplayBridge {
    fun inReplay(): Boolean

    fun onServerPacket(bytes: ByteArray): Boolean

    fun onClientPayload(payload: CustomPacketPayload): Boolean

    fun card(displayId: UUID): ReplayCard?
}
