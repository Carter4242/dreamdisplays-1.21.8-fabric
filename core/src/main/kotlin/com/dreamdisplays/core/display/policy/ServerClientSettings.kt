package com.dreamdisplays.core.display.policy

/**
 * The player's own client settings with the server's overrides on top. [serverWide] is the last server-wide
 * policy the server sent (null = none yet, e.g. a server without this feature: everything is the player's own).
 * A per-display policy can only add [DisplayPolicy.Client.enabled] and [DisplayPolicy.Client.displayDistanceChunks].
 *
 * Nothing here changes the player's saved config; values are clamped so a server cannot push anything harmful.
 */
data class ServerClientSettings(val serverWide: DisplayPolicy? = null) {
    private val client: DisplayPolicy.Client? get() = serverWide?.client
    private val debug: DisplayPolicy.Debug? get() = serverWide?.debug

    /** The server sent a server-wide policy, so it also takes viewer reports. */
    val received: Boolean get() = serverWide != null

    /** False while the server has every display switched off (the kill switch). */
    val displaysEnabled: Boolean get() = client?.enabled != false

    /** Whether a display with [policy] may be loaded and played; the server-wide switch wins over the display's. */
    fun displayEnabled(policy: DisplayPolicy?): Boolean = displaysEnabled && policy?.client?.enabled != false

    fun maxActiveDisplays(own: Int): Int = client?.maxActiveDisplays?.coerceIn(0, MAX_ACTIVE) ?: own

    fun hardwareDecoding(own: Boolean): Boolean = client?.hardwareDecoding ?: own

    /** The acoustics tier name (`OFF`, `BASIC`, ...) to use, or [own] when the server leaves it. */
    fun audioAcoustics(own: String): String = client?.audioAcoustics?.name ?: own

    val acousticsForced: Boolean get() = client?.audioAcoustics != null

    fun unshadedDisplays(own: Boolean): Boolean = client?.unshadedDisplays ?: own

    /** Display distance in chunks (0 = follow render distance): the display's, else the server's, else [own]. */
    fun displayDistanceChunks(own: Int, policy: DisplayPolicy? = null): Int =
        (policy?.client?.displayDistanceChunks ?: client?.displayDistanceChunks)?.coerceIn(0, MAX_DISTANCE_CHUNKS) ?: own

    /** Interval of the `[dd-sync]` drift log lines in ms; 0 = off. */
    val syncLogIntervalMs: Long
        get() = debug?.syncLogIntervalSeconds?.coerceIn(0, 3_600)?.let { it * 1_000L } ?: DEFAULT_SYNC_LOG_MS

    /** Media / sync debug logging requested by the server. */
    val verbose: Boolean get() = debug?.verbose == true

    /** Interval of viewer reports in ms; 0 = do not send (also when the server never sent a policy). */
    val reportIntervalMs: Long
        get() {
            if (!received) return 0L
            val seconds = debug?.reportIntervalSeconds ?: return DEFAULT_REPORT_MS
            return if (seconds <= 0) 0L else seconds.coerceIn(MIN_REPORT_SECONDS, 600) * 1_000L
        }

    companion object {
        const val MAX_ACTIVE = 16
        const val MAX_DISTANCE_CHUNKS = 32
        const val DEFAULT_SYNC_LOG_MS = 10_000L
        const val DEFAULT_REPORT_MS = 5_000L
        private const val MIN_REPORT_SECONDS = 2

        val NONE = ServerClientSettings()

        /** Settings from the bytes of a [com.dreamdisplays.core.protocol.common.packets.ServerPolicy] packet. */
        fun fromWire(bytes: ByteArray): ServerClientSettings =
            ServerClientSettings((DisplayPolicy.decode(bytes) ?: DisplayPolicy.EMPTY).serverWide())
    }
}
