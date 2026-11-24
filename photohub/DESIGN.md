# PhotoHub — Design Document (Kotlin Multiplatform)

Working name: **PhotoHub**. Status: design only, no code yet.

**Target platforms:** Android, Windows, macOS, Linux desktop — from a single Kotlin codebase.

> **iOS: out of scope.** Building an iOS app requires macOS + Xcode, and installing one requires an Apple-signed IPA. With a Windows-only setup and no Mac, neither is possible. Kotlin Multiplatform keeps the door open — the shared module is platform-agnostic, so adding an `iosMain` source set and `iosApp/` Xcode project later would not require changing Android or desktop code. Until then, iOS is not a target and no iOS-specific work is planned.

---

## 1. Purpose

A Google-Photos-style timeline app that is the **daily driver for your photos** without the bloat. One date-sorted grid of everything, no feed, no AI tabs, no sharing suggestions — just your photos.

### Daily-use mental model

**Three tiers of freshness, one timeline:**

| Tier | Where it lives | Phone shows it... |
|---|---|---|
| **Fresh** (newest, 0–N days) | DCIM on phone + chosen cloud | instantly, even offline (local copy) |
| **Mid** (N–M days) | Chosen cloud only | when online, cached once viewed |
| **Archived** (older than M) | NAS only (moved by archiver) | only when NAS is on — which is fine because you rarely look at them |

PhotoHub is the phone-side viewer + uploader. The **NAS archiver** (separate app) does the "move old stuff off the cloud to the NAS to stay within Drive's free tier" job on its own schedule; PhotoHub never coordinates with it beyond optionally reading the archive as a read-only source when the NAS happens to be on.

### What the phone app does

- Reads the device's local camera roll (Android MediaStore `DCIM/Camera`, plus any other picked folders).
- Pulls thumbnails + metadata from **one chosen cloud source** (Drive, MEGA, or OneDrive).
- Shows both as a **single unified timeline**, newest first.
- **Auto-uploads new camera photos to the chosen cloud** in the background so a cloud copy always exists (local copy is kept on phone — upload is a copy, not a move).
- Caches thumbnails on-device so the timeline scrolls smoothly offline.
- Lets you tap an older photo to download the original on demand.
- Optionally surfaces the NAS archive as a 4th source when reachable.

### What it explicitly does **not** do

- Does not require an always-on server. NAS being off is normal.
- Does not try to continuously list Google Photos (API doesn't allow it).
- Does not delete your local DCIM photos. You manage phone storage like you always have.
- Does not sync the NAS archive down to the phone. That'd defeat the point.
- Does not add Discover, Memories, face albums, chat, or any other Google Photos cruft.

**Cross-platform:** Android phone/tablet and Windows/macOS/Linux desktop from the same Kotlin codebase. Desktop is mostly for browsing the full library from a laptop; the camera-upload loop is Android-only.

---

## 2. Goals and non-goals

### Goals
- Google-Photos-like thumbnail grid grouped by day and month, sorted by **date taken** (EXIF first, file mtime fallback).
- Unified timeline across local camera roll + one chosen cloud + optional NAS archive, with a small source badge on each item.
- **Auto-upload new camera photos to the chosen cloud** (copy, not move — local stays).
- Multi-select, share, and "download to device" for cloud originals.
- Incremental background sync (Android WorkManager; desktop scheduled executor while the app is open).
- Per-source credentials isolated and encrypted.
- Personal sideload APK (Android); desktop installers (`.exe`, `.deb`/`.rpm`); no Play Store submission complexity in v1.

### Non-goals for v1
- Face recognition, AI labeling, editing, shared albums, public links, Memories, Discover, chat — none of the Google Photos extras.
- Continuous access to existing Google Photos via API (not supported by Google).
- Deleting or pruning local DCIM photos — the user manages phone storage manually.
- Uploading to multiple clouds in parallel (one chosen upload target per phone).
- Writing back to the NAS, multi-user, or a web UI.

---

## 3. Why Kotlin Multiplatform + Compose Multiplatform

| | Kotlin Multiplatform | Flutter (alternative) |
|---|---|---|
| **Shared code** | 90%+ business logic + UI via Compose Multiplatform | 90%+ business logic + UI |
| **Language** | Kotlin (native Android, interop with JVM) | Dart |
| **UI framework** | Compose (Stable on Android/desktop as of May 2025) | Flutter widgets |
| **Platform maturity** | Android/desktop **Stable** | All platforms stable |
| **Native performance** | Native per-platform; JVM on desktop | Near-native; compiled to ARM/x64 |
| **Platform APIs** | Direct access via `expect`/`actual` (Kotlin/JNI) | Via plugins/channels |
| **SQLite** | SQLDelight (type-safe, KMP-native) | `sqflite` / `drift` |
| **Network/HTTP** | Ktor (KMP) or OkHttp | `dio` / `http` |
| **Learning curve** | Kotlin + Compose (similar to Jetpack Compose) | Dart (new language for most) |
| **iOS** | Out of scope — needs Mac + Xcode | Out of scope — same requirement |

**Why KMP:** Kotlin's mature ecosystem for Android, type-safe SQLDelight, direct native interop, and Compose Multiplatform's stability on Android + desktop make it the best fit. You can write `expect` interfaces for platform-specific bits (MediaStore on Android, `java.nio.file` on desktop) and keep 90%+ of the app shared. The architecture stays iOS-ready without paying any cost for a platform you cannot build.

---

## 4. Architecture

```
Compose UI (shared across Android and desktop)
   │  (state / intents)
   ▼
ViewModel ──► Repository ──► Source adapters ──► Remote providers (SMB / WebDAV / REST)
   │              │
   │              ├──► SQLDelight DB (index, sync state, trash)
   │              └──► Local cache (thumbnails, downloaded originals)
   ▼
Background scheduler (expect/actual: WorkManager on Android, JVM ScheduledExecutor on desktop)
```

### Module structure

```
shared/                             # Kotlin Multiplatform shared code
  commonMain/
    kotlin/
      core/
        model/                      # MediaItem, RemoteMedia, MediaSource, SourceConfig, Page
        db/                         # SQLDelight schema + queries
        storage/                    # cache paths, thumbnail naming (expect/actual per platform)
      source/
        local/                      # expect/actual: MediaStore (Android), java.nio.file (desktop)
        smb/                        # SMBJ on JVM — works on both Android and desktop
        webdav/                     # Ktor HTTP + WebDAV protocol (works everywhere)
        rest/                       # Google Drive, OneDrive, Koofr REST adapters (Ktor)
        takeout/                    # Google Takeout zip/folder importer (okio)
      sync/                         # sync pipeline, incremental index, dedupe
      scheduler/                    # expect interface: BackgroundScheduler
      ui/
        timeline/                   # Compose Multiplatform: date grid, sticky headers
        sources/                    # add/configure sources, sync status
        settings/                   # cache limits, sort order, Wi-Fi-only
        detail/                     # photo/video detail viewer, zoom, metadata
  androidMain/
    kotlin/
      storage/                      # Android: app-private cache paths, MediaStore access
      scheduler/                    # Android: WorkManager
      auth/                         # Android: CustomTabs OAuth flow
  desktopMain/
    kotlin/
      storage/                      # Desktop: java.nio.file, XDG cache dirs
      scheduler/                    # Desktop: ScheduledExecutorService
      auth/                         # Desktop: embedded browser or localhost OAuth callback
  build.gradle.kts                  # KMP dependencies

androidApp/
  src/main/
    AndroidManifest.xml
    kotlin/MainActivity.kt          # Android entry point
  build.gradle.kts

# iosApp/ intentionally omitted — iOS build deferred until a Mac or cloud CI is available.
# The shared/ module has no iOS-specific code yet; adding iOS later means adding
# an iosMain source set + iosApp/ Xcode project without touching Android/desktop code.

desktopApp/
  src/jvmMain/kotlin/main.kt        # Desktop JVM entry point
  build.gradle.kts
```

### Dependency injection

Manual DI via `AppContainer` for v1 (avoid Hilt/Koin until complexity justifies it):

```kotlin
class AppContainer(
    val database: Database,
    val sources: List<MediaSource>,
    val repository: MediaRepository,
    val scheduler: BackgroundScheduler
)
```

---

## 5. Data model (SQLDelight)

SQLDelight is the KMP-native SQLite library: write SQL once, get type-safe Kotlin queries on all platforms.

### Schema (`shared/commonMain/sqldelight/photohub.sq`)

```sql
CREATE TABLE Source (
    id TEXT PRIMARY KEY NOT NULL,          -- UUID
    type TEXT NOT NULL,                    -- LOCAL, SMB, WEBDAV, GDRIVE, ONEDRIVE, MEGA, TAKEOUT, NAS_ARCHIVE
    displayName TEXT NOT NULL,
    configJson TEXT NOT NULL,              -- non-secret config; secrets in Keystore
    enabled INTEGER AS Boolean NOT NULL DEFAULT 1,
    lastScannedAt INTEGER,                 -- epoch ms
    cursor TEXT                            -- provider cursor for incremental listing
);

CREATE TABLE MediaItem (
    id TEXT PRIMARY KEY NOT NULL,          -- UUID
    sourceId TEXT NOT NULL,
    sourceKey TEXT NOT NULL,               -- stable ID/path from the provider
    displayName TEXT NOT NULL,
    mimeType TEXT NOT NULL,
    dateTaken INTEGER NOT NULL,            -- epoch ms
    dateSource TEXT NOT NULL,              -- EXIF, FILE_MTIME, PROVIDER
    width INTEGER,
    height INTEGER,
    durationMs INTEGER,
    sizeBytes INTEGER NOT NULL,
    sha256 TEXT,                           -- when known/downloaded
    thumbPath TEXT,                        -- app-private cache file
    originalPath TEXT,                     -- downloaded original, if any
    syncState TEXT NOT NULL,               -- INDEXED, THUMB_CACHED, ORIGINAL_CACHED, REMOTE_ONLY
    isFavorite INTEGER AS Boolean NOT NULL DEFAULT 0,
    trashedAt INTEGER,
    FOREIGN KEY(sourceId) REFERENCES Source(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_media_source_key ON MediaItem(sourceId, sourceKey);
CREATE INDEX idx_media_date_taken ON MediaItem(dateTaken DESC);
CREATE INDEX idx_media_sync_state ON MediaItem(syncState);

CREATE TABLE SyncJob (
    id TEXT PRIMARY KEY NOT NULL,
    sourceId TEXT NOT NULL,
    kind TEXT NOT NULL,                    -- FULL_SCAN, INCREMENTAL, DOWNLOAD
    status TEXT NOT NULL,                  -- RUNNING, OK, ERROR, SKIPPED
    startedAt INTEGER NOT NULL,
    finishedAt INTEGER,
    itemsProcessed INTEGER NOT NULL DEFAULT 0,
    itemsFailed INTEGER NOT NULL DEFAULT 0,
    error TEXT,
    FOREIGN KEY(sourceId) REFERENCES Source(id) ON DELETE CASCADE
);
```

### Queries (type-safe via SQLDelight)

```sql
-- Get all media sorted by date, paginated
selectMediaPage:
SELECT * FROM MediaItem
WHERE trashedAt IS NULL
ORDER BY dateTaken DESC
LIMIT :limit OFFSET :offset;

-- Count total items
countMedia:
SELECT COUNT(*) FROM MediaItem WHERE trashedAt IS NULL;

-- Check if item already indexed
findBySourceKey:
SELECT * FROM MediaItem WHERE sourceId = ? AND sourceKey = ?;

-- Insert or update
upsertMedia:
INSERT INTO MediaItem(id, sourceId, sourceKey, displayName, mimeType, dateTaken, dateSource, width, height, durationMs, sizeBytes, sha256, thumbPath, originalPath, syncState, isFavorite, trashedAt)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(sourceId, sourceKey) DO UPDATE SET
  displayName = excluded.displayName,
  dateTaken = excluded.dateTaken,
  syncState = excluded.syncState,
  thumbPath = excluded.thumbPath;

-- Start sync job
insertSyncJob:
INSERT INTO SyncJob(id, sourceId, kind, status, startedAt, itemsProcessed, itemsFailed)
VALUES (?, ?, ?, 'RUNNING', ?, 0, 0);

-- Finish sync job
finishSyncJob:
UPDATE SyncJob
SET status = ?, finishedAt = ?, itemsProcessed = ?, itemsFailed = ?, error = ?
WHERE id = ?;
```

---

## 6. Source abstraction

```kotlin
enum class SourceType {
    LOCAL, SMB, WEBDAV, GDRIVE, ONEDRIVE, MEGA, TAKEOUT, NAS_ARCHIVE
}

data class RemoteMedia(
    val sourceKey: String,
    val displayName: String,
    val mimeType: String,
    val dateTaken: Long?,         // epoch ms, from provider metadata
    val sizeBytes: Long?,
    val thumbHint: String?        // provider thumbnail URL or remote path
)

data class Page(val items: List<RemoteMedia>, val nextCursor: String?)

interface MediaSource {
    val id: String
    val type: SourceType
    suspend fun listPage(cursor: String?): Result<Page>
    suspend fun fetchThumbnail(item: RemoteMedia, target: File): Result<File>
    suspend fun downloadOriginal(
        item: RemoteMedia,
        target: File,
        onProgress: (Float) -> Unit
    ): Result<File>
    suspend fun delete(item: RemoteMedia): Result<Unit>   // only where provider allows
}
```

### Multiple cloud accounts (implemented)

`Source` is a table of rows and `MediaItem.sourceId` is a foreign key to it, so
N accounts were always representable. The wiring is now too: each connected
account has its own id, credentials namespace and cached client graph. The
Drive account identity is no longer a compile-time constant:

```kotlin
const val SOURCE_ID = "google_drive"                    // legacy row only

// New instances: google_drive_<8 hex>, mega_<8 hex>
```

`SOURCE_ID` is now only the id of the original Drive row, kept so existing
installs do not need a migration. Every other instance gets a generated id, and
all secrets derive their key from it (`<sourceId>.refreshToken`,
`<sourceId>.megaSession`).

Per-account state lives in factories, not Koin singletons:

- `DriveAccountFactory` / `MegaAccountFactory` cache one graph per source id:
  tokens or session, refresh mutex, API and uploader.
- `SourceRegistry` builds a `MediaSource` for a config row; `CurrentUploadTarget`
  resolves the selected cloud at call time and dispatches by source type.
- `AccountsController` exposes a list of source cards, with per-card connect,
  disconnect and "Use for uploads" actions. Adding another account never
  overwrites an existing one.

`isUploadTarget` needs no change: exactly one source may hold it and
`upsertCloudSource(makeUploadTarget = true)` clears it everywhere first, so
camera upload keeps one unambiguous destination no matter how many accounts are
connected. Reading from many accounts while uploading to one is the expected
configuration — e.g. syncing a family member's shared library into the timeline.

`drive.file` scope also keeps the blast radius small: each account exposes only
the files PhotoHub itself created there, so there is no cross-account visibility
to reason about.

### Thumbnail strategy
1. Use provider thumbnail endpoint when available (Drive/OneDrive thumbnails via API).
2. Else fetch the first bytes / full file and decode with Compose image APIs + EXIF.
3. Store in app-private cache named by `sha256(sourceKey)`; evict by LRU under a user-set cache cap.

---

## 7. Source implementations

### MEGA (vendored `kmp-mega`, M4)

`MegaSource` walks the cloud drive plus shared roots and maps image/video nodes
by extension; Trash and Inbox are skipped. The SDK has no thumbnail endpoint, so
`ThumbnailStore.fetchRemote()` asks the source to download and render a small
JPEG into the app cache in 120-item batches, skipping videos above 50 MB.
`MegaUploadTarget` creates `PhotoHub/YYYY/MM` on demand and uploads copies; on
Android a `content://` camera URI is materialised into the app-private cache
first because the SDK requires a filesystem path. Login stores only
`dumpSession()` in the encrypted credential store, and quota comes from
`getQuota()` when the Sources screen opens.

The `kmp-mega` `1.0.0-beta08` source lives in-tree at
`shared/src/commonMain/kotlin/dev/carlsen/mega/` (MIT, see
`THIRD_PARTY_NOTICES.md`) instead of as a binary dependency: its
`PreloginResp.salt` was declared non-null, but MEGA's `us0` reply has no `s`
field for version-1 accounts, which made every such account fail to log in.
The fix is a one-line nullable field plus a null check at the only call site.

### Local media (`expect`/`actual`)

```kotlin
// shared/commonMain
expect class LocalMediaSource(config: SourceConfig) : MediaSource

// androidMain
actual class LocalMediaSource actual constructor(config: SourceConfig) : MediaSource {
    // MediaStore query for Images/Video, plus SAF folder picker for extra folders
    // Date: EXIF DateTimeOriginal → MediaStore DATE_TAKEN → file mtime
}

// desktopMain
actual class LocalMediaSource actual constructor(config: SourceConfig) : MediaSource {
    // Walk directories (java.nio.file), filter by extension
    // Date: EXIF via ExifInterface JVM lib, fallback file mtime
}
```

### SMB/NAS (JVM, works on Android + desktop)

```kotlin
// shared/commonMain
interface SmbMediaSource : MediaSource

// androidMain, desktopMain
class SmbMediaSourceImpl(config: SourceConfig) : SmbMediaSource {
    // SMBJ (Apache-2.0) for share enumeration and streaming
    // Credentials in platform-specific Keystore/EncryptedSharedPreferences
    // Date from SMB attributes, fallback to EXIF after thumb download
}
```

### WebDAV (Ktor, works everywhere)

```kotlin
// shared/commonMain
class WebDavMediaSource(config: SourceConfig) : MediaSource {
    private val client = HttpClient(CIO) { /* Ktor config */ }

    override suspend fun listPage(cursor: String?): Result<Page> {
        // PROPFIND request, parse XML response
        // Date from DAV:getlastmodified or custom property
    }

    override suspend fun fetchThumbnail(item: RemoteMedia, target: File): Result<File> {
        // GET remote path, decode with Compose/Coil
    }

    override suspend fun downloadOriginal(
        item: RemoteMedia,
        target: File,
        onProgress: (Float) -> Unit
    ): Result<File> {
        // GET with progress callback
    }

    override suspend fun delete(item: RemoteMedia): Result<Unit> {
        // DELETE method
    }
}
```

**Covers:** Koofr, pCloud, any QNAP/Synology NAS with WebDAV enabled.

### Google Drive REST (Ktor, works everywhere)

```kotlin
// shared/commonMain
class GoogleDriveSource(config: SourceConfig) : MediaSource {
    private val client = HttpClient(CIO) { /* OAuth bearer token */ }

    override suspend fun listPage(cursor: String?): Result<Page> {
        // GET https://www.googleapis.com/drive/v3/files?pageToken=...
        // Filter: mimeType contains 'image' or 'video'
        // Date from imageMediaMetadata.time or modifiedTime
    }

    // OAuth flow is expect/actual:
    // - Android: CustomTabs
    // - Desktop: system browser + localhost:8080 callback listener
}
```

**Priority 1 source + v1 upload target.** Use scope `drive.file` if you want PhotoHub to only see/touch files it uploaded itself (cleanest; private namespace), or the broader `drive` scope if you also want to list and manage existing Drive files. For "camera roll upload + browse what PhotoHub has put there," `drive.file` is enough and more respectful of the rest of Drive. Use `drive` (full) only if you also want PhotoHub to show other Drive files in the timeline.

**Caveats:**
- Photos must be in **Drive**, not just Google Photos. Google Photos items are not in Drive unless "Sync to Drive" (feature discontinued). Use Takeout import for existing Google Photos.
- **OAuth consent screen must be set to Personal mode (or published)** — in a project created in "Personal" mode, there's no verification, no unverified-app warning, and refresh tokens don't expire after 7 days. If you instead use a generic External/Testing project, refresh tokens expire every 7 days, forcing re-login. Create the OAuth client in **Personal mode** for your own single account.
- Drive API free quota is generous (per-minute units + 1 TB/day egress), far beyond personal use.

### OneDrive REST (Ktor, works everywhere)

```kotlin
// shared/commonMain
class OneDriveSource(config: SourceConfig) : MediaSource {
    private val client = HttpClient(CIO) { /* OAuth bearer */ }

    override suspend fun listPage(cursor: String?): Result<Page> {
        // GET https://graph.microsoft.com/v1.0/me/drive/root/children?$skipToken=...
        // Date from photo.takenDateTime or fileSystemInfo.lastModifiedDateTime
    }

    override suspend fun fetchThumbnail(item: RemoteMedia, target: File): Result<File> {
        // GET /thumbnails/0/medium/content  — OneDrive serves real thumbnails, no full download
    }
}
```

**Priority 3 source.** The easiest of the three: plain REST, well-documented pagination (`@odata.nextLink`), server-side thumbnails, and `photo.takenDateTime` gives a true EXIF-derived capture date without downloading the file. Free tier is only 5 GB, which is why it's third despite being technically simplest.

### MEGA (priority 2 — the hard one)

MEGA has **no public REST API and no JVM-native library**. Its official SDK is C++ with SWIG/JNI bindings, shipped as a prebuilt Android AAR (`nz.mega.sdk`) — not published to Maven Central and not usable from desktop JVM or `commonMain`. This makes MEGA the most expensive source to support despite being priority 2.

Four options, in order of recommendation:

| Option | Works on | Effort | Notes |
|---|---|---|---|
| **A. Pure-Kotlin MEGA client** | Android + desktop (`commonMain`) | High | Reimplement MEGA's documented API: login (`us`/`us0` + AES key derivation), `f` (fetch node tree), decrypt node attributes, `g` (get download URL), AES-CTR decrypt stream. This is what `megajs` does in JS. Full cross-platform, no native deps. |
| **B. rclone subprocess** | Desktop only | Low | `rclone lsjson mega:` / `rclone copyto`. Works immediately on Windows/Linux, impossible on Android. |
| **C. Official MEGA AAR via JNI** | Android only | Medium | Must build the AAR yourself or vendor it; `expect`/`actual` keeps it out of `commonMain`. Diverges Android and desktop implementations. |
| **D. Hybrid: B + C** | Both, two codepaths | Medium-High | Two separate implementations to maintain for one provider. |

**Recommended: A (pure Kotlin), implemented incrementally.**

```kotlin
// shared/commonMain — MEGA protocol client, no native dependencies
class MegaSource(config: SourceConfig) : MediaSource {
    private val client = HttpClient(CIO)
    private lateinit var session: MegaSession   // sid + master key

    // Login: derive key from password (AES-128 legacy or PBKDF2 for v2 accounts),
    // POST to https://g.api.mega.co.nz/cs?id=<seqno>
    suspend fun login(email: String, password: String): Result<MegaSession>

    override suspend fun listPage(cursor: String?): Result<Page> {
        // POST [{"a":"f","c":1}] → full node tree (MEGA has no server-side paging)
        // Decrypt each node's `a` attribute blob with its node key to get the filename
        // Filter to image/video by extension; dateTaken from node `ts` (upload time)
        // or from EXIF after download — MEGA does not expose capture date in metadata
    }

    override suspend fun fetchThumbnail(item: RemoteMedia, target: File): Result<File> {
        // MEGA stores a 120x120 thumbnail as file attribute type 0 — fetch via `ufa`
        // This avoids downloading the original just to build a grid
    }

    override suspend fun downloadOriginal(item: RemoteMedia, target: File, onProgress: (Float) -> Unit): Result<File> {
        // POST [{"a":"g","g":1,"n":<nodeHandle>}] → temp URL, then AES-CTR decrypt while streaming
    }

    override suspend fun delete(item: RemoteMedia): Result<Unit> {
        // POST [{"a":"d","n":<nodeHandle>}]  — moves to Rubbish Bin
    }
}
```

**MEGA-specific risks:**
- **Crypto must be exact.** Node keys are AES-ECB-wrapped; file content is AES-CTR with a MAC. A mistake yields silently corrupted images, so verify against known-good files early.
- **Unofficial protocol.** Not covered by MEGA's terms as a supported integration, and the API can change without notice. Pin behaviour behind tests.
- **No capture date in metadata.** MEGA's `ts` is upload time, not `dateTaken`. For a correct timeline, EXIF must be read — either from the 120px thumbnail (often stripped) or by downloading the original. Practical compromise: show upload time initially, backfill true `dateTaken` when an original is cached.
- **E2EE means no server-side thumbnails for free.** Thumbnails are themselves encrypted file attributes; they must be fetched and decrypted per item.
- **Transfer quota.** Free accounts have a capped rolling download quota; a full library scan can hit it. Throttle and resume.

If option A proves too costly mid-build, fall back to **B for desktop + defer Android MEGA**, rather than blocking the rest of the app.

### Google Photos Takeout import

```kotlin
// shared/commonMain
class TakeoutImporter(archiveZipOrFolder: Path) {
    suspend fun importToDatabase(db: Database, sourceId: String): Result<Int> {
        // Extract ZIP or walk folder (okio multiplatform)
        // For each image/video:
        //   1. Find sidecar JSON (IMG_1234.jpg.json)
        //   2. Parse `photoTakenTime.timestamp` for dateTaken
        //   3. Copy to app-private cache, compute SHA-256
        //   4. Upsert MediaItem with sourceType=TAKEOUT
        //   5. Mark syncState=ORIGINAL_CACHED (since file is local now)
    }
}
```

Reuses logic from `~/gphotos-nas/gphotos_nas/takeout.py` (port to Kotlin).

### Migrating existing Google Photos → Google Drive

Google removed automatic Photos↔Drive sync in **July 2019**; there is no setting that mirrors them. To get existing Google Photos content as browsable files in Drive, pick one:

| Method | Result | Best for |
|---|---|---|
| **Google Takeout** (recommended) | Exports the whole library as `.zip` archives with EXIF + sidecar JSON preserved. Can deliver straight into Drive, or to your computer/NAS. | Bulk export, archiving, feeding PhotoHub's Takeout importer |
| **Download + re-upload** | Select in Photos → Download → upload loose files to a Drive folder | Small sets you want *living* in Drive as browsable images |
| **Don't migrate — import Takeout directly** | PhotoHub's `TakeoutImporter` reads the zips and indexes them with correct `dateTaken`; no Drive round-trip | This project's default path |

Key facts:
- **Storage is shared** across Photos, Drive, and Gmail (15 GB free). Moving Photos → Drive frees no space and can *double* usage until originals are deleted.
- **Takeout preserves capture date/GPS** in sidecar JSON; a plain download can strip or alter EXIF, which is why Takeout is preferred for archiving.
- **"Storage saver" photos stay compressed** — Takeout returns the compressed versions if originals were never kept.

Recommended for this project: **do not manually move Photos into Drive.** Export via Takeout to the NAS, let the importer index them, and keep **Google Drive as a separate live source** for whatever already lives in Drive. The two stay distinct sources in the timeline.

### Replacing Google Photos auto-backup

Native Android has **no built-in way to auto-upload the camera roll to Drive/MEGA/OneDrive** — only Google Photos auto-backs up, and it only goes to Photos. PhotoHub itself is the replacement for that auto-backup, which is why camera→cloud upload is a **core v1 feature** of this app (see §8.5).

User-side one-time setup:
1. **Turn off Google Photos backup** (Photos app → profile → Photos settings → Backup → off) so new shots do not end up in Photos.
2. In PhotoHub, pick one **upload target** (Drive, MEGA, or OneDrive) and the DCIM folders to watch.
3. For photos already in Google Photos, use **Takeout** once to pull them out (see previous section).

After that, PhotoHub's upload pipeline replaces both Google Photos auto-backup and any third-party tool like Autosync for Google Drive. No other app is needed.

### NAS archive source (read-only)

```kotlin
// shared/commonMain
class NasArchiveSource(config: SourceConfig) : MediaSource {
    // If laptop archiver is running, poll its `/status.json` endpoint
    // Or if on same network, read SMB/WebDAV from NAS directly
    // This source is read-only, never writes or deletes
}
```

---

## 8. Sync and indexing pipeline

```kotlin
suspend fun runSyncJob(source: MediaSource, db: Database, cache: CacheManager) {
    val jobId = UUID.randomUUID().toString()
    db.insertSyncJob(jobId, source.id, SyncKind.INCREMENTAL, System.currentTimeMillis())

    try {
        var cursor: String? = db.getSourceCursor(source.id)
        var itemsProcessed = 0
        var itemsFailed = 0

        while (true) {
            val page = source.listPage(cursor).getOrThrow()
            for (item in page.items) {
                try {
                    val existing = db.findBySourceKey(source.id, item.sourceKey)
                    if (existing != null && existing.syncState != SyncState.REMOTE_ONLY) {
                        continue  // already indexed and cached
                    }

                    val mediaItem = MediaItem(
                        id = UUID.randomUUID().toString(),
                        sourceId = source.id,
                        sourceKey = item.sourceKey,
                        displayName = item.displayName,
                        mimeType = item.mimeType,
                        dateTaken = item.dateTaken ?: System.currentTimeMillis(),
                        dateSource = if (item.dateTaken != null) DateSource.PROVIDER else DateSource.FILE_MTIME,
                        sizeBytes = item.sizeBytes ?: 0,
                        syncState = SyncState.INDEXED
                    )
                    db.upsertMedia(mediaItem)
                    itemsProcessed++

                    // Thumbnail fetch queued lazily when UI requests it
                } catch (e: Exception) {
                    itemsFailed++
                }
            }

            cursor = page.nextCursor
            db.updateSourceCursor(source.id, cursor)
            if (cursor == null) break
        }

        db.finishSyncJob(jobId, JobStatus.OK, System.currentTimeMillis(), itemsProcessed, itemsFailed, null)
    } catch (e: Exception) {
        db.finishSyncJob(jobId, JobStatus.ERROR, System.currentTimeMillis(), 0, 0, e.message)
    }
}
```

### Background scheduling (`expect`/`actual`)

```kotlin
// shared/commonMain
expect class BackgroundScheduler {
    fun scheduleDailySync()
    fun cancelAll()
}

// androidMain
actual class BackgroundScheduler {
    actual fun scheduleDailySync() {
        // WorkManager PeriodicWorkRequest, Wi-Fi only, charging optional
    }
}

// desktopMain
actual class BackgroundScheduler {
    actual fun scheduleDailySync() {
        // ScheduledExecutorService.scheduleAtFixedRate while the app is running
        // Or an external systemd timer / Windows Task Scheduler entry
    }
}
```

---

## 8.5. Camera-upload pipeline (Android only, core feature)

PhotoHub replaces Google Photos' auto-backup. When a new photo lands in a watched local folder (default `DCIM/Camera`), PhotoHub uploads it to the user's chosen cloud **without touching the original on-device**.

### Capability extension to `MediaSource`

Most sources are read-only. One source per account can be marked as the **upload target**:

```kotlin
interface MediaSource {
    // ...existing read methods...
    val canUpload: Boolean get() = false
    suspend fun upload(
        local: File,
        remoteFolder: String,
        onProgress: (Float) -> Unit
    ): Result<RemoteMedia> =
        Result.failure(UnsupportedOperationException("Source does not support upload"))
}
```

Overridden `true` for `GoogleDriveSource`, `MegaSource`, `OneDriveSource`. Local, SMB, WebDAV (read-only mode), Takeout, and NAS archive all leave `canUpload = false`.

### The pipeline (Android)

```
MediaStore ContentObserver on DCIM/Camera (or SAF folder)
   │  (fires on every new file)
   ▼
UploadQueue (SQLDelight table, persistent — survives reboot)
   │  (one row per local file + target cloud + state)
   ▼
UploadWorker (WorkManager, UniquePeriodicWork + ExpeditedWork on new rows)
   │  (constraints: Wi-Fi-only [configurable], not low battery, not metered)
   ▼
Chosen MediaSource.upload(file, remoteFolder = "PhotoHub/YYYY/MM")
   │
   ▼
On success:
   • Insert/upsert MediaItem with sourceId = chosen cloud, syncState = ORIGINAL_ON_REMOTE + LOCAL_COPY_PRESENT
   • Mark UploadQueue row DONE
   • DO NOT delete the local DCIM file (upload is a copy, never a move)
On failure:
   • Exponential backoff, keep in queue
   • After N retries → mark FAILED, surface in UI
```

### `UploadQueue` schema (new SQLDelight table)

```sql
CREATE TABLE UploadQueue (
    id TEXT PRIMARY KEY NOT NULL,
    localPath TEXT NOT NULL,                -- file:// or content:// URI
    sha256 TEXT,                            -- computed before upload for dedupe
    sizeBytes INTEGER NOT NULL,
    targetSourceId TEXT NOT NULL,           -- which cloud account to upload to
    targetRemotePath TEXT,                  -- destination path/folder in that cloud
    state TEXT NOT NULL,                    -- QUEUED, RUNNING, DONE, FAILED, SKIPPED_DUPLICATE
    attempts INTEGER NOT NULL DEFAULT 0,
    lastError TEXT,
    queuedAt INTEGER NOT NULL,
    finishedAt INTEGER,
    FOREIGN KEY(targetSourceId) REFERENCES Source(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_upload_sha ON UploadQueue(sha256, targetSourceId);
```

The unique index on `(sha256, targetSourceId)` guarantees the same file never uploads twice to the same cloud, even if `DCIM/` rescans or the user re-enables the folder.

### Settings exposed to the user

| Setting | Default |
|---|---|
| Upload target cloud | None (user picks Drive / MEGA / OneDrive during setup) |
| Watched local folders | `DCIM/Camera` + screenshots; user can add more |
| Remote folder path | `PhotoHub/YYYY/MM/` by default |
| Wi-Fi only | On |
| Charging only | Off |
| Include videos | On |
| Include screenshots | Off by default |
| Pause uploads | Manual toggle |

### Behaviour rules

- **Upload is always a copy.** Local DCIM files are never deleted by PhotoHub. Phone storage management stays the user's job.
- **Dedupe before upload.** SHA-256 is computed locally; if the hash is already in `UploadQueue` as DONE for the same target, skip.
- **Resumability.** `UploadQueue` persists across reboots and app kills. WorkManager picks up where it left off.
- **One active upload worker** at a time by default, to avoid saturating cellular/Wi-Fi.
- **Failures don't block the timeline.** A failed upload stays in the queue with an error badge but doesn't affect browsing.
- **No cross-cloud upload.** Exactly one upload target per phone. Changing it later re-uploads nothing — only *new* photos follow the new target.

### Why this is a core v1 feature, not v2

The original design marked "uploading new photos from the app to clouds" as a non-goal. That was wrong for this product: without camera-upload, the user has to keep running Autosync for Google Drive (or similar) in parallel, which defeats the "one clean app" goal. The upload loop is the thing that makes PhotoHub a real replacement for Google Photos' auto-backup.

### Platform limits

- **Android only.** iOS has analogous APIs (`PHPhotoLibraryObserver`, `BGTaskScheduler`) but iOS is out of scope.
- **Desktop.** Desktop doesn't have a "camera roll" to watch; the desktop app is read-only for cloud sources and won't participate in upload.
- **Android 13+** needs `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` permissions. Android 14+ supports **partial media access**, which PhotoHub should handle gracefully (fall back to SAF folder picker).

### What happens when a cloud fills up

Nothing automatic. PhotoHub does not prune the cloud itself — that's the **NAS archiver's job**, run on its own schedule when the NAS is on. The archiver pulls old photos off the cloud and into NAS storage, which naturally keeps the user under their free-tier limit. PhotoHub just sees the cloud "forget" old items and the NAS archive "remember" them when it's online.

---

## 9. UI (Compose Multiplatform)

All UI is shared across Android and desktop.

### Timeline screen

```kotlin
@Composable
fun TimelineScreen(viewModel: TimelineViewModel) {
    val items by viewModel.mediaItems.collectAsState()
    val dateGroups = items.groupBy { it.dateTaken.toLocalDate() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        dateGroups.forEach { (date, group) ->
            stickyHeader {
                Text(
                    text = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp)
                )
            }
            items(group, key = { it.id }) { item ->
                MediaItemCell(item, onLongPress = { viewModel.startSelection(item) })
            }
        }
    }
}

@Composable
fun MediaItemCell(item: MediaItem, onLongPress: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onLongClick = onLongPress) { /* detail */ }
    ) {
        AsyncImage(
            model = item.thumbPath ?: item.sourceKey,  // Kamel/Coil fetcher
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Source badge (small colored dot or icon in corner)
        SourceBadge(item.sourceId)
    }
}
```

### Detail screen

```kotlin
@Composable
fun DetailScreen(itemId: String, viewModel: DetailViewModel) {
    val item by viewModel.getItem(itemId).collectAsState()

    Column(Modifier.fillMaxSize()) {
        ZoomableImage(item.originalPath ?: item.thumbPath)  // Coil/Kamel + gesture zoom
        Spacer(Modifier.height(16.dp))
        Text("${item.displayName} • ${item.dateTaken.format()}")
        Text("Source: ${item.sourceId} • ${item.sizeBytes.formatFileSize()}")
        if (item.sha256 != null) Text("SHA-256: ${item.sha256}")

        Button(onClick = { viewModel.downloadOriginal(item) }) {
            Text(if (item.originalPath != null) "Cached" else "Download")
        }
    }
}
```

### Sources screen

```kotlin
@Composable
fun SourcesScreen(viewModel: SourcesViewModel) {
    val sources by viewModel.sources.collectAsState()

    LazyColumn {
        items(sources, key = { it.id }) { source ->
            SourceCard(
                source = source,
                onToggle = { viewModel.toggleSource(source.id) },
                onSync = { viewModel.syncNow(source.id) },
                onRemove = { viewModel.removeSource(source.id) }
            )
        }
        item {
            Button(onClick = { viewModel.showAddSourceDialog() }) {
                Text("Add Source")
            }
        }
    }
}
```

### Add source dialog (platform OAuth flows)

```kotlin
@Composable
fun AddSourceDialog(onDismiss: () -> Unit, onAdd: (SourceConfig) -> Unit) {
    // Show source type picker: Local, SMB, WebDAV, Google Drive, OneDrive, Takeout Import
    // For OAuth sources (Drive, OneDrive), call expect function:
    when (sourceType) {
        SourceType.GDRIVE -> {
            Button(onClick = { launchOAuthFlow(provider = "google", onSuccess = { token -> ... }) }) {
                Text("Sign in with Google")
            }
        }
    }
}

// expect/actual OAuth launcher
expect fun launchOAuthFlow(provider: String, onSuccess: (String) -> Unit)

// androidMain: CustomTabs
// desktopMain: system browser + http://localhost:8080 callback listener
```

### Settings screen

```kotlin
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val cacheLimit by viewModel.cacheLimit.collectAsState()
    val wifiOnly by viewModel.wifiOnly.collectAsState()

    Column(Modifier.padding(16.dp)) {
        Text("Cache limit: ${cacheLimit}MB")
        Slider(value = cacheLimit.toFloat(), onValueChange = { viewModel.setCacheLimit(it.toInt()) }, valueRange = 100f..5000f)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sync on Wi-Fi only")
            Spacer(Modifier.width(8.dp))
            Switch(checked = wifiOnly, onCheckedChange = { viewModel.setWifiOnly(it) })
        }

        Button(onClick = { viewModel.clearCache() }) {
            Text("Clear cache")
        }
    }
}
```

---

## 10. Image loading (Kamel or Coil)

**Kamel** (`io.kamel-image:kamel-image`) is KMP-native and works on all platforms. Coil 3 also has KMP support.

```kotlin
// Custom Kamel fetcher for MediaItem
val photohubFetcher = Fetcher<MediaItem> { item ->
    when {
        item.originalPath != null -> FileResource(item.originalPath)
        item.thumbPath != null -> FileResource(item.thumbPath)
        else -> {
            val source = getSourceById(item.sourceId)
            val tmpFile = cache.getTempFile(item.id)
            source.fetchThumbnail(item, tmpFile).getOrThrow()
            FileResource(tmpFile.path)
        }
    }
}

KamelConfig {
    fetcher(photohubFetcher)
}
```

---

## 11. Offline behavior

- SQLDelight is the source of truth for browsing; no network call needed to render the timeline.
- Cached thumbnails render offline; uncached items show a placeholder and retry when reachable.
- Originals are only downloaded on demand or by an explicit "cache for offline" rule.
- NAS offline ≠ app offline. The app still shows everything already indexed.

---

## 12. Delete / move semantics

- **Camera upload is always a copy, never a move.** PhotoHub never deletes the local DCIM file after uploading — that's the whole point of §8.5. The user manages phone storage manually.
- **PhotoHub itself never deletes from a cloud source.** Only the separate NAS archiver (a different app, different schedule) deletes from cloud after verified archive-to-NAS. PhotoHub only reads, uploads, and downloads.
- "Download to device" for an older cloud/NAS item: download original → cache locally. No delete involved.
- If PhotoHub later adds a manual "remove from cloud" action (out of scope for v1), it would follow: verify SHA-256 of local copy → `source.delete()` → abort on verification failure → app-local trash table with an undo window.
- Google Photos: never automated delete; show the bulk-selection guide (this app can't talk to Google Photos directly at all, only Drive/Takeout).

---

## 13. Security and privacy

- No analytics, telemetry, or ad SDKs.
- Network access only to configured providers.
- Secrets in platform-specific Keystore: Android `EncryptedSharedPreferences`, desktop JVM Keystore.
- App-private cache directory; no world-readable files.
- Optional biometric unlock (Android `BiometricPrompt`, desktop skip or OS-level).
- License: GPL-3.0 for the app.

---

## 14. Build and tooling

### Requirements
- **Kotlin 2.4+** (latest stable)
- **Compose Multiplatform 1.13+** (latest stable for Android/desktop)
- **JDK 17**
- **Android Studio** (latest stable) or IntelliJ IDEA with KMP plugin — runs on Windows
- **Gradle 8.5+**
- No macOS/Xcode needed for this scope (iOS deferred)

### Dependencies

```kotlin
// shared/build.gradle.kts
kotlin {
    androidTarget()
    jvm("desktop")
    // iOS targets intentionally omitted — add iosX64()/iosArm64()/iosSimulatorArm64() if a Mac becomes available

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation("app.cash.sqldelight:runtime:2.0.2")
                implementation("io.ktor:ktor-client-core:2.3.7")
                implementation("io.ktor:ktor-client-cio:2.3.7")
                implementation("io.kamel-image:kamel-image:0.9.5")
                implementation("com.squareup.okio:okio:3.7.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.work:work-runtime-ktx:2.9.0")
                implementation("androidx.security:security-crypto:1.1.0-alpha06")
                implementation("io.coil-kt:coil-compose:2.5.0")
                implementation("com.hierynomus:smbj:0.11.5")  // JVM only, works on Android
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("com.hierynomus:smbj:0.11.5")  // JVM
            }
        }
    }
}

sqldelight {
    databases {
        create("PhotoHubDatabase") {
            packageName.set("com.example.photohub.db")
        }
    }
}
```

### Platform builds

```sh
# Android APK (sideload via USB or file manager)
./gradlew assembleDebug   # androidApp/build/outputs/apk/debug/app-debug.apk

# Desktop JVM (runs natively on Windows — no Mac needed)
./gradlew runDesktop      # or packageDistributionForCurrentOS
# Windows: creates .exe installer
# Linux: creates .deb/.rpm
# macOS: creates .dmg (only if built on a Mac)
```

Both Android and desktop builds run entirely on Windows via Android Studio / IntelliJ IDEA with the Kotlin Multiplatform plugin. No Mac, Xcode, or paid developer account is required for either target.

### Current environment constraints
This machine (`/home/judchiu`) has **no Java and no Android SDK**. Source can be authored here, but APK/desktop builds must happen in Android Studio (e.g. on the Windows machine).

---

## 15. Milestones

Source priority: **Google Drive → MEGA → OneDrive** (Koofr/pCloud/WebDAV and Takeout are later add-ons).

The camera-upload loop lands with the first cloud (Drive) so the app is actually useful at the end of M3 — a minimal version of the product is running.

| Milestone | Scope | Exit criteria |
|---|---|---|
| **M1** *(scaffolded — needs a build in Android Studio to verify)* | Shared KMP project scaffold, SQLDelight schema (`Source`, `MediaItem`, `UploadQueue`), `MediaSource` interface, `expect`/`actual` platform layer, Koin DI, Android + desktop apps with empty-timeline UI | Can run on Android emulator and desktop JVM with empty timeline |
| **M2** | Local source (`expect`/`actual`): MediaStore on Android, directory walk on desktop. Timeline grid + thumbnail caching (Kamel), multi-select, detail view | Compose Multiplatform timeline renders device photos on both Android and desktop |
| **M3 — Google Drive + upload loop** | Drive REST source (Ktor + OAuth `drive` scope for read+write, Personal-mode consent), pagination, server-side thumbnails, download originals; **camera-upload pipeline**: ContentObserver on DCIM, `UploadQueue` SQLDelight table, WorkManager upload worker with SHA-256 dedupe; background sync on Android | Can sign in to Drive, see unified timeline of DCIM + Drive, and **new camera photos auto-upload to Drive while local stays** |
| **M4 — MEGA** | Pure-Kotlin MEGA client (login + key derivation, fetch node tree, decrypt attributes, thumbnail attribute fetch, AES-CTR download, delete, upload). Desktop rclone fallback if needed | Can sign in to MEGA on Android + desktop, browse and download; upload target can be switched to MEGA |
| **M5 — OneDrive** | OneDrive REST source (Graph API + OAuth), `@odata.nextLink` paging, server-side thumbnails, `takenDateTime` dates, upload | Can add OneDrive, browse in unified timeline, upload target can be switched to OneDrive |
| **M6 — Multi-account + polish** | **Multiple instances of any cloud source** (per-instance source ids, per-account factories, account labels from the provider — see §6 "Multiple cloud accounts"); Google Takeout import (okio ZIP), NAS archive source (read-only), optional WebDAV/Koofr bonus source, cache management, sideload APK + desktop installers | Two Google accounts (e.g. own + a family member's shared library) both appear in one timeline while upload still goes to exactly one; daily-use quality; can browse 10k+ photos smoothly |

---

## 16. Risks and open decisions

### Technical risks
- **MEGA SDK**: no clean KMP binding. Recommend **defer MEGA** to a desktop-only rclone subprocess, or skip entirely if Koofr + pCloud + Drive + OneDrive cover your needs.
- **OAuth on desktop**: system browser + localhost callback needs testing on Windows and Linux separately.
- **Desktop background sync**: no OS-level scheduler equivalent to WorkManager. Sync only runs while the app is open, unless an external Task Scheduler / systemd timer is configured.
- **10k+ photo performance**: LazyGrid pagination + thumbnail caching + SQLDelight query tuning should handle it; profile early.
- **SMBJ on Android**: works via JVM, but newer Android versions restrict cleartext/legacy SMB1 — require SMB2/3 on the NAS.

### Open decisions
- **App identity**: package name, icon, final app name not chosen. Placeholder: `com.example.photohub`.
- **NAS archive integration**: should the app read the NAS archive over SMB, or poll the archiver's JSON endpoint?
- **Google Photos strategy**: confirm Takeout import flow with real data before M6.
- **Cloud source priority**: confirmed — Google Drive, then MEGA, then OneDrive.
- **MEGA implementation**: commit to pure-Kotlin client (option A) vs desktop-only rclone fallback (option B) before starting M4.
- **iOS later**: if a Mac or Codemagic + Apple Developer Program ($99/year) is ever added, iOS can be reintroduced without reworking shared code.

---

## 17. Next step

Review this design. Then:

1. **Platforms confirmed**: Android + Windows/Linux/macOS desktop. iOS is out of scope for now.
2. **Confirm sources**: which clouds do you actually use? (Koofr, Drive, OneDrive, MEGA, pCloud?) Priority order?
3. **Scaffold decision**: scaffold KMP project here (no builds), or wait until you have Android Studio set up?
4. **Archiver integration**: clarify how phone app reads NAS archive — SMB direct, or poll archiver's `/status.json`?

Once confirmed, I can either:
- Scaffold the KMP project structure (Gradle config, SQLDelight schema, expect interfaces, stub Compose UI) for you to build in Android Studio, or
- Write a detailed setup guide for Android Studio + KMP plugin + first build.
