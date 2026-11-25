package dev.photohub.shared.data

import dev.photohub.shared.model.SourceType
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Layout shared by manual Archive actions and the older bulk cloud→NAS archiver.
 *
 * One folder scheme keeps archives browsable on any target: source type then
 * capture date. The file name keeps the original extension (so thumbnails and
 * timelines still recognise it) plus a short id-derived suffix to prevent two
 * photos with the same name from overwriting each other.
 */
object ArchivePaths {

    /** Source-relative folder; a NAS adds its configured share root to this. */
    fun relativeFolder(sourceType: SourceType, dateTakenEpochMs: Long): String {
        val label = when (sourceType) {
            SourceType.LOCAL -> "Device"
            SourceType.GOOGLE_DRIVE -> "GoogleDrive"
            SourceType.MEGA -> "MEGA"
            SourceType.ONEDRIVE -> "OneDrive"
            SourceType.TAKEOUT -> "Takeout"
            SourceType.NAS_ARCHIVE -> "NAS"
        }
        if (dateTakenEpochMs <= 0L) return "PhotoHubArchive/$label/Undated"

        val date = Instant.fromEpochMilliseconds(dateTakenEpochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        return "PhotoHubArchive/$label/${date.year}/" +
            "${date.monthNumber.toString().padStart(2, '0')}/" +
            date.dayOfMonth.toString().padStart(2, '0')
    }

    fun fileName(displayName: String, stableId: String): String {
        val safe = safeName(displayName)
        val hash = stableId.hashCode().toUInt().toString(16)
        val dot = safe.lastIndexOf('.')
        return if (dot > 0 && dot < safe.length - 1) {
            "${safe.substring(0, dot)}-$hash${safe.substring(dot)}"
        } else {
            "$safe-$hash"
        }
    }

    fun safeName(name: String): String {
        val cleaned = name.trim().map { c ->
            if (c.isLetterOrDigit() || c in ".-_ ") c else '_'
        }.joinToString("")
        return cleaned.ifBlank { "photo" }.take(120)
    }
}
