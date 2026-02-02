package com.anotepad.data

import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TemplateRepository(private val preferencesRepository: PreferencesRepository) {
    private object Keys {
        val TEMPLATES = stringPreferencesKey("templates_v1")
    }

    fun templatesFlow(): Flow<List<TemplateItem>> =
        preferencesRepository.dataStore.data.map { prefs ->
            decodeTemplates(prefs[Keys.TEMPLATES])
        }

    suspend fun setTemplates(items: List<TemplateItem>) {
        preferencesRepository.dataStore.edit { prefs ->
            prefs[Keys.TEMPLATES] = encodeTemplates(items)
        }
    }

    suspend fun ensureDefaults() {
        val current = templatesFlow().first()
        if (current.isNotEmpty()) return
        val defaults = listOf(
            TemplateItem(1, "yyyy/MM/dd HH:mm:ss", TemplateMode.TIMEFORMAT),
            TemplateItem(2, "yyyy/MM/dd", TemplateMode.TIMEFORMAT),
            TemplateItem(3, "- ", TemplateMode.NORMAL)
        )
        setTemplates(defaults)
    }

    fun renderTemplate(item: TemplateItem, number: Int? = null): String {
        val base = when (item.mode) {
            TemplateMode.NORMAL, TemplateMode.WITHNUMBER -> item.text
            TemplateMode.TIMEFORMAT, TemplateMode.TIME_NUMBER -> formatTime(item.text)
        }
        return when (item.mode) {
            TemplateMode.WITHNUMBER, TemplateMode.TIME_NUMBER -> formatNumber(base, number ?: 1)
            else -> base
        }
    }

    private fun formatTime(pattern: String): String {
        return try {
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
        } catch (_: Exception) {
            pattern
        }
    }

    private fun formatNumber(text: String, number: Int): String {
        val regex = Regex("%[ 0]?\\d+d|%d")
        val match = regex.find(text) ?: return text
        val start = match.range.first
        if (start > 0 && text[start - 1] == '\\') {
            return text.replace("\\%", "%")
        }
        return try {
            text.replaceFirst(match.value, String.format(match.value, number))
        } catch (_: Exception) {
            text
        }
    }

    private fun encodeTemplates(items: List<TemplateItem>): String {
        return items.joinToString("\n") { item ->
            val payload = Base64.encodeToString(item.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "${item.id}|${item.mode.name}|$payload"
        }
    }

    private fun decodeTemplates(raw: String?): List<TemplateItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            val mode = runCatching { TemplateMode.valueOf(parts[1]) }.getOrNull() ?: TemplateMode.NORMAL
            val text = runCatching { String(Base64.decode(parts[2], Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull() ?: ""
            TemplateItem(id, text, mode)
        }
    }
}
