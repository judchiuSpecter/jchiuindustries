package dev.photohub.shared.data

import dev.photohub.shared.db.MediaItem
import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.platform.cacheDir
import dev.photohub.shared.platform.fileSystem
import dev.photohub.shared.platform.isUnmeteredNetwork
import dev.photohub.shared.platform.sha256OfFile
import dev.photohub.shared.source.MediaSource
import dev.photohub.shared.source.SourceRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.Path
import okio.Path.Companion.toPath
import java.util.UUID

/**
 * Explicit, user-triggered Delete/Archive actions for timeline items.
 *
 * These are deliberately separate from sync: nothing here runs automatically.
 * Delete removes the item from its owning source only. Archive first copies the
 * original to the one designated archive target, verifies the bytes, and only
 * then deletes the source copy and the local index row. Any failed step keeps
 * the original and the index row, so a retry is always safe.
 */
class PhotoActionsController(
    private val repository: MediaRepository,
    private val sources: SourceRegistry,
    private val networkAllowed: () -> Boolean = { isUnmeteredNetwork() },
) {

    data class State(
        val running: Boolean = false,
        val message: String? = null,
    )

    /** Counts for one batch action, shown after the run finishes. */
    data class Outcome(
        val succeeded: Int = 0,
        val failed: Int = 0,
    ) {
        val text: String
            get() = when {
                failed == 0 -> "Done: $succeeded item(s)."
                succeeded == 0 -> "Failed: $failed item(s). Originals were kept."
                else -> "$succeeded item(s) done, $failed failed. Failed originals were kept."
            }
    }

    /** Most recent failure detail, kept so the final summary does not hide it. */
    private var lastMessage: String? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Whether Archive is meaningful for this item. Actual reachability is checked
     * when the action runs; this only covers the decisions the UI can make from
     * cached data (is there a target, and is the photo already there?).
     */
    fun canArchive(item: MediaItem): Boolean {
        val target = repository.archiveTarget() ?: return false
        if (item.sourceId == target.id) return false
        return repository.sourceById(item.sourceId)?.enabled == true
    }

    fun clearMessage() {
        lastMessage = null
        _state.value = State()
    }

    /** Deletes the source copy and the index row. No confirmation here — the UI owns that. */
    suspend fun delete(items: List<MediaItem>): Result<Outcome> {
        if (_state.value.running) return alreadyRunning()
        lastMessage = null
        _state.value = State(running = true)
        var outcome = Outcome()

        try {
            for (item in items.distinctBy { it.id }) {
                val config = repository.sourceById(item.sourceId)
                if (config == null) {
                    outcome = outcome.copy(failed = outcome.failed + 1)
                    continue
                }
                val source = sources.create(config)
                if (source == null || !source.canDelete) {
                    outcome = outcome.copy(failed = outcome.failed + 1)
                    publish(outcome, "${item.displayName}: source cannot be deleted from")
                    continue
                }

                val deleted = runCatching { source.delete(item.toRemoteMedia()) }
                    .getOrElse { Result.failure(it) }
                if (deleted.isSuccess) {
                    finishRemoval(item)
                    outcome = outcome.copy(succeeded = outcome.succeeded + 1)
                } else {
                    outcome = outcome.copy(failed = outcome.failed + 1)
                    publish(
                        outcome,
                        "Could not delete ${item.displayName} from ${config.displayName}: " +
                            (deleted.exceptionOrNull()?.message ?: "unknown error"),
                    )
                }
            }
        } finally {
            _state.value = finishedState(outcome)
        }
        return Result.success(outcome)
    }

    /**
     * Moves items to the designated archive target. Requires Wi-Fi on Android:
     * this copies full originals and deletes source copies, same as the bulk archiver.
     */
    suspend fun archive(items: List<MediaItem>): Result<Outcome> {
        if (_state.value.running) return alreadyRunning()
        if (!networkAllowed()) return fail("Connect to Wi-Fi before archiving.")

        val targetConfig = repository.archiveTarget()
            ?: return fail("Choose an archive target in Settings → Sources first.")
        val targetSource = sources.create(targetConfig)
            ?: return fail("The archive target is not available.")
        if (!targetSource.canUpload) {
            return fail("${targetConfig.displayName} cannot receive archived photos.")
        }

        lastMessage = null
        _state.value = State(running = true)
        var outcome = Outcome()
        val workDir = cacheDir / "archive-in"

        try {
            for (item in items.distinctBy { it.id }) {
                val sourceConfig = repository.sourceById(item.sourceId)
                if (sourceConfig == null || sourceConfig.id == targetConfig.id) {
                    outcome = outcome.copy(failed = outcome.failed + 1)
                    continue
                }
                val source = sources.create(sourceConfig)
                if (source == null || !source.canDelete) {
                    outcome = outcome.copy(failed = outcome.failed + 1)
                    publish(outcome, "${item.displayName}: source cannot be archived from")
                    continue
                }

                publish(outcome, "Archiving ${item.displayName}…")
                val localFile = workDir / "${UUID.randomUUID()}-${ArchivePaths.fileName(item.displayName, item.id)}"
                val verifyFile = workDir / "${UUID.randomUUID()}-verify.bin"

                val copied = try {
                    runCatching { fileSystem.createDirectories(workDir) }
                    val downloaded = source.downloadOriginal(item.toRemoteMedia(), localFile)
                    if (downloaded.isFailure) {
                        publish(
                            outcome,
                            "Could not download ${item.displayName}: " +
                                (downloaded.exceptionOrNull()?.message ?: "unknown error"),
                        )
                        false
                    } else {
                        val working = downloaded.getOrThrow()
                        val remoteFolder = ArchivePaths.relativeFolder(sourceConfig.type, item.dateTaken)
                        val uploaded = targetSource.upload(working, remoteFolder)
                        if (uploaded.isFailure) {
                            publish(
                                outcome,
                                "Could not copy ${item.displayName} to ${targetConfig.displayName}: " +
                                    (uploaded.exceptionOrNull()?.message ?: "unknown error"),
                            )
                            false
                        } else {
                            // The NAS writer reads its own copy back before returning.
                            // Other targets are checked here by hashing a fresh read.
                            val verified = targetConfig.type == SourceType.NAS_ARCHIVE ||
                                verifyCopy(targetSource, uploaded.getOrThrow(), working, verifyFile)
                            if (!verified) {
                                publish(outcome, "Archive copy of ${item.displayName} failed verification; original kept.")
                                runCatching { targetSource.delete(uploaded.getOrThrow()) }
                                false
                            } else {
                                val deleted = runCatching { source.delete(item.toRemoteMedia()) }
                                    .getOrElse { Result.failure(it) }
                                if (deleted.isSuccess) {
                                    true
                                } else {
                                    publish(
                                        outcome,
                                        "Copied ${item.displayName} to ${targetConfig.displayName} but could not " +
                                            "remove it from ${sourceConfig.displayName}: " +
                                            (deleted.exceptionOrNull()?.message ?: "unknown error"),
                                    )
                                    false
                                }
                            }
                        }
                    }
                } finally {
                    // Local desktop sources return the original path rather than a
                    // temp copy; never delete that when cleaning up.
                    if (localFile != item.remoteId.toPathOrNull()) {
                        runCatching { fileSystem.delete(localFile) }
                    }
                    runCatching { fileSystem.delete(verifyFile) }
                }

                if (copied) {
                    finishRemoval(item)
                    outcome = outcome.copy(succeeded = outcome.succeeded + 1)
                } else {
                    outcome = outcome.copy(failed = outcome.failed + 1)
                }
            }
        } finally {
            _state.value = finishedState(outcome)
        }
        return Result.success(outcome)
    }

    /** Re-downloads the archived copy and compares SHA-256 with the local original. */
    private suspend fun verifyCopy(
        target: MediaSource,
        uploaded: RemoteMedia,
        working: Path,
        verifyFile: Path,
    ): Boolean {
        val expected = sha256OfFile(working.toString()) ?: return false
        val readback = target.downloadOriginal(uploaded, verifyFile)
        if (readback.isFailure) return false
        val actual = sha256OfFile(readback.getOrThrow().toString()) ?: return false
        return expected == actual
    }

    /** Drops cached pixels too — otherwise a removed item keeps wasting space. */
    private fun finishRemoval(item: MediaItem) {
        repository.deleteMedia(item.id)
        for (path in listOf(item.thumbCachePath, item.originalCachePath)) {
            path?.takeIf { it.isNotBlank() }?.let { cached ->
                runCatching { fileSystem.delete(cached.toPath()) }
            }
        }
    }

    private fun publish(outcome: Outcome, message: String) {
        lastMessage = message
        _state.value = State(running = true, message = message)
    }

    private fun alreadyRunning(): Result<Outcome> = fail("Another photo action is already running")

    private fun fail(message: String): Result<Outcome> {
        lastMessage = message
        _state.value = State(message = message)
        return Result.failure(IllegalStateException(message))
    }

    private fun MediaItem.toRemoteMedia() = RemoteMedia(
        remoteId = remoteId,
        displayName = displayName,
        mimeType = mimeType,
        isVideo = isVideo == 1L,
        sizeBytes = sizeBytes,
        dateTakenEpochMs = dateTaken,
        width = width?.toInt(),
        height = height?.toInt(),
        thumbnailUrl = remoteThumbUrl,
        contentHash = contentHash,
    )

    private fun String.toPathOrNull(): Path? = runCatching { toPath() }.getOrNull()

    private fun finishedState(outcome: Outcome): State = State(
        message = listOfNotNull(outcome.text, lastMessage).joinToString(" "),
    )
}
