package com.anotepad.file

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafFileOpsHandlerPathValidationTest {

    @Test
    fun isSafeRelativePath_rejectsParentTraversalSegments() {
        assertFalse(isSafeRelativePath("../note.txt"))
        assertFalse(isSafeRelativePath("shared/../note.txt"))
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
}
