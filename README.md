# Anotepad

Minimal local note app for Android built with Kotlin 2.0 and Jetpack Compose. It works directly with files in a folder you choose (SAF), so notes are just `.txt` files.

<p align="center">
  <img src="screenshots/phone/phone%202.png" alt="Anotepad screenshot 1" width="23%" />
  <img src="screenshots/phone/phone%204.png" alt="Anotepad screenshot 2" width="23%" />
  <img src="screenshots/phone/phone%205.png" alt="Anotepad screenshot 3" width="23%" />
</p>

## Features and advantages
- Synchronization with **Google Drive**
- Optional auto sync on app start
- **Undo/redo controls**: on-screen undo/redo buttons (Ctrl+Z / Ctrl+Shift+Z); redo is available only after an undo.
- Folder-based workflow: pick a root directory and browse subfolders; create folders and notes inside it.
- Plain-text first: supports `.txt` and keeps notes readable outside the app. 
- Fast browsing: batched listing with a small cache; list view or feed view with inline previews.
- Powerful search: recursive search inside the chosen tree with optional regex and contextual snippets.
- Smooth editing: auto-save with debounce, manual save button, and save-on-background.
- Smart file naming: first line becomes the filename for new notes; optional "sync title" keeps name updated.
- Templates: reusable snippets (plain, time-based, numbered); insert on demand; auto-insert a date/time template for new notes.
- Customization: font size controls, sort order, default extension, and linkify toggles (web/email/phone).
- Long tap on the file: shows the menu to open, delete, rename, copy or move file.

## How it works
- **Storage access**: `FileRepository` uses the Storage Access Framework (DocumentFile/DocumentsContract) to read/write files in the user-picked tree. Only `.txt` files are listed or searched.
- **Browser**: `BrowserViewModel` loads folder contents in batches, caches recent listings, and exposes list or feed modes. Feed mode reads note text in pages to keep scrolling smooth.
- **Editor**: `EditorViewModel` keeps state, performs debounced auto-save, creates a new file on first save, and optionally renames the file based on the first line (sync title).
- **Search**: `SearchViewModel` walks the tree, reads each note, and matches either a plain query or a regex; results include a short snippet.
- **Templates & preferences**: templates and settings live in DataStore; templates can format current time or auto-numbered items.
- **Drive sync**: WorkManager runs periodic/debounced/manual/startup modes. Internally, sync uses a modular engine (`SyncPreflight`, initial/push/pull use-cases, conflict/delete/path resolvers) with explicit operation planning and execution.

## Permissions shown on Google Play

Google Play lists the following access:

This app has access to:
Other
view network connections
full network access
run at startup
prevent device from sleeping
Google Play license check

Short explanation (and when it applies):
- **view network connections** (`ACCESS_NETWORK_STATE`): used to detect connectivity for Drive sync. Needed only when Drive sync is enabled.
- **full network access** (`INTERNET`): required for Google Drive API calls, Google sign-in, and sync. Needed only when Drive sync is enabled.
- **run at startup** (`RECEIVE_BOOT_COMPLETED` via WorkManager): lets periodic sync resume after device reboot. Only used when Drive sync is enabled.
- **prevent device from sleeping** (`WAKE_LOCK` via WorkManager): used briefly while a sync task is running so the system doesn't kill it mid-sync. It does **not** keep the device awake for hours; it is held only during the short sync window and then released. Only used when Drive sync is enabled.
- **Google Play license check**: may be reported by Google Play Services dependencies (used for Google sign-in). The app does not block usage based on license checks, and this is only relevant when Drive sync is enabled.

If Drive sync is disabled, the app does not schedule background work and only uses local storage.

## Google Drive sync (detailed)

For engineering-focused implementation details (module map, token semantics, conflict/delete algorithms, retry model, test fixtures), see `README.llm.md`.

### Drive visibility (scope)
Anotepad uses Google Drive primarily as a backup. With the restricted `drive.file` scope, all devices running Anotepad will see and sync files created by Anotepad, but files added to the Drive folder by other apps (or via the Drive web UI) are not visible to the app.

### Folder selection
- Sign in with Google.
- The app looks for an app-created marker file (`anotepad_config.json`) to find the Drive folder it created earlier.
- If exactly one marker is found, its parent folder is connected automatically.
- If none are found, a new folder is created and the marker file is written inside it.
- If multiple markers are found, the app shows a native list and the user chooses one.
- The chosen folder ID and name are stored locally; the user can disconnect and re-run auto-setup.

### Local metadata
The app maintains a small local sync database:
- `sync_items`: local relative path, Drive file ID, hashes, last modified, last sync time, and state.
- `sync_folders`: mapping of local folder paths to Drive folder IDs.
- `sync_meta`: metadata such as the Drive `startPageToken` and timestamps for full scans.

Each uploaded Drive file stores `appProperties.localRelativePath` so the app can map Drive changes back to local paths.

### Sync algorithm
Sync runs on a manual tap, on a debounced schedule after local edits, on a periodic WorkManager job, and optionally once on app start.

Internal sync modules:
- `SyncPreflight`: guard checks + Drive folder resolution.
- `InitialSyncUseCase`: first-run timestamp merge.
- `IncrementalPushUseCase`: local-to-Drive updates/deletes.
- `IncrementalPullUseCase`: Drive Changes API apply (create/update/move/delete).
- `FolderPathResolver`, `ConflictResolver`, `DeleteResolver`: path mapping, conflict safety, deletion safety.
- `SyncPlan` + `SyncOperation` + `SyncExecutor`: explicit operation planning/execution.

1) **Pre-checks**
- Sync is skipped if it is disabled or paused.
- A local root folder and a valid Google account are required.

2) **Ensure Drive folder**
- If a Drive folder ID is not stored yet, sync does not start. The user must run "Find or create" in settings, which either reuses the marker folder or creates a new one.

3) **Initial sync (first run or after reset)**
When there is no saved `startPageToken`, the app performs a one-time bootstrap:
- **Remote scan**: walk the Drive folder tree and build a map of `relative path -> Drive file`.
- **Merge by timestamp**:
  - Local only: upload to Drive (creates a new Drive file).
  - Remote only: download to local.
  - Both exist: compare timestamps. If local is newer, update the existing Drive file; if Drive is newer, overwrite the local file.
- Store a fresh `startPageToken` for incremental changes going forward.

This prevents duplicate files on Drive when local files already exist.

4) **Regular sync (incremental)**
- **Push local changes**: upload modified files, create missing files, and optionally trash/delete Drive files that were removed locally (policy-driven).
- **Pull remote changes**: use the Drive Changes API with `startPageToken` to apply adds/updates/moves/deletes. Folder moves are reflected locally.
- Token safety: after paged `changes.list`, sync stores the `newStartPageToken` returned by the last page and does not overwrite it with a separate token call. This avoids missing changes between calls.

### Conflicts and deletes
- If the same file changed both locally and remotely since the last sync, the app writes a `conflict ...` copy to avoid data loss.
- Remote deletions are ignored when "Ignore deletions from Drive" is enabled.

## Limitations
- No encrypted file support (unlike Tombo).
- Only `.txt` files are supported; other file types are ignored.

## Tech stack
- Kotlin 2.0
- Android / Jetpack Compose
- DataStore
- Gradle Kotlin DSL

## Project structure
- `app/` — main Android app
  - `src/main/java/com/anotepad/`
    - `MainActivity.kt` — entry point, theme, navigation
    - `AppNav.kt` — Compose navigation graph
    - `ui/` — Compose screens and ViewModels
    - `data/` — DataStore models and repositories
    - `file/` — SAF file access
    - `sync/` — Drive auth/client, scheduler/worker glue
    - `sync/engine/` — sync preflight, use-cases, conflict/delete/path resolvers, operation executor
  - `src/main/res/` — strings, themes
- `build.gradle.kts`, `settings.gradle.kts` — build configuration

## Privacy policy link
<a href="https://anotepad.tirmudam.org/PRIVACY_POLICY">https://anotepad.tirmudam.org/PRIVACY_POLICY</a>

## License
Licensed under the MIT licenst - see LICENSE.

Trademark policy: see [TRADEMARK.md](TRADEMARK.md).
