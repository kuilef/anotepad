# Delete Refresh and Paste Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make deleted notes disappear immediately with truthful failure reporting, and restore reliable repeated Paste behavior by leaving Android insertion action mode uncustomized.

**Architecture:** Keep SAF operations unchanged and repair browser state reconciliation above the repository. Add focused pure/state tests around terminal refresh and feed deletion, expose deletion failure through a one-shot `SharedFlow`, and reduce the editor's link action callback to selection mode only.

**Tech Stack:** Kotlin 2.0, Android SDK 35, Jetpack Compose Material 3, coroutines/StateFlow/SharedFlow, JUnit 4, Gradle 9.1.

---

### Task 1: Reconcile an empty terminal refresh

**Files:**
- Modify: `app/src/main/java/com/anotepad/ui/BrowserViewModel.kt`
- Create: `app/src/test/java/com/anotepad/ui/BrowserRefreshStateTest.kt`

- [ ] **Step 1: Write the failing terminal-refresh test**

```kotlin
package com.anotepad.ui

import android.net.Uri
import com.anotepad.file.DocumentNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserRefreshStateTest {
    @Test
    fun completeBrowserRefresh_replacesStaleEntriesWithEmptyResult() {
        val stale = DocumentNode("deleted.txt", Uri.parse("content://notes/deleted"), false)
        val state = BrowserState(
            entries = listOf(stale),
            isLoading = true,
            isLoadingMore = true
        )

        val result = completeBrowserRefresh(state, emptyList())

        assertEquals(emptyList<DocumentNode>(), result.entries)
        assertFalse(result.isLoading)
        assertFalse(result.isLoadingMore)
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```text
gradlew.bat :app:testDebugUnitTest --tests com.anotepad.ui.BrowserRefreshStateTest
```

Expected: compilation fails because `completeBrowserRefresh` does not exist.

- [ ] **Step 3: Add the minimal refresh reducer and use it for terminal batches**

Add to `BrowserViewModel.kt`:

```kotlin
internal fun completeBrowserRefresh(
    state: BrowserState,
    entries: List<DocumentNode>
): BrowserState = state.copy(
    entries = entries,
    isLoading = false,
    isLoadingMore = false
)
```

Replace the terminal-batch update with:

```kotlin
} else if (batch.done) {
    _state.update { completeBrowserRefresh(it, collected.toList()) }
}
```

Keep `shouldClear = lastRefreshDirUri != dirUri`; do not make `force` blank the current list.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same focused command. Expected: `BrowserRefreshStateTest` passes.

- [ ] **Step 5: Commit the refresh fix**

```text
git add app/src/main/java/com/anotepad/ui/BrowserViewModel.kt app/src/test/java/com/anotepad/ui/BrowserRefreshStateTest.kt
git commit -m "Fix empty browser refresh reconciliation"
```

### Task 2: Remove successfully deleted nodes from list and feed

**Files:**
- Modify: `app/src/main/java/com/anotepad/ui/FeedManager.kt`
- Modify: `app/src/main/java/com/anotepad/ui/BrowserViewModel.kt`
- Create: `app/src/test/java/com/anotepad/ui/FeedManagerDeleteTest.kt`
- Modify: `app/src/test/java/com/anotepad/ui/BrowserRefreshStateTest.kt`

- [ ] **Step 1: Write failing feed and navigation-race tests**

Add a feed test:

```kotlin
@Test
fun removeNode_removesNodeFromSourceAndLoadedItems() = runTest {
    val deleted = node("deleted.txt")
    val kept = node("kept.txt")
    val manager = FeedManager(readTextPreview = { uri, _ -> uri.lastPathSegment.orEmpty() })
    var state = manager.updateSource(
        BrowserState(),
        listOf(deleted, kept)
    )
    manager.ensureFeedLoaded(
        state = state,
        stateProvider = { state },
        updateState = { reducer -> state = reducer(state) },
        scope = this
    )
    advanceUntilIdle()

    state = manager.removeNode(state, deleted.uri)

    assertEquals(listOf(kept.uri), state.feedItems.map { it.node.uri })
    assertFalse(state.feedHasMore)
}
```

Add pure browser deletion tests:

```kotlin
@Test
fun removeDeletedNode_removesEntryWhenSourceDirectoryIsStillCurrent() {
    val dir = Uri.parse("content://notes/root")
    val deleted = DocumentNode("deleted.txt", Uri.parse("content://notes/deleted"), false)
    val result = removeDeletedNode(
        state = BrowserState(currentDirUri = dir, entries = listOf(deleted)),
        sourceDirUri = dir,
        nodeUri = deleted.uri
    )
    assertEquals(emptyList<DocumentNode>(), result.entries)
}

@Test
fun removeDeletedNode_doesNotMutateAnotherDirectory() {
    val deleted = DocumentNode("deleted.txt", Uri.parse("content://notes/deleted"), false)
    val current = DocumentNode("current.txt", Uri.parse("content://other/current"), false)
    val result = removeDeletedNode(
        state = BrowserState(
            currentDirUri = Uri.parse("content://other"),
            entries = listOf(current)
        ),
        sourceDirUri = Uri.parse("content://notes/root"),
        nodeUri = deleted.uri
    )
    assertEquals(listOf(current), result.entries)
}
```

- [ ] **Step 2: Run both focused test classes and verify RED**

Expected: compilation fails because `FeedManager.removeNode` and `removeDeletedNode` do not exist.

- [ ] **Step 3: Implement minimal list/feed removal**

Add:

```kotlin
internal fun removeDeletedNode(
    state: BrowserState,
    sourceDirUri: Uri,
    nodeUri: Uri
): BrowserState {
    if (state.currentDirUri != sourceDirUri) return state
    return state.copy(entries = state.entries.filterNot { it.uri == nodeUri })
}
```

Add to `FeedManager`:

```kotlin
fun removeNode(state: BrowserState, nodeUri: Uri): BrowserState {
    feedGeneration += 1
    feedFiles = feedFiles.filterNot { it.uri == nodeUri }
    val items = state.feedItems.filterNot { it.node.uri == nodeUri }
    return state.copy(
        feedItems = items,
        feedHasMore = items.size < feedFiles.size,
        feedLoading = false
    )
}
```

On successful repository deletion, update state only if the captured source directory remains current:

```kotlin
val sourceDirUri = _state.value.currentDirUri ?: return
viewModelScope.launch {
    if (fileRepository.deleteFile(node.uri)) {
        _state.update { current ->
            val updated = removeDeletedNode(current, sourceDirUri, node.uri)
            if (updated === current) current else feedManager.removeNode(updated, node.uri)
        }
    } else {
        _events.emit(BrowserUiEvent.DeleteFailed)
    }
}
```

Do not issue an immediate refresh after success.

- [ ] **Step 4: Run focused tests and verify GREEN**

Expected: both test classes pass.

- [ ] **Step 5: Commit successful deletion state handling**

```text
git add app/src/main/java/com/anotepad/ui/FeedManager.kt app/src/main/java/com/anotepad/ui/BrowserViewModel.kt app/src/test/java/com/anotepad/ui/FeedManagerDeleteTest.kt app/src/test/java/com/anotepad/ui/BrowserRefreshStateTest.kt
git commit -m "Update browser state after deleting notes"
```

### Task 3: Report deletion failures once

**Files:**
- Modify: `app/src/main/java/com/anotepad/ui/BrowserViewModel.kt`
- Modify: `app/src/main/java/com/anotepad/ui/BrowserScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-bn/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`
- Modify: `app/src/main/res/values-hi/strings.xml`
- Modify: `app/src/main/res/values-in/strings.xml`
- Modify: `app/src/main/res/values-pt-rBR/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/res/values-tr/strings.xml`
- Modify: `app/src/main/res/values-vi/strings.xml`
- Modify: `app/src/test/java/com/anotepad/ui/BrowserRefreshStateTest.kt`

- [ ] **Step 1: Write a failing deletion-outcome test**

```kotlin
@Test
fun browserDeleteFailureEvent_returnsFailureOnlyWhenDeleteDidNotSucceed() {
    assertEquals(BrowserUiEvent.DeleteFailed, browserDeleteFailureEvent(false))
    assertEquals(null, browserDeleteFailureEvent(true))
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Expected: compilation fails because `BrowserUiEvent` and `browserDeleteFailureEvent` do not exist.

- [ ] **Step 3: Add one-shot event flow and exception handling**

Add:

```kotlin
sealed interface BrowserUiEvent {
    data object DeleteFailed : BrowserUiEvent
}

internal fun browserDeleteFailureEvent(deleted: Boolean): BrowserUiEvent? =
    if (deleted) null else BrowserUiEvent.DeleteFailed
```

Expose:

```kotlin
private val _events = MutableSharedFlow<BrowserUiEvent>(extraBufferCapacity = 1)
val events: SharedFlow<BrowserUiEvent> = _events.asSharedFlow()
```

In `deleteFile`, emit `DeleteFailed` for `false`, `SecurityException`, and `IllegalArgumentException`. Re-throw `CancellationException`.

In `BrowserScreen`, collect once:

```kotlin
val deleteFailedMessage = stringResource(R.string.error_delete_failed)
LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
        when (event) {
            BrowserUiEvent.DeleteFailed ->
                Toast.makeText(context, deleteFailedMessage, Toast.LENGTH_SHORT).show()
        }
    }
}
```

Add `error_delete_failed` to base and all ten locale overlays. Do not add it to `values-night`, which contains theme resources rather than localized strings.

Use these exact resources:

```xml
<!-- values -->
<string name="error_delete_failed">Could not delete the item.</string>

<!-- values-bn -->
<string name="error_delete_failed">আইটেমটি মুছে ফেলা যায়নি।</string>

<!-- values-de -->
<string name="error_delete_failed">Element konnte nicht gelöscht werden.</string>

<!-- values-es -->
<string name="error_delete_failed">No se pudo eliminar el elemento.</string>

<!-- values-fr -->
<string name="error_delete_failed">Impossible de supprimer l’élément.</string>

<!-- values-hi -->
<string name="error_delete_failed">आइटम को हटाया नहीं जा सका।</string>

<!-- values-in -->
<string name="error_delete_failed">Item tidak dapat dihapus.</string>

<!-- values-pt-rBR -->
<string name="error_delete_failed">Não foi possível excluir o item.</string>

<!-- values-ru -->
<string name="error_delete_failed">Не удалось удалить элемент.</string>

<!-- values-tr -->
<string name="error_delete_failed">Öğe silinemedi.</string>

<!-- values-vi -->
<string name="error_delete_failed">Không thể xóa mục.</string>
```

- [ ] **Step 4: Verify locale key parity**

Run a PowerShell key-parity check comparing every localized `strings.xml` with `values/strings.xml`. Expected: no missing `error_delete_failed` key.

- [ ] **Step 5: Run focused tests and verify GREEN**

Expected: `BrowserRefreshStateTest` passes.

- [ ] **Step 6: Commit failure reporting**

```text
git add app/src/main/java/com/anotepad/ui/BrowserViewModel.kt app/src/main/java/com/anotepad/ui/BrowserScreen.kt app/src/main/res/values*/strings.xml app/src/test/java/com/anotepad/ui/BrowserRefreshStateTest.kt
git commit -m "Report note deletion failures"
```

### Task 4: Restrict Open link customization to selection action mode

**Files:**
- Modify: `app/src/main/java/com/anotepad/ui/AnotepadEditText.kt`
- Create: `app/src/test/java/com/anotepad/ui/LinkActionModePolicyTest.kt`

- [ ] **Step 1: Write the failing action-mode policy test**

```kotlin
package com.anotepad.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkActionModePolicyTest {
    @Test
    fun linkActionModeTargets_customizesSelectionOnly() {
        assertEquals(setOf(LinkActionModeTarget.SELECTION), linkActionModeTargets)
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Expected: compilation fails because `LinkActionModeTarget` and `linkActionModeTargets` do not exist.

- [ ] **Step 3: Implement selection-only policy and callback lifecycle**

Add:

```kotlin
internal enum class LinkActionModeTarget {
    SELECTION
}

internal val linkActionModeTargets = setOf(LinkActionModeTarget.SELECTION)
```

Install only the selection callback:

```kotlin
if (LinkActionModeTarget.SELECTION in linkActionModeTargets) {
    setCustomSelectionActionModeCallback(linkActionModeCallback)
}
```

Remove `setCustomInsertionActionModeCallback(...)`.

Change callback creation:

```kotlin
override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true

override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
    return updateOpenLinkMenuItem(menu)
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Expected: `LinkActionModePolicyTest` passes.

- [ ] **Step 5: Commit Paste compatibility change**

```text
git add app/src/main/java/com/anotepad/ui/AnotepadEditText.kt app/src/test/java/com/anotepad/ui/LinkActionModePolicyTest.kt
git commit -m "Restore standard insertion action mode"
```

### Task 5: Full verification and review

**Files:**
- Review all files changed since commit `32c2688`

- [ ] **Step 1: Run focused regression tests**

```text
gradlew.bat :app:testDebugUnitTest --tests com.anotepad.ui.BrowserRefreshStateTest --tests com.anotepad.ui.FeedManagerDeleteTest --tests com.anotepad.ui.LinkActionModePolicyTest
```

Expected: all focused tests pass.

- [ ] **Step 2: Run the complete unit suite**

```text
gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Build the debug APK**

```text
gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run repository checks**

```text
git diff --check 32c2688..HEAD
git status --short
```

Expected: no whitespace errors and no uncommitted tracked changes.

- [ ] **Step 5: Review against the design spec**

Confirm:

- terminal empty refresh clears stale entries;
- successful delete updates list and feed without immediate refresh;
- navigation race is guarded;
- failed delete leaves state intact and emits one Toast event;
- all locale overlays contain the new error;
- insertion callback is absent;
- selection Open link remains installed;
- read-mode selection behavior is unchanged.

- [ ] **Step 6: Request final code review**

Review the complete diff from `32c2688` to `HEAD`, fix every Critical or Important issue, and repeat verification after any fix.
