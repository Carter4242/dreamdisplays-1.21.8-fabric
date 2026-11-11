package com.dreamdisplays.platform.client.mixins

import com.dreamdisplays.platform.client.input.PointerInput
import net.minecraft.client.MouseHandler
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonInfo
//?}
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Lets our overlays claim mouse buttons and scrolls before vanilla hands them to the open screen or the
 * game, so a click on a PiP never also lands on whatever is drawn beneath it.
 */
@Suppress("NonJavaMixin")
@Mixin(MouseHandler::class)
open class MouseInput {
    //? if >=1.21.11 {
    @Inject(method = ["onButton"], at = [At("HEAD")], cancellable = true)
    open fun onButtonHead(window: Long, buttonInfo: MouseButtonInfo, action: Int, ci: CallbackInfo) {
        if (PointerInput.onButton(window, buttonInfo.button(), action)) ci.cancel()
    }
    //?} else
    /*@Inject(method = ["onPress"], at = [At("HEAD")], cancellable = true)
    open fun onButtonHead(window: Long, button: Int, action: Int, modifiers: Int, ci: CallbackInfo) {
        if (PointerInput.onButton(window, button, action)) ci.cancel()
    }*/

    @Inject(method = ["onScroll"], at = [At("HEAD")], cancellable = true)
    open fun onScrollHead(window: Long, xOffset: Double, yOffset: Double, ci: CallbackInfo) {
        if (PointerInput.onScroll(window)) ci.cancel()
    }
}
