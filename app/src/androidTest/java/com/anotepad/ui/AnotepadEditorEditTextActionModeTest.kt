package com.anotepad.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnotepadEditorEditTextActionModeTest {
    @Test
    fun customizesSelectionButLeavesInsertionStandard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var editText: AnotepadEditorEditText

        instrumentation.runOnMainSync {
            editText = AnotepadEditorEditText(instrumentation.targetContext)
        }

        assertNotNull(editText.customSelectionActionModeCallback)
        assertNull(editText.customInsertionActionModeCallback)
    }
}
