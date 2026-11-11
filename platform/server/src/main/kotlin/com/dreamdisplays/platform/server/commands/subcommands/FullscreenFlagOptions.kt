package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.core.command.Flags

/** `/display fullscreen start` flags as the fullscreen code takes them. */
fun Flags.Parsed.startOptions(): FullscreenStartOptions {
    val radius = numbers("radius")
    return FullscreenStartOptions(
        serverScope = word("server"),
        players = word("target"),
        radiusBlocks = radius.getOrNull(0),
        radiusX = radius.getOrNull(1),
        radiusY = radius.getOrNull(2),
        radiusZ = radius.getOrNull(3),
        mode = word("view"),
        forced = has("forced"),
        transientSession = has("transient"),
        volume = number("volume")?.let { it.toFloat() / 200f },
        loop = has("loop"),
        quality = word("quality"),
    )
}

/** `/display prepare start` flags as the fullscreen code takes them. */
fun Flags.Parsed.prepareOptions(): FullscreenPrepareOptions {
    val radius = numbers("radius")
    return FullscreenPrepareOptions(
        players = word("target"),
        radiusBlocks = radius.getOrNull(0),
        radiusX = radius.getOrNull(1),
        radiusY = radius.getOrNull(2),
        radiusZ = radius.getOrNull(3),
        quality = word("quality"),
        rewind = has("rewind"),
    )
}
