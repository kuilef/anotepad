package com.anotepad.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSaveGuardTest {

    @Test
    fun runNonCancellableWrite_completesBlock_evenWhenParentIsCancelled() = runTest {
        var completed = false
        val job = launch {
            runNonCancellableWrite {
                delay(100)
                completed = true
            }
        }

        advanceTimeBy(10)
        job.cancel()
        advanceUntilIdle()

        assertTrue(completed)
    }
}
