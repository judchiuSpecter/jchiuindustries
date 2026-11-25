package dev.photohub.shared.source

import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.model.SourceConfig
import okio.Path

/**
 * A page of media returned by [MediaSource.list], with an opaque cursor for the next page.
 * A null [nextCursor] means there are no more pages.
 */
data class MediaPage(
    val items: List<RemoteMedia>,
    val nextCursor: String?,
)

/**
 * Abstraction over every photo source: local device storage, Google Drive, MEGA,
 * OneDrive, Takeout archives, and the NAS archive. Implementations live in each
 * platform's source set where they need platform APIs (MediaStore, java.io, etc.)
 * but the interface is common.
 *
 * All read methods must be usable offline for whatever is already cached; the
 * contract is that the sync layer calls these only when a refresh is explicitly
 * requested. Sources must never block the timeline — failures are returned, not thrown.
 */
interface MediaSource {

    val config: SourceConfig

    /** List one page of media, newest first. [cursor] is null for the first page. */
    suspend fun list(cursor: String?): Result<MediaPage>

    /** Download a thumbnail for [item] to [target]. */
    suspend fun fetchThumbnail(item: RemoteMedia, target: Path): Result<Path>

    /** Download the full-resolution original for [item] to [target]. */
    suspend fun downloadOriginal(
        item: RemoteMedia,
        target: Path,
        onProgress: (Float) -> Unit = {},
    ): Result<Path>

    // ---- Optional write capabilities ----

    /**
     * Whether this source can accept a local file. Default false.
     *
     * This is a capability of the source itself. Camera uploads use a narrower
     * policy in `AccountsController` (Drive/MEGA only); Archive may use any
     * writable source, including a NAS or the device's own storage.
     */
    val canUpload: Boolean get() = false

    /**
     * Upload [local] to [remoteFolder] in this source. Only meaningful when
     * [canUpload] is true. [remoteFolder] is a source-relative path; sources
     * with a configured root (notably NAS) add that root themselves.
     */
    suspend fun upload(
        local: Path,
        remoteFolder: String,
        onProgress: (Float) -> Unit = {},
    ): Result<RemoteMedia> =
        Result.failure(UnsupportedOperationException("Source does not support upload"))

    /**
     * Whether this source permits deletion. Used only by explicit user actions
     * (Delete/Archive), never by timeline sync or camera upload.
     */
    val canDelete: Boolean get() = false

    suspend fun delete(item: RemoteMedia): Result<Unit> =
        Result.failure(UnsupportedOperationException("Source does not support delete"))
}
