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
- Storage: SAF via `FileRepository` (DocumentFile/DocumentsContract); only `.txt` is supported.
- Preferences/templates: DataStore in `PreferencesRepository` + `TemplateRepository`.
- Sync: WorkManager entrypoints in `SyncScheduler`; sync logic is isolated in `sync/engine/*`.
- Drive: Google Sign-In in `DriveAuthManager`; Drive REST in `DriveClient` (OkHttp).
- Dependency wiring: `MainActivity` -> `AppDependencies` -> `AppViewModelFactory`.

## Sync goals and invariants
- Keep behavior deterministic and conflict-safe.
- Never silently lose data on concurrent local/remote edits.
- Process only `.txt` notes.
- Keep Drive folder auto-connect based on marker file (`anotepad_config.json`).
- Initial sync is timestamp-merge.
- Incremental sync is based on Drive Changes API token chain.

## Sync module map
- `sync/SyncEngine.kt`: composition root for sync dependencies.
- `sync/engine/SyncPreflight.kt`: guard checks + folder resolution.
- `sync/engine/InitialSyncUseCase.kt`: first-run/full bootstrap merge.
- `sync/engine/IncrementalPushUseCase.kt`: local snapshot -> upload/delete operations.
- `sync/engine/IncrementalPullUseCase.kt`: apply Drive changes stream.
- `sync/engine/FolderPathResolver.kt`: local<->Drive folder mapping and path recovery.
- `sync/engine/ConflictResolver.kt`: conflict copy and unique path resolution.
- `sync/engine/DeleteResolver.kt`: remote delete semantics and trash handling.
- `sync/engine/RemoteTreeWalker.kt`: reusable Drive tree traversal.
- `sync/engine/SyncPlan.kt`: operation model + executor.
- `sync/DriveSyncRunner.kt`: worker-facing retry/failure decision model.
- `sync/SyncWorkGateway.kt`: WorkManager abstraction for testable scheduling.

## Data model used by sync
Room DB (`sync.db`), via `SyncRepository` / `SyncStore`:
- `sync_items`
  - `localRelativePath` (PK)
  - `driveFileId`
  - local metadata (`localLastModified`, `localSize`, `localHash`)
  - remote metadata (`driveModifiedTime`)
  - `lastSyncedAt`, `syncState`, `lastError`
- `sync_folders`
  - `localRelativePath` (PK)
  - `driveFolderId` (indexed)
- `sync_meta`
  - keys: `drive_folder_id`, `drive_folder_name`, `drive_start_page_token`, `drive_last_full_scan_at`, sync status keys

Extra DAO operations exist for path-prefix queries (`path` and `path/%`) to avoid full-table scans during folder move/delete.

## Gateway abstraction layer
`SyncEngine` depends on interfaces, not Android/Room/SAF concrete classes:
- `DriveGateway`
- `LocalFsGateway`
- `SyncStore`
- `AuthGateway`
- `PrefsGateway`

Production adapters bridge these interfaces to existing concrete classes:
- `DriveGatewayAdapter` -> `DriveClient`
- `LocalFsGatewayAdapter` -> `FileRepository`
- `SyncStoreAdapter` -> `SyncRepository`
- `AuthGatewayAdapter` -> `DriveAuthManager`
- `PrefsGatewayAdapter` -> `PreferencesRepository`

This allows pure JVM unit tests with in-memory fakes.

## End-to-end sync flow
`DriveSyncWorker` -> `DriveSyncWorkerRunner` -> `SyncEngine.runSync()`.

`SyncEngineCore.runSync()`:
1. Run preflight.
2. Reset per-run folder caches.
3. If no saved `startPageToken`: run initial sync.
4. Else: run incremental push then incremental pull.
5. Set final `SYNCED` status.

## Preflight behavior
`SyncPreflight` checks:
1. `driveSyncEnabled`.
2. `driveSyncPaused`.
3. local root presence.
4. access token presence.

Then resolves Drive folder:
1. If folder id is already stored: reuse it and ensure marker exists.
2. Else search marker folders.
3. If marker resolution failed, search by folder name.
4. If multiple matches are found: return error (explicit user action required).

## Initial sync (bootstrap)
Triggered when local `startPageToken` is empty.

Algorithm:
1. Traverse remote tree and build `relativePath -> DriveFile` map.
2. Read local file snapshot recursively.
3. Merge by timestamps:
- local only -> upload.
- remote only -> download.
- both -> newer side wins.
4. Persist `sync_items` and `sync_folders` mappings.
5. Save fresh `startPageToken` via Drive API.
6. Save `lastFullScanAt`.

Details:
- ignored paths under `.trash/` are skipped.
- only `.txt` files are considered.
- duplicate remote paths are resolved by latest `modifiedTime`.

## Incremental push
Input: single local snapshot for the whole run.

For each local file:
1. Compute hash only when required.
2. Decide upload if item is new, hash changed, missing `driveFileId`, or `PENDING_UPLOAD`.
3. Detect local-vs-remote concurrent change using `lastSyncedAt` and create a local conflict copy from remote when needed.
4. Resolve parent Drive folder id (with folder cache and auto-create).
5. Resolve file id by cached children prefetch when db id is missing.
6. Upload/create/update Drive file.
7. Upsert synced metadata.

For local deletions:
- Policy from settings: `TRASH` / `DELETE` / `IGNORE`.
- DB item is removed in all policies.
- Ignored local `.trash/*` deletions do not trigger remote actions.

## Incremental pull
If token is missing, perform initial remote scan + save fresh token.

Otherwise process Drive `changes.list(pageToken=...)` loop:
1. Apply changes page by page in order.
2. Track `newStartPageToken` from pages.
3. After loop, persist that token only if present.
4. Do not overwrite token via separate `getStartPageToken` call after pull.

This removes the race window between last changes page and a separate token call.

Change handling:
- removed/trashed -> `DeleteResolver` (with ignore-remote-delete option).
- folder changes -> create/move local folder mappings and local tree move.
- file changes -> resolve target path, apply rename/move, resolve collisions, download/update.

Path resolution order:
1. Parent mapping from `sync_folders`.
2. Fetch parent chain from Drive metadata if mapping missing.
3. Fallback to existing item path or `appProperties.localRelativePath`.

## Conflict strategy
Conflict is detected when both sides changed since `lastSyncedAt`.

Action:
- keep current local file untouched.
- download remote version into a separate local file named `(... conflict yyyy-MM-dd HH-mm ...)`.
- mark conflict copy as `CONFLICT` state in `sync_items`.

Special suppression:
- when local move/rename is explicitly driven by remote rename/move, conflict creation is suppressed for that transition.

## Delete strategy
Remote file deletion:
- If local changed after sync -> mark item `PENDING_UPLOAD`, clear `driveFileId`.
- Else move local file into `.trash/` and remove item record.

Remote folder deletion:
- Apply per-child logic above for all descendants.
- Remove all folder mappings for that subtree.
- Delete local folder subtree.

Local deletion during push:
- Optional remote action based on policy.
- Local db record cleanup is always performed.

## Folder/path optimization behavior
`FolderPathResolver` keeps per-run in-memory caches:
- `folderPath -> driveFolderId`
- `driveFolderId -> children-by-name`

Effects:
- fewer Drive API calls on large batches.
- avoids repeating folder and child lookups per file.

## Worker error model
`DriveSyncWorkerRunner` maps exceptions to typed sync errors:
- `SyncError.Network`
- `SyncError.Auth`
- `SyncError.DriveApi`
- `SyncError.Unexpected`

Decision mapping:
- network -> `Result.retry()`
- 429 / 5xx -> `Result.retry()`
- 401 -> revoke auth + failure
- 403 -> failure
- unexpected -> retry

Human-readable sync status messages are persisted to `sync_meta` for UI.

## Scheduler modes
`SyncScheduler` supports four modes:
- manual (`syncNow`)
- debounced (`scheduleDebounced`, 10s)
- periodic (`schedulePeriodic`, 8h)
- startup (`scheduleStartup`, delayed one-shot)

Guards:
- no work if sync disabled/paused.
- startup additionally requires auto-on-start enabled, local root selected, and connected Drive folder id.

## Test infrastructure
Pure unit tests (no instrumentation), JUnit4 + coroutines-test:
- in-memory fakes for all gateways/stores.
- fixture builder for compact scenario setup.
- checks final state and side effects (Drive calls, store writes, local fs operations).

Test groups cover:
- preflight guards
- initial merge
- incremental push
- incremental pull
- delete scenarios
- token correctness
- worker retry/fail mapping
- scheduler behavior

## Project structure
- `app/src/main/java/com/anotepad/`
- `ui/` - Compose screens + ViewModels
- `data/` - DataStore models/repos
- `file/` - SAF file operations
- `sync/` - auth/client + worker/scheduler glue
- `sync/engine/` - sync domain logic and execution pipeline

## Common commands
```bash
./gradlew assembleDebug
./gradlew installDebug
./gradlew :app:testDebugUnitTest
```
