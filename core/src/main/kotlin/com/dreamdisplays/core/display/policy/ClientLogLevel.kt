package com.dreamdisplays.core.display.policy

/**
 * How much a client writes to its own `latest.log`, set by the server (server-wide `[policy.debug] log_level`, or per
 * player with `/display diag loglevel`). Error and hitch reports to the server carry the same detail at every level.
 */
enum class ClientLogLevel(val wire: String) {
    /** Default: real failures (one line each) and startup information only. */
    QUIET("quiet"),

    /** Also playback warnings such as A / V lateness and sync landings. */
    NORMAL("normal"),

    /** Also the mod's media / sync debug output and `[dd-sync]` drift lines every 10 s. */
    VERBOSE("verbose"),

    /** Everything: every hitch with its attribution (`[dd-hitch]`), `[dd-sync]` every second, timings. */
    ULTRA("ultra");

    companion object {
        fun fromWire(wire: String?): ClientLogLevel? = entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) }
    }
}
