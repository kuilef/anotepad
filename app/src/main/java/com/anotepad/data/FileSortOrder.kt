package com.anotepad.data

enum class FileSortOrder(val id: String) {
    NAME_ASC("name_asc"),
    NAME_DESC("name_desc");

    companion object {
        fun fromId(id: String?): FileSortOrder {
            return when (id) {
                NAME_ASC.id -> NAME_ASC
                NAME_DESC.id -> NAME_DESC
                else -> NAME_DESC
            }
        }
    }
}
