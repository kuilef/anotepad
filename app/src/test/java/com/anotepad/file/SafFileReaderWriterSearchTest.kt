package com.anotepad.file

import java.io.StringReader
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafFileReaderWriterSearchTest {

    @Test
    fun searchText_findsMatchInsideLargeSingleLine() {
        val text = "A".repeat(6_000) + "needle" + "B".repeat(6_000)

        val snippet = searchText(StringReader(text), "needle", regex = null)

        assertNotNull(snippet)
        assertTrue(snippet!!.contains("needle"))
    }

    @Test
    fun searchText_findsMatchAcrossChunkBoundary() {
        val prefix = "A".repeat(8 * 1024 - 3)
        val text = prefix + "needle" + "tail"

        val snippet = searchText(StringReader(text), "needle", regex = null)

        assertNotNull(snippet)
        assertTrue(snippet!!.contains("needle"))
    }
}
