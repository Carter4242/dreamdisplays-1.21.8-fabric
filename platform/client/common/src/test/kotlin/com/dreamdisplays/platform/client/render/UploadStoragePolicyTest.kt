package com.dreamdisplays.platform.client.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploadStoragePolicyTest {
    @Test
    fun `the first upload builds the ring the server asks for without replacing anything`() {
        assertFalse(UploadStoragePolicy.replacesRing(current = null, wanted = true))
        assertFalse(UploadStoragePolicy.replacesRing(current = null, wanted = false))
    }

    @Test
    fun `a change of the server's upload choice replaces an open uploader's ring`() {
        assertTrue(UploadStoragePolicy.replacesRing(current = true, wanted = false))
        assertTrue(UploadStoragePolicy.replacesRing(current = false, wanted = true))
        assertFalse(UploadStoragePolicy.replacesRing(current = true, wanted = true))
        assertFalse(UploadStoragePolicy.replacesRing(current = false, wanted = false))
    }
}
