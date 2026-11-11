package com.dreamdisplays.platform.client.mixins

import com.dreamdisplays.platform.client.input.DisplayMenuInputHandler
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Runs the sneak + use display-menu shortcut at the head of vanilla's use-item action and cancels the
 * use when it opens the menu, so the same click never also uses the held item or hits a block or entity.
 */
@Suppress("NonJavaMixin")
@Mixin(Minecraft::class)
open class UseItemMenu {
    @Inject(method = ["startUseItem"], at = [At("HEAD")], cancellable = true)
    open fun onStartUseItem(ci: CallbackInfo) {
        if (DisplayMenuInputHandler.onUseItem()) ci.cancel()
    }
}
