package com.anotepad.file

import android.net.Uri
import com.anotepad.data.FileSortOrder

class ListCacheManager(
    private val ttlMs: Long = DEFAULT_LIST_CACHE_TTL_MS,
    private val maxEntries: Int = DEFAULT_LIST_CACHE_MAX_ENTRIES
) {
    private val listCache = object : LinkedHashMap<ListCacheKey, ListCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ListCacheKey, ListCacheEntry>): Boolean {
            return size > maxEntries
        }
    }

    fun get(dirTreeUri: Uri, sortOrder: FileSortOrder): List<DocumentNode>? {
        return get(dirTreeUri.toString(), sortOrder)
    }

    fun get(dirUri: String, sortOrder: FileSortOrder): List<DocumentNode>? {
        val key = ListCacheKey(dirUri = dirUri, sortOrder = sortOrder)
        val now = System.currentTimeMillis()
        synchronized(listCache) {
            val entry = listCache[key] ?: return null
            return if (now - entry.timestampMs <= ttlMs) {
                entry.entries
            } else {
                listCache.remove(key)
                null
            }
        }
    }

    fun put(dirTreeUri: Uri, sortOrder: FileSortOrder, entries: List<DocumentNode>) {
        put(dirTreeUri.toString(), sortOrder, entries)
    }

    fun put(dirUri: String, sortOrder: FileSortOrder, entries: List<DocumentNode>) {
        val key = ListCacheKey(dirUri = dirUri, sortOrder = sortOrder)
        synchronized(listCache) {
            listCache[key] = ListCacheEntry(entries = entries, timestampMs = System.currentTimeMillis())
        }
    }

    fun invalidate(dirTreeUri: Uri) {
        invalidate(dirTreeUri.toString())
    }

    fun invalidate(dirUri: String) {
        synchronized(listCache) {
            val iterator = listCache.keys.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (key.dirUri == dirUri) {
                    iterator.remove()
                }
            }
        }
    }
}

private data class ListCacheKey(
    val dirUri: String,
    val sortOrder: FileSortOrder
)

private data class ListCacheEntry(
    val entries: List<DocumentNode>,
    val timestampMs: Long
)

private const val DEFAULT_LIST_CACHE_TTL_MS = 15_000L
private const val DEFAULT_LIST_CACHE_MAX_ENTRIES = 12
