package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.core.protocol.common.packets.FullscreenState

/** What a viewer's client does with a fullscreen broadcast's state, kept free of game classes so it can be tested. */
object FullscreenViewerRules {
    /**
     * True when [incoming] needs nothing more than an ack: it is the state last applied ([last]) and that state is
     * still on screen ([fullscreenUp] for a shown one, [pipUp] for one minimized to PiP). An overlay closed on this
     * client since (an ended share, the menu, another display's fullscreen) is shown again when the server re-sends it.
     */
    fun alreadyShown(last: FullscreenState?, incoming: FullscreenState, fullscreenUp: Boolean, pipUp: Boolean): Boolean =
        last == incoming && (if (incoming.minimized) pipUp || fullscreenUp else fullscreenUp)
}
