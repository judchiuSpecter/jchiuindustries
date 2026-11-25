package dev.photohub.shared.model

/** The kind of source a photo comes from. */
enum class SourceType {
    LOCAL,
    GOOGLE_DRIVE,
    MEGA,
    ONEDRIVE,
    TAKEOUT,
    NAS_ARCHIVE,
}

/** Lifecycle of a media item's local cache. */
enum class SyncState {
    INDEXED,          // metadata known, nothing cached
    THUMB_CACHED,     // thumbnail downloaded
    ORIGINAL_CACHED,  // full-resolution original downloaded
}

/**
 * A photo or video as it appears in a remote (or local) source, before it is
 * folded into the local database. Returned by [dev.photohub.shared.source.MediaSource].
 */
data class RemoteMedia(
    val remoteId: String,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val dateTakenEpochMs: Long,
    val width: Int? = null,
    val height: Int? = null,
    /** Direct URL the UI can load a thumbnail from, if the source exposes one. */
    val thumbnailUrl: String? = null,
    /** SHA-256 of the original if the source already knows it (rare). */
    val contentHash: String? = null,
)

/**
 * Account-wide Google storage quota.
 *
 * These are the whole account's numbers, not just PhotoHub's uploads. Missing
 * values are normal on some restricted grants, so every field is nullable.
 */
data class StorageQuota(
    val limitBytes: Long? = null,
    val usedBytes: Long? = null,
    val usedInDriveBytes: Long? = null,
)

/** A configured source as persisted in the DB, with decoded config. */
data class SourceConfig(
    val id: String,
    val type: SourceType,
    val displayName: String,
    val enabled: Boolean,
    val isUploadTarget: Boolean,
    val configJson: String,
    /** The one source that receives explicit Archive actions. */
    val isArchiveTarget: Boolean = false,
    /** Set when the last sync attempt failed; null once it succeeds again. */
    val lastSyncError: String? = null,
)
