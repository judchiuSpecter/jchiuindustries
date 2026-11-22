package dev.photohub.shared.ui

import dev.photohub.shared.db.MediaItem

/**
 * Where the UI should load this item's pixels from.
 *
 * Local items resolve to an on-device path or `content://` URI that Coil reads
 * directly; remote items resolve to a cached file once downloaded, falling back
 * to the source's thumbnail URL. Null means nothing is loadable yet, and the
 * cell shows its placeholder.
 */
val MediaItem.imageModel: String?
    get() = thumbCachePath ?: originalCachePath ?: remoteThumbUrl

/** Duration label for videos; null for stills. */
val MediaItem.isVideoItem: Boolean
    get() = isVideo == 1L
