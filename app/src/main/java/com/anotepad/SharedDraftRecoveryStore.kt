package com.anotepad

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SharedDraftRecoveryStore(
    private val file: File
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun persist(draft: SharedNoteDraft) = withContext(Dispatchers.IO) {
        runCatching {
            val payload = RecoveryPayload(
                fileName = draft.fileName,
                content = draft.content
            )
            val existing = readPayloads()
            if (existing.any { it == payload }) return@runCatching
            writePayloads(existing + payload)
        }
    }

    suspend fun peek(): SharedNoteDraft? = withContext(Dispatchers.IO) {
        runCatching {
            readPayloads().firstOrNull()?.toDraft()
        }.getOrNull()
    }

    suspend fun remove(draft: SharedNoteDraft) = withContext(Dispatchers.IO) {
        runCatching {
            val existing = readPayloads()
            val index = existing.indexOfFirst {
                it.fileName == draft.fileName && it.content == draft.content
            }
            if (index < 0) return@runCatching
            val updated = existing.toMutableList().apply { removeAt(index) }
            if (updated.isEmpty()) {
                if (file.exists()) {
                    file.delete()
                }
            } else {
                writePayloads(updated)
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching {
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun readPayloads(): List<RecoveryPayload> {
        if (!file.exists()) return emptyList()
        val raw = file.readText()
        return runCatching {
            json.decodeFromString(RecoveryPayloadList.serializer(), raw).drafts
        }.recoverCatching {
            listOf(json.decodeFromString(RecoveryPayload.serializer(), raw))
        }.getOrDefault(emptyList())
    }

    private fun writePayloads(payloads: List<RecoveryPayload>) {
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        val stored = RecoveryPayloadList(drafts = payloads)
        tempFile.writeText(json.encodeToString(RecoveryPayloadList.serializer(), stored))
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
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
    ) {
        fun toDraft(): SharedNoteDraft {
            return SharedNoteDraft(
                fileName = fileName,
                content = content
            )
        }
    }

    @Serializable
    private data class RecoveryPayloadList(
        val drafts: List<RecoveryPayload> = emptyList()
    )
}
