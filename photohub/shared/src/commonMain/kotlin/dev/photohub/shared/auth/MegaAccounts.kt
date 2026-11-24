package dev.photohub.shared.auth

import dev.photohub.shared.data.UploadTarget
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.StorageQuota
import dev.photohub.shared.source.MediaSource

/**
 * The MEGA counterpart of [DriveAccountFactory]: a per-instance account graph
 * behind a seam the controller, registry and upload queue can all use.
 *
 * Keeping this as an interface means the sources screen can be tested without
 * logging into MEGA: the fake only has to report which ids are linked.
 */
interface MegaAccounts {

    /** Cheap, synchronous check used to render a source card. */
    fun isLinked(sourceId: String): Boolean

    /**
     * Signs in and stores a resumable session for [sourceId].
     * Returns the account label to show in the UI.
     */
    suspend fun link(
        sourceId: String,
        email: String,
        password: String,
        mfa: String?,
    ): Result<String>

    /** Clears the session and drops the cached client. Never touches cloud data. */
    suspend fun unlink(sourceId: String)

    /** Account-wide storage quota, or null when offline / not linked. */
    suspend fun quota(sourceId: String): StorageQuota?

    /** Builds the media source for one configured instance. */
    suspend fun sourceFor(config: SourceConfig): MediaSource

    /** Builds the camera-upload target for one configured instance. */
    suspend fun targetFor(config: SourceConfig): UploadTarget
}
