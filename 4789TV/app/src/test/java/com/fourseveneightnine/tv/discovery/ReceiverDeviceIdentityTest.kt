package com.fourseveneightnine.tv.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverDeviceIdentityTest {
    @Test
    fun fireTvUsesRoomNameAndHardwareModelWithoutAppBrand() {
        val identity = ReceiverDeviceIdentity.fromHardware(
            manufacturer = "Amazon",
            model = "AFTKA",
            deviceName = "4789 Living Room",
        )

        assertEquals(ReceiverDeviceIdentity.KIND_FIRE_TV, identity.kind)
        assertEquals("Living Room · AFTKA · Fire TV", identity.displayName)
    }

    @Test
    fun streamingBoxesAndBuiltInTvsRemainDifferentFamilies() {
        val box = ReceiverDeviceIdentity.fromHardware("NVIDIA", "SHIELD Android TV")
        val tv = ReceiverDeviceIdentity.fromHardware("Sony", "BRAVIA 4K VH2")

        assertEquals(ReceiverDeviceIdentity.KIND_ANDROID_BOX, box.kind)
        assertEquals(ReceiverDeviceIdentity.KIND_ANDROID_TV, tv.kind)
        assertEquals("SHIELD Android TV · Android box", box.displayName)
        assertEquals("BRAVIA 4K VH2 · Android TV", tv.displayName)
    }
}
