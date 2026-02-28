package com.anotepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SharedDraftRecoveryStoreTest {

    @Test
    fun persistAndPeek_roundTripDraft() {
        val store = createStore()
        val draft = SharedNoteDraft(
            fileName = "Shared 2026-02-28 20-10-00.txt",
            content = "Shared 2026-02-28 20-10-00.txt\n\nRecovered body"
        )

        store.persist(draft)

        assertEquals(draft, store.peek())
    }

    @Test
    fun clear_removesPersistedDraft() {
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
    fun peek_returnsNullForCorruptedPayload() {
        val root = createTempDir(prefix = "shared-draft-recovery")
        val file = File(root, "recovery.json")
        file.writeText("not-json")
        val store = SharedDraftRecoveryStore(file)

        assertNull(store.peek())
    }

    private fun createStore(): SharedDraftRecoveryStore {
        val root = createTempDir(prefix = "shared-draft-recovery")
        return SharedDraftRecoveryStore(File(root, "recovery.json"))
    }
}
