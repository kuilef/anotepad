package com.anotepad.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
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
        val json = requestJson(token, url)
        val files = json.optJSONArray("files") ?: JSONArray()
        val items = buildList {
            for (i in 0 until files.length()) {
                val item = files.getJSONObject(i)
                add(DriveFolder(id = item.getString("id"), name = item.optString("name")))
            }
        }
        return DriveListResult(items, json.optString("nextPageToken", null))
    }

    suspend fun findFoldersByName(token: String, name: String): List<DriveFolder> {
        val safeName = escapeQueryValue(name)
        val query = "mimeType='application/vnd.google-apps.folder' and trashed=false and name='$safeName'"
        val url = buildString {
            append("$DRIVE_BASE/files?spaces=drive&fields=files(id,name)&q=")
            append(urlEncode(query))
        }
        val json = requestJson(token, url)
        val files = json.optJSONArray("files") ?: JSONArray()
        return buildList {
            for (i in 0 until files.length()) {
                val item = files.getJSONObject(i)
                add(DriveFolder(id = item.getString("id"), name = item.optString("name")))
            }
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
        val json = requestJson(token, url)
        val files = json.optJSONArray("files") ?: JSONArray()
        var selected: DriveFile? = null
        for (i in 0 until files.length()) {
            val candidate = parseDriveFile(files.getJSONObject(i))
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
        val json = requestJson(token, url)
        val files = json.optJSONArray("files") ?: JSONArray()
        val items = buildList {
            for (i in 0 until files.length()) {
                add(parseDriveFile(files.getJSONObject(i)))
            }
        }
        return DriveListResult(items, json.optString("nextPageToken", null))
    }

    suspend fun getFileMetadata(token: String, fileId: String): DriveFile {
        val url = "$DRIVE_BASE/files/$fileId?fields=id,name,mimeType,modifiedTime,parents,trashed,appProperties"
        val json = requestJson(token, url)
        return parseDriveFile(json)
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
            val json = requestJson(token, url)
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                items.add(parseDriveFile(files.getJSONObject(i)))
            }
            pageToken = json.optString("nextPageToken", null)
        } while (!pageToken.isNullOrBlank())
        return items
    }

    suspend fun createFolder(token: String, name: String, parentId: String?): DriveFolder {
        val body = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            if (!parentId.isNullOrBlank()) {
                put("parents", JSONArray().put(parentId))
            }
        }
        val url = "$DRIVE_BASE/files?fields=id,name"
        val json = requestJson(token, url, method = "POST", body = body)
        return DriveFolder(id = json.getString("id"), name = json.optString("name"))
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
        val metadata = JSONObject().apply {
            put("name", name)
            put("mimeType", mimeType)
            put("appProperties", JSONObject(appProperties))
            if (fileId == null) {
                // Parents can be set on create; updates must use addParents/removeParents.
                put("parents", JSONArray().put(parentId))
            }
        }
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
                metadata = metadata,
                mimeType = mimeType,
                bytes = bytes
            )
        }
        val uploadUrl = "$uploadBase?uploadType=resumable&fields=$DRIVE_FILE_FIELDS"
        val sessionLocation = startResumableSession(
            token,
            uploadUrl,
            metadata,
            method = method
        )
        return uploadToSession(token, sessionLocation, mimeType, contentLength, contentProvider)
    }

    private suspend fun createOrUpdateFileMultipart(
        token: String,
        urlBase: String,
        method: String,
        metadata: JSONObject,
        mimeType: String,
        bytes: ByteArray
    ): DriveFile {
        val url = "$urlBase?uploadType=multipart&fields=$DRIVE_FILE_FIELDS"
        val multipartBody = MultipartBody.Builder()
            .setType(MULTIPART_RELATED_MEDIA)
            .addPart(metadata.toString().toRequestBody(JSON_MEDIA))
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
                    val json = JSONObject(response.body?.string().orEmpty())
                    parseDriveFile(json)
                }
            } catch (api: DriveApiException) {
                throw api
            } catch (io: IOException) {
                throw DriveNetworkException(io)
            }
        }
    }

    suspend fun trashFile(token: String, fileId: String) {
        val body = JSONObject().put("trashed", true)
        val url = "$DRIVE_BASE/files/$fileId"
        requestJson(token, url, method = "PATCH", body = body)
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
        val json = requestJson(token, url)
        return json.getString("startPageToken")
    }

    suspend fun listChanges(token: String, pageToken: String): DriveListResult<DriveChange> {
        val url = buildString {
            append("$DRIVE_BASE/changes?pageToken=${urlEncode(pageToken)}")
            append("&spaces=drive")
            append("&fields=changes(fileId,removed,file(id,name,mimeType,modifiedTime,parents,trashed,appProperties)),newStartPageToken,nextPageToken")
        }
        val json = requestJson(token, url)
        val changes = json.optJSONArray("changes") ?: JSONArray()
        val items = buildList {
            for (i in 0 until changes.length()) {
                val change = changes.getJSONObject(i)
                val fileJson = change.optJSONObject("file")
                val file = if (fileJson != null) parseDriveFile(fileJson) else null
                add(
                    DriveChange(
                        fileId = change.getString("fileId"),
                        removed = change.optBoolean("removed", false),
                        file = file
                    )
                )
            }
        }
        val nextPage = json.optString("nextPageToken")
        return DriveListResult(items, nextPage.ifBlank { null })
    }

    private fun parseDriveFile(json: JSONObject): DriveFile {
        val parents = json.optJSONArray("parents")?.let { array ->
            buildList {
                for (i in 0 until array.length()) add(array.getString(i))
            }
        } ?: emptyList()
        val appProps = json.optJSONObject("appProperties")?.let { props ->
            props.keys().asSequence().associateWith { key -> props.optString(key) }
        } ?: emptyMap()
        val modifiedTime = json.optString("modifiedTime", null)?.let { parseRfc3339Millis(it) }
        return DriveFile(
            id = json.getString("id"),
            name = json.optString("name"),
            mimeType = json.optString("mimeType"),
            modifiedTime = modifiedTime,
            trashed = json.optBoolean("trashed", false),
            parents = parents,
            appProperties = appProps
        )
    }

    private suspend fun startResumableSession(
        token: String,
        url: String,
        metadata: JSONObject,
        method: String
    ): String {
        val body = metadata.toString().toRequestBody(JSON_MEDIA)
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
                val json = JSONObject(response.body?.string().orEmpty())
                parseDriveFile(json)
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

    private suspend fun requestJson(
        token: String,
        url: String,
        method: String = "GET",
        body: JSONObject? = null
    ): JSONObject {
        val responseText = requestRaw(token, url, method, body)
        return JSONObject(responseText.ifBlank { "{}" })
    }

    private suspend fun requestRaw(
        token: String,
        url: String,
        method: String = "GET",
        body: JSONObject? = null
    ): String {
        val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA)
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

class DriveApiException(
    val code: Int,
    val errorBody: String?,
    val url: String? = null,
    val method: String? = null
) : IOException()

fun DriveApiException.userMessage(): String? {
    val body = errorBody?.ifBlank { null } ?: return null
    return runCatching {
        val error = JSONObject(body).optJSONObject("error") ?: return@runCatching null
        val message = error.optString("message").ifBlank { null }
        val reason = error.optJSONArray("errors")
            ?.optJSONObject(0)
            ?.optString("reason")
            ?.ifBlank { null }
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
