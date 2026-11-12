package com.dreamdisplays.core.display.region

import com.dreamdisplays.core.display.policy.DisplayPolicy

/**
 * A display tied to a `WorldGuard` region (`visibility.region`): who gets it and how its sound fades at the edge.
 *
 * Clients with `ClientFeature.REGION_FADE` load it within [loadBlocks] of the region and hear it with [fade]: full
 * inside, fading to silence over [fadeBlocks] outside. Released clients (no such feature) only get it while they stand
 * inside the region: it appears when they walk in and goes when they walk out.
 */
object RegionVisibility {
    const val DEFAULT_FADE_BLOCKS = 3.0
    const val MAX_FADE_BLOCKS = 16.0
    const val DEFAULT_LOAD_BLOCKS = 8.0
    const val MAX_LOAD_BLOCKS = 256.0

    /**
     * How much further than [loadBlocks] a client keeps a loaded display (and the server keeps sending it), so one
     * standing at the load edge does not load and drop it over and over.
     */
    const val UNLOAD_SLACK_BLOCKS = 2.0

    /** How far a released client may step out of the region before the display is taken from it (no flapping). */
    const val RELEASED_EXIT_SLACK_BLOCKS = 1.0

    /** The region id [policy] ties its display to, or null (distance rules as usual). */
    fun regionId(policy: DisplayPolicy?): String? = policy?.visibility?.region?.trim()?.takeIf { it.isNotEmpty() }

    /** `visibility.regionFadeBlocks`, clamped to 0..[MAX_FADE_BLOCKS]; [DEFAULT_FADE_BLOCKS] when unset. */
    fun fadeBlocks(policy: DisplayPolicy?): Double =
        policy?.visibility?.regionFadeBlocks?.takeIf { it.isFinite() }?.coerceIn(0.0, MAX_FADE_BLOCKS) ?: DEFAULT_FADE_BLOCKS

    /** `visibility.regionLoadBlocks`, at least [fadeBlocks] (a display is loaded wherever it can be heard). */
    fun loadBlocks(policy: DisplayPolicy?): Double {
        val load = policy?.visibility?.regionLoadBlocks?.takeIf { it.isFinite() }?.coerceIn(0.0, MAX_LOAD_BLOCKS)
            ?: DEFAULT_LOAD_BLOCKS
        return maxOf(load, fadeBlocks(policy))
    }

    /**
     * Sound multiplier at [distance] blocks outside the region: 1 inside, 0 from [fadeBlocks] on, a smoothstep in
     * between (no kink where the fade begins or ends). With [fadeBlocks] 0 the sound stops at the edge.
     */
    fun fade(distance: Double, fadeBlocks: Double): Double {
        if (distance <= 0.0) return 1.0
        if (fadeBlocks <= 0.0 || distance >= fadeBlocks) return 0.0
        val s = 1.0 - distance / fadeBlocks
        return s * s * (3.0 - 2.0 * s)
    }

    /** Where a display stands with its region, as the server resolved it. */
    sealed interface State {
        /** No region set: the usual distance rules. */
        data object None : State

        /** A region is set but cannot be used ([reason]): nobody gets the display (far followers aside). */
        data class Unavailable(val regionId: String, val reason: Reason) : State

        /** The region's [shape], with the fade and load distances in effect. */
        data class Active(val regionId: String, val shape: RegionShape, val fadeBlocks: Double, val loadBlocks: Double) : State
    }

    /** Why a set region cannot be used. */
    enum class Reason {
        /** `WorldGuard` is not installed (or not enabled). */
        NO_WORLDGUARD,

        /** The display's world is not loaded, or `WorldGuard` keeps no regions for it. */
        NO_WORLD,

        /** No region with that id in the display's world. */
        NOT_FOUND,

        /** The region is neither a cuboid nor a polygon (the global region). */
        UNSUPPORTED_SHAPE,

        /** The display is virtual (a URL-only broadcast): it stands nowhere. */
        VIRTUAL,
    }

    /**
     * Whether a player at [x] [y] [z] gets a display in [state] (beyond the usual range check, which still applies).
     * [capable]: their client has `ClientFeature.REGION_FADE`. [wasAdmitted]: they got it at the last check.
     */
    fun admits(state: State, capable: Boolean, x: Double, y: Double, z: Double, wasAdmitted: Boolean): Boolean =
        when (state) {
            State.None -> true
            is State.Unavailable -> false
            is State.Active -> if (capable) {
                state.shape.distance(x, y, z) <= state.loadBlocks + UNLOAD_SLACK_BLOCKS
            } else {
                state.shape.containsPosition(x, y, z) ||
                        (wasAdmitted && state.shape.distance(x, y, z) <= RELEASED_EXIT_SLACK_BLOCKS)
            }
        }

    /** Whether a player at [x] [y] [z] hears a display in [state] at all (a karaoke singer's voice follows this). */
    fun hears(state: State, capable: Boolean, x: Double, y: Double, z: Double): Boolean =
        when (state) {
            State.None -> true
            is State.Unavailable -> false
            is State.Active -> if (capable) {
                fade(state.shape.distance(x, y, z), state.fadeBlocks) > 0.0
            } else {
                state.shape.containsPosition(x, y, z)
            }
        }

    /**
     * Client side: whether a display whose policy is [policy] and whose region the server described as [shape] (null:
     * none sent) may be loaded at [x] [y] [z], allowing [slackBlocks] beyond the load distance. True when [policy] sets
     * no region; false when it does but no shape came (the server could not resolve the region: nobody gets it).
     */
    fun clientMayLoad(policy: DisplayPolicy?, shape: RegionShape?, x: Double, y: Double, z: Double, slackBlocks: Double = 0.0): Boolean {
        if (regionId(policy) == null) return true
        val s = shape ?: return false
        return s.distance(x, y, z) <= loadBlocks(policy) + slackBlocks
    }

    /** Client side: the region fade for a display with [policy] and [shape] heard from [x] [y] [z] (1 with no region). */
    fun clientFade(policy: DisplayPolicy?, shape: RegionShape?, x: Double, y: Double, z: Double): Double {
        if (regionId(policy) == null) return 1.0
        val s = shape ?: return 0.0
        return fade(s.distance(x, y, z), fadeBlocks(policy))
    }
}
