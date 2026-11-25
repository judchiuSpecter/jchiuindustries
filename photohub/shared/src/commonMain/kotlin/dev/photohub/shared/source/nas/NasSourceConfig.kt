package dev.photohub.shared.source.nas

import kotlinx.serialization.Serializable

/**
 * Non-secret settings for one NAS archive source, stored in `Source.configJson`.
 *
 * The password never lives here: it goes to the encrypted credential store,
 * same as MEGA's session key. Unlike Drive/MEGA, the NAS is read-only — there
 * is no upload target or root-folder-for-uploads concept, just a folder to
 * read photos/videos from.
 */
@Serializable
data class NasSourceConfig(
    /** Hostname or IP PhotoHub connects to, e.g. `terrathunder.myqnapcloud.com`. */
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    /**
     * Folder to index, as an absolute SFTP path (QTS's admin login lands in
     * the OS root, not a share root, so a relative path would not resolve).
     * Configurable because every NAS layout differs — this user's photo
     * archive lives at `/share/Photos`.
     */
    val remotePath: String = "/share/Photos",
) {
    companion object {
        /** Instance ids are `<prefix>_<8 hex>` so they cannot collide with other sources. */
        const val SOURCE_ID_PREFIX = "nas"
    }
}
