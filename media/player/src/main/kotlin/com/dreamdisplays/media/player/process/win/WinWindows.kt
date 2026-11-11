package com.dreamdisplays.media.player.process.win

import com.dreamdisplays.media.player.process.ScreenCapture
import com.dreamdisplays.media.player.process.ShareWindows
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference

/** The top-level windows that can be shared (Windows), in Z order (the frontmost first). */
object WinWindows {
    private const val GWL_EXSTYLE = -20
    private const val GW_OWNER = 4
    private const val DWMWA_CLOAKED = 14
    private const val SW_SHOWNOACTIVATE = 4

    /** Windows a person would call "a window" (see [ShareWindows.offer]), excluding this game's ([selfPid]). */
    fun list(selfPid: Long): List<ScreenCapture.Window> {
        val user32 = WinApi.user32
        val found = ArrayList<ScreenCapture.Window>()
        val rect = Memory(16)
        val pidRef = IntByReference()
        val cloakedRef = IntByReference()
        val proc = object : WinApi.User32.EnumProc {
            override fun callback(hwnd: Pointer?, param: Pointer?): Boolean {
                if (hwnd == null) return true
                runCatching {
                    if (!user32.IsWindowVisible(hwnd)) return true
                    val length = user32.GetWindowTextLengthW(hwnd)
                    if (length <= 0) return true
                    val chars = CharArray(length + 1)
                    val title = String(chars, 0, user32.GetWindowTextW(hwnd, chars, chars.size).coerceIn(0, length))
                    user32.GetWindowThreadProcessId(hwnd, pidRef)
                    cloakedRef.value = 0
                    val cloaked = runCatching {
                        WinApi.dwmapi.DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, cloakedRef, 4) == 0 && cloakedRef.value != 0
                    }.getOrDefault(false)
                    user32.GetWindowRect(hwnd, rect)
                    val facts = ShareWindows.Facts(
                        visible = true,
                        title = title,
                        exStyle = user32.GetWindowLongW(hwnd, GWL_EXSTYLE),
                        cloaked = cloaked,
                        owned = user32.GetWindow(hwnd, GW_OWNER) != null,
                        pid = pidRef.value.toLong() and 0xFFFFFFFFL,
                        width = rect.getInt(8) - rect.getInt(0),
                        height = rect.getInt(12) - rect.getInt(4),
                        minimized = user32.IsIconic(hwnd),
                    )
                    if (ShareWindows.offer(facts, selfPid)) {
                        found += ScreenCapture.Window(Pointer.nativeValue(hwnd), facts.pid, "", title)
                    }
                }
                return true
            }
        }
        user32.EnumWindows(proc, null)
        java.lang.ref.Reference.reachabilityFence(proc)
        // Program names come after the walk: reading version resources inside the callback would hold it up. A Store
        // app's window belongs to ApplicationFrameHost; the app itself (its name, its sound) is the hosted window's.
        return found.map { w ->
            val pid = if (isFrameHost(w.pid)) hostedAppPid(w) ?: w.pid else w.pid
            w.copy(pid = pid, app = WinAudio.appName(pid))
        }
    }

    /** True when process [pid] is ApplicationFrameHost, the frame around every Store (UWP) app's window. */
    fun isFrameHost(pid: Long): Boolean =
        ProcessHandle.of(pid).flatMap { it.info().command() }.orElse(null)
            ?.let { ShareWindows.isFrameHostExe(it) } == true

    /** The process of the Store app inside frame [window] (its `Windows.UI.Core.CoreWindow`); null while minimized. */
    private fun hostedAppPid(window: ScreenCapture.Window): Long? = runCatching {
        val core = WinApi.user32.FindWindowExW(Pointer(window.hwnd), null, WString("Windows.UI.Core.CoreWindow"), null)
            ?: return null
        val pid = IntByReference()
        WinApi.user32.GetWindowThreadProcessId(core, pid)
        (pid.value.toLong() and 0xFFFFFFFFL).takeIf { it != 0L && it != window.pid }
    }.getOrNull()

    /** True while [window] still exists. */
    fun exists(window: ScreenCapture.Window): Boolean = runCatching { WinApi.user32.IsWindow(Pointer(window.hwnd)) }.getOrDefault(false)

    /**
     * A minimized window draws nothing to capture (and Windows Graphics Capture would size the share to its title
     * stub): shows it again without activating it and sends it behind every other window, so the game keeps the
     * focus and stays in front. Capture does not need the window to be visible on screen.
     */
    fun unminimize(window: ScreenCapture.Window) {
        runCatching {
            val hwnd = Pointer(window.hwnd)
            if (!WinApi.user32.IsIconic(hwnd)) return
            WinApi.user32.ShowWindow(hwnd, SW_SHOWNOACTIVATE)
            WinApi.user32.SetWindowPos(hwnd, Pointer(HWND_BOTTOM), 0, 0, 0, 0, SWP_NOSIZE or SWP_NOMOVE or SWP_NOACTIVATE)
        }
    }

    private const val HWND_BOTTOM = 1L
    private const val SWP_NOSIZE = 0x1
    private const val SWP_NOMOVE = 0x2
    private const val SWP_NOACTIVATE = 0x10
}
