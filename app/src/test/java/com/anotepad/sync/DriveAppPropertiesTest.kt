package com.anotepad.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAppPropertiesTest {

    @Test
    fun sanitizeDriveAppProperties_keepsEntryWithinLimit() {
        val result = sanitizeDriveAppProperties(
            mapOf("localRelativePath" to "short/note.txt")
        )

        assertEquals("short/note.txt", result["localRelativePath"])
    }

    @Test
    fun sanitizeDriveAppProperties_dropsEntryOverLimit() {
        val dropped = mutableListOf<Pair<String, Int>>()

        val result = sanitizeDriveAppProperties(
            mapOf("localRelativePath" to "a".repeat(200))
        ) { key, byteCount ->
            dropped += key to byteCount
        }

        assertFalse(result.containsKey("localRelativePath"))
        assertEquals(1, dropped.size)
        assertEquals("localRelativePath", dropped.single().first)
        assertTrue(dropped.single().second > DRIVE_APP_PROPERTY_MAX_BYTES)
    }
}
