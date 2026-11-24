package dev.photohub.shared.auth

import dev.carlsen.mega.Mega
import dev.photohub.shared.data.UploadTarget
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.StorageQuota
import dev.photohub.shared.source.MediaSource
import dev.photohub.shared.source.mega.MegaSource
import dev.photohub.shared.source.mega.MegaUploadTarget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Builds and caches one MEGA client graph per source id.
 *
 * A [Mega] instance owns mutable session state, an event-polling coroutine and
 * a credential namespace, so it cannot be a Koin singleton when several MEGA
 * accounts are connected. The cache also makes login single-flight: concurrent
 * thumbnails must not each try to resume the same session.
 */
class MegaAccountFactory(
    private val credentials: CredentialStore,
) : MegaAccounts {

    /** Everything one connected MEGA account needs. */
    data class Account(
        val sourceId: String,
        val mega: Mega,
        val sessions: MegaSessionStore,
    )

    private val cache = mutableMapOf<String, Account>()
    private val mutex = Mutex()

    override fun isLinked(sourceId: String): Boolean = sessions(sourceId).load() != null

    override suspend fun link(
        sourceId: String,
        email: String,
        password: String,
        mfa: String?,
    ): Result<String> = runCatching {
        val login = email.trim().lowercase()
        require(login.isNotBlank()) { "Enter your MEGA email address." }
        require(password.isNotBlank()) { "Enter your MEGA password." }

        val mega = Mega()
        mega.login(login, password, mfa?.takeIf { it.isNotBlank() })
        val session = mega.dumpSession()
            ?: error("MEGA did not return a session; try connecting again.")

        val store = sessions(sourceId)
        store.save(session)
        mutex.withLock { cache[sourceId] = Account(sourceId, mega, store) }
        login
    }

    override suspend fun unlink(sourceId: String) {
        val account = mutex.withLock { cache.remove(sourceId) }
        runCatching { account?.mega?.logout() }
        sessions(sourceId).clear()
    }

    override suspend fun quota(sourceId: String): StorageQuota? = runCatching {
        val response = forSource(sourceId).mega.getQuota()
        // ULong values are always non-negative here; overflow would only happen
        // on a byte count above Long.MAX_VALUE, which MEGA cannot report.
        StorageQuota(
            limitBytes = response.spaceCapacityBytes.toLong(),
            usedBytes = response.spaceUsedBytes.toLong(),
        )
    }.getOrNull()

    override suspend fun sourceFor(config: SourceConfig): MediaSource =
        MegaSource(config, forSource(config.id))

    override suspend fun targetFor(config: SourceConfig): UploadTarget =
        MegaUploadTarget(forSource(config.id))

    private suspend fun forSource(sourceId: String): Account = mutex.withLock {
        cache[sourceId] ?: build(sourceId).also { cache[sourceId] = it }
    }

    private suspend fun build(sourceId: String): Account {
        val store = sessions(sourceId)
        val session = store.load()
            ?: error("MEGA source $sourceId is not connected. Reconnect it in Sources.")
        val mega = Mega()
        mega.fastLogin(session)
        return Account(sourceId, mega, store)
    }

    private fun sessions(sourceId: String): MegaSessionStore = MegaSessionStore(credentials, sourceId)
}
