package com.anotepad.data

enum class TemplateMode {
    TIMEFORMAT
}

data class TemplateItem(
    val id: Long,
    val text: String,
    val mode: TemplateMode
)
