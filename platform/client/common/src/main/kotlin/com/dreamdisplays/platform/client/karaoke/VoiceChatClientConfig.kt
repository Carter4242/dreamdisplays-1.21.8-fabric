package com.dreamdisplays.platform.client.karaoke

import org.lwjgl.openal.ALC10
import org.lwjgl.openal.SOFTDeviceClock
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * The Simple Voice Chat client settings that decide voice latency, read from its own config file
 * (`config/voicechat/voicechat-client.properties`; its API does not expose them).
 *
 * @property outputBufferFrames `output_buffer_size`: 20 ms frames voice chat queues before playing a voice (5 by
 *   default): a listener's jitter buffer.
 * @property denoiser `denoiser`: the mic goes through RNNoise (adds a little capture delay).
 */
internal data class VoiceChatClientConfig(val outputBufferFrames: Int, val denoiser: Boolean) {
    companion object {
        val DEFAULTS = VoiceChatClientConfig(5, false)

        fun read(): VoiceChatClientConfig = runCatching {
            // Relative to the game directory, like the mod's own config
            val file = Path.of("config", "voicechat", "voicechat-client.properties")
            if (!Files.isRegularFile(file)) return DEFAULTS
            val props = Properties()
            Files.newBufferedReader(file).use { props.load(it) }
            VoiceChatClientConfig(
                outputBufferFrames = props.getProperty("output_buffer_size")?.trim()?.toIntOrNull()?.coerceIn(1, 16)
                    ?: DEFAULTS.outputBufferFrames,
                denoiser = props.getProperty("denoiser")?.trim()?.equals("true", ignoreCase = true) ?: DEFAULTS.denoiser,
            )
        }.getOrDefault(DEFAULTS)
    }
}

/**
 * The game's OpenAL output device latency (`ALC_SOFT_device_clock`), used for voice chat's output: it plays through
 * its own OpenAL Soft device, normally on the same output, which its API does not expose. Client thread.
 */
internal object GameAudioLatency {
    /** The latency in ms, or -1 when OpenAL can't tell. */
    fun deviceLatencyMs(): Int = runCatching {
        val context = ALC10.alcGetCurrentContext()
        if (context == 0L) return -1
        val device = ALC10.alcGetContextsDevice(context)
        if (device == 0L || !ALC10.alcIsExtensionPresent(device, "ALC_SOFT_device_clock")) return -1
        val nanos = SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_LATENCY_SOFT)
        if (nanos in 0..MAX_NANOS) (nanos / 1_000_000L).toInt() else -1
    }.getOrDefault(-1)

    private const val MAX_NANOS = 1_000_000_000L
}
