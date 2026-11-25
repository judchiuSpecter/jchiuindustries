package dev.photohub.shared.source.nas

import dev.photohub.shared.platform.cacheDir
import dev.photohub.shared.platform.sha256OfFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.FileSystemFile
import okio.Path
import java.io.File
import java.util.UUID

/**
 * Write side of the NAS archive. Deliberately separate from [NasMediaSource],
 * which stays read-only for the timeline: only this occasional, explicitly
 * triggered flow is ever allowed to create files on the share.
 *
 * Every operation opens its own short-lived SSH connection, matching the
 * existing NAS source and avoiding a long-lived socket on a host that is only
 * reachable sporadically.
 */
internal class NasArchiveWriter(
    private val configJson: String,
    private val credentials: () -> String?,
) {
    private val settings: NasSourceConfig =
        runCatching { Json.decodeFromString<NasSourceConfig>(configJson) }
            .getOrElse { NasSourceConfig() }

    suspend fun size(remotePath: String): Result<Long?> = withContext(Dispatchers.IO) {
        runCatching {
            withClient { sftp ->
                runCatching { sftp.stat(remotePath).size }.getOrNull()
            }
        }
    }

    suspend fun copyVerified(local: Path, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val expectedHash = sha256OfFile(local.toString())
                    ?: error("Could not hash the downloaded original")
                val parent = remotePath.substringBeforeLast('/', "/").ifBlank { "/" }
                val tempPath = "$remotePath.part-${UUID.randomUUID()}"

                withClient { sftp ->
                    sftp.mkdirs(parent)
                    var uploaded = false
                    try {
                        sftp.put(FileSystemFile(File(local.toString())), tempPath)
                        uploaded = true

                        // Verify from the NAS itself, not just the local bytes:
                        // a truncated SFTP write must never authorize a cloud delete.
                        val verifyDir = File(cacheDir.toString()).also { it.mkdirs() }
                        val verify = File.createTempFile("nas-verify-", ".bin", verifyDir)
                        try {
                            sftp.get(tempPath, FileSystemFile(verify))
                            val actualHash = sha256OfFile(verify.absolutePath)
                            if (actualHash != expectedHash) {
                                error("NAS checksum mismatch for ${local.name}")
                            }
                        } finally {
                            verify.delete()
                        }

                        // Replace any earlier partial/final copy. The temp file is
                        // fully verified before the visible name appears.
                        runCatching { sftp.rm(remotePath) }
                        sftp.rename(tempPath, remotePath)
                        uploaded = false // rename consumed the temp file
                    } finally {
                        if (uploaded) runCatching { sftp.rm(tempPath) }
                    }
                }
            }
        }

    private suspend fun <T> withClient(block: suspend (SFTPClient) -> T): T {
        val password = credentials() ?: error("Not connected to the NAS.")
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connect(settings.host, settings.port)
        ssh.authPassword(settings.username, password)
        val sftp = ssh.newSFTPClient()
        try {
            return block(sftp)
        } finally {
            runCatching { sftp.close() }
            runCatching { ssh.disconnect() }
        }
    }
}
