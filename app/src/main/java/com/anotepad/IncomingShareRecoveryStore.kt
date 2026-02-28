package com.anotepad

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object IncomingShareRecoveryStore {
    private const val FILE_NAME = "incoming-share-recovery.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var file: File? = null

    fun initialize(context: Context) {
        file = File(context.noBackupFilesDir, FILE_NAME)
    }

    fun persist(payload: SharedTextPayload) {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val tempFile = File(target.parentFile, "${target.name}.tmp")
            val stored = StoredPayload(text = payload.text)
            tempFile.writeText(json.encodeToString(StoredPayload.serializer(), stored))
            if (!tempFile.renameTo(target)) {
                tempFile.copyTo(target, overwrite = true)
                tempFile.delete()
            }
        }
    }

    fun peek(): SharedTextPayload? {
        val target = file ?: return null
        return runCatching {
            if (!target.exists()) return null
            val payload = json.decodeFromString(StoredPayload.serializer(), target.readText())
            SharedTextPayload(text = payload.text)
        }.getOrNull()
    }

    fun clear() {
        val target = file ?: return
        runCatching {
            if (target.exists()) {
                target.delete()
            }
        }
    }

    @Serializable
    private data class StoredPayload(
        val text: String
    )
}
