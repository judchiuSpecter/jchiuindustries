package dev.photohub.shared.data

import dev.photohub.shared.auth.DriveLinkCoordinator
import dev.photohub.shared.auth.GoogleOAuthConfig
import dev.photohub.shared.auth.OAuthConfigLoader
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.source.drive.DriveSourceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the accounts screen: which clouds are connected, and linking/unlinking.
 *
 * The "not configured" state is modelled explicitly rather than treated as an
 * error. A fresh install has no OAuth client id, and that is a setup step the
 * user has to perform — not a failure. Collapsing it into a generic error would
 * leave them with no idea what to do next.
 */
class AccountsController(
    private val repository: MediaRepository,
    private val driveLink: DriveLinkCoordinator,
    private val oauthConfig: GoogleOAuthConfig,
) {
    /** Connection state of one cloud account. */
    enum class LinkState {
        /** No OAuth client id supplied yet; the user must do setup first. */
        NOT_CONFIGURED,
        NOT_LINKED,
        LINKING,
        LINKED,
    }

    data class DriveAccount(
        val state: LinkState,
        val displayName: String = "Google Drive",
        val isUploadTarget: Boolean = false,
        val error: String? = null,
        /** Where to put `google-oauth.json`, shown when NOT_CONFIGURED. */
        val setupPath: String = OAuthConfigLoader.expectedPath(),
    )

    data class State(
        val drive: DriveAccount,
        /** Set after a successful link so the UI can confirm it. */
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State(drive = currentDrive()))
    val state: StateFlow<State> = _state.asStateFlow()

    /** Recomputes from storage; call when the screen is shown. */
    fun refresh() {
        _state.value = State(drive = currentDrive())
    }

    /**
     * Runs the Google sign-in flow.
     *
     * On success the Drive source row is created and made the camera-upload
     * target, since connecting a cloud is only useful if photos go to it.
     */
    suspend fun linkDrive() {
        if (!oauthConfig.isConfigured) {
            _state.value = State(
                drive = currentDrive().copy(
                    state = LinkState.NOT_CONFIGURED,
                    error = "No Google OAuth client ID found. See README → Connecting Google Drive.",
                ),
            )
            return
        }

        // Only one Google account is supported today: the source row id and the
        // credential-store key namespace are both the constant
        // DriveSourceConfig.SOURCE_ID, so a second sign-in would overwrite the
        // first account's refresh token and its Source row in place — losing an
        // account silently. Refuse instead. Multi-account needs per-instance
        // source ids; see DESIGN.md "Multiple cloud accounts".
        if (driveLink.isLinked()) {
            _state.value = State(
                drive = currentDrive().copy(
                    error = "A Google account is already connected. " +
                        "Disconnect it first — PhotoHub cannot hold two Google accounts yet.",
                ),
            )
            return
        }

        _state.value = State(drive = currentDrive().copy(state = LinkState.LINKING))

        when (val outcome = driveLink.link()) {
            is DriveLinkCoordinator.Outcome.Success -> {
                repository.upsertCloudSource(
                    id = DriveSourceConfig.SOURCE_ID,
                    type = SourceType.GOOGLE_DRIVE,
                    displayName = "Google Drive",
                    makeUploadTarget = true,
                )
                _state.value = State(
                    drive = currentDrive(),
                    message = "Google Drive connected. New photos will upload here.",
                )
            }

            is DriveLinkCoordinator.Outcome.Failed -> {
                _state.value = State(
                    drive = currentDrive().copy(error = outcome.reason),
                )
            }
        }
    }

    /**
     * Disconnects Drive.
     *
     * Tokens are cleared and the source is disabled, but its indexed rows are
     * kept so the timeline does not suddenly lose entries — and nothing is ever
     * deleted from the cloud itself.
     */
    fun unlinkDrive() {
        driveLink.unlink()
        repository.sourceById(DriveSourceConfig.SOURCE_ID)?.let {
            repository.upsertCloudSource(
                id = it.id,
                type = it.type,
                displayName = it.displayName,
                configJson = it.configJson,
                enabled = false,
            )
        }
        _state.value = State(drive = currentDrive(), message = "Google Drive disconnected.")
    }

    /** Clears a one-shot message or error after the UI has shown it. */
    fun consumeMessage() {
        _state.value = _state.value.copy(
            message = null,
            drive = _state.value.drive.copy(error = null),
        )
    }

    private fun currentDrive(): DriveAccount {
        val source = repository.sourceById(DriveSourceConfig.SOURCE_ID)
        val linked = driveLink.isLinked() && source?.enabled == true

        return DriveAccount(
            state = when {
                !oauthConfig.isConfigured -> LinkState.NOT_CONFIGURED
                linked -> LinkState.LINKED
                else -> LinkState.NOT_LINKED
            },
            displayName = source?.displayName ?: "Google Drive",
            isUploadTarget = source?.isUploadTarget == true,
        )
    }
}
