package dev.photohub.shared.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.source.LocalDirectorySource
import dev.photohub.shared.source.MediaSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

actual val platformName: String =
    "${System.getProperty("os.name")} ${System.getProperty("os.version")}"

/**
 * Per-OS application data directory, following each platform's convention:
 * Windows %APPDATA%, macOS ~/Library/Application Support, Linux XDG_DATA_HOME.
 */
/**
 * Application data directory as a [java.io.File], for host code that has no
 * okio dependency (the desktop launcher module).
 */
fun appDataDirFile(): java.io.File = java.io.File(appDataDir.toString())

internal val appDataDir: Path by lazy {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val base = when {
        os.contains("win") ->
            System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
        os.contains("mac") ->
            "$home/Library/Application Support"
        else ->
            System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
    }
    val dir = "$base/PhotoHub".toPath()
    FileSystem.SYSTEM.createDirectories(dir)
    dir
}

actual fun createDatabaseDriver(): SqlDriver {
    val dbPath = appDataDir / "photohub.db"
    // Check existence BEFORE opening the driver — opening creates the file.
    val isNew = !FileSystem.SYSTEM.exists(dbPath)
    val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
    // JDBC driver does not auto-create the schema; do it on first run.
    if (isNew) {
        PhotoHubDatabase.Schema.create(driver)
    } else {
        migrateSchema(driver)
    }
    return driver
}

actual val fileSystem: FileSystem = FileSystem.SYSTEM

actual val cacheDir: Path by lazy {
    val dir = appDataDir / "cache"
    FileSystem.SYSTEM.createDirectories(dir)
    dir
}

actual fun createLocalSource(config: SourceConfig): MediaSource? =
    LocalDirectorySource(config)

actual fun isUnmeteredNetwork(): Boolean = true

actual class BackgroundScheduler {
    private var executor: ScheduledExecutorService? = null

    actual fun scheduleDailySync() {
        cancelAll()
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "photohub-sync").apply { isDaemon = true }
        }.also {
            it.scheduleAtFixedRate(
                { /* TODO(M3): refresh enabled sources */ },
                1,
                24 * 60,
                TimeUnit.MINUTES,
            )
        }
    }

    /**
     * No-op on desktop: camera upload exists to get photos off a phone's camera
     * roll, and a desktop has no camera roll to watch.
     */
    actual fun setCameraUploadEnabled(enabled: Boolean) = Unit

    actual fun requestUploadNow() = Unit

    actual fun cancelAll() {
        executor?.shutdownNow()
        executor = null
    }
}
