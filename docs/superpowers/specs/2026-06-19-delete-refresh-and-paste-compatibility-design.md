# Delete Refresh and Paste Compatibility Design

Date: 2026-06-19

## Goal

Fix two client-reported Android bugs without changing unrelated browser, editor, read-mode, filename, or synchronization behavior:

1. A successfully deleted note can remain visible until the application is reopened.
2. Text can be pasted into one note, but the Paste action may not appear when the same clipboard content is pasted into a newly opened note.

The deletion issue has a confirmed state-reconciliation defect in the current code. The Paste issue is device-dependent and not locally reproduced, so its fix must minimize customization of Android's insertion action mode while preserving the existing Open link action for selected URLs.

## Non-goals

- No changes to automatic saving or blank-note creation semantics.
- No changes to `syncTitle`, filename prompting, sorting, navigation, or sync deletion policies.
- No custom clipboard implementation or custom Paste menu.
- No broad refactor of `BrowserViewModel`, `FeedManager`, or `AnotepadEditText`.
- No attempt to guarantee Open link when only a collapsed cursor is placed inside a URL. Open link for selected URL text remains required.

## Confirmed deletion failure

`BrowserViewModel.refresh()` collects SAF listing batches into a new local list. When SAF reports an empty terminal batch, the current implementation clears the loading flags but does not assign the empty collected result to `BrowserState.entries`.

This leaves the previous entries visible when the refreshed directory is empty, including the common case where the user deletes its last note. Reopening the application constructs fresh state, which explains why the note then disappears.

`BrowserViewModel.deleteFile()` also ignores the Boolean returned by `FileRepository.deleteFile()`, so the UI cannot distinguish a successful deletion from a provider refusal or failure.

## Deletion design

### Refresh reconciliation

At successful completion of every listing operation, `BrowserState.entries` must equal the complete list collected for that refresh, including an empty list.

The terminal `ChildBatch(done = true)` path will therefore assign:

- `entries = collected.toList()`
- `isLoading = false`
- `isLoadingMore = false`

The existing progressive batch updates remain unchanged.

`force = true` will continue to mean bypass the listing cache. It will not automatically blank the visible list at refresh start. This avoids flicker and preserves the current incremental loading behavior.

The refresh implementation must retain cancellation safety: a cancelled older refresh must not publish a final result after a newer refresh or directory navigation has started.

### Successful deletion

`BrowserViewModel.deleteFile(node)` will capture the directory URI in which the action began and inspect the repository result.

If deletion succeeds and that directory is still current:

1. Remove the node from `BrowserState.entries` immediately.
2. Remove it from the feed through a dedicated `FeedManager.removeNode(...)` operation.
3. Keep the current list/feed scroll state where possible.

`FeedManager.removeNode(...)` must update both its internal `feedFiles` source and public feed state. Filtering only `BrowserState.feedItems` is insufficient because a later page load could otherwise reintroduce the deleted node.

The initial fix will not run an unconditional immediate SAF refresh after a successful deletion. The storage layer already invalidates its list cache after a successful delete, and the Boolean result confirms that the provider accepted the operation. Avoiding an immediate query also prevents an eventually consistent provider from temporarily returning the deleted document again.

The existing manual refresh, application recreation, sync completion refresh, and subsequent navigation remain reconciliation paths. If later evidence shows a provider returns success without actually deleting, that provider-specific behavior should be diagnosed separately rather than hidden by optimistic UI rollback.

### Failed deletion

If deletion returns `false` or throws a recoverable storage exception:

- Do not remove the node from the visible list or feed.
- Emit a one-shot browser UI event.
- Show a localized short Toast indicating that the item could not be deleted.
- Do not report success.

The error event must not be stored as persistent screen state, so rotation or recomposition does not repeatedly display it.

### Navigation race

If deletion finishes after the user has navigated to another directory, it must not filter that directory's entries or feed. The operation compares the captured source directory with the current directory before applying the optimistic state change.

## Paste compatibility design

### Current risk

`AnotepadEditorEditText` installs the same custom `ActionMode.Callback` for both:

- text selection action mode;
- text insertion action mode.

Official Android behavior normally populates the insertion menu with supported Select All, Paste, Paste as plain text, and Replace actions before allowing a custom callback to extend it. Therefore the callback is not proven to be the root cause on AOSP.

However, the bug is specific to the insertion popup, appeared after this customization was introduced, and can depend on OEM framework behavior. The safest compatibility change is to stop customizing insertion action mode entirely.

### Callback change

Keep:

```kotlin
setCustomSelectionActionModeCallback(linkActionModeCallback)
```

Remove:

```kotlin
setCustomInsertionActionModeCallback(linkActionModeCallback)
```

The selection callback will continue to add Open link when the selected range belongs to one URL span. Standard selection actions such as Cut, Copy, Paste, and Share remain handled by Android.

The Open link menu item will be added or removed from `onPrepareActionMode()`, matching the Android API contract for extending the default menu. `onCreateActionMode()` will only allow creation and will not mutate the menu.

No clipboard content will be consumed, replaced, or cleared by application code.

### Accepted behavior trade-off

After this change:

- Selecting URL text must still expose Open link.
- A collapsed cursor inside a URL may no longer expose Open link through insertion mode.
- Standard Paste behavior has priority over the collapsed-cursor Open link shortcut.

This trade-off keeps link handling available without intercepting the Android menu involved in the reported bug.

## Testing strategy

### JVM tests

Add focused browser state tests using fakes for repository/listing behavior:

1. A terminal empty refresh replaces stale entries with an empty list.
2. A successful deletion removes the node from list state.
3. A successful deletion removes the node from feed source and loaded feed items.
4. A failed deletion leaves state unchanged and emits one error event.
5. A deletion completed after directory navigation does not mutate the new directory.
6. Refresh cancellation does not allow an obsolete result to overwrite a newer directory state.

If direct `BrowserViewModel` construction is unnecessarily difficult because of concrete dependencies, extract only the smallest pure state helper needed for deterministic tests. Do not broaden the production refactor.

Add focused tests for link-action menu eligibility where feasible as pure functions:

- selected range wholly inside one URL span is eligible for Open link;
- collapsed selection is not required after insertion callback removal;
- selection spanning multiple URLs is not eligible.

### Instrumented/manual Android checks

The native Android action mode behavior must be checked on a device or emulator because JVM tests cannot validate OEM text-selection menus.

Required scenarios:

1. Copy text once.
2. Paste it into note A.
3. Return to the browser and create note B.
4. Tap at an insertion point and confirm Paste appears.
5. Paste the same clipboard content again.
6. Repeat with an existing second note.
7. Select a URL and confirm Open link remains available.
8. Confirm Copy still works in selectable read mode.
9. Delete the only note in a folder and confirm the empty state appears immediately.
10. Delete one of several notes in list mode and feed mode and confirm it disappears immediately.
11. Force a deletion failure where practical and confirm the error appears while the note remains visible.

When possible, repeat the Paste scenarios on the client's device manufacturer and Android version. Those details should be requested for regression coverage but are not required to implement the compatibility reduction.

### Build verification

Run:

```text
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
git diff --check
```

If an Android device is available, run the relevant instrumented tests and the manual matrix above.

## Localization

Add one deletion-failure string to the base resources and every currently supported locale overlay. The message should be generic enough for files and folders, because the same browser action handles both.

## Rollout and diagnostics

The fixes are intentionally independent:

- deletion state reconciliation and error handling;
- removal of insertion action mode customization.

They should be implemented and tested as separate logical changes so a regression can be isolated.

If the Paste problem persists on the client device, collect:

- device manufacturer and model;
- Android version;
- keyboard application and version;
- whether the clipboard item appears in the keyboard clipboard panel;
- whether long-press shows any menu or no menu at all;
- a screen recording of note A to note B.

The next diagnostic step would inspect focus, editability, and action-mode lifecycle on that device. A custom Paste implementation remains a last resort because it would duplicate framework behavior and introduce accessibility, formatting, and security edge cases.
