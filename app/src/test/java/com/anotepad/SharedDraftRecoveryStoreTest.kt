package com.anotepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

class SharedDraftRecoveryStoreTest {

    @Test
    fun persistAndPeek_roundTripDraft() = runBlocking {
        val store = createStore()
        val draft = SharedNoteDraft(
            fileName = "Shared 2026-02-28 20-10-00.txt",
            content = "Shared 2026-02-28 20-10-00.txt\n\nRecovered body"
        )

        store.persist(draft)

        assertEquals(draft, store.peek())
    }

    @Test
    fun clear_removesPersistedDraft() = runBlocking {
        val store = createStore()
        store.persist(
            SharedNoteDraft(
                fileName = "Shared 2026-02-28 20-10-00.txt",
                content = "Shared 2026-02-28 20-10-00.txt\n\nRecovered body"
            )
        )

        store.clear()

        assertNull(store.peek())
    }

    @Test
    fun peek_returnsNullForCorruptedPayload() = runBlocking {
        val root = createTempDir(prefix = "shared-draft-recovery")
        val file = File(root, "recovery.json")
        file.writeText("not-json")
        val store = SharedDraftRecoveryStore(file)

        assertNull(store.peek())
    }

    @Test
    fun remove_keepsOtherDraftsAvailableForRecovery() = runBlocking {
        val store = createStore()
        val first = SharedNoteDraft(
            fileName = "Shared 2026-02-28 20-10-00-001.txt",
            content = "Shared 2026-02-28 20-10-00-001.txt\n\nFirst"
        )
        val second = SharedNoteDraft(
            fileName = "Shared 2026-02-28 20-10-00-002.txt",
            content = "Shared 2026-02-28 20-10-00-002.txt\n\nSecond"
        )

        store.persist(first)
        store.persist(second)
        store.remove(first)

        assertEquals(second, store.peek())
    }

    private fun createStore(): SharedDraftRecoveryStore {
        val root = createTempDir(prefix = "shared-draft-recovery")
        return SharedDraftRecoveryStore(File(root, "recovery.json"))
    }
}
