package com.dreamdisplays.platform.client.voice

import com.dreamdisplays.platform.client.karaoke.KaraokeClient
import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.events.ClientSoundEvent
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.MergeClientSoundEvent

/**
 * Simple Voice Chat client add-on for karaoke timing (the `voicechat` entrypoint in `fabric.mod.json`). Voice chat
 * loads it only when it is installed; without voice chat this class is never touched, so the mod does not need it.
 *
 * It only forwards voice chat's mic events to [KaraokeClient]: every mic poll (to number the frames the way voice chat
 * does) and every mic frame about to be sent, which [KaraokeClient] analyses only while its player sings (on this
 * client; the audio is never sent anywhere by this add-on). It never changes or cancels the audio.
 */
class KaraokeVoicechatPlugin : VoicechatPlugin {
    override fun getPluginId(): String = "dreamdisplays_karaoke"

    override fun initialize(api: VoicechatApi) {
        KaraokeClient.voiceChatInstalled = true
    }

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(MergeClientSoundEvent::class.java) { KaraokeClient.onMicPoll() }
        registration.registerEvent(ClientSoundEvent::class.java) { event ->
            event.rawAudio?.takeIf { it.isNotEmpty() }?.let { KaraokeClient.onMicFrame(it) }
        }
        registration.registerEvent(ClientVoicechatConnectionEvent::class.java) { KaraokeClient.onVoiceConnection() }
    }
}
