package com.anotepad

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SharedDraftRecoveryStore(
    private val file: File
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun persist(draft: SharedNoteDraft) {
        runCatching {
            file.parentFile?.mkdirs()
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            val payload = RecoveryPayload(
                fileName = draft.fileName,
                content = draft.content
            )
            tempFile.writeText(json.encodeToString(RecoveryPayload.serializer(), payload))
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        }
    }

    fun peek(): SharedNoteDraft? {
        return runCatching {
            if (!file.exists()) return null
            val payload = json.decodeFromString(RecoveryPayload.serializer(), file.readText())
            SharedNoteDraft(
                fileName = payload.fileName,
                content = payload.content
            )
        }.getOrNull()
    }

    fun clear() {
        runCatching {
            if (file.exists()) {
                file.delete()
            }
        }
    }

    companion object {
        private const val FILE_NAME = "shared-draft-recovery.json"

        fun fromContext(context: Context): SharedDraftRecoveryStore {
            return SharedDraftRecoveryStore(File(context.noBackupFilesDir, FILE_NAME))
        }
    }

    @Serializable
    private data class RecoveryPayload(
        val fileName: String,
        val content: String
    )
}
