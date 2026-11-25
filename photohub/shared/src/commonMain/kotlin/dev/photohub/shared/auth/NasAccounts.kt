package dev.photohub.shared.auth

import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.source.MediaSource

/**
 * The NAS counterpart of [MegaAccounts]: a per-instance connection graph behind
 * a seam the registry and sources screen can both use.
 *
 * Unlike Drive/MEGA there is no upload target or account-wide quota — the NAS
 * archive is read-only, so this interface is deliberately smaller.
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
}
