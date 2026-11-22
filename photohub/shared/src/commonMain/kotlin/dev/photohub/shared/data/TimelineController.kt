package dev.photohub.shared.data

import dev.photohub.shared.db.MediaItem
import dev.photohub.shared.platform.createLocalSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the timeline: exposes the cached items the UI renders and refreshes
 * them by re-indexing the device-local source.
 *
 * The cached rows are published first and the index runs afterwards, so the
 * timeline appears instantly offline and then fills in — the app never blocks
 * on a scan.
 */
class TimelineController(
    private val repository: MediaRepository,
    private val indexer: MediaIndexer,
    private val thumbnails: ThumbnailStore,
) {
    /** What went wrong on the last refresh, or null when it succeeded. */
    data class State(
        val items: List<MediaItem> = emptyList(),
        val isRefreshing: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Publishes whatever is already cached, without touching any source. */
    fun loadCached() {
        _state.value = _state.value.copy(items = page(), error = null)
    }

    /**
     * Re-scans the device-local source and republishes the timeline.
     *
     * Safe to call when permission has not been granted: the source returns a
     * failure (or an empty list) and the cached timeline is left in place.
     */
    suspend fun refresh() {
        _state.value = _state.value.copy(isRefreshing = true, error = null)

        val config = repository.ensureLocalSource()
        val source = createLocalSource(config)
        if (source == null) {
            _state.value = State(items = page(), isRefreshing = false)
            return
        }

        val result = indexer.index(source)
        // Show the newly indexed items immediately; thumbnails fill in after.
        _state.value = State(
            items = page(),
            isRefreshing = true,
            error = result.exceptionOrNull()?.message,
        )

        thumbnails.generateMissing(
            onProgress = { completed ->
                // Republish periodically so the grid sharpens as it goes rather
                // than staying blank until the whole library is done.
                if (completed % THUMB_REFRESH_INTERVAL == 0) {
                    _state.value = _state.value.copy(items = page())
                }
            },
        )

        _state.value = _state.value.copy(items = page(), isRefreshing = false)
    }

    private fun page(): List<MediaItem> =
        repository.timelinePage(limit = PAGE_LIMIT, offset = 0)

    private companion object {
        // One screenful of a large library; paging in on scroll comes with M5.
        const val PAGE_LIMIT = 2000L

        // How many thumbnails to finish before republishing the timeline.
        const val THUMB_REFRESH_INTERVAL = 12
    }
}
