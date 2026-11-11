package com.dreamdisplays.platform.client.managers

/** When a display's player must be rebuilt for the render path, kept free of game classes so it can be tested. */
object RenderPathRules {
    /**
     * The playback path changed: a shader pack was switched on ([shadersTurnedOn]) or off, or the server switched a
     * decode / draw / upload / sound path ([serverPathChanged]). A display that stays up through it ([keptUp]: a PiP,
     * window or fullscreen of it is open, or it only exists for a broadcast) is not torn down and rebuilt like the
     * others, so it needs a new player in place: always for a server path change (its player keeps the path it was
     * created with), and for shaders when its player decodes for a path they can't draw, GPU YUV planes ([yuvTexture])
     * or an alpha video ([alpha]). Switching shaders off needs nothing: the plain path still draws.
     */
    fun newPlayerInPlace(
        shadersTurnedOn: Boolean, serverPathChanged: Boolean, keptUp: Boolean, yuvTexture: Boolean, alpha: Boolean,
    ): Boolean = keptUp && (serverPathChanged || (shadersTurnedOn && (yuvTexture || alpha)))
}
