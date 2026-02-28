package com.anotepad.file

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafFileOpsHandlerPathValidationTest {

    @Test
    fun isSafeRelativePath_rejectsParentTraversalSegments() {
        assertFalse(isSafeRelativePath("../note.txt"))
        assertFalse(isSafeRelativePath("shared/../note.txt"))
        assertFalse(isSafeRelativePath("shared/./note.txt"))
        assertFalse(isSafeRelativePath("shared/%2e%2e/note.txt"))
        assertFalse(isSafeRelativePath("shared/.%2e/note.txt"))
    }

    @Test
    fun isSafeRelativePath_rejectsAbsolutePaths() {
        assertFalse(isSafeRelativePath("/shared/note.txt"))
    }

    @Test
    fun isSafeRelativePath_allowsRegularRelativePaths() {
        assertTrue(isSafeRelativePath(""))
        assertTrue(isSafeRelativePath("shared/note.txt"))
    }

    @Test
    fun isSafeRelativePath_rejectsControlCharsBackslashesAndEmptySegments() {
        assertFalse(isSafeRelativePath("shared\\note.txt"))
        assertFalse(isSafeRelativePath("shared/\u0000note.txt"))
        assertFalse(isSafeRelativePath("shared//note.txt"))
        assertFalse(isSafeRelativePath("shared/note.txt/"))
    }
}
