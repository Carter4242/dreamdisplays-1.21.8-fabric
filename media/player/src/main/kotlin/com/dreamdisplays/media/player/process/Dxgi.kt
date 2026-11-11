package com.dreamdisplays.media.player.process

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * Lists the graphics adapters and their screens the way Desktop Duplication sees them, straight from DXGI (Windows
 * only; JNA comes with the game). Adapter indexes are those of `IDXGIFactory1::EnumAdapters1`, the same order
 * `FFmpeg`'s `d3d11va=<name>:<index>` device and `ddagrab` (which enumerates the outputs of that device's adapter)
 * use. A few COM calls, no device is created: cheap enough to run each time the share picker opens.
 */
internal object Dxgi {
    data class Adapter(
        val index: Int, val description: String, val vendorId: Int, val deviceId: Int, val luid: Long,
        val software: Boolean,
    ) {
        /** `vvvv:dddd`, as `FFmpeg` prints the device it opened. */
        val pciId: String get() = String.format("%04x:%04x", vendorId, deviceId)
    }

    data class Output(
        val adapter: Adapter, val index: Int, val deviceName: String,
        val left: Int, val top: Int, val right: Int, val bottom: Int, val attachedToDesktop: Boolean,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class Snapshot(val adapters: List<Adapter>, val outputs: List<Output>)

    // {770aae78-f26f-4dba-a829-253c83d1b387}, little-endian GUID layout
    private val IID_IDXGIFactory1 = byteArrayOf(
        0x78, 0xae.toByte(), 0x0a, 0x77, 0x6f, 0xf2.toByte(), 0xba.toByte(), 0x4d,
        0xa8.toByte(), 0x29, 0x25, 0x3c, 0x83.toByte(), 0xd1.toByte(), 0xb3.toByte(), 0x87.toByte(),
    )
    private const val DXGI_ERROR_NOT_FOUND = 0x887A0002.toInt()
    private const val DXGI_ADAPTER_FLAG_SOFTWARE = 2

    // vtable slots: IUnknown 0-2, IDXGIObject 3-6, then the interface's own methods
    private const val RELEASE = 2
    private const val FACTORY1_ENUM_ADAPTERS1 = 12
    private const val ADAPTER_ENUM_OUTPUTS = 7
    private const val ADAPTER1_GET_DESC1 = 10
    private const val OUTPUT_GET_DESC = 7

    // DXGI_ADAPTER_DESC1: WCHAR Description[128]; UINT VendorId, DeviceId, SubSysId, Revision;
    // SIZE_T x3; LUID AdapterLuid; UINT Flags
    private const val ADAPTER_DESC1_SIZE = 312L
    private const val AD_VENDOR = 256L
    private const val AD_DEVICE = 260L
    private const val AD_LUID = 296L
    private const val AD_FLAGS = 304L

    // DXGI_OUTPUT_DESC: WCHAR DeviceName[32]; RECT DesktopCoordinates; BOOL AttachedToDesktop; rotation; HMONITOR
    private const val OUTPUT_DESC_SIZE = 96L
    private const val OD_RECT = 64L
    private const val OD_ATTACHED = 80L

    /** Every adapter and every screen on it, in DXGI order; throws when DXGI or JNA cannot be used. */
    fun enumerate(): Snapshot {
        check(Native.POINTER_SIZE == 8) { "64-bit only" }
        val create = NativeLibrary.getInstance("dxgi").getFunction("CreateDXGIFactory1", Function.ALT_CONVENTION)
        val iid = Memory(16).apply { write(0, IID_IDXGIFactory1, 0, 16) }
        val ref = PointerByReference()
        val hr = create.invokeInt(arrayOf(iid, ref))
        check(hr >= 0) { "CreateDXGIFactory1 failed: 0x%08x".format(hr) }
        val factory = ref.value ?: error("CreateDXGIFactory1 returned no factory")
        val adapters = ArrayList<Adapter>()
        val outputs = ArrayList<Output>()
        try {
            var i = 0
            while (true) {
                val aRef = PointerByReference()
                val ahr = call(factory, FACTORY1_ENUM_ADAPTERS1, i, aRef)
                if (ahr == DXGI_ERROR_NOT_FOUND) break
                check(ahr >= 0) { "EnumAdapters1($i) failed: 0x%08x".format(ahr) }
                val adapter = aRef.value
                try {
                    val desc = Memory(ADAPTER_DESC1_SIZE).apply { clear() }
                    check(call(adapter, ADAPTER1_GET_DESC1, desc) >= 0) { "GetDesc1($i) failed" }
                    val a = Adapter(
                        index = i,
                        description = desc.getWideString(0).trim(),
                        vendorId = desc.getInt(AD_VENDOR),
                        deviceId = desc.getInt(AD_DEVICE),
                        luid = (desc.getInt(AD_LUID + 4).toLong() shl 32) or (desc.getInt(AD_LUID).toLong() and 0xffffffffL),
                        software = desc.getInt(AD_FLAGS) and DXGI_ADAPTER_FLAG_SOFTWARE != 0,
                    )
                    adapters += a
                    var o = 0
                    while (true) {
                        val oRef = PointerByReference()
                        if (call(adapter, ADAPTER_ENUM_OUTPUTS, o, oRef) < 0) break // DXGI_ERROR_NOT_FOUND: no more
                        val output = oRef.value
                        try {
                            val od = Memory(OUTPUT_DESC_SIZE).apply { clear() }
                            if (call(output, OUTPUT_GET_DESC, od) >= 0) {
                                outputs += Output(
                                    adapter = a, index = o, deviceName = od.getWideString(0).trim(),
                                    left = od.getInt(OD_RECT), top = od.getInt(OD_RECT + 4),
                                    right = od.getInt(OD_RECT + 8), bottom = od.getInt(OD_RECT + 12),
                                    attachedToDesktop = od.getInt(OD_ATTACHED) != 0,
                                )
                            }
                        } finally {
                            release(output)
                        }
                        o++
                    }
                } finally {
                    release(adapter)
                }
                i++
            }
        } finally {
            release(factory)
        }
        return Snapshot(adapters, outputs)
    }

    private fun call(obj: Pointer, slot: Int, vararg args: Any): Int {
        val fn = obj.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(fn, Function.ALT_CONVENTION).invokeInt(arrayOf(obj, *args))
    }

    private fun release(obj: Pointer) {
        runCatching {
            val fn = obj.getPointer(0).getPointer(RELEASE.toLong() * Native.POINTER_SIZE)
            Function.getFunction(fn, Function.ALT_CONVENTION).invokeInt(arrayOf(obj))
        }
    }
}
