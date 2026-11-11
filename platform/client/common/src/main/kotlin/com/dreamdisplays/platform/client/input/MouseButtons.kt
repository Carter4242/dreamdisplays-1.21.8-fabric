package com.dreamdisplays.platform.client.input

import net.minecraft.client.Minecraft
//? if >=26.3 {
import com.mojang.blaze3d.platform.InputConstants
import org.lwjgl.sdl.SDLMouse
import org.lwjgl.system.MemoryStack
//?} else
/*import org.lwjgl.glfw.GLFW*/

/** Mouse-button indices: GLFW used 0 / 1 for left / right; 26.3 SDL uses 1 / 3. */
object MouseButtons {
    fun isLeft(button: Int): Boolean =
        //? if >=26.3 {
        button == InputConstants.MOUSE_BUTTON_LEFT
        //?} else
        /*button == 0*/

    fun isRight(button: Int): Boolean =
        //? if >=26.3 {
        button == InputConstants.MOUSE_BUTTON_RIGHT
        //?} else
        /*button == 1*/

    /** Hardware state of [button] (an index as delivered by the game's mouse callback). */
    fun isDown(button: Int): Boolean =
        //? if >=26.3 {
        button in 1..32 && (sdlButtonMask() and (1 shl (button - 1))) != 0
        //?} else
        /*button >= 0 && glfwDown(button)*/

    //? if >=26.3 {
    private fun sdlButtonMask(): Int = MemoryStack.stackPush().use { stack ->
        SDLMouse.SDL_GetMouseState(stack.mallocFloat(1), stack.mallocFloat(1))
    }
    //?} else
    /*private fun glfwDown(button: Int): Boolean {
        val mc = Minecraft.getInstance()
        val window =
            //? if >=1.21.11 {
            mc.window.handle()
            //?}
            //? if <1.21.11 {
            mc.window.window
            //?}
        return GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS
    }*/
}
