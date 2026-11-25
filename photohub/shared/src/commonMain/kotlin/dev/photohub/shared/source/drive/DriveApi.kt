package dev.photohub.shared.source.drive

import dev.photohub.shared.auth.TokenProvider
import dev.photohub.shared.auth.urlEncode
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A file as returned by the Drive v3 API. */
@Serializable
data class DriveFile(
    val id: String = "",
    val name: String = "",
    val mimeType: String = "",
    /** Drive returns 64-bit numbers as strings in JSON. */
    val size: String? = null,
    val createdTime: String? = null,
    val modifiedTime: String? = null,
    val thumbnailLink: String? = null,
    val md5Checksum: String? = null,
    val imageMediaMetadata: ImageMeta? = null,
    val videoMediaMetadata: VideoMeta? = null,
    val trashed: Boolean = false,
) {
    @Serializable
    data class ImageMeta(
        val width: Int? = null,
        val height: Int? = null,
        /** EXIF capture time — the only trustworthy "when was this taken". */
        val time: String? = null,
    )

    @Serializable
    data class VideoMeta(
        val width: Int? = null,
        val height: Int? = null,
        val durationMillis: String? = null,
    )
}

@Serializable
data class DriveFileList(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

/** Minimal `about` projection: account identity plus account-wide storage quota. */
@Serializable
data class DriveAbout(
    val user: User? = null,
    val storageQuota: StorageQuota? = null,
) {
    @Serializable
    data class User(
        val emailAddress: String? = null,
        val displayName: String? = null,
    )

    /** Drive reports 64-bit byte counts as JSON strings. */
    @Serializable
    data class StorageQuota(
        val limit: String? = null,
        /** Total storage used by the Google account (Drive, Gmail, Photos). */
        val usage: String? = null,
        val usageInDrive: String? = null,
        val usageInDriveTrash: String? = null,
    )
}

/** Raised for Drive responses that are worth distinguishing from generic failures. */
class DriveApiException(
    val status: Int,
    message: String,
    /** True for 429/5xx, where retrying later is the correct response. */
    val retryable: Boolean,
) : Exception(message)

/**
 * Thin wrapper over the Google Drive v3 REST API.
 *
 * Only the handful of operations PhotoHub needs are implemented, so there is no
 * dependency on Google's Java client — which has no Kotlin Multiplatform build
 * and would not work on desktop and Android from shared code.
 *
 * Every call attaches a freshly-validated bearer token via [TokenProvider].
 */
class DriveApi(
    private val http: HttpClient,
    private val tokens: TokenProvider,
    private val baseUrl: String = BASE_URL,
    private val uploadUrl: String = UPLOAD_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fields requested on every listing.
     *
     * Drive returns a minimal projection unless asked, and omitting this would
     * mean a second request per photo for dimensions and capture time.
     */
    private val fileFields =
        "id,name,mimeType,size,createdTime,modifiedTime,thumbnailLink," +
            "md5Checksum,trashed,imageMediaMetadata(width,height,time)," +
            "videoMediaMetadata(width,height,durationMillis)"

    /**
     * Lists photos and videos under [folderId], newest first.
     *
     * With the `drive.file` scope this only ever sees files PhotoHub created,
     * which is exactly the intent: the app shows its own library, not the user's
     * unrelated documents.
     */
    suspend fun listMedia(folderId: String?, pageToken: String?, pageSize: Int = 200): DriveFileList {
        val clauses = buildList {
            add("trashed = false")
            add("(mimeType contains 'image/' or mimeType contains 'video/')")
            folderId?.let { add("'$it' in parents") }
        }
        val query = clauses.joinToString(" and ")

        val url = buildString {
            append("$baseUrl/files")
            append("?q=${query.urlEncode()}")
            append("&fields=${"nextPageToken,files($fileFields)".urlEncode()}")
            append("&pageSize=$pageSize")
            append("&orderBy=${"createdTime desc".urlEncode()}")
            // Without this, shared-drive files are silently missing.
            append("&supportsAllDrives=true&includeItemsFromAllDrives=true")
            pageToken?.let { append("&pageToken=${it.urlEncode()}") }
        }

        return json.decodeFromString(authorizedGet(url).requireSuccess("list files"))
    }

    /** Finds a folder by exact name under [parentId], or null when absent. */
    suspend fun findFolder(name: String, parentId: String?): DriveFile? {
        // Embedded apostrophes would terminate the quoted literal in Drive's
        // query language and change the query's meaning.
        val safeName = name.replace("\\", "\\\\").replace("'", "\\'")
        val clauses = buildList {
            add("name = '$safeName'")
            add("mimeType = '$FOLDER_MIME'")
            add("trashed = false")
            add(if (parentId != null) "'$parentId' in parents" else "'root' in parents")
        }
        val url = "$baseUrl/files" +
            "?q=${clauses.joinToString(" and ").urlEncode()}" +
            "&fields=${"files(id,name,mimeType)".urlEncode()}" +
            "&pageSize=1&supportsAllDrives=true&includeItemsFromAllDrives=true"

        return json.decodeFromString<DriveFileList>(authorizedGet(url).requireSuccess("find folder"))
            .files
            .firstOrNull()
    }

    /** Creates a folder named [name] under [parentId]. */
    suspend fun createFolder(name: String, parentId: String?): DriveFile {
        val metadata = buildJsonObjectString(
            "name" to name,
            "mimeType" to FOLDER_MIME,
            parentsKey = parentId,
        )
        val response = http.post("$baseUrl/files?fields=${"id,name,mimeType".urlEncode()}") {
            header("Authorization", "Bearer ${tokens.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody(metadata)
        }
        return json.decodeFromString(response.requireSuccess("create folder"))
    }

    /**
     * Resolves a `PhotoHub/2026/11`-style path, creating any missing segments.
     *
     * Each segment is looked up before being created, so this converges on the
     * same folders across devices instead of making duplicates — Drive happily
     * allows two folders with the same name in one parent.
     */
    suspend fun ensureFolderPath(path: String): String {
        var parent: String? = null
        for (segment in path.split('/').filter { it.isNotBlank() }) {
            parent = (findFolder(segment, parent) ?: createFolder(segment, parent)).id
        }
        return parent ?: "root"
    }

    /** Streams a file's bytes. Used for thumbnails and originals. */
    suspend fun download(fileId: String): HttpResponse =
        http.get("$baseUrl/files/$fileId?alt=media&supportsAllDrives=true") {
            header("Authorization", "Bearer ${tokens.accessToken()}")
        }

    /** Permanently deletes one app-owned file. Only the archiver calls this. */
    suspend fun delete(fileId: String) {
        http.delete("$baseUrl/files/$fileId?supportsAllDrives=true") {
            header("Authorization", "Bearer ${tokens.accessToken()}")
        }.requireSuccess("delete file")
    }

    /** Fetches an arbitrary Drive URL (e.g. `thumbnailLink`) with auth attached. */
    suspend fun downloadUrl(url: String): HttpResponse =
        http.get(url) { header("Authorization", "Bearer ${tokens.accessToken()}") }

    /**
     * Starts a resumable upload and returns the session URL to send bytes to.
     *
     * Resumable is used even for small files because camera uploads happen on
     * mobile networks: a dropped connection resumes from the last confirmed byte
     * instead of restarting a 50 MB video.
     */
    suspend fun startResumableUpload(
        name: String,
        parentFolderId: String,
        mimeType: String,
    ): String {
        val metadata = buildJsonObjectString(
            "name" to name,
            "mimeType" to mimeType,
            parentsKey = parentFolderId,
        )
        val response = http.post("$uploadUrl/files?uploadType=resumable&supportsAllDrives=true") {
            header("Authorization", "Bearer ${tokens.accessToken()}")
            contentType(ContentType.Application.Json)
            setBody(metadata)
        }
        if (!response.status.isSuccess()) {
            response.requireSuccess("start upload")
        }
        return response.headers["Location"]
            ?: throw DriveApiException(
                response.status.value,
                "Drive did not return a resumable session URL",
                retryable = true,
            )
    }

    /**
     * Fetches the signed-in user's identity so a second account can be labelled
     * meaningfully in the sources list. Never fatal: a label is a convenience,
     * not a precondition for a working source.
     */
    suspend fun about(): Result<DriveAbout> = runCatching {
        val fields = "user(emailAddress,displayName)," +
            "storageQuota(limit,usage,usageInDrive,usageInDriveTrash)"
        val url = "$baseUrl/about?fields=${fields.urlEncode()}"
        json.decodeFromString(authorizedGet(url).requireSuccess("read account"))
    }

    private suspend fun authorizedGet(url: String): HttpResponse =
        http.get(url) { header("Authorization", "Bearer ${tokens.accessToken()}") }

    /**
     * Returns the body on success, or throws a classified [DriveApiException].
     *
     * The retryable split matters for the upload queue: rate limits and server
     * faults should back off and retry, while a 404 or 403 means the request
     * itself is wrong and retrying would loop forever.
     */
    private suspend fun HttpResponse.requireSuccess(action: String): String {
        if (status.isSuccess()) return bodyAsText()

        val retryable = status.value == 429 ||
            status.value >= 500 ||
            status == HttpStatusCode.RequestTimeout
        throw DriveApiException(
            status = status.value,
            message = "Drive $action failed (${status.value}): ${bodyAsText().take(300)}",
            retryable = retryable,
        )
    }

    /**
     * Builds a small JSON object by hand.
     *
     * Using the serializer would need a @Serializable type per request shape for
     * three one-off bodies; the values are escaped so names with quotes are safe.
     */
    private fun buildJsonObjectString(
        vararg fields: Pair<String, String>,
        parentsKey: String?,
    ): String {
        val parts = fields.map { (k, v) -> "\"$k\":\"${v.jsonEscape()}\"" }.toMutableList()
        parentsKey?.let { parts += "\"parents\":[\"${it.jsonEscape()}\"]" }
        return "{${parts.joinToString(",")}}"
    }

    private fun String.jsonEscape(): String = buildString {
        for (c in this@jsonEscape) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                // String.format is JVM-only, so build the escape by hand.
                else -> if (c < ' ') {
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
    }

    companion object {
        const val BASE_URL = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
