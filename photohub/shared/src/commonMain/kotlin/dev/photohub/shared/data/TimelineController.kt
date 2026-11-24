package dev.photohub.shared.data

import dev.photohub.shared.db.MediaItem
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.source.SourceRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the timeline: exposes the cached items the UI renders and refreshes
 * them by re-indexing every enabled source.
 *
 * The cached rows are published first and the index runs afterwards, so the
 * timeline appears instantly offline and then fills in — the app never blocks
 * on a scan. Local sources go first because they are instant and offline; a
 * cloud failure must not discard items already indexed from elsewhere.
 */
class TimelineController(
    private val repository: MediaRepository,
    private val indexer: MediaIndexer,
    private val thumbnails: ThumbnailStore,
    private val sources: SourceRegistry,
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
     * Re-indexes all enabled sources and republishes the timeline.
     *
     * Safe to call when permission has not been granted: the local source
     * returns a failure (or an empty list) and the cached timeline is left in
     * place.
     */
    suspend fun refresh() {
        _state.value = _state.value.copy(isRefreshing = true, error = null)

        // The device source is implicit: make sure its row exists before the
        // first scan, but never reset its settings on later launches.
        val localId = repository.ensureLocalSource().id
        val configs = repository.allSources()
            .sortedWith(compareBy({ if (it.type == SourceType.LOCAL) 0 else 1 }, { it.id }))

        var localError: String? = null
        val cloudErrors = mutableListOf<String>()

        for (config in configs) {
            val source = sources.create(config) ?: continue
            val result = indexer.index(source)
            if (config.id == localId) {
                result.exceptionOrNull()?.message?.let { localError = it }
            } else {
                result.onFailure { failure ->
                    cloudErrors += "${config.displayName}: ${failure.message ?: "sync failed"}"
                }
            }
            // Show newly indexed items after every source, not just at the end:
            // one unreachable cloud must not hold up the rest of the grid.
            _state.value = _state.value.copy(items = page())
        }

        thumbnails.generateMissing(
            onProgress = { completed ->
                // Republish periodically so the grid sharpens as it goes rather
                // than staying blank until the whole library is done.
                if (completed % THUMB_REFRESH_INTERVAL == 0) {
                    _state.value = _state.value.copy(items = page())
                }
            },
        )

        _state.value = _state.value.copy(
            items = page(),
            isRefreshing = false,
            // Keep a local failure if there was one; otherwise report the clouds.
            error = localError ?: cloudErrors.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
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
