package dev.photohub.shared.auth

import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.source.MediaSource
import dev.photohub.shared.source.nas.NasArchiveWriter
import dev.photohub.shared.source.nas.NasMediaSource
import dev.photohub.shared.source.nas.NasSourceConfig
import okio.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier

/**
 * The NAS counterpart of [MegaAccountFactory].
 *
 * There is no session to resume the way MEGA has one: SFTP just needs the
 * password on every connection, so what is cached here is nothing more than
 * "is this source's password present" — the [NasMediaSource] opens its own
 * short-lived connection per call rather than keeping one alive, since the NAS
 * is only occasionally reachable and a long-lived SSH session would otherwise
 * need its own reconnect logic.
 */
class NasAccountFactory(
    private val credentials: CredentialStore,
) : NasAccounts {

    override fun isLinked(sourceId: String): Boolean = passwords(sourceId).load() != null

    override suspend fun link(
        sourceId: String,
        host: String,
        port: Int,
        username: String,
        password: String,
        remotePath: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedHost = host.trim()
            require(trimmedHost.isNotBlank()) { "Enter the NAS hostname or IP address." }
            require(username.isNotBlank()) { "Enter the NAS username." }
            require(password.isNotBlank()) { "Enter the NAS password." }

            // Verify the credentials actually work before saving anything, so a
            // typo does not leave a source that looks linked but never connects.
            val ssh = SSHClient()
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connect(trimmedHost, port)
            try {
                ssh.authPassword(username, password)
                // Trimming trailing slashes only: QTS's SFTP login lands in the
                // OS root rather than a share root, so the configured path is
                // normally absolute (e.g. `/share/Photos`) and must stay that way.
                ssh.newSFTPClient().use { it.ls(remotePath.ifBlank { "/" }.removeSuffix("/")) }
            } finally {
                runCatching { ssh.disconnect() }
            }

            passwords(sourceId).save(password)
            trimmedHost
        }
    }

    override suspend fun unlink(sourceId: String) {
        passwords(sourceId).clear()
    }

    override suspend fun sourceFor(config: SourceConfig): MediaSource {
        val password = { passwords(config.id).load() }
        return NasMediaSource(config, password)
    }

    override suspend fun archivedFileSize(config: SourceConfig, remotePath: String): Result<Long?> =
        writer(config).size(remotePath)

    override suspend fun copyToArchive(
        config: SourceConfig,
        local: Path,
        remotePath: String,
    ): Result<Unit> = writer(config).copyVerified(local, remotePath)

    private fun writer(config: SourceConfig) = NasArchiveWriter(
        configJson = config.configJson,
        credentials = { passwords(config.id).load() },
    )

    private fun passwords(sourceId: String) = NasCredentialStore(credentials, sourceId)
}
