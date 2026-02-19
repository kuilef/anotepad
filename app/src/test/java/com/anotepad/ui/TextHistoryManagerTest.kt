package com.anotepad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextHistoryManagerTest {

    @Test
    fun pushUndo_withClearRedo_clearsRedoHistory() {
        val undo = mutableListOf<TextSnapshot>()
        val redo = mutableListOf(TextSnapshot("redo", 0, 0))
        val manager = TextHistoryManager(
            undoStack = undo,
            redoStack = redo,
            maxEntriesPerStack = 10,
            maxTotalChars = 100
        )

        manager.pushUndo(TextSnapshot("undo", 0, 0), clearRedo = true)

        assertEquals(listOf("undo"), undo.map { it.text })
        assertTrue(redo.isEmpty())
    }

    @Test
    fun pushUndo_trimsOldestSnapshots_whenCountLimitExceeded() {
        val undo = mutableListOf<TextSnapshot>()
        val redo = mutableListOf<TextSnapshot>()
        val manager = TextHistoryManager(
            undoStack = undo,
            redoStack = redo,
            maxEntriesPerStack = 2,
            maxTotalChars = 100
        )

        manager.pushUndo(TextSnapshot("a", 0, 0), clearRedo = true)
        manager.pushUndo(TextSnapshot("bb", 0, 0), clearRedo = true)
        manager.pushUndo(TextSnapshot("ccc", 0, 0), clearRedo = true)

        assertEquals(listOf("bb", "ccc"), undo.map { it.text })
    }

    @Test
    fun pushRedo_respectsTotalCharBudget_andEvictsFromUndoFirst() {
        val undo = mutableListOf<TextSnapshot>()
        val redo = mutableListOf<TextSnapshot>()
        val manager = TextHistoryManager(
            undoStack = undo,
            redoStack = redo,
            maxEntriesPerStack = 10,
            maxTotalChars = 10
        )

        manager.pushUndo(TextSnapshot("123456", 0, 0), clearRedo = true)
        manager.pushRedo(TextSnapshot("ABCDE", 0, 0))

        assertTrue(undo.isEmpty())
        assertEquals(listOf("ABCDE"), redo.map { it.text })
    }
}
