package com.dreamdisplays.media.player.process.win

import com.dreamdisplays.media.player.process.ScreenCapture
import com.sun.jna.Memory
import com.sun.jna.WString

/**
 * Which physical monitor a Windows screen is, and which screen covers a desktop rectangle now (user32 only: cheap
 * enough to ask many times a second). A screen's name (`\.\DISPLAY2`) belongs to a display source, not a monitor:
 * a topology change (Win+P, docking, a monitor switched off) can hand it to another monitor. A monitor's device
 * interface name (`\?\DISPLAY#DEL40F7#5&...&UID4352#{...}`) names the monitor on its connector.
 */
object WinScreens {
    private const val EDD_GET_DEVICE_INTERFACE_NAME = 0x1
    private const val DISPLAY_DEVICE_ACTIVE = 0x1
    private const val MONITOR_DEFAULTTONULL = 0

    // DISPLAY_DEVICEW: DWORD cb; WCHAR DeviceName[32]; WCHAR DeviceString[128]; DWORD StateFlags; WCHAR DeviceID[128];
    // WCHAR DeviceKey[128]
    private const val DD_SIZE = 840
    private const val DD_STATE = 324L
    private const val DD_ID = 328L

    // MONITORINFOEXW: DWORD cbSize; RECT rcMonitor; RECT rcWork; DWORD dwFlags; WCHAR szDevice[32]
    private const val MI_SIZE = 104
    private const val MI_RECT = 4L
    private const val MI_DEVICE = 40L

    /**
     * The monitors showing screen [deviceName] now, by device interface name (sorted, `|`-joined; a mirrored screen
     * has several); "" when Windows lists none.
     */
    fun monitorKey(deviceName: String): String {
        if (deviceName.isEmpty()) return ""
        val ids = ArrayList<String>()
        val all = ArrayList<String>()
        val dd = Memory(DD_SIZE.toLong())
        for (i in 0 until 8) {
            dd.clear()
            dd.setInt(0, DD_SIZE)
            if (!WinApi.user32.EnumDisplayDevicesW(WString(deviceName), i, dd, EDD_GET_DEVICE_INTERFACE_NAME)) break
            val id = String(dd.getCharArray(DD_ID, 128)).substringBefore('\u0000').trim()
            if (id.isEmpty()) continue
            all += id
            if (dd.getInt(DD_STATE) and DISPLAY_DEVICE_ACTIVE != 0) ids += id
        }
        return ScreenCapture.monitorKey(ids.ifEmpty { all })
    }

    /**
     * The screen covering most of [x], [y], [width], [height] now: its name, its own rectangle and [monitorKey]; null
     * when no screen does.
     */
    fun screenAt(x: Int, y: Int, width: Int, height: Int): ScreenCapture.Monitor? {
        val rect = Memory(16).apply {
            setInt(0, x); setInt(4, y); setInt(8, x + width); setInt(12, y + height)
        }
        val monitor = WinApi.user32.MonitorFromRect(rect, MONITOR_DEFAULTTONULL) ?: return null
        val info = Memory(MI_SIZE.toLong()).apply { clear(); setInt(0, MI_SIZE) }
        check(WinApi.user32.GetMonitorInfoW(monitor, info)) { "GetMonitorInfoW failed" }
        val left = info.getInt(MI_RECT)
        val top = info.getInt(MI_RECT + 4)
        val name = String(info.getCharArray(MI_DEVICE, 32)).substringBefore('\u0000').trim()
        return ScreenCapture.Monitor(
            index = -1, name = "", x = left, y = top, width = info.getInt(MI_RECT + 8) - left,
            height = info.getInt(MI_RECT + 12) - top, deviceName = name, key = monitorKey(name),
        )
    }
}
