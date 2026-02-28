package com.anotepad

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class IncomingShareViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    val manager = IncomingShareManager(savedStateHandle)

    init {
        viewModelScope.launch {
            manager.restorePendingShare()
        }
    }
}
