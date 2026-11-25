package dev.photohub.shared

import dev.photohub.shared.data.SettingsStore
import dev.photohub.shared.data.ThemeMode
import dev.photohub.shared.platform.fileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsStoreTest {

    private fun tempPath() =
        Files.createTempDirectory("settings").resolve("settings.json").toString().toPath()

    @Test
    fun defaultsToFollowingTheSystemTheme() {
        // A fresh install must not force a theme the user never chose.
        assertEquals(ThemeMode.SYSTEM, SettingsStore(tempPath()).settings.value.themeMode)
    }

    @Test
    fun themeChoiceSurvivesARestart() {
        val path = tempPath()

        SettingsStore(path).setThemeMode(ThemeMode.DARK)

        // A second instance is what the next app launch sees.
        assertEquals(ThemeMode.DARK, SettingsStore(path).settings.value.themeMode)
    }

    @Test
    fun theFlowEmitsSoComposeRecomposesOnChange() {
        val store = SettingsStore(tempPath())

        store.setThemeMode(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, store.settings.value.themeMode)
    }

    @Test
    fun deleteConfirmationDefaultsOnAndSurvivesARestart() {
        val path = tempPath()

        // Destructive by default, but the user can opt out and that choice is
        // a preference like the theme — not something to re-ask on every launch.
        assertEquals(true, SettingsStore(path).settings.value.confirmDelete)

        SettingsStore(path).setConfirmDelete(false)

        assertEquals(false, SettingsStore(path).settings.value.confirmDelete)
    }

    @Test
    fun aCorruptFileFallsBackToDefaultsInsteadOfCrashingStartup() {
        val path = tempPath()
        path.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(path) { writeUtf8("{not json at all") }

        // Startup reads this before any UI exists, so throwing here would be an
        // unrecoverable launch crash fixable only by clearing app data.
        assertEquals(ThemeMode.SYSTEM, SettingsStore(path).settings.value.themeMode)
    }

    @Test
    fun anUnknownThemeNameFallsBackRatherThanThrowing() {
        val path = tempPath()
        path.parent?.let { fileSystem.createDirectories(it) }
        // Could come from a newer build, or a hand edit.
        fileSystem.write(path) { writeUtf8("""{"themeMode":"SOLARIZED"}""") }

        assertEquals(ThemeMode.SYSTEM, SettingsStore(path).settings.value.themeMode)
    }

    @Test
    fun writingLeavesNoTemporaryFileBehind() {
        val path = tempPath()
        val store = SettingsStore(path)

        store.setThemeMode(ThemeMode.DARK)

        // The write is temp-then-rename; a leftover .tmp would mean the rename
        // did not happen and the real file may be stale.
        assertTrue(fileSystem.exists(path), "settings file was not created")
        assertTrue(
            !fileSystem.exists("$path.tmp".toPath()),
            "temporary file was left behind",
        )
    }
}
