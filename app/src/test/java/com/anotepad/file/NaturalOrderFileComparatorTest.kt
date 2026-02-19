package com.anotepad.file

import android.net.Uri
import com.anotepad.data.FileSortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderFileComparatorTest {

    @Test
    fun sort_ordersNumbersNaturallyAscending() {
        val entries = listOf("note10.txt", "note2.txt", "note1.txt").map { name ->
            DocumentNode(name = name, uri = Uri.parse("content://test/$name"), isDirectory = false)
        }

        val sorted = NaturalOrderFileComparator.sort(entries, FileSortOrder.NAME_ASC)

        assertEquals(listOf("note1.txt", "note2.txt", "note10.txt"), sorted.map { it.name })
    }

    @Test
    fun sort_ordersNumbersNaturallyDescending() {
        val entries = listOf("note10.txt", "note2.txt", "note1.txt").map { name ->
            DocumentNode(name = name, uri = Uri.parse("content://test/$name"), isDirectory = false)
        }

        val sorted = NaturalOrderFileComparator.sort(entries, FileSortOrder.NAME_DESC)

        assertEquals(listOf("note10.txt", "note2.txt", "note1.txt"), sorted.map { it.name })
    }
}
