package com.dreamdisplays.media.player.process.win

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * The few Win32 / COM entry points the screen share needs, through the JNA that Minecraft ships (core JNA only, no
 * jna-platform, so any JNA 5 works). COM methods are called by vtable slot; every object is released by its caller.
 */
internal object WinApi {
    interface Ole32 : StdCallLibrary {
        fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
        fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, out: PointerByReference): Int
        fun CoTaskMemFree(p: Pointer?)
    }

    interface Kernel32 : StdCallLibrary {
        fun CreateEventW(attributes: Pointer?, manualReset: Boolean, initialState: Boolean, name: WString?): Pointer?
        fun WaitForSingleObject(handle: Pointer, millis: Int): Int
        fun CloseHandle(handle: Pointer): Boolean
        fun CreateNamedPipeW(
            name: WString, openMode: Int, pipeMode: Int, maxInstances: Int, outBuffer: Int, inBuffer: Int,
            timeoutMillis: Int, attributes: Pointer?,
        ): Pointer?

        fun ConnectNamedPipe(pipe: Pointer, overlapped: Pointer?): Boolean
        fun WriteFile(file: Pointer, buffer: ByteArray, bytes: Int, written: IntByReference, overlapped: Pointer?): Boolean
    }

    interface Mmdevapi : StdCallLibrary {
        fun ActivateAudioInterfaceAsync(
            path: WString, iid: Pointer, params: Pointer?, handler: Pointer, operation: PointerByReference,
        ): Int
    }

    interface User32 : StdCallLibrary {
        interface EnumProc : StdCallLibrary.StdCallCallback {
            fun callback(hwnd: Pointer?, param: Pointer?): Boolean
        }

        fun EnumWindows(proc: EnumProc, param: Pointer?): Boolean
        fun IsWindowVisible(hwnd: Pointer): Boolean
        fun IsIconic(hwnd: Pointer): Boolean
        fun GetWindowTextLengthW(hwnd: Pointer): Int
        fun GetWindowTextW(hwnd: Pointer, text: CharArray, max: Int): Int
        fun GetWindowLongW(hwnd: Pointer, index: Int): Int
        fun GetWindow(hwnd: Pointer, cmd: Int): Pointer?
        fun GetWindowThreadProcessId(hwnd: Pointer, pid: IntByReference): Int
        fun GetWindowRect(hwnd: Pointer, rect: Memory): Boolean
        fun ShowWindow(hwnd: Pointer, cmd: Int): Boolean
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun IsWindow(hwnd: Pointer): Boolean
    }

    interface Dwmapi : StdCallLibrary {
        fun DwmGetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    interface Version : StdCallLibrary {
        fun GetFileVersionInfoSizeW(file: WString, handle: IntByReference?): Int
        fun GetFileVersionInfoW(file: WString, handle: Int, size: Int, data: Pointer): Boolean
        fun VerQueryValueW(block: Pointer, subBlock: WString, buffer: PointerByReference, length: IntByReference): Boolean
    }

    interface Ntdll : Library {
        fun RtlGetVersion(info: Memory): Int
    }

    val ole32: Ole32 by lazy { Native.load("ole32", Ole32::class.java) }
    val kernel32: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    val mmdevapi: Mmdevapi by lazy { Native.load("mmdevapi", Mmdevapi::class.java) }
    val user32: User32 by lazy { Native.load("user32", User32::class.java) }
    val dwmapi: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }
    val version: Version by lazy { Native.load("version", Version::class.java) }
    private val ntdll: Ntdll by lazy { Native.load("ntdll", Ntdll::class.java) }

    const val S_OK = 0
    const val E_NOINTERFACE = 0x80004002.toInt()
    const val RPC_E_CHANGED_MODE = 0x80010106.toInt()
    const val COINIT_MULTITHREADED = 0
    const val CLSCTX_ALL = 0x17

    /** Windows build number (e.g. 22631), or 0 when unknown. */
    val buildNumber: Int by lazy {
        runCatching {
            val info = Memory(284).apply { clear(); setInt(0, 284) }
            if (ntdll.RtlGetVersion(info) == 0) info.getInt(12) else 0
        }.getOrDefault(0)
    }

    /** Joins this thread to the multithreaded COM apartment; false if it already lives in another one (still usable). */
    fun comInit(): Boolean {
        val hr = ole32.CoInitializeEx(null, COINIT_MULTITHREADED)
        return hr >= 0 && hr != RPC_E_CHANGED_MODE
    }

    /** A GUID in memory, from its usual text form. */
    fun guid(text: String): Memory {
        val hex = text.replace("-", "")
        require(hex.length == 32) { text }
        val m = Memory(16)
        m.setInt(0, java.lang.Long.parseLong(hex.substring(0, 8), 16).toInt())
        m.setShort(4, Integer.parseInt(hex.substring(8, 12), 16).toShort())
        m.setShort(6, Integer.parseInt(hex.substring(12, 16), 16).toShort())
        for (i in 0 until 8) m.setByte(8L + i, Integer.parseInt(hex.substring(16 + i * 2, 18 + i * 2), 16).toByte())
        return m
    }

    fun sameGuid(a: Pointer, b: Memory): Boolean = a.getByteArray(0, 16).contentEquals(b.getByteArray(0, 16))

    /** Calls method [slot] of COM object [obj] with [args] (the object itself goes first); returns the HRESULT. */
    fun call(obj: Pointer, slot: Int, vararg args: Any?): Int {
        val vtable = obj.getPointer(0)
        val fn = Function.getFunction(vtable.getPointer(slot.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        return fn.invokeInt(arrayOf<Any?>(obj, *args))
    }

    fun release(obj: Pointer?) {
        if (obj != null) runCatching { call(obj, 2) }
    }

    /** Throws when [hr] is a failure, naming [what]. */
    fun check(hr: Int, what: String) {
        if (hr < 0) throw IllegalStateException(String.format("%s failed (0x%08X)", what, hr))
    }

    val IID_IUNKNOWN = guid("00000000-0000-0000-C000-000000000046")
    val IID_IAGILE_OBJECT = guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90")
    val CLSID_MM_DEVICE_ENUMERATOR = guid("BCDE0395-E52F-467C-8E3D-C4579291692E")
    val IID_IMM_DEVICE_ENUMERATOR = guid("A95664D2-9614-4F35-A746-DE8DB63617E6")
    val IID_IAUDIO_CLIENT = guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2")
    val IID_IAUDIO_CAPTURE_CLIENT = guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317")
    val IID_IAUDIO_SESSION_MANAGER2 = guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F")
    val IID_IAUDIO_SESSION_CONTROL2 = guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d")
    val IID_ACTIVATE_COMPLETION_HANDLER = guid("41D949AB-9862-444A-80F6-C261334DA5EB")

    /**
     * A minimal free-threaded COM object implementing IActivateAudioInterfaceCompletionHandler (and IAgileObject, which
     * ActivateAudioInterfaceAsync insists on). [onCompleted] gets the async operation. Keep the instance reachable until
     * the callback ran: its vtable points at JNA callbacks.
     */
    class CompletionHandler(private val onCompleted: (Pointer) -> Unit) {
        private interface QueryInterface : Callback {
            fun invoke(self: Pointer, riid: Pointer, out: Pointer): Int
        }

        private interface RefCount : Callback {
            fun invoke(self: Pointer): Int
        }

        private interface Completed : Callback {
            fun invoke(self: Pointer, operation: Pointer): Int
        }

        private val qi = object : QueryInterface {
            override fun invoke(self: Pointer, riid: Pointer, out: Pointer): Int {
                val known = sameGuid(riid, IID_IUNKNOWN) || sameGuid(riid, IID_IAGILE_OBJECT) ||
                        sameGuid(riid, IID_ACTIVATE_COMPLETION_HANDLER)
                out.setPointer(0, if (known) self else null)
                return if (known) S_OK else E_NOINTERFACE
            }
        }
        // Lifetime is this Kotlin object's, so the count only has to look plausible
        private val addRef = object : RefCount {
            override fun invoke(self: Pointer): Int = 1
        }
        private val release = object : RefCount {
            override fun invoke(self: Pointer): Int = 1
        }
        private val completed = object : Completed {
            override fun invoke(self: Pointer, operation: Pointer): Int {
                runCatching { onCompleted(operation) }
                return S_OK
            }
        }

        private val vtable = Memory(4L * Native.POINTER_SIZE).apply {
            setPointer(0, CallbackReference.getFunctionPointer(qi))
            setPointer(1L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRef))
            setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(release))
            setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(completed))
        }

        /** The COM object: a pointer to the vtable. */
        val pointer: Memory = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, vtable) }
    }
}
