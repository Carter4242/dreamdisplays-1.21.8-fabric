package com.dreamdisplays.platform.client.displays

/**
 * A virtual display (a URL-only fullscreen broadcast) exists only to be shown in its popouts: it has no place in any
 * world, and the distance unload never touches it. Once shown and closed it would otherwise keep decoding forever.
 */
internal object VirtualDisplayLifetime {
    /**
     * True when the virtual display is finished: it was shown in a popout, none is open now, and it is not being
     * held ready for a broadcast that is about to start.
     */
    fun isDone(virtual: Boolean, wasShown: Boolean, popoutOpen: Boolean, prepared: Boolean): Boolean =
        virtual && wasShown && !popoutOpen && !prepared
}
