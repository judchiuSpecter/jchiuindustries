package dev.photohub.shared.source.mega

import kotlinx.serialization.Serializable

/**
 * Non-secret settings for one MEGA source, stored in `Source.configJson`.
 *
 * The session key never lives here: it goes to the encrypted credential store.
 */
@Serializable
data class MegaSourceConfig(
    /** Root folder PhotoHub owns. Uploads go to `<root>/YYYY/MM`. */
    val rootFolderName: String = "PhotoHub",
    val accountEmail: String? = null,
) {
    companion object {
        /** Instance ids are `<prefix>_<8 hex>` so they cannot collide with Drive. */
        const val SOURCE_ID_PREFIX = "mega"
    }
}
