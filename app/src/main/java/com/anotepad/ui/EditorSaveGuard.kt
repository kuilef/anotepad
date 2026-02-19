package com.anotepad.ui

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

suspend fun runNonCancellableWrite(block: suspend () -> Unit) {
    withContext(NonCancellable) {
        block()
    }
}
