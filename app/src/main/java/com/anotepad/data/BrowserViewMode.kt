package com.anotepad.data

enum class BrowserViewMode(val id: String) {
    LIST("list"),
    FEED("feed");

    companion object {
        fun fromId(id: String?): BrowserViewMode {
            return when (id) {
                LIST.id -> LIST
                FEED.id -> FEED
                else -> LIST
            }
        }
    }
}
