package dev.photohub.shared.auth

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop credential store using AES-GCM with a key file on disk.
 *
 * Desktop has no universal hardware keystore (and no Mac to use the Keychain
 * from), so this is honest about its threat model: it protects against casual
 * inspection and accidental leakage into backups or dotfile repos, not against
 * an attacker who already has read access to the user's home directory. Both the
 * key and the store are chmod 600.
 *
 * GCM is used rather than CBC because it authenticates the ciphertext, so a
 * tampered token file fails to decrypt instead of yielding garbage.
 */
class DesktopCredentialStore(
    private val dir: File,
) : CredentialStore {

    private val storeFile = File(dir, "credentials.properties")
    private val keyFile = File(dir, "credentials.key")

    private val key: SecretKey by lazy { loadOrCreateKey() }

    /** Decrypted view, loaded once and written through on change. */
    private val entries: MutableMap<String, String> by lazy { load() }

    override fun get(key: String): String? = entries[key]

    override fun put(key: String, value: String) {
        entries[key] = value
        persist()
    }

    override fun remove(key: String) {
        if (entries.remove(key) != null) persist()
    }

    override fun removeAll(keyPrefix: String) {
        val removed = entries.keys.filter { it.startsWith(keyPrefix) }
        if (removed.isNotEmpty()) {
            removed.forEach(entries::remove)
            persist()
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        dir.mkdirs()
        if (keyFile.isFile) {
            return SecretKeySpec(Base64.getDecoder().decode(keyFile.readText().trim()), "AES")
        }
        val generated = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        keyFile.writeText(Base64.getEncoder().encodeToString(generated.encoded))
        keyFile.restrictToOwner()
        return generated
    }

    private fun load(): MutableMap<String, String> {
        if (!storeFile.isFile) return mutableMapOf()
        return runCatching {
            storeFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    val name = line.substring(0, idx)
                    decrypt(line.substring(idx + 1))?.let { name to it }
                }
                .toMap()
                .toMutableMap()
        // A corrupt or key-mismatched store must not crash the app; the user
        // simply has to re-link, which is recoverable.
        }.getOrDefault(mutableMapOf())
    }

    private fun persist() {
        dir.mkdirs()
        val body = entries.entries.joinToString("\n") { (k, v) -> "$k=${encrypt(v)}" }
        storeFile.writeText("# PhotoHub encrypted credentials. Do not edit.\n$body\n")
        storeFile.restrictToOwner()
    }

    /** Output is `base64(iv):base64(ciphertext)`; the IV must differ per message. */
    private fun encrypt(plain: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val encrypted = cipher.doFinal(plain.toByteArray())
        val encoder = Base64.getEncoder()
        return "${encoder.encodeToString(iv)}:${encoder.encodeToString(encrypted)}"
    }

    private fun decrypt(value: String): String? = runCatching {
        val (ivPart, dataPart) = value.split(':', limit = 2).let { it[0] to it[1] }
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, decoder.decode(ivPart)),
            )
        }
        String(cipher.doFinal(decoder.decode(dataPart)))
    }.getOrNull()

    /** Best-effort chmod 600; silently skipped on filesystems without POSIX perms. */
    private fun File.restrictToOwner() {
        runCatching {
            setReadable(false, false)
            setWritable(false, false)
            setReadable(true, true)
            setWritable(true, true)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
