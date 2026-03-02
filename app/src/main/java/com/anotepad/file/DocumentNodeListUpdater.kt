package com.anotepad.file

import android.net.Uri
import com.anotepad.data.FileSortOrder

object DocumentNodeListUpdater {
    fun mergeAndSortEntries(
        entries: List<DocumentNode>,
        updatedNode: DocumentNode,
        matchUri: Uri,
        sortOrder: FileSortOrder
    ): List<DocumentNode> {
        val matchIndex = entries.indexOfFirst { it.uri == matchUri }
        val currentIndex = if (matchIndex >= 0 || matchUri == updatedNode.uri) {
            -1
        } else {
            entries.indexOfFirst { it.uri == updatedNode.uri }
        }
        val indexToUpdate = if (matchIndex >= 0) matchIndex else currentIndex
        val merged = if (indexToUpdate >= 0) {
            entries.toMutableList().apply { set(indexToUpdate, updatedNode) }
        } else {
            entries + updatedNode
        }
        val dirs = merged.filter { it.isDirectory }
        val files = merged.filterNot { it.isDirectory }
        return NaturalOrderFileComparator.sort(dirs, sortOrder) +
            NaturalOrderFileComparator.sort(files, sortOrder)
    }
}
