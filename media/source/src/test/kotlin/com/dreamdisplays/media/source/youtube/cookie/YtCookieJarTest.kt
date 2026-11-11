package com.dreamdisplays.media.source.youtube.cookie

import kotlin.test.Test
import kotlin.test.assertEquals

class YtCookieJarTest {
    @Test
    fun `only youtube and google cookies survive, http-only ones included`() {
        val jar = [
            "# Netscape HTTP Cookie File",
            "",
            ".youtube.com\tTRUE\t/\tTRUE\t0\tSAPISID\ta",
            "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tb",
            "accounts.google.com\tFALSE\t/\tTRUE\t0\tLSID\tc",
            ".bank.example\tTRUE\t/\tTRUE\t0\tsession\tsecret",
            "#HttpOnly_mail.example.com\tFALSE\t/\tTRUE\t0\tauth\tsecret",
            ".notyoutube.com\tTRUE\t/\tTRUE\t0\tx\tsecret",
            ".google.com.evil.example\tTRUE\t/\tTRUE\t0\tx\tsecret",
        ]
        assertEquals(
            [
                "# Netscape HTTP Cookie File",
                "",
                ".youtube.com\tTRUE\t/\tTRUE\t0\tSAPISID\ta",
                "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tb",
                "accounts.google.com\tFALSE\t/\tTRUE\t0\tLSID\tc",
            ],
            YtCookieManager.youTubeCookiesOnly(jar),
        )
    }
}
