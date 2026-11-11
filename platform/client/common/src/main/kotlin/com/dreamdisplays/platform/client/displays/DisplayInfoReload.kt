package com.dreamdisplays.platform.client.displays

/** When an incoming display description makes a loaded display start its video over. */
internal object DisplayInfoReload {
    /**
     * True when the display must (re)load for a packet carrying [packetUrl] / [packetLang]. A display with no video
     * and a packet with none are the same. [lastPacketLang] is null until the screen has applied a packet (one restored
     * from the distance cache): it then adopts the packet's language instead of restarting a video that is already
     * the right one.
     */
    fun needsReload(currentUrl: String?, lastPacketLang: String?, packetUrl: String, packetLang: String): Boolean {
        if ((currentUrl ?: "") != packetUrl) return true
        return lastPacketLang != null && lastPacketLang != packetLang
    }
}
