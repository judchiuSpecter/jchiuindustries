package dev.photohub.shared.source

import dev.photohub.shared.model.LocalSourceConfig
import dev.photohub.shared.model.RemoteMedia
import dev.photohub.shared.model.SourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File

/**
 * Desktop local source: walks the configured root directories and reports every
 * supported image/video it finds.
 *
 * Desktop has no media index, so this is a filesystem walk. Dates come from the
 * file's last-modified time; EXIF date-taken is read on Android (where the
 * platform provides a parser) and is a later refinement here.
 */
class LocalDirectorySource(
    override val config: SourceConfig,
    private val fs: FileSystem = FileSystem.SYSTEM,
) : MediaSource {

    private val settings: LocalSourceConfig =
        runCatching { Json.decodeFromString<LocalSourceConfig>(config.configJson) }
            .getOrElse { LocalSourceConfig() }

    private val roots: List<Path>
        get() = settings.roots
            .takeIf { it.isNotEmpty() }
            ?.map { it.toPath() }
            ?: listOfNotNull(defaultPicturesDir())

    /** Local files can be written when this source is the designated archive target. */
    override val canUpload: Boolean get() = true
    override val canDelete: Boolean get() = true

    override suspend fun list(cursor: String?): Result<MediaPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val items = roots
                    .filter { fs.exists(it) }
                    .flatMap { walk(it) }
                    .sortedByDescending { it.dateTakenEpochMs }
                // A filesystem walk has no natural paging boundary, so the whole
                // tree is returned at once and the cursor is always exhausted.
                MediaPage(items = items, nextCursor = null)
            }
        }

    private fun walk(root: Path): List<RemoteMedia> {
        val out = ArrayList<RemoteMedia>()
        val stack = ArrayDeque(listOf(root))
        val visited = HashSet<String>()

        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            // Guard against symlink loops revisiting the same directory.
            val key = runCatching { fs.canonicalize(dir).toString() }.getOrElse { dir.toString() }
            if (!visited.add(key)) continue

            val children = runCatching { fs.list(dir) }.getOrElse { continue }
            for (child in children) {
                val meta = runCatching { fs.metadata(child) }.getOrNull() ?: continue
                when {
                    meta.isDirectory -> stack.addLast(child)
                    meta.isRegularFile -> toMedia(child, meta.size, meta.lastModifiedAtMillis)
                        ?.let(out::add)
                }
            }
        }
        return out
    }

    private fun toMedia(path: Path, size: Long?, modifiedAtMillis: Long?): RemoteMedia? {
        val name = path.name
        val mime = MediaTypes.mimeTypeOf(name) ?: return null
        val isVideo = MediaTypes.isVideoMime(mime)
        if (isVideo && !settings.includeVideos) return null

        return RemoteMedia(
            // The absolute path is the stable identity of a local desktop file.
            remoteId = path.toString(),
            displayName = name,
            mimeType = mime,
            isVideo = isVideo,
            sizeBytes = size ?: 0L,
            dateTakenEpochMs = modifiedAtMillis ?: 0L,
        )
    }

    /**
     * Local files need no download: the "cache" is the file itself, so the
     * thumbnail and original both resolve to the original path. The UI reads
     * pixels straight off disk.
     */
    override suspend fun fetchThumbnail(item: RemoteMedia, target: Path): Result<Path> =
        Result.success(item.remoteId.toPath())

    override suspend fun downloadOriginal(
        item: RemoteMedia,
        target: Path,
        onProgress: (Float) -> Unit,
    ): Result<Path> {
        onProgress(1f)
        return Result.success(item.remoteId.toPath())
    }

    /** Copies into `PhotoHubArchive` under the first configured root. */
    override suspend fun upload(
        local: Path,
        remoteFolder: String,
        onProgress: (Float) -> Unit,
    ): Result<RemoteMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val base = roots.firstOrNull() ?: error("No local folder is configured")
            val segments = remoteFolder.split('/').map { it.trim() }.filter {
                it.isNotBlank() && it != "." && it != ".."
            }
            require(segments.isNotEmpty()) { "Archive folder must not be empty" }
            val target = base / "PhotoHubArchive" / segments.joinToString("/") / local.name
            target.parent?.let { fs.createDirectories(it) }
            ensureInside(base, target)
            File(local.toString()).copyTo(File(target.toString()), overwrite = true)
            onProgress(1f)
            val meta = fs.metadata(target)
            val mime = MediaTypes.mimeTypeOf(target.name) ?: "application/octet-stream"
            RemoteMedia(
                remoteId = target.toString(),
                displayName = target.name,
                mimeType = mime,
                isVideo = MediaTypes.isVideoMime(mime),
                sizeBytes = meta.size ?: 0L,
                dateTakenEpochMs = meta.lastModifiedAtMillis ?: 0L,
            )
        }
    }

    /** Only files inside a configured root may be deleted by an explicit action. */
    override suspend fun delete(item: RemoteMedia): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = item.remoteId.toPath()
            ensureInside(roots.firstOrNull() ?: error("No local folder is configured"), target)
            require(fs.metadata(target).isRegularFile) { "Not a file: ${item.displayName}" }
            fs.delete(target)
        }
    }

    private fun ensureInside(root: Path, target: Path) {
        val canonicalRoot = fs.canonicalize(root).toString().trimEnd(File.separatorChar)
        // An upload target does not exist yet, so canonicalise its parent instead.
        val canonicalTarget = if (fs.exists(target)) {
            fs.canonicalize(target).toString()
        } else {
            val parent = target.parent ?: error("Target has no parent directory")
            fs.canonicalize(parent).toString().trimEnd(File.separatorChar) + File.separator + target.name
        }
        require(canonicalTarget.startsWith(canonicalRoot + File.separator)) {
            "Refusing to touch a file outside the indexed folder"
        }
    }

    private companion object {
        /** The OS "Pictures" folder, used when no roots are configured. */
        fun defaultPicturesDir(): Path? {
            val home = System.getProperty("user.home") ?: return null
            val candidates = listOf("Pictures", "Photos", "Images")
            return candidates
                .map { File(home, it) }
                .firstOrNull { it.isDirectory }
                ?.absolutePath
                ?.toPath()
                ?: home.toPath()
        }
    }
}
