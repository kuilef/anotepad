package com.anotepad.file

import com.anotepad.data.FileSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListCacheManagerTest {

    @Test
    fun get_returnsNullAfterInvalidate() {
        val manager = ListCacheManager()
        val dirKey = "content://test/dir"
        val cached = emptyList<DocumentNode>()

        manager.put(dirKey, FileSortOrder.NAME_ASC, cached)
        manager.invalidate(dirKey)

        assertNull(manager.get(dirKey, FileSortOrder.NAME_ASC))
    }

    @Test
    fun get_respectsTtl() {
        val manager = ListCacheManager(ttlMs = -1L, maxEntries = 12)
        val dirKey = "content://test/dir"
        val cached = emptyList<DocumentNode>()

        manager.put(dirKey, FileSortOrder.NAME_ASC, cached)

        assertNull(manager.get(dirKey, FileSortOrder.NAME_ASC))
    }

    @Test
    fun put_evictsOldestEntryWhenCapacityExceeded() {
        val manager = ListCacheManager(ttlMs = Long.MAX_VALUE, maxEntries = 1)
        val firstKey = "content://test/first"
        val secondKey = "content://test/second"
        val first = emptyList<DocumentNode>()
        val second = emptyList<DocumentNode>()

        manager.put(firstKey, FileSortOrder.NAME_ASC, first)
        manager.put(secondKey, FileSortOrder.NAME_ASC, second)

        assertNull(manager.get(firstKey, FileSortOrder.NAME_ASC))
        assertEquals(second, manager.get(secondKey, FileSortOrder.NAME_ASC))
    }
}
