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

    fun addTemplate(text: String) {
        val newId = (_templates.value.maxOfOrNull { it.id } ?: 0L) + 1
        val updated = _templates.value + TemplateItem(newId, text, TemplateMode.TIMEFORMAT)
        persist(updated)
    }

    fun updateTemplate(id: Long, text: String) {
        val updated = _templates.value.map { item ->
            if (item.id == id) item.copy(text = text, mode = TemplateMode.TIMEFORMAT) else item
        }
        persist(updated)
    }

    fun deleteTemplate(id: Long) {
        val updated = _templates.value.filterNot { it.id == id }
        persist(updated)
    }

    fun renderTemplate(item: TemplateItem): String = templateRepository.renderTemplate(item)

    private fun persist(updated: List<TemplateItem>) {
        viewModelScope.launch {
            templateRepository.setTemplates(updated)
        }
    }
}
