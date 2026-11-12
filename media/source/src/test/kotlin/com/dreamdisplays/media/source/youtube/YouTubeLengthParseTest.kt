package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.util.asJsonObjectOrNull
import com.dreamdisplays.util.json.DreamJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeLengthParseTest {

    private fun lengthOf(json: String) =
        YouTubeInnerTube.extractLengthSeconds(DreamJson.compact.parseToJsonElement(json).asJsonObjectOrNull()!!)

    @Test
    fun readsLengthEvenWhenThePlayerCallsTheVideoUnplayable() {
        // Trimmed from a real `player` reply for a normal video (WEB client, no PO token)
        assertEquals(
            213L,
            lengthOf(
                """
                {
                  "playabilityStatus": { "status": "UNPLAYABLE", "reason": "Video unavailable" },
                  "videoDetails": {
                    "videoId": "dQw4w9WgXcQ", "title": "Never Gonna Give You Up", "lengthSeconds": "213",
                    "isLiveContent": false, "viewCount": "1700000000"
                  },
                  "microformat": { "playerMicroformatRenderer": { "lengthSeconds": "214" } }
                }
                """,
            ),
        )
    }

    @Test
    fun readsLengthOfAnEndedLiveStream() {
        // Trimmed from a real `player` reply for a finished stream recording
        assertEquals(
            121_601_512L,
            lengthOf(
                """
                {
                  "videoDetails": { "lengthSeconds": "121601512", "isLiveContent": true },
                  "microformat": { "playerMicroformatRenderer": { "liveBroadcastDetails": {
                    "isLiveNow": false, "startTimestamp": "2022-07-12T15:59:30+00:00",
                    "endTimestamp": "2026-05-20T02:11:23+00:00"
                  } } }
                }
                """,
            ),
        )
    }

    @Test
    fun liveAndUpcomingHaveNoLength() {
        assertNull(lengthOf("""{ "videoDetails": { "lengthSeconds": "5400", "isLive": true, "isLiveContent": true } }"""))
        assertNull(lengthOf("""{ "videoDetails": { "lengthSeconds": "0", "isUpcoming": true } }"""))
        assertNull(
            lengthOf(
                """
                { "videoDetails": { "lengthSeconds": "5400", "isLiveContent": true },
                  "microformat": { "playerMicroformatRenderer": { "liveBroadcastDetails": { "isLiveNow": true } } } }
                """,
            ),
        )
        // Running stream without a live flag: started, never ended
        assertNull(
            lengthOf(
                """
                { "videoDetails": { "lengthSeconds": "5400", "isLiveContent": true },
                  "microformat": { "playerMicroformatRenderer": { "liveBroadcastDetails": {
                    "isLiveNow": false, "startTimestamp": "2026-11-11T20:00:00+00:00"
                  } } } }
                """,
            ),
        )
    }

    @Test
    fun missingOrBadLengthIsNull() {
        assertNull(lengthOf("""{ "playabilityStatus": { "status": "ERROR" } }"""))
        assertNull(lengthOf("""{ "videoDetails": { "videoId": "x" } }"""))
        assertNull(lengthOf("""{ "videoDetails": { "lengthSeconds": "0" } }"""))
        assertNull(lengthOf("""{ "videoDetails": { "lengthSeconds": "abc" } }"""))
        assertNull(lengthOf("""{ "videoDetails": "nope" }"""))
    }
}
