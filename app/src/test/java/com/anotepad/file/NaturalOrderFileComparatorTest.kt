package com.anotepad.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderFileComparatorTest {

    @Test
    fun sort_ordersNumbersNaturallyAscending() {
        val entries = listOf("note10.txt", "note2.txt", "note1.txt")
        val sorted = entries.sortedWith { left, right ->
            NaturalOrderFileComparator.compareNamesNatural(left, right)
        }

        assertEquals(listOf("note1.txt", "note2.txt", "note10.txt"), sorted)
    }

    @Test
    fun sort_ordersNumbersNaturallyDescending() {
        val entries = listOf("note10.txt", "note2.txt", "note1.txt")
        val sorted = entries.sortedWith { left, right ->
            NaturalOrderFileComparator.compareNamesNatural(right, left)
        }

        assertEquals(listOf("note10.txt", "note2.txt", "note1.txt"), sorted)
    }

    @Test
    fun compareNamesNatural_comparesNumericPartsByValue() {
        assertTrue(NaturalOrderFileComparator.compareNamesNatural("note2.txt", "note10.txt") < 0)
        assertTrue(NaturalOrderFileComparator.compareNamesNatural("note10.txt", "note2.txt") > 0)
    }
}
