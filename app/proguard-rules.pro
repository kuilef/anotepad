# Keep Compose default rules
-keep class androidx.compose.** { *; }

# Keep WorkManager workers
-keep class com.anotepad.sync.DriveSyncWorker { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep Room entities and database
-keep class com.anotepad.sync.db.** { *; }

# Keep Drive client and related models
-keep class com.anotepad.sync.DriveClient { *; }
-keep class com.anotepad.sync.DriveFile { *; }
-keep class com.anotepad.sync.DriveFolder { *; }
-keep class com.anotepad.sync.DriveChange { *; }
-keep class com.anotepad.sync.DriveListResult { *; }

# Keep annotations for Room and other reflection users
-keepattributes *Annotation*
