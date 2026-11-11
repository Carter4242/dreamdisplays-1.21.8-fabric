package com.dreamdisplays.media.source.youtube.process

import com.dreamdisplays.api.media.model.DreamMediaException

/**
 * Turns `yt-dlp`'s error output into a failure the player can show as-is. YouTube refuses some public videos to
 * anonymous clients (sign-in, age, members-only); those can never play on a display, so they are fatal and say why
 * instead of a generic "all clients failed".
 */
object YtDlpErrors {
    fun classify(output: String?): DreamMediaException? {
        val text = output?.takeIf { it.isNotBlank() } ?: return null
        val t = text.lowercase()
        return when {
            "not a bot" in t ->
                DreamMediaException.Network("YouTube is asking this connection to prove it isn't a bot. Try again later.")

            "confirm your age" in t || "age-restricted" in t || "age restricted" in t ->
                DreamMediaException.Unknown(
                    "YouTube only shows this video to signed-in adults, so it can't play on a display.", isFatal = true,
                )

            "members-only" in t || "join this channel" in t || "members only" in t ->
                DreamMediaException.Unknown("This video is for channel members only, so it can't play on a display.", isFatal = true)

            "private video" in t -> DreamMediaException.NotFound("This video is private.")

            "not available in your country" in t || "geo restrict" in t || "geo-restrict" in t ->
                DreamMediaException.GeoBlocked("This video isn't available in your country.")

            "please sign in" in t || "sign in to" in t || "requires authentication" in t ->
                DreamMediaException.Unknown(
                    "YouTube requires a signed-in account for this video, so it can't play on a display.", isFatal = true,
                )

            "video unavailable" in t || "has been removed" in t || "no longer available" in t ->
                DreamMediaException.NotFound("This video is unavailable on YouTube.")

            else -> null
        }
    }
}
