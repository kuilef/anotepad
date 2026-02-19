package com.anotepad.ui

class TextHistoryManager(
    private val undoStack: MutableList<TextSnapshot>,
    private val redoStack: MutableList<TextSnapshot>,
    private val maxEntriesPerStack: Int = DEFAULT_MAX_ENTRIES_PER_STACK,
    private val maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS
) {
    private val safeMaxEntries = maxEntriesPerStack.coerceAtLeast(1)
    private val safeMaxTotalChars = maxTotalChars.coerceAtLeast(1)
    private var undoChars = undoStack.sumOf { it.text.length }
    private var redoChars = redoStack.sumOf { it.text.length }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        undoChars = 0
        redoChars = 0
    }

    fun pushUndo(snapshot: TextSnapshot, clearRedo: Boolean) {
        undoStack.add(snapshot)
        undoChars += snapshot.text.length
        trimStackByCount(undoStack, isUndo = true)
        if (clearRedo) {
            clearRedoInternal()
        }
        trimTotalBudget(preferUndo = true)
    }

    fun pushRedo(snapshot: TextSnapshot) {
        redoStack.add(snapshot)
        redoChars += snapshot.text.length
        trimStackByCount(redoStack, isUndo = false)
        trimTotalBudget(preferUndo = false)
    }

    fun popUndo(): TextSnapshot? {
        if (undoStack.isEmpty()) return null
        val snapshot = undoStack.removeAt(undoStack.lastIndex)
        undoChars = (undoChars - snapshot.text.length).coerceAtLeast(0)
        return snapshot
    }

    fun popRedo(): TextSnapshot? {
        if (redoStack.isEmpty()) return null
        val snapshot = redoStack.removeAt(redoStack.lastIndex)
        redoChars = (redoChars - snapshot.text.length).coerceAtLeast(0)
        return snapshot
    }

    private fun clearRedoInternal() {
        redoStack.clear()
        redoChars = 0
    }

    private fun trimStackByCount(stack: MutableList<TextSnapshot>, isUndo: Boolean) {
        while (stack.size > safeMaxEntries) {
            val removed = stack.removeAt(0)
            if (isUndo) {
                undoChars = (undoChars - removed.text.length).coerceAtLeast(0)
            } else {
                redoChars = (redoChars - removed.text.length).coerceAtLeast(0)
            }
        }
    }

    private fun trimTotalBudget(preferUndo: Boolean) {
        while (undoChars + redoChars > safeMaxTotalChars) {
            val trimmed = if (preferUndo) {
                trimOldestFromRedoOrUndo()
            } else {
                trimOldestFromUndoOrRedo()
            }
            if (!trimmed) break
        }
    }

    private fun trimOldestFromUndoOrRedo(): Boolean {
        if (undoStack.isNotEmpty()) {
            val removed = undoStack.removeAt(0)
            undoChars = (undoChars - removed.text.length).coerceAtLeast(0)
            return true
        }
        if (redoStack.isNotEmpty()) {
            val removed = redoStack.removeAt(0)
            redoChars = (redoChars - removed.text.length).coerceAtLeast(0)
            return true
        }
        return false
    }

    private fun trimOldestFromRedoOrUndo(): Boolean {
        if (redoStack.isNotEmpty()) {
            val removed = redoStack.removeAt(0)
            redoChars = (redoChars - removed.text.length).coerceAtLeast(0)
            return true
        }
        if (undoStack.isNotEmpty()) {
            val removed = undoStack.removeAt(0)
            undoChars = (undoChars - removed.text.length).coerceAtLeast(0)
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES_PER_STACK = 200
        const val DEFAULT_MAX_TOTAL_CHARS = 2_000_000
    }
}
