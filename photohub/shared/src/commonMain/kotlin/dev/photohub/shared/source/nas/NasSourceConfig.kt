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
     * Folder to index, relative to the SFTP root PhotoHub's user lands in.
     * Configurable because every NAS layout differs — this user's photo
     * archive lives at `Volume2/Photos`.
     */
    val remotePath: String = "Volume2/Photos",
) {
    companion object {
        /** Instance ids are `<prefix>_<8 hex>` so they cannot collide with other sources. */
        const val SOURCE_ID_PREFIX = "nas"
    }
}
