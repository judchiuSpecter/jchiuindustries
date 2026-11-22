package dev.photohub.shared.source.drive

import dev.photohub.shared.auth.TokenProvider
import dev.photohub.shared.platform.fileSystem
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import okio.Path

/**
 * Uploads a local file to Drive with a resumable session.
 *
 * Camera uploads happen on mobile networks where connections drop mid-transfer.
 * A resumable session lets an interrupted upload continue from the last byte
 * Drive confirmed, instead of re-sending a 50 MB video from the start.
 *
 * Uploads are always additive: nothing is ever deleted locally or remotely.
 */
class DriveUploader(
    private val http: HttpClient,
    private val api: DriveApi,
    private val tokens: TokenProvider,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Uploads [local] into [remoteFolderPath], creating the folders if needed.
     *
     * [onProgress] receives 0f..1f. Returns the created [DriveFile].
     */
    suspend fun upload(
        local: Path,
        remoteFolderPath: String,
        mimeType: String,
        onProgress: (Float) -> Unit = {},
    ): DriveFile {
        val metadata = fileSystem.metadata(local)
        val total = metadata.size ?: error("Cannot determine size of $local")

        val folderId = api.ensureFolderPath(remoteFolderPath)
        val sessionUrl = api.startResumableUpload(local.name, folderId, mimeType)

        return uploadChunks(sessionUrl, local, total, onProgress)
    }

    /**
     * Sends the file in chunks, honouring Drive's resume protocol.
     *
     * Drive replies 308 while it wants more data, and its `Range` header is
     * authoritative about how much it actually stored — which can be less than
     * what was sent. Trusting the local counter instead would corrupt the file
     * by continuing from the wrong offset.
     */
    private suspend fun uploadChunks(
        sessionUrl: String,
        local: Path,
        total: Long,
        onProgress: (Float) -> Unit,
    ): DriveFile {
        var offset = 0L
        var attempts = 0

        while (true) {
            val remaining = total - offset
            val chunkSize = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
            val chunk = readChunk(local, offset, chunkSize)

            val response = http.put(sessionUrl) {
                header("Authorization", "Bearer ${tokens.accessToken()}")
                // Drive requires the exact byte range and the total length.
                header("Content-Range", "bytes $offset-${offset + chunkSize - 1}/$total")
                contentType(ContentType.Application.OctetStream)
                setBody(chunk)
            }

            when {
                // 200/201: the final chunk landed and Drive returned the file.
                response.status.isSuccess() -> {
                    onProgress(1f)
                    return json.decodeFromString(response.bodyAsText())
                }

                // 308 Resume Incomplete: more bytes wanted.
                response.status.value == 308 -> {
                    attempts = 0
                    offset = response.headers["Range"]
                        ?.substringAfter('-')
                        ?.toLongOrNull()
                        ?.plus(1)
                        ?: (offset + chunkSize)
                    onProgress((offset.toFloat() / total).coerceIn(0f, 1f))
                }

                // Rate limit or server fault: the session survives, so retry
                // this chunk rather than abandoning the whole upload.
                response.status.value == 429 || response.status.value >= 500 -> {
                    if (++attempts > MAX_CHUNK_ATTEMPTS) {
                        throw DriveApiException(
                            response.status.value,
                            "Upload stalled at $offset/$total after $attempts attempts",
                            retryable = true,
                        )
                    }
                }

                else -> throw DriveApiException(
                    response.status.value,
                    "Upload failed (${response.status.value}): ${response.bodyAsText().take(200)}",
                    retryable = false,
                )
            }
        }
    }

    /**
     * Reads [length] bytes starting at [offset].
     *
     * Only one chunk is held in memory at a time, so a 4 GB video uses the same
     * memory as a 2 MB photo.
     */
    private fun readChunk(path: Path, offset: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        fileSystem.openReadOnly(path).use { handle ->
            var read = 0
            while (read < length) {
                val n = handle.read(offset + read, buffer, read, length - read)
                if (n <= 0) break
                read += n
            }
            if (read < length) error("Short read on $path at $offset: $read of $length")
        }
        return buffer
    }

    private companion object {
        /**
         * Drive requires chunks to be a multiple of 256 KiB (except the last).
         * 8 MiB balances request overhead against how much work is repeated
         * when a chunk has to be retried.
         */
        const val CHUNK_SIZE = 8 * 1024 * 1024

        const val MAX_CHUNK_ATTEMPTS = 5
    }
}
