@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.core.display.region.RegionShape
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.protocol.common.packets.DisplayRegionShape
import com.dreamdisplays.shared.capability.ClientFeature
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegionWireTest {
    private val proto = ProtoBuf { encodeDefaults = false }
    private val id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

    /** What released (protocol 12, 038a5ab3..) clients know of `DisplayInfo`: everything up to field 27. */
    @Serializable
    private data class InfoV12(
        @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
        @ProtoNumber(8) val url: String = "",
        @ProtoNumber(25) val customData: Map<String, ByteArray> = emptyMap(),
        @ProtoNumber(27) val world: String = "",
    )

    private val cuboid = RegionShape.Cuboid(-10, 60, -20, 30, 80, 5)
    private val concave = RegionShape.Polygon(listOf(0, 20, 20, 10, 10, 0), listOf(0, 0, 10, 10, 20, 20), -64, 320)

    @Test
    fun `region shapes round trip`() {
        for (shape in listOf(cuboid, concave)) {
            val info = DisplayInfo(id, url = "https://youtu.be/a", world = "minecraft:overworld", region = DisplayRegionShape.of(shape))
            val decoded = proto.decodeFromByteArray(DisplayInfo.serializer(), proto.encodeToByteArray(DisplayInfo.serializer(), info))
            assertEquals(info, decoded)
            assertEquals(shape, decoded.region?.toShape())
        }
    }

    @Test
    fun `a description without a region is unchanged on the wire`() {
        val info = DisplayInfo(id, url = "https://youtu.be/a", world = "minecraft:overworld")
        val bytes = proto.encodeToByteArray(DisplayInfo.serializer(), info)
        assertNull(proto.decodeFromByteArray(DisplayInfo.serializer(), bytes).region)
        // Same bytes as before the field existed
        val v12 = InfoV12(id, "https://youtu.be/a", world = "minecraft:overworld")
        assertEquals(proto.encodeToByteArray(InfoV12.serializer(), v12).toList(), bytes.toList())
    }

    @Test
    fun `released clients read a description with a region as before`() {
        val info = DisplayInfo(id, url = "https://youtu.be/a", world = "minecraft:the_end", region = DisplayRegionShape.of(concave))
        val old = proto.decodeFromByteArray(InfoV12.serializer(), proto.encodeToByteArray(DisplayInfo.serializer(), info))
        assertEquals(InfoV12(id, "https://youtu.be/a", world = "minecraft:the_end"), old)
    }

    @Test
    fun `unknown or broken shapes read as no usable region`() {
        assertNull(DisplayRegionShape(kind = 9).toShape())
        assertNull(DisplayRegionShape(kind = 0).toShape())
        assertNull(DisplayRegionShape(DisplayRegionShape.POLYGON, xs = listOf(1, 2), zs = listOf(1, 2)).toShape())
        assertNull(DisplayRegionShape(DisplayRegionShape.CUBOID, minX = 5, maxX = 1).toShape())
        val huge = (0..DisplayRegionShape.MAX_CORNERS).toList()
        assertNull(DisplayRegionShape.of(RegionShape.Polygon(huge, huge.map { it * it % 97 }, 0, 1)))
    }

    @Test
    fun `the capability token is region_fade`() {
        assertEquals("region_fade", ClientFeature.REGION_FADE.wire)
        assertEquals(ClientFeature.REGION_FADE, ClientFeature.fromWire("region_fade"))
    }
}
