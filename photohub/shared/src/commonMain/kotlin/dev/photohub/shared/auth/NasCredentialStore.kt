package dev.photohub.shared.auth

/**
 * Persists the SFTP password for one NAS source in the encrypted credential store.
 *
 * The NAS archive has no OAuth flow to resume from, so unlike Drive/MEGA the
 * password itself is what gets stored — same keystore-backed guarantee, just a
 * plain secret instead of a token.
 */
class NasCredentialStore(
    private val store: CredentialStore,
    private val sourceId: String,
) {
    fun load(): String? = store.get(key)?.takeIf { it.isNotBlank() }

    fun save(password: String) = store.put(key, password)

    fun clear() = store.remove(key)

    private val key: String get() = "$sourceId.$FIELD"

    private companion object {
        const val FIELD = "nasPassword"
    }
}
