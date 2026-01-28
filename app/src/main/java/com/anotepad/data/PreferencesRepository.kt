package com.anotepad.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "anotepad_settings")

class PreferencesRepository(private val context: Context) {
    private object Keys {
        val ROOT_URI = stringPreferencesKey("root_tree_uri")
        val AUTO_LINK_WEB = booleanPreferencesKey("auto_link_web")
        val AUTO_LINK_EMAIL = booleanPreferencesKey("auto_link_email")
        val AUTO_LINK_TEL = booleanPreferencesKey("auto_link_tel")
        val SYNC_TITLE = booleanPreferencesKey("sync_title")
        val AUTO_SAVE_DEBOUNCE = longPreferencesKey("autosave_debounce_ms")
        val AUTO_SAVE_ENABLED = booleanPreferencesKey("autosave_enabled")
        val BROWSER_FONT_SIZE_SP = floatPreferencesKey("browser_font_size_sp")
        val EDITOR_FONT_SIZE_SP = floatPreferencesKey("editor_font_size_sp")
        val AUTO_INSERT_TEMPLATE_ENABLED = booleanPreferencesKey("auto_insert_template_enabled")
        val AUTO_INSERT_TEMPLATE = stringPreferencesKey("auto_insert_template")
        val DEFAULT_FILE_EXTENSION = stringPreferencesKey("default_file_extension")
    }

    val preferencesFlow: Flow<AppPreferences> = context.dataStore.data.map { prefs ->
        val defaultExt = prefs[Keys.DEFAULT_FILE_EXTENSION]
            ?.trim()
            ?.lowercase(Locale.US)
            ?.let { if (it == "md") "md" else "txt" }
            ?: "txt"
        AppPreferences(
            rootTreeUri = prefs[Keys.ROOT_URI],
            autoLinkWeb = prefs[Keys.AUTO_LINK_WEB] ?: false,
            autoLinkEmail = prefs[Keys.AUTO_LINK_EMAIL] ?: false,
            autoLinkTel = prefs[Keys.AUTO_LINK_TEL] ?: false,
            syncTitle = prefs[Keys.SYNC_TITLE] ?: false,
            autoSaveDebounceMs = prefs[Keys.AUTO_SAVE_DEBOUNCE] ?: 1200L,
            autoSaveEnabled = prefs[Keys.AUTO_SAVE_ENABLED] ?: true,
            browserFontSizeSp = prefs[Keys.BROWSER_FONT_SIZE_SP] ?: 14f,
            editorFontSizeSp = prefs[Keys.EDITOR_FONT_SIZE_SP] ?: 16f,
            autoInsertTemplateEnabled = prefs[Keys.AUTO_INSERT_TEMPLATE_ENABLED] ?: true,
            autoInsertTemplate = prefs[Keys.AUTO_INSERT_TEMPLATE] ?: "yyyy-MM-dd",
            defaultFileExtension = defaultExt
        )
    }

    suspend fun setRootTreeUri(uri: Uri?) {
        context.dataStore.edit { prefs ->
            if (uri == null) {
                prefs.remove(Keys.ROOT_URI)
            } else {
                prefs[Keys.ROOT_URI] = uri.toString()
            }
        }
    }

    suspend fun setAutoLinkWeb(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_LINK_WEB] = enabled }
    }

    suspend fun setAutoLinkEmail(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_LINK_EMAIL] = enabled }
    }

    suspend fun setAutoLinkTel(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_LINK_TEL] = enabled }
    }

    suspend fun setSyncTitle(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SYNC_TITLE] = enabled }
    }

    suspend fun setAutoSaveDebounce(ms: Long) {
        context.dataStore.edit { it[Keys.AUTO_SAVE_DEBOUNCE] = ms }
    }

    suspend fun setAutoSaveEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SAVE_ENABLED] = enabled }
    }

    suspend fun setBrowserFontSizeSp(value: Float) {
        context.dataStore.edit { it[Keys.BROWSER_FONT_SIZE_SP] = value }
    }

    suspend fun setEditorFontSizeSp(value: Float) {
        context.dataStore.edit { it[Keys.EDITOR_FONT_SIZE_SP] = value }
    }

    suspend fun setAutoInsertTemplateEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_INSERT_TEMPLATE_ENABLED] = enabled }
    }

    suspend fun setAutoInsertTemplate(pattern: String) {
        context.dataStore.edit { it[Keys.AUTO_INSERT_TEMPLATE] = pattern }
    }

    suspend fun setDefaultFileExtension(extension: String) {
        val normalized = extension.trim().lowercase(Locale.US).let { if (it == "md") "md" else "txt" }
        context.dataStore.edit { it[Keys.DEFAULT_FILE_EXTENSION] = normalized }
    }

    internal val dataStore = context.dataStore
}
