package com.dreamdisplays.platform.client.net

/**
 * Runs [handle] for one server packet; an exception goes to [onError] instead of propagating. One escaping a payload
 * handler reaches the vanilla packet listener, which disconnects the player ("Network Protocol Error"), so a single
 * malformed packet (a bad facing byte, an unexpected value) would kick them off the server.
 *
 * @return true when the packet was handled without an exception.
 */
internal inline fun dispatchGuarded(handle: () -> Unit, onError: (Exception) -> Unit): Boolean =
    try {
        handle()
        true
    } catch (e: Exception) {
        onError(e)
        false
    }
