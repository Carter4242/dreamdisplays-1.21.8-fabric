package com.dreamdisplays.platform.client.utils

import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen

/** Opens links the way vanilla chat does: honours the "Chat links" / "Prompt on links" options. */
object LinkOpener {
    /** False when the player turned chat links off; links then stay inert. */
    fun enabled(mc: Minecraft = Minecraft.getInstance()): Boolean {
        //? if >=1.19 {
        return mc.options.chatLinks().get()
        //?} else
        /*return mc.options.chatLinks*/
    }

    private fun prompt(mc: Minecraft): Boolean {
        //? if >=1.19 {
        return mc.options.chatLinksPrompt().get()
        //?} else
        /*return mc.options.chatLinksPrompt*/
    }

    /**
     * Opens [url] (already checked as http / https) behind the vanilla link confirmation, returning to [back]
     * afterwards; opens it straight away when the player turned the prompt off. No-op when chat links are off.
     */
    fun open(url: String, back: Screen) {
        val mc = Minecraft.getInstance()
        if (!enabled(mc)) return
        if (prompt(mc)) {
            MinecraftScreenUtil.setScreen(mc, ConfirmLinkScreen({ confirmed ->
                if (confirmed) Util.getPlatform().openUri(url)
                MinecraftScreenUtil.setScreen(mc, back)
            }, url, false))
        } else {
            Util.getPlatform().openUri(url)
        }
    }
}
