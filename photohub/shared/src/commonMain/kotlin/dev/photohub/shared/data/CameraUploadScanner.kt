package dev.photohub.shared.data

import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.SourceType

/**
 * Finds indexed local photos that have not been queued for upload yet.
 *
 * Camera upload is defined against PhotoHub's own index rather than by watching
 * the filesystem: the indexer already knows every local photo, so a scan is a
 * database query instead of a directory walk. That also means upload picks up
 * anything that appeared while the app was not running, including photos from
 * before PhotoHub was installed.
 *
 * Uploads are always a **copy** — see DESIGN.md §8.5. Nothing is deleted
 * locally or remotely.
 */
class CameraUploadScanner(
    private val db: PhotoHubDatabase,
    private val repository: MediaRepository,
    private val uploads: UploadManager,
) {
    data class Result(
        val queued: Int = 0,
        /** Items whose bytes could not be read, or already queued. */
        val skipped: Int = 0,
        /** Set when the scan did not run; nothing was changed. */
        val reason: String? = null,
    )

    /**
     * Queues up to [limit] not-yet-uploaded local items.
     *
     * Bounded per pass because enqueueing hashes each file, and a first run on a
     * large library would otherwise hold a background worker for a very long
     * time. Later passes continue where this one stopped.
     */
    suspend fun scan(limit: Long = DEFAULT_LIMIT): Result {
        val target = repository.uploadTarget()
            ?: return Result(reason = "No cloud account is set as the upload target.")

        if (!target.enabled) {
            return Result(reason = "${target.displayName} is disconnected.")
        }

        // Uploading from one cloud to another is not the feature; only
        // device-local photos are camera-upload candidates.
        val localSourceIds = repository.allSources()
            .filter { it.type == SourceType.LOCAL }
            .map { it.id }
        if (localSourceIds.isEmpty()) return Result(reason = "No local source is configured.")

        var queued = 0
        var skipped = 0

        for (sourceId in localSourceIds) {
            val candidates = db.uploadQueueQueries
                .selectUnqueuedLocalItems(sourceId, target.id, limit - queued)
                .executeAsList()

            for (candidate in candidates) {
                // Prefer the content URI / local URI the indexer recorded, since
                // on Android that is the only reference that can be opened.
                val ref = candidate.remoteThumbUrl ?: candidate.remoteId

                val added = uploads.enqueueRef(
                    ref = ref,
                    targetSourceId = target.id,
                    // Folder is derived from capture time, not from when the
                    // upload happens, so a backlog still files by the right month.
                    remoteFolder = uploads.remoteFolderFor(
                        epochMs = candidate.dateTaken,
                        local = ref,
                        root = UploadManager.DEFAULT_ROOT,
                    ),
                )
                if (added) queued++ else skipped++
            }

            if (queued >= limit) break
        }

        return Result(queued = queued, skipped = skipped)
    }

    private companion object {
        /** One pass hashes this many files at most; the next pass continues. */
        const val DEFAULT_LIMIT = 200L
    }
}
