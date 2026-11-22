package dev.photohub.shared.source

/**
 * Filename-extension to MIME mapping for the formats PhotoHub indexes.
 *
 * The desktop source has no media database to ask, and the Android source needs
 * the same answers for files MediaStore reports without a type, so both platforms
 * share this table to avoid classifying the same file differently.
 */
object MediaTypes {

    private val imageTypes: Map<String, String> = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "dng" to "image/x-adobe-dng",
    )

    private val videoTypes: Map<String, String> = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mov" to "video/quicktime",
        "3gp" to "video/3gpp",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
    )

    /** Every extension PhotoHub will index, lowercase and without the dot. */
    val supportedExtensions: Set<String> = imageTypes.keys + videoTypes.keys

    /** MIME type for [fileName], or null when the extension is not supported. */
    fun mimeTypeOf(fileName: String): String? {
        val ext = extensionOf(fileName) ?: return null
        return imageTypes[ext] ?: videoTypes[ext]
    }

    /** True when [mimeType] names a video container. */
    fun isVideoMime(mimeType: String): Boolean = mimeType.startsWith("video/")

    /** True when PhotoHub should index [fileName]. */
    fun isSupported(fileName: String): Boolean = mimeTypeOf(fileName) != null

    private fun extensionOf(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.lastIndex) return null
        return fileName.substring(dot + 1).lowercase()
    }
}
