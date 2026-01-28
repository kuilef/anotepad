package com.anotepad.data

data class AppPreferences(
    val rootTreeUri: String? = null,
    val autoLinkWeb: Boolean = false,
    val autoLinkEmail: Boolean = false,
    val autoLinkTel: Boolean = false,
    val syncTitle: Boolean = false,
    val autoSaveDebounceMs: Long = 1200L,
    val autoSaveEnabled: Boolean = true,
    val browserFontSizeSp: Float = 14f,
    val editorFontSizeSp: Float = 16f,
    val autoInsertTemplateEnabled: Boolean = true,
    val autoInsertTemplate: String = "yyyy-MM-dd",
    val defaultFileExtension: String = "txt"
)
