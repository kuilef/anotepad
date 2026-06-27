package com.anotepad.ui

import android.content.Context
import android.text.SpannableString
import android.text.style.URLSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import com.anotepad.R
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun selectionActionModeAddsOpenLinkOnCreateWithoutClearingStandardItems() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val linkText = SpannableString("https://example.com").apply {
            setSpan(URLSpan("https://example.com"), 0, length, 0)
        }
        lateinit var editText: AnotepadEditorEditText
        lateinit var menu: Menu

        instrumentation.runOnMainSync {
            editText = AnotepadEditorEditText(context).apply {
                setText(linkText)
                setSelection(0, linkText.length)
            }
            menu = PopupMenu(context, editText).menu.apply {
                add(Menu.NONE, android.R.id.copy, Menu.NONE, "Copy")
            }

            val callback = editText.customSelectionActionModeCallback
            assertNotNull(callback)
            assertTrue(callback.onCreateActionMode(FakeActionMode(context, menu), menu))
        }

        assertNotNull(menu.findItem(android.R.id.copy))
        assertTrue(menu.containsTitle(context.getString(R.string.action_open_link)))
    }

    @Test
    fun selectionActionModeDoesNotAddOpenLinkForPlainTextSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var editText: AnotepadEditorEditText
        lateinit var menu: Menu

        instrumentation.runOnMainSync {
            editText = AnotepadEditorEditText(context).apply {
                setText("plain text")
                setSelection(0, text.length)
            }
            menu = PopupMenu(context, editText).menu

            val callback = editText.customSelectionActionModeCallback
            assertNotNull(callback)
            assertTrue(callback.onCreateActionMode(FakeActionMode(context, menu), menu))
        }

        assertFalse(menu.containsTitle(context.getString(R.string.action_open_link)))
    }

    @Test
    fun nonCollapsedSelectionDoesNotForceScrollToSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var editText: AnotepadEditorEditText
        var scrollAfterSelection = -1

        instrumentation.runOnMainSync {
            editText = AnotepadEditorEditText(instrumentation.targetContext).apply {
                layoutParams = ViewGroup.LayoutParams(400, 80)
                setSingleLine(false)
                setText((1..80).joinToString(separator = "\n") { line -> "line $line" })
                measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 400, 80)
                setSelection(0)
                scrollTo(0, 0)
            }

            val textLength = editText.text.length
            editText.setSelection(textLength - 6, textLength)
            scrollAfterSelection = editText.scrollY
        }

        assertEquals(0, scrollAfterSelection)
    }

    private fun Menu.containsTitle(title: String): Boolean {
        return (0 until size()).any { index -> getItem(index).title == title }
    }

    private class FakeActionMode(
        context: Context,
        private val actionMenu: Menu
    ) : ActionMode() {
        private val inflater = MenuInflater(context)
        private var titleValue: CharSequence? = null
        private var subtitleValue: CharSequence? = null
        private var customViewValue: View? = null

        override fun setTitle(title: CharSequence?) {
            titleValue = title
        }

        override fun setTitle(resId: Int) = Unit

        override fun setSubtitle(subtitle: CharSequence?) {
            subtitleValue = subtitle
        }

        override fun setSubtitle(resId: Int) = Unit

        override fun setCustomView(view: View?) {
            customViewValue = view
        }

        override fun invalidate() = Unit

        override fun finish() = Unit

        override fun getMenu(): Menu = actionMenu

        override fun getTitle(): CharSequence? = titleValue

        override fun getSubtitle(): CharSequence? = subtitleValue

        override fun getCustomView(): View? = customViewValue

        override fun getMenuInflater(): MenuInflater = inflater
    }
}
