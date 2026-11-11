package com.dreamdisplays.core.input

/** Something drawn above the game's own UI that can take mouse input, such as a picture-in-picture overlay. */
interface PointerTarget {
    /** True while ([x], [y]) lies on this target and it currently accepts input. */
    fun hitTest(x: Double, y: Double): Boolean

    /** A [button] went down over this target; the target now owns that button until its release. */
    fun onPointerPress(button: Int, x: Double, y: Double)

    /**
     * The [button] this target owns went up. ([x], [y]) is NaN when the pointer position is unknown
     * (the cursor got grabbed by gameplay in between), so position-based actions must not fire.
     */
    fun onPointerRelease(button: Int, x: Double, y: Double)

    /** The target lost [button] without a release (missed event); drop any drag without acting. */
    fun onPointerCancel(button: Int) {}
}

/**
 * Routes raw mouse button and scroll events to [PointerTarget]s ahead of the game, so a click lands on
 * exactly one thing.
 *
 * A press over a target belongs to the top-most target under the pointer: it is consumed, and the
 * matching release goes to the same target and is consumed too, wherever it happens. Nothing below ever
 * sees half of a click. Events that miss every target are left alone for the game to handle.
 */
class PointerRouter {
    private val captures = HashMap<Int, PointerTarget>()

    /**
     * Handles a [button] press at ([x], [y]). [targetsTopFirst] lists the candidates, top-most first.
     * [pointerFree] is false while the cursor is grabbed by gameplay; such presses never hit a target.
     * Returns true when the press was consumed.
     */
    fun press(button: Int, x: Double, y: Double, pointerFree: Boolean, targetsTopFirst: List<PointerTarget>): Boolean {
        // A second press without a release means the release was missed; let go of the stale owner first.
        captures.remove(button)?.onPointerCancel(button)
        if (!pointerFree) return false
        val target = targetsTopFirst.firstOrNull { it.hitTest(x, y) } ?: return false
        captures[button] = target
        target.onPointerPress(button, x, y)
        return true
    }

    /** Handles a [button] release. Returns true when it belongs to a press a target consumed. */
    fun release(button: Int, x: Double, y: Double): Boolean {
        val owner = captures.remove(button) ?: return false
        owner.onPointerRelease(button, x, y)
        return true
    }

    /** Returns true when a scroll at ([x], [y]) is over a target, which swallows it. */
    fun scroll(x: Double, y: Double, pointerFree: Boolean, targetsTopFirst: List<PointerTarget>): Boolean =
        pointerFree && targetsTopFirst.any { it.hitTest(x, y) }

    /** True while a target owns [button]. */
    fun isCaptured(button: Int): Boolean = button in captures

    /** Cancels every capture whose button [isDown] says is no longer held (a release that never arrived). */
    fun reconcile(isDown: (Int) -> Boolean) {
        if (captures.isEmpty()) return
        val stale = captures.keys.filterNot(isDown)
        for (button in stale) captures.remove(button)?.onPointerCancel(button)
    }
}
