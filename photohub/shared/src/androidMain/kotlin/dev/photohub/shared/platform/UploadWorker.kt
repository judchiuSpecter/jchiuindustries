package dev.photohub.shared.platform

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.photohub.shared.auth.ReauthRequiredException
import dev.photohub.shared.data.CameraUploadScanner
import dev.photohub.shared.data.UploadManager
import dev.photohub.shared.data.UploadTarget
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.TimeUnit

/**
 * Runs a camera-upload pass in the background.
 *
 * WorkManager is used rather than a foreground service because upload is not
 * time-critical: it should happen when the phone is charging on Wi-Fi, and the
 * OS is better placed to pick that moment than the app is. Queue state lives in
 * SQLite, so an interrupted pass resumes rather than restarting.
 *
 * Upload is always a **copy**; the DCIM file is never deleted.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    override suspend fun doWork(): Result {
        val scanner = get<CameraUploadScanner>()
        val uploads = get<UploadManager>()

        // No account linked yet, or disconnected: not a failure, just nothing
        // to do. Retrying would burn battery on a no-op forever.
        val scan = scanner.scan()
        if (scan.reason != null) return Result.success()

        val target = runCatching { get<UploadTarget>() }.getOrNull()
            ?: return Result.success()

        val stats = try {
            uploads.runPending(target)
        } catch (cause: ReauthRequiredException) {
            // Only the user can fix this, so stop instead of retrying. The queue
            // keeps its items for after they re-link.
            return Result.success()
        } catch (cause: Throwable) {
            // Transient: let WorkManager back off and try the whole pass again.
            return Result.retry()
        }

        // Items left QUEUED after a pass hit retryable errors; ask WorkManager
        // for another attempt with backoff rather than waiting for the next
        // periodic window.
        val stillQueued = stats.haltedReason == null &&
            uploads.pendingCount() > 0
        return if (stillQueued) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "photohub-camera-upload"
        private const val ONE_SHOT_NAME = "photohub-camera-upload-now"

        /**
         * Constraints shared by both schedules.
         *
         * Unmetered-only by default: silently uploading a weekend of 4K video
         * over cellular is exactly the behaviour that makes people distrust
         * photo apps. Battery-not-low keeps it from draining a nearly-dead phone.
         */
        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        /**
         * Registers the recurring upload pass.
         *
         * KEEP, so an app restart does not reset the interval and cause a burst
         * of work on every launch. Six hours is a compromise: frequent enough
         * that photos reach the cloud the same day, rare enough to be invisible
         * on battery.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<UploadWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Runs a pass as soon as the constraints allow, for the "upload now"
         * action.
         *
         * KEEP again, so tapping twice does not run two passes concurrently —
         * they would both read the same queue rows and duplicate work.
         */
        fun runNow(context: Context, requireUnmetered: Boolean = true) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    if (requireUnmetered) {
                        constraints()
                    } else {
                        // Explicit user action overrides the Wi-Fi-only default.
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    },
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /** Stops all camera upload, for when the user disconnects their cloud. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC_NAME)
                cancelUniqueWork(ONE_SHOT_NAME)
            }
        }
    }
}
