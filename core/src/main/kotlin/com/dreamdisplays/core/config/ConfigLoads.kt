package com.dreamdisplays.core.config

/**
 * What to do with a `config.toml` that was read with syntax errors. Keys on a broken line read as missing, so their
 * defaults would apply silently (a typo in `base_material` makes every display look invalid to the startup check).
 */
object ConfigLoads {
    enum class Action {
        /** Clean file: apply it. */
        APPLY,

        /** Errors at startup: nothing older to keep, so apply what could be read, but run nothing destructive on it. */
        APPLY_DEGRADED,

        /** Errors on a reload: keep the settings in effect and report the reload as failed. */
        KEEP_PREVIOUS,
    }

    /** The action for a read with [hasErrors] (unreadable counts), [firstLoad] at startup. */
    fun decide(hasErrors: Boolean, firstLoad: Boolean): Action = when {
        !hasErrors -> Action.APPLY
        firstLoad -> Action.APPLY_DEGRADED
        else -> Action.KEEP_PREVIOUS
    }
}
