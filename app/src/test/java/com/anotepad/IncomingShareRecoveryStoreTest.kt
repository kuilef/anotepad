package com.anotepad

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingShareRecoveryStoreTest {

    @Test
    fun persistAndPeek_roundTripPayload() = runBlocking {
        val file = createStoreFile()
        setStoreFile(file)

        IncomingShareRecoveryStore.persist(SharedTextPayload("Recovered share"))

        assertEquals(SharedTextPayload("Recovered share"), IncomingShareRecoveryStore.peek())
    }

    @Test
    fun clear_removesPersistedPayload() = runBlocking {
        val file = createStoreFile()
        setStoreFile(file)
        IncomingShareRecoveryStore.persist(SharedTextPayload("Recovered share"))

        IncomingShareRecoveryStore.clear()

        assertNull(IncomingShareRecoveryStore.peek())
    }

    private fun createStoreFile(): File {
        val root = createTempDir(prefix = "incoming-share-recovery")
        return File(root, "recovery.json")
    }

    private fun setStoreFile(file: File) {
        val field = IncomingShareRecoveryStore::class.java.getDeclaredField("file")
        field.isAccessible = true
        field.set(IncomingShareRecoveryStore, file)
    }
}
