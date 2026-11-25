package dev.photohub.shared.source.drive

import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.platform.fileSystem
import dev.photohub.shared.source.MediaPage
import dev.photohub.shared.source.MediaSource
import dev.photohub.shared.source.MediaTypes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path

/**
 * Persisted settings for a Drive source.
 *
 * Only non-secret values live here — this is stored in `Source.configJson` as
 * plaintext. Tokens go to the encrypted credential store.
 */
@Serializable
data class DriveSourceConfig(
    /** Root folder PhotoHub owns. Uploads go to `<root>/YYYY/MM`. */
    val rootFolderName: String = "PhotoHub",
    /** Cached id of [rootFolderName], so it is not re-resolved every sync. */
    val rootFolderId: String? = null,
    val accountEmail: String? = null,
) {
    companion object {
        const val SOURCE_ID = "google_drive"
    }
}

/**
 * Google Drive as a PhotoHub media source.
 *
 * Listing is scoped to the app's own folder tree, which under the `drive.file`
 * scope is all the app can see anyway. Thumbnails come from Drive's own
 * `thumbnailLink`, so no original is downloaded to render the grid.
 */
class GoogleDriveSource(
    override val config: SourceConfig,
    private val api: DriveApi,
    /** Present for sources built by the registry; tests can construct without it. */
    private val uploader: DriveUploader? = null,
) : MediaSource {

    private val settings: DriveSourceConfig =
        runCatching { Json.decodeFromString<DriveSourceConfig>(config.configJson) }
            .getOrElse { DriveSourceConfig() }

    override val canUpload: Boolean get() = true
    override val canDelete: Boolean get() = true

    override suspend fun list(cursor: String?): Result<MediaPage> = runCatching {
        // Deliberately unscoped by folder. Drive's `'<id>' in parents` matches
        // only *direct* children, and uploads are filed into
        // `PhotoHub/YYYY/MM`, so scoping to the root folder returned nothing
        // and every uploaded photo stayed invisible to the timeline.
        //
        // Listing without a parent filter is safe and correct here precisely
        // because the grant is `drive.file`: the app can only ever see files it
        // created itself, so this is "PhotoHub's own media at any depth" rather
        // than the user's whole Drive. Re-introducing a folder filter would
        // require either a recursive walk of the year/month tree or tracking
        // folder ids, both of which buy nothing under this scope.
        val page = api.listMedia(folderId = null, pageToken = cursor)
        MediaPage(
            items = page.files.map { it.toRemoteMedia() },
            nextCursor = page.nextPageToken,
        )
    }

    override suspend fun fetchThumbnail(item: RemoteMedia, target: Path): Result<Path> =
        runCatching {
            val link = item.thumbnailUrl
                ?: error("Drive item ${item.remoteId} has no thumbnail link")
            val response = api.downloadUrl(link)
            if (!response.status.isSuccess()) {
                throw DriveApiException(
                    response.status.value,
                    "Thumbnail download failed (${response.status.value})",
                    retryable = response.status.value >= 500 || response.status.value == 429,
                )
            }
            writeChannel(response.bodyAsChannel(), target) { }
            target
        }

    override suspend fun downloadOriginal(
        item: RemoteMedia,
        target: Path,
        onProgress: (Float) -> Unit,
    ): Result<Path> = runCatching {
        val response = api.download(item.remoteId)
        if (!response.status.isSuccess()) {
            throw DriveApiException(
                response.status.value,
                "Download failed (${response.status.value})",
                retryable = response.status.value >= 500 || response.status.value == 429,
            )
        }
        writeChannel(response.bodyAsChannel(), target) { written ->
            if (item.sizeBytes > 0) onProgress((written.toFloat() / item.sizeBytes).coerceIn(0f, 1f))
        }
        onProgress(1f)
        target
    }

    /** Called only after the caller has verified a copy elsewhere. */
    override suspend fun delete(item: RemoteMedia): Result<Unit> = runCatching {
        api.delete(item.remoteId)
    }

    /**
     * Writes a local file through the same resumable uploader camera uploads use.
     * The archive controller names the temp file after the original, so Drive
     * receives a recognisable filename rather than a UUID.
     */
    override suspend fun upload(
        local: Path,
        remoteFolder: String,
        onProgress: (Float) -> Unit,
    ): Result<RemoteMedia> = runCatching {
        val uploader = uploader ?: error("Drive uploads are unavailable for ${config.id}")
        val mime = MediaTypes.mimeTypeOf(local.name) ?: "application/octet-stream"
        uploader.upload(local.toString(), remoteFolder, mime, onProgress).toRemoteMedia()
    }

    /**
     * Streams [channel] to [target] via a temp file.
     *
     * Downloads are written to `.part` and renamed on completion so an
     * interrupted transfer cannot leave a truncated file that later looks like a
     * valid cached original.
     */
    private suspend fun writeChannel(
        channel: ByteReadChannel,
        target: Path,
        onBytes: (Long) -> Unit,
    ) {
        target.parent?.let { fileSystem.createDirectories(it) }
        val temp = target.parent?.resolve("${target.name}.part") ?: target

        var written = 0L
        try {
            fileSystem.write(temp) {
                val buffer = ByteArray(DOWNLOAD_BUFFER)
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    write(buffer, 0, read)
                    written += read
                    onBytes(written)
                }
            }
            if (temp != target) fileSystem.atomicMove(temp, target)
        } catch (t: Throwable) {
            runCatching { fileSystem.delete(temp) }
            throw t
        }
    }

    private fun DriveFile.toRemoteMedia(): RemoteMedia {
        val isVideo = mimeType.startsWith("video/")
        return RemoteMedia(
            remoteId = id,
            displayName = name,
            mimeType = mimeType,
            isVideo = isVideo,
            sizeBytes = size?.toLongOrNull() ?: 0L,
            // EXIF capture time first: createdTime is when it was *uploaded*,
            // which for a migrated library is wrong by years.
            dateTakenEpochMs = parseRfc3339(imageMediaMetadata?.time)
                ?: parseRfc3339(createdTime)
                ?: parseRfc3339(modifiedTime)
                ?: 0L,
            width = imageMediaMetadata?.width ?: videoMediaMetadata?.width,
            height = imageMediaMetadata?.height ?: videoMediaMetadata?.height,
            thumbnailUrl = thumbnailLink,
            // Drive exposes MD5, not SHA-256, so it cannot fill contentHash,
            // which the dedupe logic defines as SHA-256.
            contentHash = null,
        )
    }

    private companion object {
        const val DOWNLOAD_BUFFER = 64 * 1024
    }
}
