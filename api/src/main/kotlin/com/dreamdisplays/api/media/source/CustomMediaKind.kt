package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.Experimental

/**
 * Syntactic classification of a custom URL (by extension/host); probe-validated by resolver.
 *
 * @since 1.9.x
 */
@Experimental
enum class CustomMediaKind {
    /** A plain media file (`.mp4`, `.webm`, `.mkv`, ...) the player can open and byte-range seek. */
    PROGRESSIVE,

    /** An HLS playlist (`.m3u8`) — either a master with several renditions or a single media playlist. */
    HLS,

    /** An MPEG-DASH manifest (`.mpd`). */
    DASH,

    /** An audio container (`.mp3`, `.flac`, ...): recognizable media, but a display needs a picture. */
    AUDIO_ONLY,

    /**
     * A real-time relay stream (`srt://`, `rtmp://`, `rtmps://`, `rtsp://`, see [LiveStreamUrls]): always live,
     * played straight by `FFmpeg` at the live edge, never probed over HTTP or sent to an extractor.
     */
    LIVE,

    /** Not recognizably direct media; the extractor chain (`NewPipe` / `yt-dlp`) decides. */
    UNKNOWN;

    /** True when the URL can be handed straight to the player without an extractor. */
    val isDirect: Boolean get() = this == PROGRESSIVE || this == HLS || this == DASH || this == LIVE

    /** True when the URL is a manifest whose variants are fetched rather than the media itself. */
    val isManifest: Boolean get() = this == HLS || this == DASH
}
