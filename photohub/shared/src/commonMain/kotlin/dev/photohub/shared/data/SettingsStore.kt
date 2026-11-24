package dev.photohub.shared.data

import dev.photohub.shared.platform.cacheDir
import dev.photohub.shared.platform.fileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path

/** How the app picks between the light and dark colour schemes. */
enum class ThemeMode {
    /** Follow the OS setting. */
    SYSTEM,
    LIGHT,
    DARK,
}

/** On-disk shape of `settings.json`. Field names are the file format — renaming one is a migration. */
@Serializable
private data class SettingsFile(
    val themeMode: String = ThemeMode.SYSTEM.name,
)

/** User preferences, as the UI consumes them. */
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

/**
 * Small persistent preference store, backed by a JSON file in the app data dir.
 *
 * Deliberately not a SQLDelight table: the database holds the indexed library,
 * and adding a column there would mean a schema migration risking real indexed
 * rows for what is a handful of UI flags. A file also means a corrupt or
 * hand-edited settings file degrades to defaults instead of blocking startup.
 *
 * Reads are cached in a [StateFlow] so Compose can observe changes without
 * touching the disk on every recomposition.
 */
class SettingsStore(
    private val path: Path = defaultPath(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        update { it.copy(themeMode = mode) }
    }

    private fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        persist(next)
    }

    private fun load(): Settings {
        if (!fileSystem.exists(path)) return Settings()

        val parsed = runCatching {
            json.decodeFromString<SettingsFile>(fileSystem.read(path) { readUtf8() })
        }.getOrNull() ?: return Settings()

        return Settings(
            // An unknown value (older/newer build, hand edit) falls back to the
            // default rather than throwing on a cold start.
            themeMode = ThemeMode.entries.firstOrNull { it.name == parsed.themeMode }
                ?: ThemeMode.SYSTEM,
        )
    }

    private fun persist(settings: Settings) {
        runCatching {
            path.parent?.let { fileSystem.createDirectories(it) }
            // Temp-then-rename: a crash mid-write must not leave a truncated
            // file that reads back as corrupt on next launch.
            val temp = path.parent?.resolve("${path.name}.tmp") ?: return
            fileSystem.write(temp) {
                writeUtf8(json.encodeToString(SettingsFile(themeMode = settings.themeMode.name)))
            }
            fileSystem.atomicMove(temp, path)
        }
    }

    companion object {
        const val FILE_NAME = "settings.json"

        /** Alongside the database and credentials, not in the cache: a cache is clearable. */
        fun defaultPath(): Path =
            cacheDir.parent?.resolve(FILE_NAME) ?: cacheDir.resolve(FILE_NAME)
    }
}
