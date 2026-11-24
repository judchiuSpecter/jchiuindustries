package dev.photohub.shared.data

import dev.photohub.shared.auth.ReauthRequiredException
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.platform.displayName
import dev.photohub.shared.platform.fileSize
import dev.photohub.shared.platform.fileSystem
import dev.photohub.shared.platform.sha256OfFile
import dev.photohub.shared.source.MediaTypes
import dev.photohub.shared.source.drive.DriveApiException
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.Path

/**
 * What an uploader must provide for [UploadManager] to drive it.
 *
 * Abstracting this keeps the queue logic — dedupe, retry, bookkeeping —
 * independent of Drive, so MEGA and OneDrive reuse all of it in M4/M5.
 */
interface UploadTarget {
    /** Source id this target writes to, matching `Source.id`. */
    val sourceId: String

    /**
     * Uploads [local] into [remoteFolder]; returns the remote id.
     *
     * [local] is the opaque queue reference (a `content://` URI on Android), so
     * implementations must read it through the platform file seam rather than
     * okio.
     */
    suspend fun upload(
        local: String,
        remoteFolder: String,
        mimeType: String,
        onProgress: (Float) -> Unit,
    ): String
}

/**
 * Durable camera-upload queue.
 *
 * Uploads are a **copy**: the DCIM file is never deleted, and nothing is ever
 * removed from the cloud. Queue state lives in SQLite so a reboot or app kill
 * resumes instead of losing pending work.
 *
 * Progress is deliberately coarse-grained in the DB (state transitions only);
 * per-byte progress stays in memory, since writing it would mean thousands of
 * writes per file.
 */
class UploadManager(
    private val db: PhotoHubDatabase,
    private val now: () -> Long,
) {
    data class Stats(
        val uploaded: Int = 0,
        val skippedDuplicate: Int = 0,
        val failed: Int = 0,
        /** Set when the queue stopped early; remaining items stay QUEUED. */
        val haltedReason: String? = null,
    )

    /**
     * Adds [local] to the queue for [targetSourceId].
     *
     * The file is hashed now rather than at upload time so the unique index on
     * `(sha256, targetSourceId)` can reject a re-queued duplicate immediately —
     * this is what makes a repeated camera scan cheap.
     *
     * @return true when newly queued, false when already present.
     */
    suspend fun enqueue(
        local: String,
        targetSourceId: String,
        remoteFolder: String? = null,
    ): Boolean = enqueueRef(local, targetSourceId, remoteFolder)

    /**
     * Queues a media reference, which on Android may be a `content://` URI
     * rather than a path.
     *
     * MediaStore identifies camera photos by content URI, and on Android 10+
     * their real paths are frequently unreadable — so the queue stores whatever
     * reference can actually be opened, and the platform resolves it.
     */
    suspend fun enqueueRef(
        ref: String,
        targetSourceId: String,
        remoteFolder: String? = null,
    ): Boolean {
        val size = fileSize(ref) ?: return false

        val hash = sha256OfFile(ref)
        val id = "$targetSourceId:${hash ?: ref}"

        val before = db.uploadQueueQueries.countByState("QUEUED").executeAsOne()
        db.uploadQueueQueries.enqueue(
            id = id,
            localPath = ref,
            sha256 = hash,
            sizeBytes = size,
            targetSourceId = targetSourceId,
            targetRemotePath = remoteFolder,
            queuedAt = now(),
        )
        return db.uploadQueueQueries.countByState("QUEUED").executeAsOne() > before
    }

    /**
     * Uploads everything pending for [target].
     *
     * Items are processed oldest-first so the backlog drains in capture order.
     * The pass stops early on an auth failure — every subsequent item would fail
     * the same way, and hammering a revoked grant risks rate limiting.
     */
    suspend fun runPending(
        target: UploadTarget,
        onProgress: (completed: Int, total: Int, fileProgress: Float) -> Unit = { _, _, _ -> },
    ): Stats {
        val pending = db.uploadQueueQueries.selectPending().executeAsList()
            .filter { it.targetSourceId == target.sourceId }
        if (pending.isEmpty()) return Stats()

        var uploaded = 0
        var skipped = 0
        var failed = 0
        var completed = 0

        for (item in pending) {
            // The user may have deleted the photo after it was queued. Checked
            // through the platform rather than okio, since on Android the
            // reference is usually a content URI that okio cannot resolve.
            if (fileSize(item.localPath) == null) {
                db.uploadQueueQueries.markFailed("Local file no longer exists", now(), item.id)
                failed++
                completed++
                continue
            }

            // Already in the cloud from a previous device or run.
            if (item.sha256 != null && isAlreadyUploaded(item.sha256, target.sourceId)) {
                db.uploadQueueQueries.markSkippedDuplicate(now(), item.id)
                skipped++
                completed++
                continue
            }

            db.uploadQueueQueries.markRunning(item.id)

            val folder = item.targetRemotePath
                ?: remoteFolderFor(item.queuedAt, item.localPath, DEFAULT_ROOT)
            val mime = MediaTypes.mimeTypeOf(displayName(item.localPath))
                ?: "application/octet-stream"

            val result = runCatching {
                target.upload(item.localPath, folder, mime) { p -> onProgress(completed, pending.size, p) }
            }

            result.fold(
                onSuccess = {
                    db.uploadQueueQueries.markDone(now(), item.id)
                    uploaded++
                },
                onFailure = { cause ->
                    // Auth is broken for every item, so stop rather than burning
                    // through the queue marking everything failed.
                    if (cause is ReauthRequiredException) {
                        db.uploadQueueQueries.markRetry(cause.message, item.id)
                        return Stats(uploaded, skipped, failed, cause.message)
                    }

                    val retryable = (cause as? DriveApiException)?.retryable ?: true
                    if (retryable && item.attempts + 1 < MAX_ATTEMPTS) {
                        // Back to QUEUED so the next pass picks it up.
                        db.uploadQueueQueries.markRetry(cause.message, item.id)
                    } else {
                        db.uploadQueueQueries.markFailed(cause.message, now(), item.id)
                        failed++
                    }
                },
            )
            completed++
            onProgress(completed, pending.size, 1f)
        }

        return Stats(uploaded, skipped, failed)
    }

    /** Items still waiting to upload, for deciding whether to run another pass. */
    fun pendingCount(): Long =
        db.uploadQueueQueries.countByState("QUEUED").executeAsOne()

    /** Counts by state, for the accounts/status UI. */
    fun countByState(state: String): Long =
        db.uploadQueueQueries.countByState(state).executeAsOne()

    /** True when this content already reached the cloud. */
    private fun isAlreadyUploaded(sha256: String, targetSourceId: String): Boolean =
        db.uploadQueueQueries.selectDoneByHash(sha256, targetSourceId).executeAsOneOrNull() != null

    /**
     * Builds the `PhotoHub/YYYY/MM` destination from capture time.
     *
     * Date-based foldering keeps any single folder small, which matters because
     * cloud UIs and APIs both degrade on folders with tens of thousands of
     * children. The device-local zone is used so a photo files under the month
     * the user took it in, not a UTC-shifted one.
     */
    internal fun remoteFolderFor(
        epochMs: Long,
        local: String,
        root: String,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val source = if (epochMs > 0) {
            epochMs
        } else {
            // Fall back to the file's own mtime before giving up on a date.
            // Only a real path has an mtime available here; a content URI
            // falls through to "now", which is correct enough for foldering.
            runCatching { fileSystem.metadata(toPath(local)).lastModifiedAtMillis }
                .getOrNull() ?: now()
        }
        val dt = Instant.fromEpochMilliseconds(source).toLocalDateTime(zone)
        val month = dt.monthNumber.toString().padStart(2, '0')
        return "$root/${dt.year}/$month"
    }

    private fun toPath(value: String): Path = with(okio.Path.Companion) { value.toPath() }

    companion object {
        const val DEFAULT_ROOT = "PhotoHub"

        /** Transient failures get a few passes before being written off. */
        const val MAX_ATTEMPTS = 5
    }
}
