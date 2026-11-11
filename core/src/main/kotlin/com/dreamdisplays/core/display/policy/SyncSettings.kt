package com.dreamdisplays.core.display.policy

/**
 * [DisplayPolicy.Sync] resolved against the client defaults and clamped to sane ranges, so a server can
 * tune sync but never push a client into something harmful (an audible rate change, a seek storm).
 */
data class SyncSettings(
    val enabled: Boolean = true,
    val deadBandMs: Int = 40,
    val maxRatePct: Double = 1.0,
    val seekThresholdMs: Int = 1_500,
    val seekCooldownMs: Int = 8_000,
    /** See [DisplayPolicy.Sync.correctiveSeeks]. */
    val correctiveSeeks: Boolean = true,
    /** See [DisplayPolicy.Sync.cheapSeeks]. */
    val cheapSeeks: Boolean = true,
) {
    /** Largest rate offset as a fraction (0.01 for 1 %). */
    val maxRateFraction: Double get() = maxRatePct / 100.0

    /** These settings as a policy section. */
    fun toSection(): DisplayPolicy.Sync =
        DisplayPolicy.Sync(enabled, deadBandMs, maxRatePct, seekThresholdMs, seekCooldownMs, correctiveSeeks, cheapSeeks)

    companion object {
        val DEFAULT = SyncSettings()

        /** The policy section as settings: unset fields keep the defaults, set ones are clamped. */
        fun from(sync: DisplayPolicy.Sync?): SyncSettings {
            if (sync == null) return DEFAULT
            val deadBand = (sync.deadBandMs ?: DEFAULT.deadBandMs).coerceIn(0, 1_000)
            val maxRate = (sync.maxRatePct ?: DEFAULT.maxRatePct).takeIf { it.isFinite() }
                ?.coerceIn(0.0, 5.0) ?: DEFAULT.maxRatePct
            val seekThreshold = (sync.seekThresholdMs ?: DEFAULT.seekThresholdMs).coerceIn(200, 60_000)
            return SyncSettings(
                enabled = sync.enabled ?: DEFAULT.enabled,
                deadBandMs = deadBand.coerceAtMost(seekThreshold / 2),
                maxRatePct = maxRate,
                seekThresholdMs = seekThreshold,
                seekCooldownMs = (sync.seekCooldownMs ?: DEFAULT.seekCooldownMs).coerceIn(1_000, 600_000),
                correctiveSeeks = sync.correctiveSeeks ?: DEFAULT.correctiveSeeks,
                cheapSeeks = sync.cheapSeeks ?: DEFAULT.cheapSeeks,
            )
        }
    }
}
