package dev.photohub.shared.source

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import dev.photohub.shared.model.LocalSourceConfig
import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.platform.AndroidPlatform
import dev.photohub.shared.platform.fileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import java.io.FileOutputStream

/**
 * Android local source: enumerates the camera roll through MediaStore.
 *
 * MediaStore already holds EXIF-derived `DATE_TAKEN` and the dimensions, so no
 * file parsing is needed for indexing. Items are queried newest-first and paged
 * so a large library does not build one huge list in memory.
 */
class MediaStoreSource(
    override val config: SourceConfig,
) : MediaSource {

    private val settings: LocalSourceConfig =
        runCatching { Json.decodeFromString<LocalSourceConfig>(config.configJson) }
            .getOrElse { LocalSourceConfig() }

    /** Writable only for explicit Archive actions, never for camera uploads. */
    override val canUpload: Boolean get() = true
    override val canDelete: Boolean get() = true

    override suspend fun list(cursor: String?): Result<MediaPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val offset = cursor?.toIntOrNull() ?: 0
                val items = query(offset, PAGE_SIZE)
                MediaPage(
                    items = items,
                    nextCursor = if (items.size < PAGE_SIZE) null else (offset + items.size).toString(),
                )
            }
        }

    private fun query(offset: Int, limit: Int): List<RemoteMedia> {
        val resolver = AndroidPlatform.appContext.contentResolver

        // Query images and videos together from the unified "files" collection so
        // the result is one chronological stream rather than two separate lists.
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )

        val mediaTypes = buildList {
            add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            if (settings.includeVideos) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
        }
        val selection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${mediaTypes.joinToString(",")})"

        // MediaStore validates the sort order against a strict SQL grammar, so it
        // must be plain columns: no COALESCE, and no embedded LIMIT/OFFSET.
        //
        // This ordering only has to be *stable* for paging, not semantically
        // correct — the real timeline order comes from `dateTaken` in our own
        // database, which does apply the DATE_TAKEN/DATE_MODIFIED fallback.
        // _ID breaks ties so paging can never skip or repeat an item.
        val sortOrder =
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC, " +
                "${MediaStore.Files.FileColumns._ID} DESC"

        return queryPaged(collection, projection, selection, sortOrder, limit, offset)
            ?.use { c -> readAll(c) }
            ?: emptyList()
    }

    /**
     * Runs a paged query.
     *
     * Android 11 started rejecting `LIMIT`/`OFFSET` inside the sort-order string
     * (`IllegalArgumentException: Invalid token LIMIT`), and requires them to be
     * passed as query arguments instead. Older versions have no such bundle
     * support, so they keep the inline form.
     */
    private fun queryPaged(
        collection: Uri,
        projection: Array<String>,
        selection: String,
        sortOrder: String,
        limit: Int,
        offset: Int,
    ): Cursor? {
        val resolver = AndroidPlatform.appContext.contentResolver

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            return resolver.query(
                collection,
                projection,
                selection,
                null,
                "$sortOrder LIMIT $limit OFFSET $offset",
            )
        }

        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        return resolver.query(collection, projection, args, null)
    }

    private fun readAll(c: Cursor): List<RemoteMedia> {
        val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
        val widthCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
        val heightCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
        val takenCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
        val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
        val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)

        val out = ArrayList<RemoteMedia>(c.count)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val name = c.getStringOrNull(nameCol) ?: "$id"
            val isVideo =
                c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val mime = c.getStringOrNull(mimeCol)
                ?: MediaTypes.mimeTypeOf(name)
                ?: if (isVideo) "video/*" else "image/*"

            val dateTaken = c.getLongOrNull(takenCol)
                ?: c.getLongOrNull(modifiedCol)?.times(1000)
                ?: 0L

            out += RemoteMedia(
                // The MediaStore id is stable for the life of the file, unlike its path.
                remoteId = id.toString(),
                displayName = name,
                mimeType = mime,
                isVideo = isVideo,
                sizeBytes = c.getLongOrNull(sizeCol) ?: 0L,
                dateTakenEpochMs = dateTaken,
                width = c.getIntOrNull(widthCol),
                height = c.getIntOrNull(heightCol),
                // content:// URI the image loader can read directly.
                thumbnailUrl = ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    id,
                ).toString(),
            )
        }
        return out
    }

    /**
     * Grid thumbnails are read straight from the content URI by Coil, so there
     * is nothing to fetch here.
     */
    override suspend fun fetchThumbnail(item: RemoteMedia, target: Path): Result<Path> =
        Result.failure(
            UnsupportedOperationException("Local media is read through its content URI"),
        )

    /** Copies the content-URI bytes out so Archive can upload them elsewhere. */
    override suspend fun downloadOriginal(
        item: RemoteMedia,
        target: Path,
        onProgress: (Float) -> Unit,
    ): Result<Path> = withContext(Dispatchers.IO) {
        runCatching {
            target.parent?.let { fileSystem.createDirectories(it) }
            val resolver = AndroidPlatform.appContext.contentResolver
            val input = resolver.openInputStream(contentUri(item))
                ?: error("Cannot open ${item.displayName} from MediaStore")
            input.use { source ->
                FileOutputStream(target.toString()).use { source.copyTo(it) }
            }
            onProgress(1f)
            target
        }
    }

    /** Writes an archived copy into MediaStore under `Pictures/PhotoHubArchive`. */
    override suspend fun upload(
        local: Path,
        remoteFolder: String,
        onProgress: (Float) -> Unit,
    ): Result<RemoteMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = MediaTypes.mimeTypeOf(local.name) ?: "application/octet-stream"
            val isVideo = MediaTypes.isVideoMime(mime)
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            }
            val relativeFolder = "Pictures/PhotoHubArchive/" +
                remoteFolder.split('/').map { it.trim() }
                    .filter { it.isNotBlank() && it != "." && it != ".." }
                    .joinToString("/")
            require(relativeFolder.length > "Pictures/PhotoHubArchive/".length) {
                "Archive folder must not be empty"
            }
            val resolver = AndroidPlatform.appContext.contentResolver

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, local.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val inserted = resolver.insert(collection, values)
                    ?: error("MediaStore refused to create ${local.name}")
                try {
                    val output = resolver.openOutputStream(inserted)
                        ?: error("Cannot open MediaStore output for ${local.name}")
                    output.use { File(local.toString()).inputStream().use { it.copyTo(output) } }
                } finally {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(inserted, values, null, null)
                }
                inserted
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    relativeFolder.removePrefix("Pictures/"),
                )
                dir.mkdirs()
                val file = File(dir, local.name)
                File(local.toString()).copyTo(file, overwrite = true)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, file.absolutePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, local.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                }
                resolver.insert(collection, values)
                    ?: error("MediaStore refused to index ${local.name}")
            }

            onProgress(1f)
            RemoteMedia(
                remoteId = ContentUris.parseId(uri).toString(),
                displayName = local.name,
                mimeType = mime,
                isVideo = isVideo,
                sizeBytes = fileSizeFor(local),
                dateTakenEpochMs = System.currentTimeMillis(),
                thumbnailUrl = uri.toString(),
            )
        }
    }

    /** Uses Android's own consent dialog on modern versions before deleting. */
    override suspend fun delete(item: RemoteMedia): Result<Unit> =
        AndroidPlatform.requestMediaDelete(listOf(contentUri(item)))

    private fun contentUri(item: RemoteMedia): Uri =
        item.thumbnailUrl?.takeIf { it.startsWith("content://") }?.let(Uri::parse)
            ?: ContentUris.withAppendedId(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                item.remoteId.toLongOrNull()
                    ?: error("Local media id ${item.remoteId} is not a MediaStore id"),
            )

    private fun fileSizeFor(local: Path): Long =
        runCatching { fileSystem.metadata(local).size }.getOrNull() ?: 0L

    private companion object {
        const val PAGE_SIZE = 500

        fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
        fun Cursor.getLongOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
        fun Cursor.getIntOrNull(i: Int): Int? =
            if (isNull(i)) null else getInt(i).takeIf { it > 0 }
    }
}
