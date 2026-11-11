package com.dreamdisplays.media.player.process

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FFmpegCapabilitiesTest {
    @Test
    fun `a help listing with stats_mux_pre enables live stamps, one without does not`() {
        // `ffmpeg -h long` of FFmpeg 8.0 (6.1 and later list the option the same way)
        val modern = """
            -stats_enc_post_fmt[:<stream_spec>]  format of the stats written with -stats_enc_post
            -stats_mux_pre[:<stream_spec>]  write packets stats before muxing
            -stats_mux_pre_fmt[:<stream_spec>]  format of the stats written with -stats_mux_pre
        """.trimIndent()
        // FFmpeg 6.0 / 5.1: no stats options; the format option mentioning it must not count either
        val older = """
            -fps_mode[:<stream_spec>]  set framerate mode for matching video streams; overrides vsync
            -stats_mux_pre_fmt  something
            -enc_time_base[:<stream_spec>] <ratio>  set the desired time base for the encoder
        """.trimIndent()
        assertTrue(FFmpegCapabilities.listsOption(modern, "stats_mux_pre"))
        assertFalse(FFmpegCapabilities.listsOption(older, "stats_mux_pre"))
        assertTrue(FFmpegCapabilities.listsOption("-stats_mux_pre <file>  write stats", "stats_mux_pre"))
    }
}
