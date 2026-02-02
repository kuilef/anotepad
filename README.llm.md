# LLM Notes

## Project snapshot
- Android app module: `app`
- Language: Kotlin 2.0
- UI: Jetpack Compose (Material 3)
- Build: Gradle Kotlin DSL
- Min/target SDK: 29/35

## Architecture
- MVVM: Compose UI in `ui/*Screen.kt`, state/logic in `*ViewModel`.
- Navigation: Compose Navigation in `AppNav.kt` (browser, editor, search, templates, settings, sync).
- Storage: SAF via `FileRepository` (DocumentFile/DocumentsContract); only `.txt`/`.md`; batched listing + small LRU cache (TTL 15s).
- Preferences/templates: DataStore in `PreferencesRepository` + `TemplateRepository` (default templates on first run).
- Sync: WorkManager jobs in `SyncScheduler` (auto debounced 10s, periodic 8h, manual expedited). Sync state stored in Room (`sync.db`) via `SyncRepository`.
- Drive: Google Sign-In + Drive REST calls in `DriveClient` (OkHttp). Auto-connect searches for folders by name; if none, creates one; if multiple, user picks. Each file stores `appProperties.localRelativePath`.
- Dependency wiring: `MainActivity` → `AppDependencies` → `AppViewModelFactory`.

## Sync behavior notes
- Initial sync compares timestamps (local vs Drive) and uploads/downloads accordingly.
- Incremental sync pushes local changes, then applies Drive Changes API.
- Conflicts create a new file named like `name (conflict yyyy-MM-dd HH-mm).ext`.
- Remote deletions move local files into `.trash/` unless "Ignore deletions from Drive" is enabled.

## Editor notes
- Autosave is debounced (default 1200 ms) with manual save + save-on-background.
- New file name uses the first line; optional "sync title" keeps renaming.
- Undo/redo stacks are custom (limit 200), with Ctrl+Z / Ctrl+Shift+Z support.

## Project structure
- `app/src/main/java/com/anotepad/`
  - `ui/` — Compose screens + ViewModels
  - `data/` — DataStore models/repos
  - `file/` — SAF file operations
  - `sync/` — Drive auth/client, sync engine, WorkManager
- `app/src/main/res/` — strings/themes

## Common commands
```bash
./gradlew assembleDebug
./gradlew installDebug
```
