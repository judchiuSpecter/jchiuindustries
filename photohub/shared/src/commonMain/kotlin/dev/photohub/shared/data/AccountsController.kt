package dev.photohub.shared.data

import dev.photohub.shared.auth.DriveAccountFactory
import dev.photohub.shared.auth.DriveLinkCoordinator
import dev.photohub.shared.auth.MegaAccounts
import dev.photohub.shared.auth.NasAccounts
import dev.photohub.shared.auth.GoogleOAuthConfig
import dev.photohub.shared.auth.OAuthConfigLoader
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.model.StorageQuota
import dev.photohub.shared.platform.BackgroundScheduler
import dev.photohub.shared.source.drive.DriveSourceConfig
import dev.photohub.shared.source.mega.MegaSourceConfig
import dev.photohub.shared.source.nas.NasSourceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Drives the sources screen: every configured source instance, plus linking,
 * disconnecting and picking which cloud receives camera uploads.
 *
 * The "not configured" state is modelled explicitly rather than treated as an
 * error. A fresh install has no OAuth client id, and that is a setup step the
 * user has to perform — not a failure. Collapsing it into a generic error would
 * leave them with no idea what to do next.
 */
class AccountsController(
    private val repository: MediaRepository,
    private val drives: DriveAccountFactory,
    private val megas: MegaAccounts,
    private val nas: NasAccounts,
    private val oauthConfig: GoogleOAuthConfig,
    private val scheduler: BackgroundScheduler = BackgroundScheduler(),
    /** Overridable so tests can use predictable ids. */
    private val generateSourceId: () -> String = { newDriveSourceId() },
) {
    enum class LinkState {
        /** No OAuth client id supplied yet; the user must do setup first. */
        NOT_CONFIGURED,
        NOT_LINKED,
        LINKING,
        LINKED,
        /** A source row for a provider that has no implementation yet. */
        UNSUPPORTED,
    }

    /** One configured source instance as the screen renders it. */
    data class SourceAccount(
        val id: String,
        val type: SourceType,
        val displayName: String,
        val state: LinkState,
        /** Whether this source can receive camera uploads (Drive/MEGA only). */
        val canUpload: Boolean = false,
        val isUploadTarget: Boolean = false,
        /** Whether this source can receive explicit Archive actions. */
        val canArchive: Boolean = false,
        val isArchiveTarget: Boolean = false,
        /** Account-wide storage quota, fetched only while this screen is open. */
        val quota: StorageQuota? = null,
        /** A link attempt (connect/reconnect) that failed; cleared by retrying the dialog. */
        val error: String? = null,
        /**
         * The last *sync* attempt failed (account blocked, host unreachable…).
         * Persisted, so it survives a restart, and shown as an alert icon
         * rather than inline text since it can happen to an otherwise-linked
         * source at any time. Cleared by [retrySource].
         */
        val syncError: String? = null,
    )

    data class State(
        val sources: List<SourceAccount> = emptyList(),
        /** Set after a successful action so the UI can confirm it. */
        val message: String? = null,
        val quotaLoading: Boolean = false,
        val oauthConfigured: Boolean = true,
        /** Where to put `google-oauth.json`, shown when OAuth is not configured. */
        val setupPath: String = OAuthConfigLoader.expectedPath(),
    )

    /** Transient UI detail kept outside [State] so a storage refresh cannot lose it. */
    private var linkingId: String? = null
    private val errors = mutableMapOf<String, String>()
    /** Provider for errors that occurred before a Source row existed. */
    private val errorTypes = mutableMapOf<String, SourceType>()
    /** Last known quota per source; kept so an offline visit still shows a value. */
    private val quotas = mutableMapOf<String, StorageQuota>()
    private var quotaLoading = false
    private var message: String? = null

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Recomputes from storage; call when the screen is shown. */
    fun refresh() {
        _state.value = buildState()
    }

    /**
     * Fetches account-wide storage quota for each connected Drive source.
     *
     * Called from the sources screen only. It is deliberately not part of sync:
     * quota is informational, changes independently of our index, and a network
     * failure must not affect the timeline. The last successful value stays on
     * screen when a later fetch fails.
     */
    suspend fun refreshQuotas() {
        val connected = repository.allSources().filter { source ->
            source.enabled && when (source.type) {
                SourceType.GOOGLE_DRIVE -> drives.isLinked(source.id)
                SourceType.MEGA -> megas.isLinked(source.id)
                else -> false
            }
        }
        if (connected.isEmpty()) return

        quotaLoading = true
        publish()
        for (source in connected) {
            val quota = when (source.type) {
                SourceType.MEGA -> megas.quota(source.id)
                else -> {
                    // `about` is already a Result — no need for a second runCatching.
                    val details = drives.forSource(source.id).api.about().getOrNull()?.storageQuota
                    details?.let {
                        StorageQuota(
                            limitBytes = it.limit?.toLongOrNull(),
                            usedBytes = it.usage?.toLongOrNull(),
                            usedInDriveBytes = it.usageInDrive?.toLongOrNull(),
                        )
                    }
                }
            }
            if (quota != null) quotas[source.id] = quota
        }
        quotaLoading = false
        publish()
    }

    /**
     * Re-arms background upload at app start when an account is linked.
     *
     * WorkManager schedules survive restarts, but not an app reinstall or a
     * "clear data", and a user who linked in an older build never got the
     * schedule at all. Re-asserting it is cheap: the periodic request uses KEEP,
     * so an existing schedule is left alone rather than restarted.
     */
    fun syncBackgroundWork() {
        scheduler.setCameraUploadEnabled(repository.uploadTarget()?.enabled == true)
    }

    /** Explicit "upload now", for when the user does not want to wait. */
    fun uploadNow() = scheduler.requestUploadNow()

    /**
     * Runs the Google sign-in flow for a new or previously disconnected source.
     *
     * Passing no id creates a new instance with its own credential namespace;
     * passing an existing id re-links that account. On success the source row
     * is created (or re-enabled) and, if it is the first cloud, made the camera
     * upload target.
     */
    suspend fun linkDrive(sourceId: String? = null) {
        if (!oauthConfig.isConfigured) {
            val id = sourceId ?: DriveSourceConfig.SOURCE_ID
            errors[id] = "No Google OAuth client ID found. See README → Connecting Google Drive."
            errorTypes[id] = SourceType.GOOGLE_DRIVE
            publish()
            return
        }

        val id = sourceId ?: generateSourceId()
        linkingId = id
        errors.remove(id)
        errorTypes[id] = SourceType.GOOGLE_DRIVE
        publish()

        val account = drives.forSource(id)
        when (val outcome = account.link.link()) {
            is DriveLinkCoordinator.Outcome.Success -> {
                linkingId = null

                // A label is a convenience, not a precondition: if `about`
                // fails (offline, tightened scope), the source still works.
                val email = account.api.about().getOrNull()?.user?.emailAddress
                    ?.takeIf { it.isNotBlank() }
                val displayName = if (email != null) "Google Drive ($email)" else "Google Drive"

                val existing = repository.sourceById(id)
                val previousConfig = existing?.configJson?.let { json ->
                    runCatching { Json.decodeFromString<DriveSourceConfig>(json) }.getOrNull()
                }
                val config = (previousConfig ?: DriveSourceConfig()).copy(accountEmail = email)

                // Take the upload slot when there is no target yet (first link,
                // or a re-link after disconnecting the old target). Never steal
                // it from a different cloud the user explicitly chose.
                val currentTargetId = repository.uploadTarget()?.id
                val makeTarget = existing?.isUploadTarget == true ||
                    currentTargetId == null ||
                    currentTargetId == id

                repository.upsertCloudSource(
                    id = id,
                    type = SourceType.GOOGLE_DRIVE,
                    displayName = displayName,
                    configJson = Json.encodeToString(config),
                    enabled = true,
                    makeUploadTarget = makeTarget,
                )
                scheduler.setCameraUploadEnabled(true)
                message = "$displayName connected. New photos will be copied to it."
                publish()
            }

            is DriveLinkCoordinator.Outcome.Failed -> {
                linkingId = null
                errors[id] = outcome.reason
                publish()
            }
        }
    }

    /**
     * Connects a MEGA account with email/password (and an optional 2FA code).
     *
     * Only the resumable session is stored; the password is never persisted.
     * On success a new source instance is created and, if no cloud owns camera
     * uploads yet, it becomes the upload target.
     */
    suspend fun linkMega(
        email: String,
        password: String,
        mfa: String? = null,
        sourceId: String? = null,
    ) {
        val id = sourceId ?: ("mega_" + Random.nextBytes(4).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        })
        linkingId = id
        errors.remove(id)
        errorTypes[id] = SourceType.MEGA
        publish()

        val outcome = megas.link(id, email, password, mfa)
        val label = outcome.getOrNull()?.ifBlank { "MEGA" }
        if (label != null) {
            linkingId = null
            val displayName = "MEGA ($label)"

            val existing = repository.sourceById(id)
            val previousConfig = existing?.configJson?.let { json ->
                runCatching { Json.decodeFromString<MegaSourceConfig>(json) }.getOrNull()
            }
            val config = (previousConfig ?: MegaSourceConfig()).copy(accountEmail = label)

            val currentTargetId = repository.uploadTarget()?.id
            val makeTarget = existing?.isUploadTarget == true ||
                currentTargetId == null ||
                currentTargetId == id
            repository.upsertCloudSource(
                id = id,
                type = SourceType.MEGA,
                displayName = displayName,
                configJson = Json.encodeToString(config),
                enabled = true,
                makeUploadTarget = makeTarget,
            )
            scheduler.setCameraUploadEnabled(true)
            message = "$displayName connected. New photos will be copied to it."
        } else {
            linkingId = null
            errors[id] = outcome.exceptionOrNull()?.message ?: "Could not connect to MEGA."
        }
        publish()
    }

    /**
     * Connects a NAS archive over SFTP with a host/username/password and the
     * folder to index. The password is verified against the NAS before being
     * stored; nothing in this source is ever an upload target.
     */
    suspend fun linkNas(
        host: String,
        username: String,
        password: String,
        remotePath: String,
        port: Int = 22,
        sourceId: String? = null,
    ) {
        val id = sourceId ?: ("nas_" + Random.nextBytes(4).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        })
        linkingId = id
        errors.remove(id)
        errorTypes[id] = SourceType.NAS_ARCHIVE
        publish()

        val outcome = nas.link(id, host, port, username, password, remotePath)
        val label = outcome.getOrNull()
        if (label != null) {
            linkingId = null
            val displayName = "NAS ($label)"
            val config = NasSourceConfig(host = host, port = port, username = username, remotePath = remotePath)

            repository.upsertCloudSource(
                id = id,
                type = SourceType.NAS_ARCHIVE,
                displayName = displayName,
                configJson = Json.encodeToString(config),
                enabled = true,
                // Read-only: never becomes the upload target.
                makeUploadTarget = false,
            )
            message = "$displayName connected."
        } else {
            linkingId = null
            errors[id] = outcome.exceptionOrNull()?.message ?: "Could not connect to the NAS."
        }
        publish()
    }

    /**
     * Disconnects one source: tokens are cleared and the row is disabled, but
     * its indexed rows are kept so the timeline does not suddenly lose entries.
     * Nothing is ever deleted from the cloud itself.
     */
    suspend fun unlinkSource(id: String) {
        val source = repository.sourceById(id) ?: return
        if (source.type == SourceType.MEGA) {
            megas.unlink(id)
        } else if (source.type == SourceType.GOOGLE_DRIVE) {
            drives.forSource(id).tokens.unlink()
        } else if (source.type == SourceType.NAS_ARCHIVE) {
            nas.unlink(id)
        } else {
            return
        }
        repository.upsertCloudSource(
            id = id,
            type = source.type,
            displayName = source.displayName,
            configJson = source.configJson,
            enabled = false,
        )
        if (source.isUploadTarget) repository.clearUploadTarget(id)
        if (source.isArchiveTarget) repository.clearArchiveTarget(id)
        updateScheduler()

        message = "${source.displayName} disconnected."
        errors.remove(id)
        publish()
    }

    /** Makes this cloud the destination for new camera photos. Exactly one may be. */
    fun setUploadTarget(id: String) {
        val source = repository.sourceById(id) ?: return
        if (source.type !in setOf(SourceType.GOOGLE_DRIVE, SourceType.MEGA) || !source.enabled) return

        repository.upsertCloudSource(
            id = id,
            type = source.type,
            displayName = source.displayName,
            configJson = source.configJson,
            enabled = source.enabled,
            makeUploadTarget = true,
        )
        scheduler.setCameraUploadEnabled(true)
        message = "Camera uploads now go to ${source.displayName}."
        publish()
    }

    /** Makes this source the destination of explicit Archive actions. Exactly one may be. */
    fun setArchiveTarget(id: String) {
        val source = repository.sourceById(id) ?: return
        if (source.type !in ARCHIVE_TARGET_TYPES || !source.enabled) return
        repository.setArchiveTarget(id)
        message = "Archived photos now go to ${source.displayName}."
        publish()
    }

    /** Clears a one-shot message or error after the UI has shown it. */
    fun consumeMessage() {
        message = null
        errors.clear()
        errorTypes.clear()
        publish()
    }

    private fun updateScheduler() {
        scheduler.setCameraUploadEnabled(repository.uploadTarget()?.enabled == true)
    }

    private fun publish() {
        _state.value = buildState()
    }

    private fun buildState(): State {
        val configured = repository.allSources()
        val rows = configured.map { source ->
            val linked = when (source.type) {
                SourceType.GOOGLE_DRIVE -> drives.isLinked(source.id)
                SourceType.MEGA -> megas.isLinked(source.id)
                SourceType.NAS_ARCHIVE -> nas.isLinked(source.id)
                // The device source is always available; unsupported types never
                // reach this point through the UI but render honestly if present.
                SourceType.LOCAL -> true
                else -> false
            }
            SourceAccount(
                id = source.id,
                type = source.type,
                displayName = source.displayName,
                state = when {
                    source.type !in SUPPORTED_TYPES -> LinkState.UNSUPPORTED
                    !oauthConfig.isConfigured && source.type == SourceType.GOOGLE_DRIVE -> LinkState.NOT_CONFIGURED
                    source.id == linkingId -> LinkState.LINKING
                    linked && source.enabled -> LinkState.LINKED
                    else -> LinkState.NOT_LINKED
                },
                canUpload = source.type == SourceType.GOOGLE_DRIVE || source.type == SourceType.MEGA,
                isUploadTarget = source.isUploadTarget,
                canArchive = source.enabled && source.type in ARCHIVE_TARGET_TYPES,
                isArchiveTarget = source.isArchiveTarget,
                quota = quotas[source.id],
                error = errors[source.id],
                syncError = source.lastSyncError,
            )
        }.toMutableList()

        // A first-time link that failed has no Source row, but its error still
        // needs a card to live on — otherwise the "+" flow fails silently.
        for ((id, error) in errors) {
            if (configured.none { it.id == id }) {
                val type = errorTypes[id] ?: SourceType.GOOGLE_DRIVE
                rows += SourceAccount(
                    id = id,
                    type = type,
                    displayName = when (type) {
                        SourceType.MEGA -> "MEGA"
                        SourceType.NAS_ARCHIVE -> "NAS archive"
                        else -> "Google Drive"
                    },
                    state = when {
                        !oauthConfig.isConfigured && type == SourceType.GOOGLE_DRIVE -> LinkState.NOT_CONFIGURED
                        else -> LinkState.NOT_LINKED
                    },
                    canUpload = type != SourceType.NAS_ARCHIVE,
                    quota = quotas[id],
                    error = error,
                )
            }
        }

        return State(
            sources = rows,
            message = message,
            quotaLoading = quotaLoading,
            oauthConfigured = oauthConfig.isConfigured,
        )
    }

    companion object {
        /** Provider types the sources screen can actually connect. */
        private val SUPPORTED_TYPES =
            setOf(SourceType.LOCAL, SourceType.GOOGLE_DRIVE, SourceType.MEGA, SourceType.NAS_ARCHIVE)

        /** Sources with a write/delete implementation, so they can be archive targets. */
        private val ARCHIVE_TARGET_TYPES = SUPPORTED_TYPES

        /**
         * Instance ids must be unique per account. The legacy single-account id is
         * kept as the prefix so existing installs keep their credential keys and
         * Source row, with no migration.
         */
        private fun newDriveSourceId(): String {
            val suffix = Random.nextBytes(4).joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            return "${DriveSourceConfig.SOURCE_ID}_$suffix"
        }
    }
}
