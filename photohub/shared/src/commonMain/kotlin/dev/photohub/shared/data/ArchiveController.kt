package dev.photohub.shared.data

import dev.photohub.shared.auth.DriveAccountFactory
import dev.photohub.shared.auth.MegaAccounts
import dev.photohub.shared.auth.NasAccounts
import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.platform.cacheDir
import dev.photohub.shared.platform.fileSystem
import dev.photohub.shared.platform.isUnmeteredNetwork
import dev.photohub.shared.source.MediaSource
import dev.photohub.shared.source.SourceRegistry
import dev.photohub.shared.source.nas.NasSourceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import okio.Path
import java.util.UUID

/**
 * Occasional cloud → NAS bulk archiver.
 *
 * This is deliberately not a background job: it deletes from the cloud, so the
 * user starts it explicitly from Sources after choosing a cutoff date. The
 * destination is the source marked as the archive target (which must be a NAS
 * for this bulk flow). Only linked cloud sources are considered, and a cloud
 * item is deleted only after a byte-identical copy has been verified. Every
 * failure keeps the cloud original and moves on to the next item.
 */
class ArchiveController(
    private val repository: MediaRepository,
    private val sources: SourceRegistry,
    private val drives: DriveAccountFactory,
    private val megas: MegaAccounts,
    private val nas: NasAccounts,
    private val networkAllowed: () -> Boolean = { isUnmeteredNetwork() },
) {

    data class State(
        val running: Boolean = false,
        val processed: Int = 0,
        val archived: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        val message: String? = null,
    )

    data class Summary(
        val processed: Int = 0,
        val archived: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
    ) {
        val text: String
            get() = "Archived $archived item(s) and removed them from the cloud; " +
                "$failed failed, $skipped skipped."
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** True when the button can do anything useful right now. */
    fun canArchive(): Boolean {
        val target = repository.archiveTarget()
        val all = repository.allSources()
        return target != null && target.enabled && target.type == SourceType.NAS_ARCHIVE &&
            nas.isLinked(target.id) &&
            all.any { it.enabled && it.isCloudSource() && it.isLinked() }
    }

    suspend fun run(cutoffEpochMs: Long): Result<Summary> {
        if (_state.value.running) {
            return Result.failure(IllegalStateException("An archive run is already in progress"))
        }
        if (!networkAllowed()) {
            val error = IllegalStateException("Connect to Wi-Fi before archiving.")
            _state.value = State(message = error.message)
            return Result.failure(error)
        }

        val all = repository.allSources()
        val target = repository.archiveTarget()
        val nasConfig = target?.takeIf {
            it.enabled && it.type == SourceType.NAS_ARCHIVE && nas.isLinked(it.id)
        } ?: return fail("Choose a connected NAS as the archive target in Settings first.")
        val clouds = all.filter { it.enabled && it.isCloudSource() && it.isLinked() }
        if (clouds.isEmpty()) return fail("Connect a cloud source in Settings first.")

        var summary = Summary()
        _state.value = State(running = true)
        try {
            for (cloud in clouds) {
                val source = sources.create(cloud)
                if (source == null) {
                    summary = summary.copy(failed = summary.failed + 1)
                    publish(summary, "Cannot open ${cloud.displayName}: unsupported source")
                    continue
                }
                if (!source.canDelete) {
                    summary = summary.copy(failed = summary.failed + 1)
                    continue
                }

                var cursor: String? = null
                val seenCursors = HashSet<String>()
                do {
                    val listed = source.list(cursor)
                    if (listed.isFailure) {
                        summary = summary.copy(failed = summary.failed + 1)
                        publish(summary, "Cannot list ${cloud.displayName}: ${listed.exceptionOrNull()?.message}")
                        break
                    }
                    val page = listed.getOrThrow()

                    for (item in page.items) {
                        if (item.dateTakenEpochMs <= 0L) {
                            // Without a date there is no safe "older than cutoff"
                            // decision, and the archive layout needs a folder.
                            summary = summary.copy(skipped = summary.skipped + 1)
                            continue
                        }
                        if (item.dateTakenEpochMs >= cutoffEpochMs) continue

                        summary = summary.copy(processed = summary.processed + 1)
                        publish(summary, "Archiving ${item.displayName}…")

                        val remotePath = archivePath(nasConfig, cloud, item)
                        val existingSize = nas.archivedFileSize(nasConfig, remotePath).getOrNull()
                        val copied = when {
                            existingSize != null && sizeMatches(existingSize, item.sizeBytes) -> true
                            existingSize != null -> {
                                publish(
                                    summary,
                                    "Archive copy of ${item.displayName} has a different size; cloud copy kept.",
                                )
                                false
                            }
                            else -> downloadAndCopy(source, item, remotePath, nasConfig, cloud, summary)
                        }

                        if (!copied) {
                            summary = summary.copy(failed = summary.failed + 1)
                            continue
                        }

                        val deleted = source.delete(item)
                        if (deleted.isSuccess) {
                            summary = summary.copy(archived = summary.archived + 1)
                        } else {
                            summary = summary.copy(failed = summary.failed + 1)
                            publish(
                                summary,
                                "Copied ${item.displayName} to NAS but could not remove it from " +
                                    "${cloud.displayName}: ${deleted.exceptionOrNull()?.message}. " +
                                    "It will be retried next run.",
                            )
                        }
                    }
                    cursor = page.nextCursor?.takeIf { seenCursors.add(it) }
                } while (cursor != null)
            }
        } finally {
            _state.value = State(
                processed = summary.processed,
                archived = summary.archived,
                failed = summary.failed,
                skipped = summary.skipped,
                message = summary.text,
            )
        }
        return Result.success(summary)
    }

    private fun fail(message: String): Result<Summary> {
        _state.value = State(message = message)
        return Result.failure(IllegalStateException(message))
    }

    private suspend fun downloadAndCopy(
        source: MediaSource,
        item: RemoteMedia,
        remotePath: String,
        nasConfig: SourceConfig,
        cloud: SourceConfig,
        summary: Summary,
    ): Boolean {
        val dir = cacheDir / "archive-in"
        fileSystem.createDirectories(dir)
        val local = dir / "${UUID.randomUUID()}-${safeName(item.displayName)}"
        try {
            val downloaded = source.downloadOriginal(item, local)
            if (downloaded.isFailure) {
                publish(
                    summary,
                    "Could not download ${item.displayName}: ${downloaded.exceptionOrNull()?.message}. Cloud copy kept.",
                )
                return false
            }
            val copied = nas.copyToArchive(nasConfig, local, remotePath)
            if (copied.isFailure) {
                publish(
                    summary,
                    "Could not archive ${item.displayName} from ${cloud.displayName}: " +
                        "${copied.exceptionOrNull()?.message}. Cloud copy kept.",
                )
            }
            return copied.isSuccess
        } finally {
            runCatching { fileSystem.delete(local) }
        }
    }

    private fun publish(summary: Summary, message: String) {
        _state.value = State(
            running = true,
            processed = summary.processed,
            archived = summary.archived,
            failed = summary.failed,
            skipped = summary.skipped,
            message = message,
        )
    }

    private fun SourceConfig.isCloudSource(): Boolean =
        type == SourceType.GOOGLE_DRIVE || type == SourceType.MEGA

    private fun SourceConfig.isLinked(): Boolean = when (type) {
        SourceType.GOOGLE_DRIVE -> drives.isLinked(id)
        SourceType.MEGA -> megas.isLinked(id)
        else -> false
    }

    private fun sizeMatches(remoteSize: Long, expected: Long): Boolean =
        expected <= 0L || remoteSize == expected

    /**
     * `PhotoHubArchive/<cloud>/YYYY/MM/DD/<name>-<id>.<ext>` under the NAS share.
     * Keeping the cloud prefix and a hash of the provider id prevents two
     * unrelated photos with the same filename from overwriting each other. The
     * extension is preserved so the NAS timeline can still recognise the file.
     */
    private fun archivePath(nasConfig: SourceConfig, cloud: SourceConfig, item: RemoteMedia): String {
        val settings = runCatching { Json.decodeFromString<NasSourceConfig>(nasConfig.configJson) }
            .getOrDefault(NasSourceConfig())
        val root = settings.remotePath.ifBlank { "/" }.trimEnd('/')
        val prefix = when (cloud.type) {
            SourceType.GOOGLE_DRIVE -> "GoogleDrive"
            SourceType.MEGA -> "MEGA"
            else -> "Cloud"
        }
        val date = Instant.fromEpochMilliseconds(item.dateTakenEpochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        val year = date.year
        val month = date.monthNumber.toString().padStart(2, '0')
        val day = date.dayOfMonth.toString().padStart(2, '0')
        val idHash = item.remoteId.hashCode().toUInt().toString(16)
        return "$root/PhotoHubArchive/$prefix/$year/$month/$day/${archiveFileName(item.displayName, idHash)}"
    }

    private fun archiveFileName(name: String, idHash: String): String {
        val safe = safeName(name)
        val dot = safe.lastIndexOf('.')
        return if (dot > 0 && dot < safe.length - 1) {
            "${safe.substring(0, dot)}-$idHash${safe.substring(dot)}"
        } else {
            "$safe-$idHash"
        }
    }

    private fun safeName(name: String): String {
        val cleaned = name.trim().map { c ->
            if (c.isLetterOrDigit() || c in ".-_ ") c else '_'
        }.joinToString("")
        return cleaned.ifBlank { "photo" }.take(120)
    }
}
