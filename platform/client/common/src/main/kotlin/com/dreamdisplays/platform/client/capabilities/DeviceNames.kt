package com.dreamdisplays.platform.client.capabilities

/**
 * Device names as reported to a server. Bluetooth devices carry their owner's name ("Alex's AirPods Pro"), and the
 * operating system passes it on in the audio device's name ("Headphones (Alex's AirPods Pro)"), so the possessive is
 * replaced; what kind of device it is stays.
 */
internal object DeviceNames {
    private val POSSESSIVE = Regex("""[\p{L}\p{N}_.\-]+['’‘`]s(?=[\s)\]"]|$)""")

    fun anonymize(text: String): String = POSSESSIVE.replace(text, "<name>'s")
}
