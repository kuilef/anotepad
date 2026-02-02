# Anotepad

Minimal local note app for Android built with Kotlin 2.0 and Jetpack Compose. It works directly with files in a folder you choose (SAF), so notes are just `.txt` or `.md` files.

## Features and advantages
- Synchronization with **Google Drive**
- Drive sync controls: Wi‑Fi only, charging only, pause, ignore remote deletes, and manual sync.
- **Undo/redo controls**: on-screen undo/redo buttons (Ctrl+Z / Ctrl+Shift+Z); redo is available only after an undo.
- Folder-based workflow: pick a root directory and browse subfolders; create folders and notes inside it.
- Plain-text first: supports `.txt` and `.md` and keeps notes readable outside the app.
- Fast browsing: batched listing with a small cache; list view or feed view with inline previews.
- Powerful search: recursive search inside the chosen tree with optional regex and contextual snippets.
- Smooth editing: auto-save with debounce, manual save button, and save-on-background.
- Smart file naming: first line becomes the filename for new notes; optional "sync title" keeps name updated.
- Templates: reusable snippets (plain, time-based, numbered); insert on demand; auto-insert a date/time template for new notes.
- Customization: font size controls, sort order, default extension, and linkify toggles (web/email/phone).
- Long tap on the file: shows the menu to open, delete, rename, copy or move file.

## How it works
- **Storage access**: `FileRepository` uses the Storage Access Framework (DocumentFile/DocumentsContract) to read/write files in the user-picked tree. Only `.txt` and `.md` files are listed or searched.
- **Browser**: `BrowserViewModel` loads folder contents in batches, caches recent listings, and exposes list or feed modes. Feed mode reads note text in pages to keep scrolling smooth.
- **Editor**: `EditorViewModel` keeps state, performs debounced auto-save, creates a new file on first save, and optionally renames the file based on the first line (sync title).
- **Search**: `SearchViewModel` walks the tree, reads each note, and matches either a plain query or a regex; results include a short snippet.
- **Templates & preferences**: templates and settings live in DataStore; templates can format current time or auto-numbered items.
- **Drive sync**: WorkManager runs a periodic sync (every 8 hours) and schedules a debounced sync about 10 seconds after local saves. Auto/periodic sync respects Wi-Fi/charging/battery constraints; manual sync only requires network connectivity. Sync auto-selects a Drive folder by name and can be triggered manually from settings.

## Google Drive sync (detailed)

### Folder selection
- Sign in with Google.
- The app searches Drive for folders with the configured name (default: `Anotepad`).
- If exactly one folder is found, it is connected automatically.
- If none are found, a new folder is created.
- If multiple folders are found, the app shows a native list and the user chooses one.
- The chosen folder ID and name are stored locally; the user can disconnect and re-run auto-setup.

### Local metadata
The app maintains a small local sync database:
- `sync_items`: local relative path, Drive file ID, hashes, last modified, last sync time, and state.
- `sync_folders`: mapping of local folder paths to Drive folder IDs.
- `sync_meta`: metadata such as the Drive `startPageToken` and timestamps for full scans.

Each uploaded Drive file stores `appProperties.localRelativePath` so the app can map Drive changes back to local paths.

### Sync algorithm
Sync runs on a manual tap, on a debounced schedule after local edits, and on a periodic WorkManager job.

1) **Pre-checks**
- Sync is skipped if it is disabled or paused.
- A local root folder and a valid Google account are required.

2) **Ensure Drive folder**
- If a Drive folder ID is not stored yet, the app creates the folder (or reuses one from auto-setup) and stores the ID.

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

### Conflicts and deletes
- If the same file changed both locally and remotely since the last sync, the app writes a `conflict ...` copy to avoid data loss.
- Remote deletions are ignored when "Ignore deletions from Drive" is enabled.

## Limitations
- No encrypted file support (unlike Tombo).
- Only `.txt` and `.md` files are supported; other file types are ignored.

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
    - `sync/` — Drive auth/client, sync engine, WorkManager workers
  - `src/main/res/` — strings, themes
- `build.gradle.kts`, `settings.gradle.kts` — build configuration

## Build
```bash
./gradlew assembleDebug
```

## Run on device/emulator
```bash
./gradlew installDebug
```
