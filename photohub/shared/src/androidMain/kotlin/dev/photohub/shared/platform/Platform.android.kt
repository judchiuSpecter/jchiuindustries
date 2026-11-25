package dev.photohub.shared.platform

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.photohub.shared.db.PhotoHubDatabase
import kotlinx.coroutines.CompletableDeferred
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

    private var activityRef: java.lang.ref.WeakReference<Activity>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The foreground activity, needed for MediaStore's delete-consent dialog. */
    fun attachActivity(activity: Activity) {
        activityRef = java.lang.ref.WeakReference(activity)
    }

    fun detachActivity(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    val currentActivity: Activity? get() = activityRef?.get()

    private var nextDeleteRequestCode = 1000
    private val deleteRequests = mutableMapOf<Int, CompletableDeferred<Boolean>>()

    /**
     * Deletes MediaStore items, asking the user through Android's own consent
     * dialog on API 29+. We wait for the activity result before reporting success
     * so a cancelled dialog cannot leave the item marked as gone in our index.
     */
    suspend fun requestMediaDelete(uris: List<Uri>): Result<Unit> {
        val activity = currentActivity
            ?: return Result.failure(IllegalStateException("PhotoHub is not in the foreground"))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return runCatching { uris.forEach { activity.contentResolver.delete(it, null, null) } }
        }

        val sender = MediaStore.createDeleteRequest(activity.contentResolver, uris)
        val code = nextDeleteRequestCode++
        val deferred = CompletableDeferred<Boolean>()
        deleteRequests[code] = deferred
        try {
            activity.startIntentSenderForResult(sender.intentSender, code, null, 0, 0, 0)
        } catch (t: Throwable) {
            deleteRequests.remove(code)
            return Result.failure(t)
        }
        return if (deferred.await()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Delete was cancelled"))
        }
    }

    /** Called by MainActivity.onActivityResult; returns true when we handled it. */
    fun onDeleteResult(requestCode: Int, resultCode: Int): Boolean {
        val deferred = deleteRequests.remove(requestCode) ?: return false
        deferred.complete(resultCode == Activity.RESULT_OK)
        return true
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

actual fun isUnmeteredNetwork(): Boolean {
    val manager = AndroidPlatform.appContext.getSystemService(ConnectivityManager::class.java)
        ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

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
