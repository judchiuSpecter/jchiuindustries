package dev.photohub.shared.auth

import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.source.MediaSource
import okio.Path

/**
 * The NAS counterpart of [MegaAccounts]: a per-instance connection graph behind
 * a seam the registry and sources screen can both use.
 *
 * Unlike Drive/MEGA there is no camera upload target or account-wide quota.
 * The source only writes when the user explicitly picks it as the archive target.
 */
interface NasAccounts {

    /** Cheap, synchronous check used to render a source card. */
    fun isLinked(sourceId: String): Boolean

    /**
     * Stores the password for [sourceId] and verifies the connection actually
     * works before committing to it, returning a label (the host) to show in
     * the UI.
     */
    suspend fun link(
        sourceId: String,
        host: String,
        port: Int,
        username: String,
        password: String,
        remotePath: String,
    ): Result<String>

    /** Clears the stored password and drops the cached client. Never touches the NAS. */
    suspend fun unlink(sourceId: String)

    /** Builds the media source for one configured instance. */
    suspend fun sourceFor(config: SourceConfig): MediaSource

    /**
     * Size of an already-archived file, used to skip re-downloading an item that
     * was copied before a previous run failed at the cloud-delete step.
     * Default keeps test fakes simple: "not present".
     */
    suspend fun archivedFileSize(config: SourceConfig, remotePath: String): Result<Long?> =
        Result.success(null)

    /**
     * Copies [local] to [remotePath] and verifies the uploaded bytes by reading
     * them back and comparing SHA-256 hashes. Returns success only when the NAS
     * holds a byte-identical copy; the cloud delete is allowed after that.
     */
    suspend fun copyToArchive(config: SourceConfig, local: Path, remotePath: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("This NAS source cannot accept archives"))
}
