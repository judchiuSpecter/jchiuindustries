package dev.photohub.shared.auth

import dev.photohub.shared.platform.cacheDir
import dev.photohub.shared.platform.fileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** On-disk shape of `google-oauth.json`. */
@Serializable
private data class OAuthConfigFile(
    val clientId: String = "",
    val clientSecret: String? = null,
)

/**
 * Loads the Google OAuth client ID from configuration.
 *
 * The client ID is deliberately *not* compiled in. It identifies this
 * installation to Google, it is not a shared secret, and keeping it in a config
 * file means the project can be published without embedding the author's
 * credentials — anyone building PhotoHub supplies their own.
 *
 * Resolution order:
 *  1. `PHOTOHUB_GOOGLE_CLIENT_ID` environment variable (handy for development)
 *  2. `google-oauth.json` next to the app's data directory
 *
 * Returns a config with a blank client id when nothing is found;
 * [GoogleOAuthConfig.isConfigured] is false in that case so the UI can prompt
 * for setup instead of failing mid-sign-in.
 */
object OAuthConfigLoader {

    const val FILE_NAME = "google-oauth.json"

    fun load(envClientId: String? = null): GoogleOAuthConfig {
        envClientId?.takeIf { it.isNotBlank() }?.let {
            return GoogleOAuthConfig(clientId = it.trim())
        }

        val path = cacheDir.parent?.resolve(FILE_NAME) ?: cacheDir.resolve(FILE_NAME)
        if (!fileSystem.exists(path)) return GoogleOAuthConfig(clientId = "")

        val parsed = runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString<OAuthConfigFile>(fileSystem.read(path) { readUtf8() })
        }.getOrNull() ?: return GoogleOAuthConfig(clientId = "")

        return GoogleOAuthConfig(
            clientId = parsed.clientId.trim(),
            clientSecret = parsed.clientSecret?.takeIf { it.isNotBlank() },
        )
    }

    /** Where the user should put the file, for display in setup instructions. */
    fun expectedPath(): String =
        (cacheDir.parent?.resolve(FILE_NAME) ?: cacheDir.resolve(FILE_NAME)).toString()
}
