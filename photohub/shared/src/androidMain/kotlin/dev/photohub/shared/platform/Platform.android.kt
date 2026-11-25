package dev.photohub.shared.platform

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.source.MediaSource
import dev.photohub.shared.source.MediaStoreSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

/**
 * Holder for the application [Context]. The Android app sets this in its
 * Application.onCreate before any platform function is used.
 */
object AndroidPlatform {
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

actual val platformName: String = "Android ${android.os.Build.VERSION.SDK_INT}"

actual fun createDatabaseDriver(): SqlDriver =
    AndroidSqliteDriver(
        schema = PhotoHubDatabase.Schema,
        context = AndroidPlatform.appContext,
        name = "photohub.db",
    ).also { migrateSchema(it) }

actual val fileSystem: FileSystem = FileSystem.SYSTEM

actual val cacheDir: Path by lazy {
    AndroidPlatform.appContext.cacheDir.resolve("photohub").apply { mkdirs() }.toOkioPath()
}

actual fun createLocalSource(config: SourceConfig): MediaSource? =
    MediaStoreSource(config)

actual class BackgroundScheduler {
    private val workManager get() = WorkManager.getInstance(AndroidPlatform.appContext)

    actual fun scheduleDailySync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi only by default
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            "photohub-daily-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    actual fun setCameraUploadEnabled(enabled: Boolean) {
        if (enabled) {
            UploadWorker.schedulePeriodic(AndroidPlatform.appContext)
        } else {
            UploadWorker.cancel(AndroidPlatform.appContext)
        }
    }

    actual fun requestUploadNow() {
        // Explicit user action, so allow a metered network: the user asked for
        // this upload and is presumably watching it.
        UploadWorker.runNow(AndroidPlatform.appContext, requireUnmetered = false)
    }

    actual fun cancelAll() {
        workManager.cancelUniqueWork("photohub-daily-sync")
        UploadWorker.cancel(AndroidPlatform.appContext)
    }
}
