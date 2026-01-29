# Anotepad

Minimal local note app for Android built with Kotlin 2.0 and Jetpack Compose. It works directly with files in a folder you choose (SAF), so notes are just `.txt` or `.md` files.

## Features and advantages
- Folder-based workflow: pick a root directory and browse subfolders; create folders and notes inside it.
- Plain-text first: supports `.txt` and `.md` and keeps notes readable outside the app.
- Fast browsing: batched listing with a small cache; list view or feed view with inline previews.
- Powerful search: recursive search inside the chosen tree with optional regex and contextual snippets.
- Smooth editing: auto-save with debounce, manual save button, and save-on-background.
- **Undo/redo controls**: on-screen undo/redo buttons (Ctrl+Z / Ctrl+Shift+Z); redo is available only after an undo.
- Smart file naming: first line becomes the filename for new notes; optional "sync title" keeps name updated.
- Templates: reusable snippets (plain, time-based, numbered); insert on demand; auto-insert a date/time template for new notes.
- Customization: font size controls, sort order, default extension, and linkify toggles (web/email/phone).

## How it works
- **Storage access**: `FileRepository` uses the Storage Access Framework (DocumentFile/DocumentsContract) to read/write files in the user-picked tree. Only `.txt` and `.md` files are listed or searched.
- **Browser**: `BrowserViewModel` loads folder contents in batches, caches recent listings, and exposes list or feed modes. Feed mode reads note text in pages to keep scrolling smooth.
- **Editor**: `EditorViewModel` keeps state, performs debounced auto-save, creates a new file on first save, and optionally renames the file based on the first line (sync title).
- **Search**: `SearchViewModel` walks the tree, reads each note, and matches either a plain query or a regex; results include a short snippet.
- **Templates & preferences**: templates and settings live in DataStore; templates can format current time or auto-numbered items.

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
