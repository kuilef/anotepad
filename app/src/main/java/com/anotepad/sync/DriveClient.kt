package com.anotepad.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

data class DriveFolder(val id: String, val name: String)

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val modifiedTime: Long?,
    val trashed: Boolean,
    val parents: List<String>,
    val appProperties: Map<String, String>
)

data class DriveChange(
    val fileId: String,
    val removed: Boolean,
    val file: DriveFile?
)

data class DriveListResult<T>(
    val items: List<T>,
    val nextPageToken: String?
)

data class DriveChangesResult(
    val items: List<DriveChange>,
    val nextPageToken: String?,
    val newStartPageToken: String?
)

class DriveClient(
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    suspend fun findMarkerFolders(token: String): List<DriveFolder> {
        val markers = findMarkerFiles(token)
        if (markers.isEmpty()) return emptyList()
        val parentIds = markers.mapNotNull { it.parents.firstOrNull() }.distinct()
        return parentIds.mapNotNull { parentId ->
            val marker = markers.firstOrNull { it.parents.contains(parentId) }
            val name = marker?.appProperties?.get(MARKER_FOLDER_NAME_KEY)
                ?: runCatching { getFileMetadata(token, parentId).name }.getOrNull()
            name?.let { DriveFolder(id = parentId, name = it) }
        }
    }

    suspend fun createFolderWithMarker(token: String, name: String): DriveFolder {
        val folder = createFolder(token, name, null)
        createMarkerFile(token, folder.id, folder.name)
        return folder
    }

    suspend fun ensureMarkerFile(token: String, folderId: String, folderName: String) {
        val markers = findMarkerFiles(token)
        if (markers.any { it.parents.contains(folderId) }) return
        createMarkerFile(token, folderId, folderName)
    }

    suspend fun listFolders(token: String, pageToken: String?): DriveListResult<DriveFolder> {
        val query = "mimeType='application/vnd.google-apps.folder' and trashed=false"
        val url = buildString {
            append("$DRIVE_BASE/files?spaces=drive&fields=files(id,name),nextPageToken&q=")
            append(urlEncode(query))
            if (!pageToken.isNullOrBlank()) append("&pageToken=${urlEncode(pageToken)}")
        }
        val response = requestDecoded<DriveFoldersResponseDto>(token, url)
        val items = response.files.map { item ->
            DriveFolder(id = item.id, name = item.name)
        }
        return DriveListResult(items, response.nextPageToken)
    }

    suspend fun findFoldersByName(token: String, name: String): List<DriveFolder> {
        val safeName = escapeQueryValue(name)
        val query = "mimeType='application/vnd.google-apps.folder' and trashed=false and name='$safeName'"
        val url = buildString {
            append("$DRIVE_BASE/files?spaces=drive&fields=files(id,name)&q=")
            append(urlEncode(query))
        }
        val response = requestDecoded<DriveFoldersResponseDto>(token, url)
        return response.files.map { item ->
            DriveFolder(id = item.id, name = item.name)
        }
    }

    suspend fun findChildByName(token: String, parentId: String, name: String): DriveFile? {
        val safeParentId = escapeQueryValue(parentId)
        val safeName = escapeQueryValue(name)
        val query = "'$safeParentId' in parents and trashed=false and name='$safeName'"
        val url = buildString {
            append("$DRIVE_BASE/files?spaces=drive&fields=files(id,name,mimeType,modifiedTime,parents,trashed,appProperties)&pageSize=20&q=")
            append(urlEncode(query))
        }
        val response = requestDecoded<DriveFilesResponseDto>(token, url)
        val candidates = response.files.map { it.toDomain() }
        var selected: DriveFile? = null
        for (candidate in candidates) {
            val current = selected
            if (current == null || (candidate.modifiedTime ?: 0L) >= (current.modifiedTime ?: 0L)) {
                selected = candidate
            }
        }
        return selected
    }

    suspend fun listChildren(token: String, folderId: String, pageToken: String?): DriveListResult<DriveFile> {
        val query = "'$folderId' in parents and trashed=false"
        val url = buildString {
            append("$DRIVE_BASE/files?spaces=drive&fields=files(id,name,mimeType,modifiedTime,parents,trashed,appProperties),nextPageToken&q=")
            append(urlEncode(query))
            if (!pageToken.isNullOrBlank()) append("&pageToken=${urlEncode(pageToken)}")
        }
        val response = requestDecoded<DriveFilesResponseDto>(token, url)
        val items = response.files.map { it.toDomain() }
        return DriveListResult(items, response.nextPageToken)
    }

    suspend fun getFileMetadata(token: String, fileId: String): DriveFile {
        val url = "$DRIVE_BASE/files/$fileId?fields=id,name,mimeType,modifiedTime,parents,trashed,appProperties"
        val response = requestDecoded<DriveFileDto>(token, url)
        return response.toDomain()
    }

    private suspend fun findMarkerFiles(token: String): List<DriveFile> {
        val query = "appProperties has { key='$MARKER_APP_PROPERTY_KEY' and value='$MARKER_APP_PROPERTY_VALUE' } and trashed=false"
        val items = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$DRIVE_BASE/files?spaces=drive&fields=files(id,name,mimeType,modifiedTime,parents,trashed,appProperties),nextPageToken&q=")
                append(urlEncode(query))
                if (!pageToken.isNullOrBlank()) append("&pageToken=${urlEncode(pageToken)}")
            }
            val response = requestDecoded<DriveFilesResponseDto>(token, url)
            items.addAll(response.files.map { it.toDomain() })
            pageToken = response.nextPageToken
        } while (!pageToken.isNullOrBlank())
        return items
    }

    suspend fun createFolder(token: String, name: String, parentId: String?): DriveFolder {
        val body = DRIVE_JSON.encodeToString(
            CreateFolderRequestDto(
                name = name,
                mimeType = "application/vnd.google-apps.folder",
                parents = parentId?.takeIf { it.isNotBlank() }?.let { listOf(it) }
            )
        )
        val url = "$DRIVE_BASE/files?fields=id,name"
        val response = requestDecoded<DriveFolderDto>(token, url, method = "POST", body = body)
        return DriveFolder(id = response.id, name = response.name)
    }

    private suspend fun createMarkerFile(token: String, folderId: String, folderName: String): DriveFile {
        val content = ByteArray(0)
        val props = mapOf(
            MARKER_APP_PROPERTY_KEY to MARKER_APP_PROPERTY_VALUE,
            MARKER_FOLDER_NAME_KEY to folderName
        )
        return createOrUpdateFile(
            token = token,
            fileId = null,
            name = MARKER_FILE_NAME,
            parentId = folderId,
            mimeType = MARKER_MIME,
            contentLength = content.size.toLong(),
            contentProvider = { ByteArrayInputStream(content) },
            appProperties = props
        )
    }

    suspend fun createOrUpdateFile(
        token: String,
        fileId: String?,
        name: String,
        parentId: String,
        mimeType: String,
        contentLength: Long?,
        contentProvider: () -> InputStream?,
        appProperties: Map<String, String>
    ): DriveFile {
        val metadataJson = DRIVE_JSON.encodeToString(
            UploadMetadataRequestDto(
                name = name,
                mimeType = mimeType,
                appProperties = appProperties,
                parents = if (fileId == null) listOf(parentId) else null
            )
        )
        val uploadBase = if (fileId == null) "$UPLOAD_BASE/files" else "$UPLOAD_BASE/files/$fileId"
        val method = if (fileId == null) "POST" else "PATCH"
        if (contentLength != null && contentLength in 0..MULTIPART_MAX_BYTES) {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    val stream = contentProvider()
                        ?: throw IOException("Unable to open content stream for upload")
                    stream.use { it.readBytes() }
                } catch (io: IOException) {
                    throw DriveNetworkException(io)
                }
            }
            return createOrUpdateFileMultipart(
                token = token,
                urlBase = uploadBase,
                method = method,
                metadataJson = metadataJson,
                mimeType = mimeType,
                bytes = bytes
            )
        }
        val uploadUrl = "$uploadBase?uploadType=resumable&fields=$DRIVE_FILE_FIELDS"
        val sessionLocation = startResumableSession(
            token,
            uploadUrl,
            metadataJson,
            method = method
        )
        return uploadToSession(token, sessionLocation, mimeType, contentLength, contentProvider)
    }

    private suspend fun createOrUpdateFileMultipart(
        token: String,
        urlBase: String,
        method: String,
        metadataJson: String,
        mimeType: String,
        bytes: ByteArray
    ): DriveFile {
        val url = "$urlBase?uploadType=multipart&fields=$DRIVE_FILE_FIELDS"
        val multipartBody = MultipartBody.Builder()
            .setType(MULTIPART_RELATED_MEDIA)
            .addPart(metadataJson.toRequestBody(JSON_MEDIA))
            .addPart(bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val builder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
        when (method) {
            "PATCH" -> builder.patch(multipartBody)
            else -> builder.post(multipartBody)
        }
        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw DriveApiException(response.code, response.body?.string(), url, method)
                    }
                    val payload = response.body?.string().orEmpty()
                    DRIVE_JSON.decodeFromString<DriveFileDto>(payload.ifBlank { "{}" }).toDomain()
                }
            } catch (api: DriveApiException) {
                throw api
            } catch (io: IOException) {
                throw DriveNetworkException(io)
            }
        }
    }

    suspend fun trashFile(token: String, fileId: String) {
        val body = DRIVE_JSON.encodeToString(TrashRequestDto(trashed = true))
        val url = "$DRIVE_BASE/files/$fileId"
        requestRaw(token, url, method = "PATCH", body = body)
    }

    suspend fun deleteFile(token: String, fileId: String) {
        val url = "$DRIVE_BASE/files/$fileId"
        requestRaw(token, url, method = "DELETE")
    }

    suspend fun downloadFile(token: String, fileId: String, consumer: suspend (InputStream) -> Unit) {
        val url = "$DRIVE_BASE/files/$fileId?alt=media"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DriveApiException(response.code, response.body?.string(), url, "GET")
                }
                val body = response.body ?: throw DriveApiException(response.code, "Empty body", url, "GET")
                body.byteStream().use { input ->
                    consumer(input)
                }
            }
        }
    }

    suspend fun getStartPageToken(token: String): String {
        val url = "$DRIVE_BASE/changes/startPageToken"
        val response = requestDecoded<StartPageTokenResponseDto>(token, url)
        return response.startPageToken
    }

    suspend fun listChanges(token: String, pageToken: String): DriveChangesResult {
        val url = buildString {
            append("$DRIVE_BASE/changes?pageToken=${urlEncode(pageToken)}")
            append("&spaces=drive")
            append("&fields=changes(fileId,removed,file(id,name,mimeType,modifiedTime,parents,trashed,appProperties)),newStartPageToken,nextPageToken")
        }
        val response = requestDecoded<DriveChangesResponseDto>(token, url)
        val items = response.changes.map { change ->
            DriveChange(
                fileId = change.fileId,
                removed = change.removed,
                file = change.file?.toDomain()
            )
        }
        return DriveChangesResult(
            items = items,
            nextPageToken = response.nextPageToken?.ifBlank { null },
            newStartPageToken = response.newStartPageToken?.ifBlank { null }
        )
    }

    private suspend fun startResumableSession(
        token: String,
        url: String,
        metadataJson: String,
        method: String
    ): String {
        val body = metadataJson.toRequestBody(JSON_MEDIA)
        val builder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json; charset=UTF-8")
        when (method) {
            "PATCH" -> builder.patch(body)
            else -> builder.post(body)
        }
        val request = builder.build()
        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DriveApiException(response.code, response.body?.string(), url, method)
                }
                response.header("Location")
                    ?: throw DriveApiException(response.code, "Missing upload location", url, method)
            }
        }
    }

    private suspend fun uploadToSession(
        token: String,
        sessionUrl: String,
        mimeType: String,
        contentLength: Long?,
        contentProvider: () -> InputStream?
    ): DriveFile {
        val body = StreamRequestBody(mimeType, contentLength, contentProvider)
        val request = Request.Builder()
            .url(sessionUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", mimeType)
            .put(body)
            .build()
        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DriveApiException(response.code, response.body?.string(), sessionUrl, "PUT")
                }
                val payload = response.body?.string().orEmpty()
                DRIVE_JSON.decodeFromString<DriveFileDto>(payload.ifBlank { "{}" }).toDomain()
            }
        }
    }

    private class StreamRequestBody(
        private val mimeType: String,
        private val contentLength: Long?,
        private val contentProvider: () -> InputStream?
    ) : RequestBody() {
        override fun contentType() = mimeType.toMediaType()

        override fun contentLength(): Long {
            return contentLength?.takeIf { it >= 0 } ?: -1L
        }

        override fun writeTo(sink: BufferedSink) {
            val input = contentProvider()
                ?: throw IOException("Unable to open content stream for upload")
            input.use { stream ->
                sink.writeAll(stream.source())
            }
        }
    }

    private suspend inline fun <reified T> requestDecoded(
        token: String,
        url: String,
        method: String = "GET",
        body: String? = null
    ): T {
        val responseText = requestRaw(token, url, method, body)
        return DRIVE_JSON.decodeFromString(responseText.ifBlank { "{}" })
    }

    private suspend fun requestRaw(
        token: String,
        url: String,
        method: String = "GET",
        body: String? = null
    ): String {
        val requestBody = body?.toRequestBody(JSON_MEDIA)
        val builder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
        when (method) {
            "POST" -> builder.post(requestBody ?: EMPTY_JSON)
            "PATCH" -> builder.patch(requestBody ?: EMPTY_JSON)
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw DriveApiException(response.code, response.body?.string(), url, method)
                    }
                    response.body?.string().orEmpty()
                }
            } catch (api: DriveApiException) {
                throw api
            } catch (io: IOException) {
                throw DriveNetworkException(io)
            }
        }
    }

    private fun DriveFileDto.toDomain(): DriveFile {
        return DriveFile(
            id = id,
            name = name,
            mimeType = mimeType,
            modifiedTime = modifiedTime?.let { parseRfc3339Millis(it) },
            trashed = trashed,
            parents = parents,
            appProperties = appProperties
        )
    }

    private fun parseRfc3339Millis(value: String): Long? {
        return runCatching {
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun escapeQueryValue(value: String): String = value.replace("'", "\\'")

    companion object {
        private val JSON_MEDIA = "application/json; charset=UTF-8".toMediaType()
        private val MULTIPART_RELATED_MEDIA = "multipart/related".toMediaType()
        private val EMPTY_JSON = "{}".toRequestBody(JSON_MEDIA)
        private const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val DRIVE_FILE_FIELDS = "id,name,mimeType,modifiedTime,parents,trashed,appProperties"
        private const val MULTIPART_MAX_BYTES = 256L * 1024L
        private const val MARKER_FILE_NAME = "anotepad_config.json"
        private const val MARKER_MIME = "application/json"
        private const val MARKER_APP_PROPERTY_KEY = "anotepad_marker"
        private const val MARKER_APP_PROPERTY_VALUE = "1"
        private const val MARKER_FOLDER_NAME_KEY = "anotepad_folder_name"
    }
}

@Serializable
private data class DriveFolderDto(
    val id: String,
    val name: String = ""
)

@Serializable
private data class DriveFoldersResponseDto(
    val files: List<DriveFolderDto> = emptyList(),
    val nextPageToken: String? = null
)

@Serializable
private data class DriveFileDto(
    val id: String,
    val name: String = "",
    val mimeType: String = "",
    val modifiedTime: String? = null,
    val trashed: Boolean = false,
    val parents: List<String> = emptyList(),
    val appProperties: Map<String, String> = emptyMap()
)

@Serializable
private data class DriveFilesResponseDto(
    val files: List<DriveFileDto> = emptyList(),
    val nextPageToken: String? = null
)

@Serializable
private data class StartPageTokenResponseDto(
    val startPageToken: String
)

@Serializable
private data class DriveChangeDto(
    val fileId: String,
    val removed: Boolean = false,
    val file: DriveFileDto? = null
)

@Serializable
private data class DriveChangesResponseDto(
    val changes: List<DriveChangeDto> = emptyList(),
    val nextPageToken: String? = null,
    val newStartPageToken: String? = null
)

@Serializable
private data class CreateFolderRequestDto(
    val name: String,
    val mimeType: String,
    val parents: List<String>? = null
)

@Serializable
private data class UploadMetadataRequestDto(
    val name: String,
    val mimeType: String,
    val appProperties: Map<String, String>,
    val parents: List<String>? = null
)

@Serializable
private data class TrashRequestDto(
    val trashed: Boolean
)

@Serializable
private data class DriveErrorEnvelopeDto(
    val error: DriveErrorPayloadDto? = null
)

@Serializable
private data class DriveErrorPayloadDto(
    val message: String? = null,
    val errors: List<DriveErrorItemDto> = emptyList()
)

@Serializable
private data class DriveErrorItemDto(
    val reason: String? = null
)

private val DRIVE_JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

class DriveApiException(
    val code: Int,
    val errorBody: String?,
    val url: String? = null,
    val method: String? = null
) : IOException()

fun DriveApiException.userMessage(): String? {
    val body = errorBody?.ifBlank { null } ?: return null
    return runCatching {
        val parsed = DRIVE_JSON.decodeFromString<DriveErrorEnvelopeDto>(body)
        val message = parsed.error?.message?.ifBlank { null }
        val reason = parsed.error?.errors?.firstOrNull()?.reason?.ifBlank { null }
        when {
            !message.isNullOrBlank() && !reason.isNullOrBlank() -> "$message ($reason)"
            !message.isNullOrBlank() -> message
            !reason.isNullOrBlank() -> reason
            else -> null
        }
    }.getOrNull()
}

class DriveNetworkException(cause: IOException) : IOException(cause) {
    val detail: String? = cause.message?.ifBlank { null }
    val type: String? = cause::class.java.simpleName?.ifBlank { null }
    val description: String? = when {
        type != null && detail != null -> "$type: $detail"
        type != null -> type
        detail != null -> detail
        else -> null
    }
}
