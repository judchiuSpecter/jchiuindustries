package dev.photohub.shared.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Background sync entry point on Android. In M1 it is a no-op placeholder so the
 * scheduler wiring compiles and runs; M3 fills in source refresh + thumbnail
 * prefetch, and the camera-upload worker is added alongside.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // TODO(M3): refresh enabled sources, update index, prefetch thumbnails.
        return Result.success()
    }
}
