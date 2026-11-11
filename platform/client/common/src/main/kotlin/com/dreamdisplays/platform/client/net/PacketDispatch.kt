package com.dreamdisplays.platform.client.net

import org.slf4j.Logger

/**
 * Runs [handle] for one server packet named [name]. An exception is logged and swallowed: one escaping a payload
 * handler reaches the vanilla packet listener, which disconnects the player ("Network Protocol Error"), so a single
 * malformed packet (a bad facing byte, an unexpected value) would kick them off the server.
 *
 * @return true when the packet was handled without an exception.
 */
internal inline fun dispatchGuarded(name: String?, logger: Logger, handle: () -> Unit): Boolean =
    try {
        handle()
        true
    } catch (e: Exception) {
        logger.error("Failed to handle server packet {}; ignoring it.", name, e)
        false
    }
