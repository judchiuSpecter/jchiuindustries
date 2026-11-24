package dev.photohub.shared.auth

/**
 * Persists a MEGA session key for one source id in the encrypted credential store.
 *
 * A session key is enough to resume an account without keeping the password.
 * It is still a credential: it lives in the keystore-backed store, never in the
 * SQLDelight database or `Source.configJson`.
 */
class MegaSessionStore(
    private val store: CredentialStore,
    private val sourceId: String,
) {
    fun load(): String? = store.get(key)?.takeIf { it.isNotBlank() }

    fun save(session: String) = store.put(key, session)

    fun clear() = store.remove(key)

    private val key: String get() = "$sourceId.$FIELD"

    private companion object {
        const val FIELD = "megaSession"
    }
}
