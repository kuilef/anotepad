package com.anotepad.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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

    suspend fun createOrUpdateFile(
        token: String,
        fileId: String?,
        name: String,
        parentId: String,
        mimeType: String,
        content: ByteArray,
        appProperties: Map<String, String>
    ): DriveFile {
        val metadata = JSONObject().apply {
            put("name", name)
            put("mimeType", mimeType)
            put("parents", JSONArray().put(parentId))
            put("appProperties", JSONObject(appProperties))
        }
        val uploadUrl = if (fileId == null) {
            "$UPLOAD_BASE/files?uploadType=resumable&fields=id,name,mimeType,modifiedTime,parents,trashed,appProperties"
        } else {
            "$UPLOAD_BASE/files/$fileId?uploadType=resumable&fields=id,name,mimeType,modifiedTime,parents,trashed,appProperties"
        }
        val sessionLocation = startResumableSession(
            token,
            uploadUrl,
            metadata,
            method = if (fileId == null) "POST" else "PATCH"
        )
        return uploadToSession(token, sessionLocation, mimeType, content)
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

    suspend fun downloadFile(token: String, fileId: String): String {
        val url = "$DRIVE_BASE/files/$fileId?alt=media"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DriveApiException(response.code, response.body?.string(), url, "GET")
                }
                response.body?.string() ?: ""
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
        content: ByteArray
    ): DriveFile {
        val body = content.toRequestBody(mimeType.toMediaType())
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

    companion object {
        private val JSON_MEDIA = "application/json; charset=UTF-8".toMediaType()
        private val EMPTY_JSON = "{}".toRequestBody(JSON_MEDIA)
        private const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
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
