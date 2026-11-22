package dev.photohub.shared.model

import kotlinx.serialization.Serializable

/**
 * Decoded form of [SourceConfig.configJson] for a [SourceType.LOCAL] source.
 *
 * On Android the camera roll is enumerated through MediaStore, so [roots] is
 * ignored — the OS decides what the app may see based on the granted media
 * permission. On desktop there is no media index, so [roots] lists the
 * directories to walk (defaulting to the user's Pictures folder).
 */
@Serializable
data class LocalSourceConfig(
    val roots: List<String> = emptyList(),
    val includeVideos: Boolean = true,
) {
    companion object {
        /** Stable id for the single device-local source. */
        const val SOURCE_ID: String = "local"
    }
}
