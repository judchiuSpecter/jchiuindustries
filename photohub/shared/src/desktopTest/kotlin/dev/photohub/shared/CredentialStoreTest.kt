package dev.photohub.shared

import dev.photohub.shared.auth.DesktopCredentialStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopCredentialStoreTest {

    @Test
    fun roundTripsValuesAcrossInstances() {
        val dir = Files.createTempDirectory("cred").toFile()

        DesktopCredentialStore(dir).put("google_drive.refreshToken", "secret-rt")

        // A new instance must decrypt what the previous one wrote, which is what
        // makes tokens survive an app restart.
        assertEquals("secret-rt", DesktopCredentialStore(dir).get("google_drive.refreshToken"))
    }

    @Test
    fun secretsAreNotStoredInPlaintext() {
        val dir = Files.createTempDirectory("cred2").toFile()
        val secret = "ya29.super-secret-token-value"
        DesktopCredentialStore(dir).put("google_drive.accessToken", secret)

        val onDisk = dir.resolve("credentials.properties").readText()
        // The whole point: a token must not be greppable out of the file, which
        // is how credentials leak into backups and dotfile repos.
        assertTrue(!onDisk.contains(secret), "token found in plaintext on disk")
        assertTrue(onDisk.contains("google_drive.accessToken"))
    }

    @Test
    fun eachValueUsesAFreshIv() {
        val dir = Files.createTempDirectory("cred3").toFile()
        val store = DesktopCredentialStore(dir)
        store.put("a", "identical")
        store.put("b", "identical")

        val lines = dir.resolve("credentials.properties").readLines()
            .filter { it.startsWith("a=") || it.startsWith("b=") }
            .map { it.substringAfter('=') }

        // Reusing an IV with GCM is a critical failure; identical plaintexts
        // must not produce identical ciphertexts.
        assertEquals(2, lines.size)
        assertTrue(lines[0] != lines[1], "IV reuse: identical ciphertext for identical input")
    }

    @Test
    fun removeAllClearsOnlyTheMatchingPrefix() {
        val dir = Files.createTempDirectory("cred4").toFile()
        val store = DesktopCredentialStore(dir)
        store.put("google_drive.accessToken", "a")
        store.put("google_drive.refreshToken", "b")
        store.put("mega.password", "keep")

        store.removeAll("google_drive.")

        assertNull(store.get("google_drive.accessToken"))
        assertNull(store.get("google_drive.refreshToken"))
        // Disconnecting one account must not sign the user out of another.
        assertEquals("keep", store.get("mega.password"))
    }

    @Test
    fun corruptStoreDegradesToEmptyRatherThanCrashing() {
        val dir = Files.createTempDirectory("cred5").toFile()
        DesktopCredentialStore(dir).put("k", "v")
        dir.resolve("credentials.properties").writeText("k=not-valid-base64!!\n")

        // Losing credentials means re-linking, which is recoverable; crashing on
        // startup is not.
        assertNull(DesktopCredentialStore(dir).get("k"))
    }

    @Test
    fun tamperedCiphertextIsRejectedByGcmAuthentication() {
        val dir = Files.createTempDirectory("cred6").toFile()
        DesktopCredentialStore(dir).put("k", "original-value")

        val file = dir.resolve("credentials.properties")
        val line = file.readLines().first { it.startsWith("k=") }
        val (iv, data) = line.substringAfter('=').split(':')
        // Flip a character in the ciphertext.
        val flipped = data.toCharArray().also {
            it[0] = if (it[0] == 'A') 'B' else 'A'
        }.concatToString()
        file.writeText("k=$iv:$flipped\n")

        // GCM authenticates the ciphertext, so tampering fails cleanly instead
        // of yielding a garbage token that would confuse every later request.
        assertNull(DesktopCredentialStore(dir).get("k"))
    }
}
