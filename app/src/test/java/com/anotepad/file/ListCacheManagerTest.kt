package com.anotepad.file

import android.net.Uri
import com.anotepad.data.FileSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListCacheManagerTest {

    @Test
    fun get_returnsNullAfterInvalidate() {
        val manager = ListCacheManager()
        val uri = Uri.parse("content://test/dir")
        val cached = listOf(DocumentNode("a.txt", Uri.parse("content://test/a.txt"), isDirectory = false))

        manager.put(uri, FileSortOrder.NAME_ASC, cached)
        manager.invalidate(uri)

        assertNull(manager.get(uri, FileSortOrder.NAME_ASC))
    }

    @Test
    fun get_respectsTtl() {
        val manager = ListCacheManager(ttlMs = -1L, maxEntries = 12)
        val uri = Uri.parse("content://test/dir")
        val cached = listOf(DocumentNode("a.txt", Uri.parse("content://test/a.txt"), isDirectory = false))

        manager.put(uri, FileSortOrder.NAME_ASC, cached)

        assertNull(manager.get(uri, FileSortOrder.NAME_ASC))
    }

    @Test
    fun put_evictsOldestEntryWhenCapacityExceeded() {
        val manager = ListCacheManager(ttlMs = Long.MAX_VALUE, maxEntries = 1)
        val firstUri = Uri.parse("content://test/first")
        val secondUri = Uri.parse("content://test/second")
        val first = listOf(DocumentNode("first.txt", Uri.parse("content://test/first.txt"), isDirectory = false))
        val second = listOf(DocumentNode("second.txt", Uri.parse("content://test/second.txt"), isDirectory = false))

        manager.put(firstUri, FileSortOrder.NAME_ASC, first)
        manager.put(secondUri, FileSortOrder.NAME_ASC, second)

        assertNull(manager.get(firstUri, FileSortOrder.NAME_ASC))
        assertEquals(second, manager.get(secondUri, FileSortOrder.NAME_ASC))
    }
}
