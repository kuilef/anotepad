package com.anotepad.data

enum class TemplateMode {
    NORMAL,
    TIMEFORMAT,
    WITHNUMBER,
    TIME_NUMBER
}

data class TemplateItem(
    val id: Long,
    val text: String,
    val mode: TemplateMode
)
