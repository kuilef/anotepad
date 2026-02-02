# LLM Notes

## Project snapshot
- Android app module: `app`
- Language: Kotlin 2.0
- UI: Jetpack Compose
- Build: Gradle Kotlin DSL

## Architecture
- MVVM: Compose UI in `ui/*Screen.kt`, state/logic in `*ViewModel`.
- Navigation: Compose Navigation in `AppNav.kt` (browser, editor, search, templates, settings).
- Data layer: DataStore repositories (`PreferencesRepository`, `TemplateRepository`), file access via SAF in `FileRepository`.
- Drive sync: WorkManager schedules `DriveSyncWorker` periodically (8h) and after debounced saves; `SyncEngine` reconciles local files with Drive.
- Dependency wiring: `MainActivity` → `AppDependencies` → `AppViewModelFactory`.

## Project structure
- `app/src/main/java/com/anotepad/`
  - `ui/` — Compose screens + ViewModels
  - `data/` — DataStore models/repos
  - `file/` — SAF file operations
  - `sync/` — Drive auth/client, sync engine, WorkManager
- `app/src/main/res/` — strings/themes
- `legacy/` — archived legacy project sources

## Анализ проблемы: "Waiting - Sync Scheduled" (зависание)

Симптомы указывают на race condition между автосохранением и ручной синхронизацией, а не только на ProGuard.
Факт, что переключение Pause/Unpause запускает синхронизацию, подтверждает: Worker рабочий, но "мгновенная"
задача может быть перезаписана задачей автосинхронизации с задержкой.

### Причина: конфликт задач с одинаковым уникальным именем
В `SyncScheduler.kt` обе функции используют одно и то же имя:
```kotlin
private const val WORK_SYNC_NOW = "drive_sync_now"
```
При автосохранении (debounce ~1200мс в `EditorViewModel`) вызывается `scheduleDebounced()`, которая ставит
`WORK_SYNC_NOW` с `setInitialDelay(DEBOUNCE_SECONDS)` (сейчас 10 секунд). Если вскоре нажать "Sync Now",
`syncNow()` также ставит `WORK_SYNC_NOW` (policy `REPLACE`). При дальнейшем наборе текста автосохранение может
снова вызвать `scheduleDebounced()` и перезаписать ручную задачу задержанной, из-за чего статус остается
"Sync scheduled", а запуск откладывается.

### Идея исправления: разделить очереди для manual и auto
Разнести уникальные имена задач, чтобы ручная синхронизация не была перезаписана автосинком.
Также оставить `setExpedited` для manual, чтобы ОС не откладывала выполнение.

Пример (ключевые места):
```kotlin
// scheduleDebounced()
workManager.enqueueUniqueWork(WORK_SYNC_AUTO, ExistingWorkPolicy.REPLACE, request)

// syncNow()
workManager.enqueueUniqueWork(WORK_SYNC_MANUAL, ExistingWorkPolicy.REPLACE, request)

companion object {
    private const val WORK_SYNC_AUTO = "drive_sync_auto"
    private const val WORK_SYNC_MANUAL = "drive_sync_manual"
    private const val WORK_SYNC_PERIODIC = "drive_sync_periodic"
    private const val DEBOUNCE_SECONDS = 10L
}
```

### Быстрая проверка гипотезы
- Нажать "Sync Now" и не редактировать текст 20–30 секунд: если начнется — конфликт с auto.
- Посмотреть лог WorkManager:
  ```bash
  adb shell setprop log.tag.WorkManager VERBOSE
  adb logcat | rg "WorkManager|WM-"
  ```

## Common commands
```bash
./gradlew assembleDebug
./gradlew installDebug
```
