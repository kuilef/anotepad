package com.anotepad.ui

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedAnnotatedTextTest {
    @Test
    fun feedText_whenSyncTitleOff_boldsFileNameWithoutTxtExtensionOnly() {
        val text = buildFeedAnnotatedText(
            fileName = "Meeting.txt",
            text = "First line\nSecond line",
            syncTitle = false
        )

        assertEquals("Meeting\nFirst line\nSecond line", text.text)
        assertEquals(1, text.spanStyles.size)
        val boldRange = text.spanStyles.single()
        assertEquals(0, boldRange.start)
        assertEquals("Meeting".length, boldRange.end)
        assertEquals(FontWeight.Bold, boldRange.item.fontWeight)
    }

    @Test
    fun feedText_whenSyncTitleOff_removesTxtExtensionCaseInsensitively() {
        val text = buildFeedAnnotatedText(
            fileName = "meeting.TXT",
            text = "Preview",
            syncTitle = false
        )

        assertEquals("meeting\nPreview", text.text)
        assertEquals("meeting".length, text.spanStyles.single().end)
    }

    @Test
    fun feedText_whenSyncTitleOff_keepsNonTxtExtensions() {
        val text = buildFeedAnnotatedText(
            fileName = "notes.md",
            text = "Preview",
            syncTitle = false
        )

        assertEquals("notes.md\nPreview", text.text)
        assertEquals("notes.md".length, text.spanStyles.single().end)
    }

    @Test
    fun feedText_whenSyncTitleOff_omitsSeparatorWhenPreviewIsEmpty() {
        val text = buildFeedAnnotatedText(
            fileName = "Empty.txt",
            text = "",
            syncTitle = false
        )

        assertEquals("Empty", text.text)
        assertEquals("Empty".length, text.spanStyles.single().end)
    }

    @Test
    fun feedText_whenSyncTitleOn_boldsFirstPreviewLineAndIgnoresFileName() {
        val text = buildFeedAnnotatedText(
            fileName = "Meeting.txt",
            text = "First line\nSecond line",
            syncTitle = true
        )

        assertEquals("First line\nSecond line", text.text)
        assertEquals(1, text.spanStyles.size)
        val boldRange = text.spanStyles.single()
        assertEquals(0, boldRange.start)
        assertEquals("First line".length, boldRange.end)
        assertEquals(FontWeight.Bold, boldRange.item.fontWeight)
    }

    @Test
    fun feedText_whenSyncTitleOn_normalizesWindowsLineEndingsBeforeChoosingTitle() {
        val text = buildFeedAnnotatedText(
            fileName = "Meeting.txt",
            text = "First line\r\nSecond line",
            syncTitle = true
        )

        assertEquals("First line\nSecond line", text.text)
        assertEquals("First line".length, text.spanStyles.single().end)
    }

    @Test
    fun feedText_whenSyncTitleOn_keepsEmptyPreviewEmpty() {
        val text = buildFeedAnnotatedText(
            fileName = "Empty.txt",
            text = "",
            syncTitle = true
        )

        assertEquals("", text.text)
    }
}
