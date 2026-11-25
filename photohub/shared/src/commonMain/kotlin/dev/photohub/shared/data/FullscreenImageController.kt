package dev.photohub.shared.data

import dev.photohub.shared.db.MediaItem
import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.platform.cacheDir
import dev.photohub.shared.platform.fileSystem
import dev.photohub.shared.source.SourceRegistry
import okio.Path
import java.io.File

/**
 * Supplies full-resolution pixels for the fullscreen viewer.
 *
 * Local items already resolve through a `content://` URI or an absolute path, so
 * there is nothing to fetch. Remote items have only a thumbnail URL in the index;
 * opening one downloads the original through its own source (with that account's
 * OAuth token) and records the cache path, so the second open is instant and
 * works offline.
 */
class FullscreenImageController(
    private val repository: MediaRepository,
    private val sources: SourceRegistry,
) {
    /**
     * Returns a local path to the original, or null when none can be produced.
     *
     * Failure is deliberately non-fatal: the viewer falls back to the thumbnail
     * URL in that case, so an offline or revoked account still shows something.
     */
    suspend fun ensureOriginal(item: MediaItem): String? {
        item.originalCachePath?.takeIf { File(it).exists() }?.let { return it }

        val config = repository.sourceById(item.sourceId) ?: return null
        if (config.type !in REMOTE_ORIGINAL_TYPES || !config.enabled) return null
        val source = sources.create(config) ?: return null

        val remote = RemoteMedia(
            remoteId = item.remoteId,
            displayName = item.displayName,
            mimeType = item.mimeType,
            isVideo = item.isVideo == 1L,
            sizeBytes = item.sizeBytes,
            dateTakenEpochMs = item.dateTaken,
            width = item.width?.toInt(),
            height = item.height?.toInt(),
            thumbnailUrl = item.remoteThumbUrl,
            contentHash = item.contentHash,
        )

        val dir = cacheDir / ORIGINALS_DIR
        val target = dir / "${item.id.safeFileName()}.bin"
        return runCatching {
            source.downloadOriginal(remote, target).getOrThrow()
            val path = target.toString()
            repository.setOriginalCache(path, item.id)
            path
        }.getOrNull()
    }

    /** Row ids contain ':' and '/', which are not portable in filenames. */
    private fun String.safeFileName(): String = map { c ->
        if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_'
    }.joinToString("").takeLast(80)

    private companion object {
        const val ORIGINALS_DIR = "originals"

        /** Sources whose thumbnail is too small to view full-screen as-is. */
        val REMOTE_ORIGINAL_TYPES = setOf(SourceType.GOOGLE_DRIVE, SourceType.MEGA, SourceType.NAS_ARCHIVE)
    }
}
