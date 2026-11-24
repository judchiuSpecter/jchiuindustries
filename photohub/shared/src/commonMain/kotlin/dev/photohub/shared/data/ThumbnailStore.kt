package dev.photohub.shared.data

import dev.photohub.shared.platform.cacheDir
import dev.photohub.shared.platform.fileSystem
import dev.photohub.shared.platform.renderThumbnail
import dev.photohub.shared.platform.thumbnailConcurrency
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.source.MediaSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okio.Path

/**
 * Generates and tracks downscaled thumbnails for indexed media.
 *
 * The timeline grid draws ~108dp cells, so pointing the image loader at a 4K
 * original means decoding tens of megapixels per cell. Thumbnails are rendered
 * once into the app cache and recorded in `thumbCachePath`, after which the grid
 * only ever decodes small files.
 *
 * Thumbnails are derived data: deleting the cache directory is always safe, and
 * missing files are regenerated on the next pass.
 */
class ThumbnailStore(private val db: PhotoHubDatabase) {

    companion object {
        /** How many downloaded thumbnails to build per refresh. */
        const val REMOTE_THUMB_LIMIT = 120
    }

    /** Longest edge of a generated thumbnail, in pixels. */
    private val maxEdge = 384

    private val thumbDir: Path by lazy {
        (cacheDir / "thumbs").also { fileSystem.createDirectories(it) }
    }

    data class Stats(val generated: Int, val reused: Int, val failed: Int) {
        val completed: Int get() = generated + reused + failed
    }

    /**
     * Fills in thumbnails for items that do not have one yet, newest first so
     * the visible top of the timeline sharpens before older photos.
     *
     * Decoding is CPU-bound, so items are rendered [thumbnailConcurrency] at a
     * time. Database writes are applied serially per batch — SQLite has a single
     * connection, and funnelling writes through the collecting coroutine keeps
     * them off the worker threads entirely.
     *
     * [onProgress] fires once per batch so the UI can sharpen incrementally
     * instead of waiting for the whole library.
     */
    suspend fun generateMissing(
        limit: Int = Int.MAX_VALUE,
        onProgress: (completed: Int) -> Unit = {},
    ): Stats {
        val pending = db.mediaItemQueries.selectMissingThumbnails(limit.toLong()).executeAsList()
        if (pending.isEmpty()) return Stats(0, 0, 0)

        var generated = 0
        var reused = 0
        var failed = 0

        /** What one worker determined about a single item. */
        data class Rendered(val id: String, val path: Path?, val wasCached: Boolean)

        for (batch in pending.chunked(thumbnailConcurrency)) {
            val results = coroutineScope {
                batch.map { row ->
                    async {
                        val target = thumbDir / "${row.id.thumbFileName()}"

                        // A file left by a previous run is reused; only the DB
                        // link was lost, so there is nothing to re-render.
                        if (fileSystem.exists(target)) {
                            return@async Rendered(row.id, target, wasCached = true)
                        }

                        // Android reads via the content URI, desktop via the path.
                        val ref = row.remoteThumbUrl ?: row.remoteId
                        val ok = runCatching { renderThumbnail(ref, target, maxEdge) }
                            .getOrDefault(false)
                        Rendered(row.id, target.takeIf { ok }, wasCached = false)
                    }
                }.awaitAll()
            }

            // Serialized write phase: one transaction per batch.
            db.transaction {
                for (r in results) {
                    if (r.path == null) {
                        // Leave thumbCachePath null so the cell falls back to the
                        // original and the item is retried on a later pass.
                        failed++
                    } else {
                        db.mediaItemQueries.setThumbCache(r.path.toString(), r.id)
                        if (r.wasCached) reused++ else generated++
                    }
                }
            }

            onProgress(generated + reused + failed)
        }

        return Stats(generated = generated, reused = reused, failed = failed)
    }

    /**
     * Builds thumbnails that require bytes from a remote source.
     *
     * Drive and local media already have an image reference, so
     * [generateMissing] handles them. A source like MEGA has no thumbnail URL;
     * its [MediaSource.fetchThumbnail] downloads and renders one instead. The
     * per-sync cap keeps a large first run from saturating the connection —
     * the next refresh picks up where this one stopped because successful rows
     * no longer appear in the query.
     */
    suspend fun fetchRemote(
        source: MediaSource,
        limit: Int = REMOTE_THUMB_LIMIT,
        onProgress: (Int) -> Unit = {},
    ): Stats {
        val pending = db.mediaItemQueries
            .selectMissingThumbnailsForSource(source.config.id, limit.toLong())
            .executeAsList()
        if (pending.isEmpty()) return Stats(0, 0, 0)

        var generated = 0
        var failed = 0
        for (row in pending) {
            val target = thumbDir / "${row.id.thumbFileName()}"
            val remote = RemoteMedia(
                remoteId = row.remoteId,
                displayName = row.displayName,
                mimeType = row.mimeType,
                isVideo = row.isVideo == 1L,
                sizeBytes = row.sizeBytes,
                dateTakenEpochMs = row.dateTaken,
                thumbnailUrl = row.remoteThumbUrl,
                contentHash = row.contentHash,
            )
            val path = source.fetchThumbnail(remote, target).getOrNull()
            if (path != null) {
                db.mediaItemQueries.setThumbCache(path.toString(), row.id)
                generated++
            } else {
                failed++
            }
            onProgress(generated + failed)
        }
        return Stats(generated = generated, reused = 0, failed = failed)
    }

    /**
     * Row ids contain ':' and '/', neither of which is portable in a filename.
     * The sanitized prefix keeps thumbnails recognizable while debugging; the
     * appended hash keeps ids that sanitize identically from colliding.
     */
    private fun String.thumbFileName(): String {
        val safe = map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .takeLast(48)
        return "$safe-${hashCode().toUInt().toString(16)}.jpg"
    }
}
