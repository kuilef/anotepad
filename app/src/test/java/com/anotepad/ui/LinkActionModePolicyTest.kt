package com.anotepad.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkActionModePolicyTest {
    @Test
    fun linkActionModeTargets_customizesSelectionOnly() {
        assertEquals(
            setOf(LinkActionModeTarget.SELECTION),
            linkActionModeTargets
        )
    }
}
