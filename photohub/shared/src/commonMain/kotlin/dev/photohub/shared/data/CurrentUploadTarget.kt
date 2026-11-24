package dev.photohub.shared.data

import dev.photohub.shared.auth.DriveAccountFactory
import dev.photohub.shared.auth.MegaAccounts
import dev.photohub.shared.model.SourceType

/**
 * Resolves the upload target from storage on every call instead of being bound
 * to one account at startup.
 *
 * With several clouds connected the user can switch the destination in
 * Settings, so a DI singleton holding one [UploadTarget] would keep writing to
 * the old account until the next app restart. The queue only needs two things
 * from a target — its id (for filtering and dedupe) and an upload call — and
 * both can be answered from the current `isUploadTarget` row.
 */
class CurrentUploadTarget(
    private val repository: MediaRepository,
    private val drives: DriveAccountFactory,
    private val megas: MegaAccounts,
) : UploadTarget {

    override val sourceId: String
        get() = repository.uploadTarget()?.id.orEmpty()

    override suspend fun upload(
        local: String,
        remoteFolder: String,
        mimeType: String,
        onProgress: (Float) -> Unit,
    ): String {
        val id = sourceId
        require(id.isNotBlank()) { "No cloud account is set as the upload target." }
        val config = requireNotNull(repository.sourceById(id)) {
            "The upload target is no longer configured."
        }
        return when (config.type) {
            SourceType.MEGA -> megas.targetFor(config).upload(local, remoteFolder, mimeType, onProgress)
            else -> drives.forSource(id).target.upload(local, remoteFolder, mimeType, onProgress)
        }
    }
}
