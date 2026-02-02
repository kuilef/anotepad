package com.anotepad.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anotepad.data.TemplateItem
import com.anotepad.data.TemplateMode
import com.anotepad.data.TemplateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TemplatesViewModel(private val templateRepository: TemplateRepository) : ViewModel() {
    private val _templates = MutableStateFlow<List<TemplateItem>>(emptyList())
    val templates: StateFlow<List<TemplateItem>> = _templates.asStateFlow()

    init {
        viewModelScope.launch {
            templateRepository.ensureDefaults()
            templateRepository.templatesFlow().collectLatest { items ->
                _templates.value = items
            }
        }
    }

    fun addTemplate(text: String, mode: TemplateMode) {
        val newId = (_templates.value.maxOfOrNull { it.id } ?: 0L) + 1
        val updated = _templates.value + TemplateItem(newId, text, mode)
        persist(updated)
    }

    fun updateTemplate(id: Long, text: String, mode: TemplateMode) {
        val updated = _templates.value.map { item ->
            if (item.id == id) item.copy(text = text, mode = mode) else item
        }
        persist(updated)
    }

    fun deleteTemplate(id: Long) {
        val updated = _templates.value.filterNot { it.id == id }
        persist(updated)
    }

    fun renderTemplate(item: TemplateItem, number: Int? = null): String {
        return templateRepository.renderTemplate(item, number)
    }

    private fun persist(updated: List<TemplateItem>) {
        viewModelScope.launch {
            templateRepository.setTemplates(updated)
        }
    }
}
