package com.anotepad

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

class IncomingShareViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    val manager = IncomingShareManager(savedStateHandle)
}
