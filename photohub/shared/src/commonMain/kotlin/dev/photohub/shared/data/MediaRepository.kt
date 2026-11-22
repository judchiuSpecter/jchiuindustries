package dev.photohub.shared.data

import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.LocalSourceConfig
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.db.MediaItem as DbMediaItem

/**
 * Reads the local index for the timeline. In M1 this is backed only by the
 * database; sources populate it in later milestones. The UI never talks to a
 * source directly — it reads cached rows here, so the timeline works offline.
 */
class MediaRepository(private val db: PhotoHubDatabase) {

    /** One page of the unified timeline, newest first. */
    fun timelinePage(limit: Long, offset: Long): List<DbMediaItem> =
        db.mediaItemQueries.timelinePage(limit, offset).executeAsList()

    fun itemById(id: String): DbMediaItem? =
        db.mediaItemQueries.selectById(id).executeAsOneOrNull()

    /** Total indexed items across enabled sources (for empty-state detection). */
    fun totalCount(): Long =
        db.mediaItemQueries.countTimeline().executeAsOne()

    /**
     * Ensures the device-local source row exists and returns it. Called on
     * startup so the first index has a source to attach rows to.
     *
     * Existing rows are left alone — the user may have disabled the local source
     * or pointed it at specific folders, and startup must not undo that.
     */
    fun ensureLocalSource(): SourceConfig {
        val existing = db.sourceQueries
            .selectById(LocalSourceConfig.SOURCE_ID)
            .executeAsOneOrNull()

        if (existing != null) {
            return SourceConfig(
                id = existing.id,
                type = SourceType.valueOf(existing.type),
                displayName = existing.displayName,
                enabled = existing.enabled == 1L,
                isUploadTarget = existing.isUploadTarget == 1L,
                configJson = existing.configJson,
            )
        }

        val created = SourceConfig(
            id = LocalSourceConfig.SOURCE_ID,
            type = SourceType.LOCAL,
            displayName = "This device",
            enabled = true,
            // The local camera roll is a source, never an upload destination.
            isUploadTarget = false,
            configJson = "{}",
        )
        db.sourceQueries.upsert(
            id = created.id,
            type = created.type.name,
            displayName = created.displayName,
            enabled = 1L,
            isUploadTarget = 0L,
            configJson = created.configJson,
            lastSyncedAt = null,
            // Local first, so it sorts above cloud sources in the sources list.
            sortOrder = 0L,
        )
        return created
    }

    /** Every configured source, for the sources screen. */
    fun allSources(): List<SourceConfig> =
        db.sourceQueries.selectAll().executeAsList().map { it.toSourceConfig() }

    fun sourceById(id: String): SourceConfig? =
        db.sourceQueries.selectById(id).executeAsOneOrNull()?.toSourceConfig()

    /** The source camera uploads go to, or null when none is configured. */
    fun uploadTarget(): SourceConfig? =
        db.sourceQueries.selectUploadTarget().executeAsOneOrNull()?.toSourceConfig()

    /**
     * Registers (or updates) a cloud source.
     *
     * [makeUploadTarget] clears the flag everywhere first: exactly one source may
     * receive camera uploads, so two targets would double-upload every photo.
     * An existing row's [SourceConfig.configJson] is preserved unless [configJson]
     * is supplied, so re-linking an account does not discard its folder settings.
     */
    fun upsertCloudSource(
        id: String,
        type: SourceType,
        displayName: String,
        configJson: String? = null,
        enabled: Boolean = true,
        makeUploadTarget: Boolean = false,
    ): SourceConfig {
        val existing = db.sourceQueries.selectById(id).executeAsOneOrNull()

        if (makeUploadTarget) db.sourceQueries.clearAllUploadTargets()

        val resolvedConfig = configJson ?: existing?.configJson ?: "{}"
        val isTarget = makeUploadTarget || (existing?.isUploadTarget == 1L && !makeUploadTarget)

        db.sourceQueries.upsert(
            id = id,
            type = type.name,
            displayName = displayName,
            enabled = if (enabled) 1L else 0L,
            isUploadTarget = if (isTarget) 1L else 0L,
            configJson = resolvedConfig,
            lastSyncedAt = existing?.lastSyncedAt,
            // Cloud sources sort after the local device.
            sortOrder = existing?.sortOrder ?: 1L,
        )

        return SourceConfig(
            id = id,
            type = type,
            displayName = displayName,
            enabled = enabled,
            isUploadTarget = isTarget,
            configJson = resolvedConfig,
        )
    }

    /** Removes a source; its media rows cascade away via the foreign key. */
    fun deleteSource(id: String) = db.sourceQueries.delete(id)

    private fun dev.photohub.shared.db.Source.toSourceConfig() = SourceConfig(
        id = id,
        type = SourceType.valueOf(type),
        displayName = displayName,
        enabled = enabled == 1L,
        isUploadTarget = isUploadTarget == 1L,
        configJson = configJson,
    )
}
