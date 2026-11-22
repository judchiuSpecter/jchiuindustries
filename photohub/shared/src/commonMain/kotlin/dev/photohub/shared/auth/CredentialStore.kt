package dev.photohub.shared.auth

/**
 * Encrypted storage for OAuth tokens and other secrets.
 *
 * Secrets never go in the SQLDelight database or in `Source.configJson` — those
 * are plaintext and get copied around by backups and debugging tools. Each
 * platform backs this with its own keystore-protected store.
 *
 * Keys are opaque strings, conventionally `"<sourceId>.<field>"`.
 */
interface CredentialStore {

    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)

    /** Wipes every secret for a source, used when an account is disconnected. */
    fun removeAll(keyPrefix: String)
}

/**
 * An OAuth token pair plus the moment the access token stops being valid.
 *
 * Only the refresh token is durable; access tokens are short-lived and are
 * re-minted on demand. [expiresAtEpochMs] is stored rather than a duration so a
 * restart does not make a stale token look fresh.
 */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMs: Long,
) {
    /**
     * Treats the token as expired slightly early so a request cannot be sent
     * with a token that lapses mid-flight.
     */
    fun isExpired(nowEpochMs: Long, skewMs: Long = 60_000): Boolean =
        nowEpochMs >= expiresAtEpochMs - skewMs
}

/**
 * Reads and writes [OAuthTokens] for one source through a [CredentialStore].
 *
 * Kept separate from the store so the key layout lives in one place rather than
 * being spelled out at every call site.
 */
class OAuthTokenStore(
    private val store: CredentialStore,
    private val sourceId: String,
) {
    fun load(): OAuthTokens? {
        val access = store.get(key(ACCESS)) ?: return null
        val expiry = store.get(key(EXPIRY))?.toLongOrNull() ?: 0L
        return OAuthTokens(
            accessToken = access,
            refreshToken = store.get(key(REFRESH)),
            expiresAtEpochMs = expiry,
        )
    }

    fun save(tokens: OAuthTokens) {
        store.put(key(ACCESS), tokens.accessToken)
        store.put(key(EXPIRY), tokens.expiresAtEpochMs.toString())
        // A refresh response omits the refresh token; keep the one we already have.
        tokens.refreshToken?.let { store.put(key(REFRESH), it) }
    }

    fun clear() = store.removeAll("$sourceId.")

    private fun key(field: String) = "$sourceId.$field"

    private companion object {
        const val ACCESS = "accessToken"
        const val REFRESH = "refreshToken"
        const val EXPIRY = "expiresAt"
    }
}
