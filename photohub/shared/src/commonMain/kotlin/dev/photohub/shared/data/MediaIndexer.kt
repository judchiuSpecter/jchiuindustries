package dev.photohub.shared.data

import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.source.MediaSource
import kotlin.time.Clock

/**
 * Pulls every page from a [MediaSource] and folds the results into the local
 * [PhotoHubDatabase] so the timeline can read them offline. Pure common code:
 * the platform-specific scanning lives behind the [MediaSource] interface.
 *
 * Indexing is idempotent — re-running updates existing rows (via upsert) and
 * removes rows for items the source no longer reports, so a photo deleted on
 * the device disappears from PhotoHub on the next index.
 */
class MediaIndexer(private val db: PhotoHubDatabase) {

    /** Outcome of one indexing pass. */
    data class Stats(val indexed: Int, val removed: Int)

    /**
     * Walks [source] fully and syncs its rows in [db].
     *
     * Listing is completed *before* any write, so a source that fails midway
     * leaves the database untouched rather than pruning rows that still exist.
     */
    suspend fun index(source: MediaSource): Result<Stats> {
        val sourceId = source.config.id
        val items = buildList {
            var cursor: String? = null
            do {
                val page = source.list(cursor).getOrElse { return Result.failure(it) }
                addAll(page.items)
                cursor = page.nextCursor
            } while (cursor != null)
        }

        val now = Clock.System.now().toEpochMilliseconds()
        val seenIds = items.mapTo(HashSet()) { rowId(sourceId, it.remoteId) }
        var removed = 0

        db.transaction {
            for (item in items) {
                db.mediaItemQueries.upsert(
                    id = rowId(sourceId, item.remoteId),
                    sourceId = sourceId,
                    remoteId = item.remoteId,
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    isVideo = if (item.isVideo) 1L else 0L,
                    sizeBytes = item.sizeBytes,
                    width = item.width?.toLong(),
                    height = item.height?.toLong(),
                    dateTaken = item.dateTakenEpochMs,
                    contentHash = item.contentHash,
                    thumbCachePath = null,
                    originalCachePath = null,
                    syncState = "INDEXED",
                    remoteThumbUrl = item.thumbnailUrl,
                    indexedAt = now,
                )
            }

            // Prune rows for items this source no longer reports.
            for (row in db.mediaItemQueries.selectBySource(sourceId).executeAsList()) {
                if (row.id !in seenIds) {
                    db.mediaItemQueries.delete(row.id)
                    removed++
                }
            }

            db.sourceQueries.setLastSynced(now, sourceId)
        }

        return Result.success(Stats(indexed = items.size, removed = removed))
    }

    private fun rowId(sourceId: String, remoteId: String): String = "$sourceId:$remoteId"
}
