package dev.photohub.shared.source.drive

import dev.photohub.shared.data.UploadTarget
import okio.Path

/**
 * Adapts [DriveUploader] to the generic [UploadTarget] the upload queue drives.
 *
 * This indirection is what lets MEGA and OneDrive reuse the entire queue —
 * dedupe, retry classification, date foldering — in M4/M5 by supplying their own
 * target instead of their own queue.
 */
class DriveUploadTarget(
    private val uploader: DriveUploader,
    override val sourceId: String,
) : UploadTarget {

    override suspend fun upload(
        local: String,
        remoteFolder: String,
        mimeType: String,
        onProgress: (Float) -> Unit,
    ): String = uploader.upload(local, remoteFolder, mimeType, onProgress).id
}
