package com.dreamdisplays.core.activity

import java.util.*

/**
 * Who may see a display menu panel (`[activity] visible_to` / `viewers_visible_to`): `everyone` (anyone who can open
 * the display's menu), `admins` (holders of the admin permission) or `nobody`. Anything else reads as the default,
 * `everyone`.
 */
enum class PanelVisibility(val token: String) {
    EVERYONE("everyone"),
    ADMINS("admins"),
    NOBODY("nobody");

    /** True when a player who [isAdmin] (or not) may see the panel at all. */
    fun allows(isAdmin: Boolean): Boolean = when (this) {
        EVERYONE -> true
        ADMINS -> isAdmin
        NOBODY -> false
    }

    companion object {
        fun parse(raw: String?): PanelVisibility =
            entries.firstOrNull { it.token == raw?.trim()?.lowercase(Locale.ROOT) } ?: EVERYONE

        /**
         * True when a player may get a display's panel right now: the panel is on and allowed to them ([visibility],
         * [isAdmin]) and they could open the display's menu ([present]: in its range, or following it from afar).
         */
        fun mayView(visibility: PanelVisibility, isAdmin: Boolean, present: Boolean): Boolean =
            present && visibility.allows(isAdmin)
    }
}
