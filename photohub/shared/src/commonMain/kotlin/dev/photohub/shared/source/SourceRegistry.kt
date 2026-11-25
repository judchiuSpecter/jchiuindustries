package dev.photohub.shared.source

import dev.photohub.shared.auth.DriveAccountFactory
import dev.photohub.shared.auth.MegaAccounts
import dev.photohub.shared.auth.NasAccounts
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.platform.createLocalSource
import dev.photohub.shared.source.drive.GoogleDriveSource

/**
 * Builds a [MediaSource] for any configured source instance, local or cloud.
 *
 * Sources cannot be plain DI singletons: which ones exist is decided at runtime
 * by what the user has added, and each cloud account needs its own token
 * namespace. This keeps that construction in one place so the timeline does not
 * have to know how any particular provider is assembled.
 *
 * Returns null for a type with no implementation yet (OneDrive and Takeout are
 * still planned), so an unimplemented row in the database cannot crash a sync.
 */
class SourceRegistry(
    private val drives: DriveAccountFactory,
    private val megas: MegaAccounts,
    private val nas: NasAccounts,
) {
    suspend fun create(config: SourceConfig): MediaSource? = when (config.type) {
        SourceType.LOCAL -> createLocalSource(config)
        SourceType.GOOGLE_DRIVE -> drives.forSource(config.id).let { account ->
            GoogleDriveSource(config, account.api, account.uploader)
        }
        SourceType.MEGA -> megas.sourceFor(config)
        SourceType.NAS_ARCHIVE -> nas.sourceFor(config)
        else -> null
    }

    /**
     * Every enabled source that is currently usable.
     *
     * Disabled rows are skipped: disconnecting disables a source rather than
     * deleting it, so its indexed photos survive in the timeline, but it must not
     * be contacted again until it is re-connected.
     */
    suspend fun enabledSources(all: List<SourceConfig>): List<MediaSource> =
        all.filter { it.enabled }.mapNotNull { create(it) }
}
