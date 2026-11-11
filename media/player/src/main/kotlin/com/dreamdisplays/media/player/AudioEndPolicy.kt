package com.dreamdisplays.media.player

/**
 * What a player does when an audio half's PCM ends with a clean exit while the picture plays on. On a VOD that is
 * either a track shorter than the video or a cut connection; neither may restart the picture, which used to turn
 * every such end (and every natural end of a file whose length is unknown) into a stall / re-resolve loop.
 */
internal object AudioEndPolicy {
    enum class Action {
        /** Live media, or a source without sound: the ordinary audio-failure handling. */
        FAILURE_PATH,

        /** The track has no sound left at this position: let the picture play on silently. */
        FINISHED,

        /** The end is within the last seconds of the media: the video's own end decides what follows. */
        DEFER_TO_VIDEO,

        /** Reopen only the sound at the playhead: a cut connection resumes, a finished track ends again empty. */
        REOPEN_AUDIO,
    }

    /**
     * [hadPcm]: the half decoded anything at all; [stderrBlank]: FFmpeg logged no error; [nearEnd]: the playhead is in
     * the last seconds of a VOD of known length; [restartAllowed]: takes one in-place restart from the budget, true
     * while it is not used up (only asked when a reopen is otherwise due).
     */
    fun onEnd(
        live: Boolean, noAudioStream: Boolean, hadPcm: Boolean, stderrBlank: Boolean, nearEnd: Boolean,
        restartAllowed: () -> Boolean,
    ): Action = when {
        live || noAudioStream -> Action.FAILURE_PATH
        !hadPcm && stderrBlank -> Action.FINISHED
        nearEnd -> Action.DEFER_TO_VIDEO
        !restartAllowed() -> Action.FAILURE_PATH
        else -> Action.REOPEN_AUDIO
    }
}
