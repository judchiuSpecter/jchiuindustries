package dev.photohub.shared.source.mega

import dev.carlsen.mega.model.Node
import dev.carlsen.mega.util.CancellationToken
import dev.photohub.shared.auth.MegaAccountFactory.Account
import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.platform.fileSize
import dev.photohub.shared.platform.fileSystem
import dev.photohub.shared.platform.renderThumbnail
import dev.photohub.shared.source.MediaPage
import dev.photohub.shared.source.MediaSource
import dev.photohub.shared.source.MediaTypes
import kotlinx.io.buffered
import kotlinx.io.files.Path as KxPath
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import okio.Path

/**
 * MEGA as a PhotoHub media source.
 *
 * The SDK has no server-side thumbnail endpoint, so thumbnails are rendered
 * locally from a downloaded node and cached as small JPEGs. Nothing is deleted
 * from MEGA: listing and downloading are read-only, and uploads are copies.
 */
@OptIn(ExperimentalTime::class)
class MegaSource(
    override val config: SourceConfig,
    private val account: Account,
) : MediaSource {

    private val uploadTarget = MegaUploadTarget(account)

    override val canUpload: Boolean get() = true
    override val canDelete: Boolean get() = true

    /** Called only after the caller has verified a copy elsewhere. */
    override suspend fun delete(item: RemoteMedia): Result<Unit> = runCatching {
        val node = account.mega.getNodeByHash(item.remoteId)
            ?: error("MEGA node ${item.remoteId} is no longer available")
        // Permanent rather than trash: the point of archiving is to free cloud
        // space, and the verified copy already lives on the NAS.
        account.mega.delete(node, destroy = true)
    }

    /**
     * Lists every image and video in the account, newest first.
     *
     * Unlike Drive's `drive.file` grant, MEGA exposes the whole account, so the
     * walk includes the cloud drive and any shared roots but skips Trash and
     * Inbox. Unsorted duplicates (shared nodes reachable twice) are removed by
     * hash. No cursor is needed: the SDK has already loaded the whole tree.
     */
    override suspend fun list(cursor: String?): Result<MediaPage> = runCatching {
        if (cursor != null) return@runCatching MediaPage(emptyList(), null)

        val fs = account.mega.getFileSystem()
        val excluded = setOfNotNull(fs.trash?.hash, fs.inbox?.hash)
        val seen = HashSet<String>()
        val items = mutableListOf<RemoteMedia>()

        suspend fun walk(node: Node) {
            if (!seen.add(node.hash) || node.hash in excluded) return
            if (node.isFile) {
                val mime = MediaTypes.mimeTypeOf(node.name) ?: return
                items += RemoteMedia(
                    remoteId = node.hash,
                    displayName = node.name,
                    mimeType = mime,
                    isVideo = MediaTypes.isVideoMime(mime),
                    sizeBytes = node.size,
                    dateTakenEpochMs = node.timestamp.toEpochMilliseconds(),
                    // MEGA has no thumbnail URL; ThumbnailStore downloads and
                    // renders one into the cache before the grid needs it.
                    thumbnailUrl = null,
                )
            } else if (node.isFolder) {
                for (child in account.mega.getChildren(node)) walk(child)
            }
        }

        val roots = listOfNotNull(fs.root) + fs.sroots
        for (root in roots) walk(root)

        MediaPage(items.sortedByDescending { it.dateTakenEpochMs }, null)
    }

    /**
     * Downloads the node and renders a small JPEG into [target].
     *
     * The full download is unavoidable with this SDK; the important part is that
     * only the compact render is kept in the thumbnail cache, and the temporary
     * original is deleted even when decoding fails.
     */
    override suspend fun fetchThumbnail(item: RemoteMedia, target: Path): Result<Path> =
        runCatching {
            // Rendering a thumbnail means downloading the whole node with this
            // SDK. Large videos are common on MEGA and would turn a background
            // refresh into a multi-gigabyte transfer, so skip them explicitly.
            if (item.isVideo && item.sizeBytes > MAX_VIDEO_THUMB_BYTES) {
                error("Video ${item.displayName} is too large to thumbnail")
            }
            val node = nodeFor(item.remoteId)
            target.parent?.let { fileSystem.createDirectories(it) }
            val raw = target.parent?.resolve("${target.name}.original")
                ?: error("Thumbnail path has no parent directory")

            try {
                downloadNode(node, raw) { }
                if (!renderThumbnail(raw.toString(), target, THUMB_EDGE)) {
                    error("Could not render a MEGA thumbnail for ${item.displayName}")
                }
                target
            } finally {
                runCatching { fileSystem.delete(raw) }
            }
        }

    override suspend fun downloadOriginal(
        item: RemoteMedia,
        target: Path,
        onProgress: (Float) -> Unit,
    ): Result<Path> = runCatching {
        val node = nodeFor(item.remoteId)
        target.parent?.let { fileSystem.createDirectories(it) }
        val temp = target.parent?.resolve("${target.name}.part") ?: target
        try {
            downloadNode(node, temp, onProgress)
            if (temp != target) fileSystem.atomicMove(temp, target)
            onProgress(1f)
            target
        } catch (t: Throwable) {
            runCatching { fileSystem.delete(temp) }
            throw t
        }
    }

    /**
     * The queue uses [MegaUploadTarget]; this override keeps [MediaSource]
     * itself honest for callers that hold a source directly.
     */
    override suspend fun upload(
        local: Path,
        remoteFolder: String,
        onProgress: (Float) -> Unit,
    ): Result<RemoteMedia> = runCatching {
        val id = uploadTarget.upload(local.toString(), remoteFolder, "", onProgress)
        RemoteMedia(
            remoteId = id,
            displayName = local.name,
            mimeType = MediaTypes.mimeTypeOf(local.name) ?: "application/octet-stream",
            isVideo = MediaTypes.isVideoMime(MediaTypes.mimeTypeOf(local.name) ?: ""),
            sizeBytes = fileSize(local.toString()) ?: 0L,
            dateTakenEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
    }

    private fun nodeFor(hash: String): Node =
        account.mega.getNodeByHash(hash)
            ?: error("MEGA node $hash is no longer available")

    private suspend fun downloadNode(node: Node, target: Path, onProgress: (Float) -> Unit) {
        val sink = SystemFileSystem.sink(KxPath(target.toString())).buffered()
        account.mega.downloadFile(node, sink, CancellationToken.default()) { bytes, total ->
            if (total > 0) onProgress((bytes.toFloat() / total).coerceIn(0f, 1f))
        }
    }

    private companion object {
        /** Matches ThumbnailStore's cache resolution; no point rendering larger. */
        const val THUMB_EDGE = 384

        /** Above this, a video thumbnail would cost more bandwidth than it's worth. */
        const val MAX_VIDEO_THUMB_BYTES = 50L * 1024 * 1024
    }
}
