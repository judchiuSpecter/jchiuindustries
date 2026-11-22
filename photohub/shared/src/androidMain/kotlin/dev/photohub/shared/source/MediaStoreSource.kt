package dev.photohub.shared.source

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import dev.photohub.shared.model.LocalSourceConfig
import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.platform.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toPath

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
     * Local media needs no download — MediaStore serves the bytes through its
     * content URI, which the image loader handles natively.
     */
    override suspend fun fetchThumbnail(item: RemoteMedia, target: Path): Result<Path> =
        Result.failure(
            UnsupportedOperationException("Local media is read through its content URI"),
        )

    override suspend fun downloadOriginal(
        item: RemoteMedia,
        target: Path,
        onProgress: (Float) -> Unit,
    ): Result<Path> = Result.failure(
        UnsupportedOperationException("Local media is read through its content URI"),
    )

    private companion object {
        const val PAGE_SIZE = 500

        fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
        fun Cursor.getLongOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
        fun Cursor.getIntOrNull(i: Int): Int? =
            if (isNull(i)) null else getInt(i).takeIf { it > 0 }
    }
}
