package dev.photohub.shared.platform

import app.cash.sqldelight.db.SqlDriver
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.source.MediaSource
import okio.FileSystem
import okio.Path

/** Human-readable platform name, for the About screen / logs. */
expect val platformName: String

/** Creates the platform's SQLDelight driver (Android: AndroidSqliteDriver, Desktop: JDBC). */
expect fun createDatabaseDriver(): SqlDriver

/**
 * Adds columns introduced after a database already shipped, for an existing
 * installation whose `Source` table predates them.
 *
 * SQLDelight's `Schema.create` only runs for a brand-new database file, so an
 * additive column (like `lastSyncError`) needs a manual `ALTER TABLE` for
 * everyone who already has an index on disk. `ALTER TABLE ADD COLUMN` has no
 * `IF NOT EXISTS` in SQLite, so running it twice (e.g. on every launch) throws
 * "duplicate column" — that failure is expected and ignored rather than
 * checked for up front, since inspecting `pragma_table_info` per platform
 * driver would be more code for the same result.
 */
fun migrateSchema(driver: SqlDriver) {
    runCatching {
        driver.execute(null, "ALTER TABLE Source ADD COLUMN lastSyncError TEXT", 0)
    }
    runCatching {
        driver.execute(
            null,
            "ALTER TABLE Source ADD COLUMN isArchiveTarget INTEGER NOT NULL DEFAULT 0",
            0,
        )
    }
}

/** The filesystem to use for cache reads/writes (okio). */
expect val fileSystem: FileSystem

/** Directory where PhotoHub caches thumbnails and originals. Created if missing. */
expect val cacheDir: Path

/**
 * Builds the device-local photo source (MediaStore on Android, directory walk on
 * desktop). Returns null on platforms with no local camera roll.
 */
expect fun createLocalSource(config: SourceConfig): MediaSource?

/**
 * Renders a downscaled thumbnail of [sourceRef] into [target] as JPEG, with its
 * longest edge capped at [maxEdge] pixels and aspect ratio preserved.
 *
 * [sourceRef] is whatever the platform's loader understands: a `content://` URI
 * on Android, an absolute file path on desktop. Returns false when the image
 * could not be decoded (corrupt file, unsupported codec) so the caller can skip
 * it rather than failing the whole batch.
 */
expect suspend fun renderThumbnail(sourceRef: String, target: Path, maxEdge: Int): Boolean

/**
 * How many thumbnails to render at once. Tracks available CPU cores, capped on
 * mobile so a first-run scan does not saturate the device or drain the battery.
 */
expect val thumbnailConcurrency: Int

/**
 * Builds the app's shared HTTP client (OkHttp on Android, the JDK client on
 * desktop). Timeouts are generous enough for large video uploads on a slow
 * connection but bounded, so a stalled request cannot hang a sync forever.
 */
expect fun createHttpClient(): io.ktor.client.HttpClient

/**
 * Cryptographically secure random bytes, for PKCE verifiers and OAuth state.
 * Must not be a seeded/predictable generator.
 */
expect fun randomBytes(count: Int): ByteArray

/** SHA-256 digest, used for the PKCE challenge and upload dedupe. */
expect fun sha256(bytes: ByteArray): ByteArray

/**
 * SHA-256 of the bytes at [ref], streamed rather than buffered.
 *
 * Camera uploads include multi-gigabyte videos, so reading the whole file to
 * hash it would exhaust memory on a phone. [ref] is a plain path on desktop and
 * may additionally be a `content://` URI on Android, which okio cannot open —
 * so resolving it is the platform's job.
 *
 * Returns null when the bytes cannot be read (deleted file, revoked permission).
 */
expect suspend fun sha256OfFile(ref: String): String?

/**
 * Total byte size of [ref], or null when unknown.
 *
 * Exists for the same reason as [sha256OfFile]: on Android the reference may be
 * a content URI whose size only the ContentResolver knows.
 */
expect fun fileSize(ref: String): Long?

/**
 * Reads [length] bytes of [ref] starting at [offset], or null when unreadable.
 *
 * Resumable uploads send a file in chunks, and the chunk has to come from the
 * same seam as [fileSize] rather than okio: an okio `Path` cannot represent a
 * `content://` URI, and constructing one silently rewrites `content://` to
 * `content:/`, which then fails to open. Returns fewer bytes than requested at
 * end of file.
 */
expect suspend fun readChunk(ref: String, offset: Long, length: Int): ByteArray?

/** A local file prepared for APIs that need a real filesystem path. */
data class MaterializedFile(
    val path: Path,
    /** True when this is a temporary copy the caller must delete after use. */
    val isTemporary: Boolean,
)

/**
 * Resolves [ref] — a plain path on desktop, possibly a `content://` URI on
 * Android — to a real file some SDK can open.
 *
 * MEGA's upload API reads from a filesystem path, unlike Drive's chunk seam, so
 * content URIs have to be copied into the app's private cache first.
 */
expect suspend fun materializeLocalFile(ref: String): MaterializedFile

/**
 * File name to use for [ref] in the cloud.
 *
 * A content URI ends in a numeric id, so the real name has to be asked of the
 * ContentResolver; otherwise every uploaded photo would be called something
 * like `1000016271`.
 */
expect fun displayName(ref: String): String

/**
 * Opens [url] in the user's real browser for the OAuth consent screen.
 *
 * The flow deliberately avoids an in-app webview: Google blocks those for OAuth,
 * and a real browser lets the user see the domain and reuse an existing session.
 */
expect fun openInBrowser(url: String): Boolean

/**
 * True when large transfers should be allowed right now. Android treats any
 * metered connection as "no" so the NAS archiver only runs on Wi-Fi; desktop has
 * no mobile data concept and always allows it.
 */
expect fun isUnmeteredNetwork(): Boolean

/** Schedules periodic background sync (WorkManager on Android, executor on desktop). */
expect class BackgroundScheduler() {
    fun scheduleDailySync()

    /**
     * Starts the recurring camera-upload pass, or stops it.
     *
     * Called when a cloud becomes (or stops being) the upload target, so the
     * schedule follows the account rather than running against a cloud that is
     * not linked. A no-op on platforms without camera upload — desktop has no
     * camera roll, so there is nothing to upload from.
     */
    fun setCameraUploadEnabled(enabled: Boolean)

    /** Requests one upload pass now, for an explicit user action. */
    fun requestUploadNow()

    fun cancelAll()
}
