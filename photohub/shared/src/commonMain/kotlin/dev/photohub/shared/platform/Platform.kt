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
 * Opens [url] in the user's real browser for the OAuth consent screen.
 *
 * The flow deliberately avoids an in-app webview: Google blocks those for OAuth,
 * and a real browser lets the user see the domain and reuse an existing session.
 */
expect fun openInBrowser(url: String): Boolean

/** Schedules periodic background sync (WorkManager on Android, executor on desktop). */
expect class BackgroundScheduler() {
    fun scheduleDailySync()
    fun cancelAll()
}
