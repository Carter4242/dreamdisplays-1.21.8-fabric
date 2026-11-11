package com.dreamdisplays.media.source.youtube.binary

import kotlin.test.Test
import kotlin.test.assertEquals

class YtDlpOutputParserSchemeTest {
    private fun format(url: String, protocol: String?) =
        """{"url":"$url",${protocol?.let { "\"protocol\":\"$it\"," } ?: ""}"vcodec":"h264","acodec":"aac","ext":"mp4"}"""

    @Test
    fun `only http(s) stream urls survive, whatever protocol yt-dlp labels them with`() {
        val json = """{"title":"t","duration":10,"formats":[
            ${format("file://evil.example/share/googlevideo.com/x?gir=yes&clen=1&a=.mpd", "file")},
            ${format("file://evil.example/share/x.m3u8", "m3u8_native")},
            ${format("smb://evil.example/share/x.mpd", null)},
            ${format("https://cdn.example/v.mp4", "https")},
            ${format("http://cdn.example/live.m3u8", "m3u8_native")}
        ]}"""
        assertEquals(
            ["https://cdn.example/v.mp4", "http://cdn.example/live.m3u8"],
            YtDlpOutputParser.parseFormats(json).map { it.url },
        )
    }
}
