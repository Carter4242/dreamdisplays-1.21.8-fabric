package com.dreamdisplays.media.source.twitch

import com.dreamdisplays.media.player.adskip.BackupPlaylistSource
import com.dreamdisplays.media.player.adskip.BackupVariant
import com.dreamdisplays.media.player.adskip.LiveRendition
import com.dreamdisplays.util.net.DreamHttpClient

/**
 * Signs a live channel for another player variant (anonymously, with the public web client id, like every other
 * Twitch call here) and picks that variant's rendition matching the one playing: the same `VIDEO` group, else the same
 * height at the nearest frame rate. Never a different picture size: a backup only stands in while an ad runs.
 */
internal object TwitchBackupPlaylists : BackupPlaylistSource {
    private val OPTIONS = DreamHttpClient.RequestOptions(readTimeoutMs = 5_000L, callTimeoutMs = 6_000L)

    override fun mediaPlaylistUrl(variant: BackupVariant, rendition: LiveRendition): String? {
        val token = TwitchApi.liveToken(rendition.login, variant.playerType, variant.platform) ?: return null
        val master = DreamHttpClient.readText(TwitchHls.liveUrl(rendition.login, token), OPTIONS)
        return match(TwitchHls.parseMaster(master), rendition)?.url
    }

    /** The rendition of [ladder] that shows the same picture as [rendition], or null. */
    internal fun match(ladder: List<TwitchHls.Rendition>, rendition: LiveRendition): TwitchHls.Rendition? {
        val video = ladder.filter { !it.isAudioOnly }
        rendition.group?.let { group -> video.firstOrNull { it.videoGroup == group }?.let { return it } }
        val height = rendition.height ?: return null
        return video.filter { it.height == height }.minByOrNull { kotlin.math.abs((it.fps ?: 30.0) - (rendition.fps ?: 30.0)) }
    }
}
