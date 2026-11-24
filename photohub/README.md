# PhotoHub

An offline-first photo timeline across your local camera roll and one chosen cloud
(Google Drive, MEGA, or OneDrive), with an optional NAS archive as a read-only
extra source. Google Photos without the bloat.

See [DESIGN.md](DESIGN.md) for the full design and
[ARCHIVER-DESIGN.md](ARCHIVER-DESIGN.md) for the separate NAS archiver.

## Project layout

```
photohub/
├── gradle/libs.versions.toml   Version catalog (single source of truth for deps)
├── settings.gradle.kts         Module registry
├── shared/                     Kotlin Multiplatform shared module
│   └── src/
│       ├── commonMain/
│       │   ├── kotlin/dev/photohub/shared/
│       │   │   ├── model/      Domain types (RemoteMedia, SourceType, SyncState)
│       │   │   ├── source/     MediaSource abstraction (read + optional upload)
│       │   │   ├── data/       MediaRepository — the timeline's only data entry point
│       │   │   ├── platform/   expect declarations (DB driver, local source, scheduler)
│       │   │   ├── di/         Koin modules
│       │   │   └── ui/         Compose Multiplatform UI (App, TimelineScreen, MediaCell)
│       │   └── sqldelight/     SQL schema: Source, MediaItem, UploadQueue
│       ├── androidMain/        actual impls: AndroidSqliteDriver, WorkManager, MediaStore
│       ├── desktopMain/        actual impls: JDBC SQLite, scheduled executor, file walk
│       └── desktopTest/        Unit tests (run on JVM)
├── android/                    Android application module (sideload APK)
└── desktop/                    Desktop application module (.msi/.deb/.dmg)
```

## Architecture in one paragraph

Everything the UI shows comes from the local SQLite index via `MediaRepository` —
never directly from a source. That is what makes the timeline work offline. Each
source (local, Drive, MEGA, OneDrive, Takeout, NAS) implements `MediaSource` and
is only contacted when a sync or an on-demand fetch is requested. Exactly one
source can be the camera-upload target; new photos in `DCIM/Camera` are queued in
`UploadQueue` and uploaded by a WorkManager worker as a **copy** — the local file
is never deleted.

## Building

**This machine has no JDK or Android SDK.** Source authoring happens here; builds
run in Android Studio on Windows.

Requirements:
- JDK 21
- Android Studio (Otter or newer) with Android SDK 36
- Android Gradle Plugin 9.4 support

### Desktop

```bash
./gradlew :desktop:run                      # run the app
./gradlew :desktop:packageDistributionForCurrentOS   # build installer
```

### Android

```bash
./gradlew :android:assembleDebug            # debug APK
./gradlew :android:assembleRelease          # sideload APK (needs signing config)
```

APK output: `android/build/outputs/apk/`

### Tests

```bash
./gradlew :shared:desktopTest
```

### If the first build fails

This scaffold was authored without a local JDK/Android SDK, so the first build in
Android Studio is the real verification step. The two most likely failure points,
with fixes:

**1. `com.android.kotlin.multiplatform.library` plugin not found / incompatible**

`shared/build.gradle.kts` uses AGP 9's new KMP Android target. If your AGP is
older than 9.0, switch to the classic form:

```kotlin
// shared/build.gradle.kts
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("com.android.library")          // instead of androidKotlinMultiplatformLibrary
    // ...
}

kotlin {
    androidTarget()                    // instead of the androidLibrary { } block
    jvm("desktop")
}

android {                              // separate top-level block
    namespace = "dev.photohub.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
}
```
You will also need an `androidLibrary` alias in `libs.versions.toml`:
`androidLibrary = { id = "com.android.library", version.ref = "agp" }`

**2. Dependency versions have moved on**

Everything is pinned in `gradle/libs.versions.toml`. If a version is not
resolvable, bump it there — nothing else references versions directly. The
pinned set assumes Kotlin 2.4.x, Compose Multiplatform 1.12.x, AGP 9.4.

**3. Android shows an empty timeline (`DatabaseUtils: Invalid token LIMIT`)**

Already fixed, but worth knowing. MediaStore validates the `sortOrder` string
against a strict SQL grammar on Android 8.1+, so it must be plain column names:
no `COALESCE(...)`, and no trailing `LIMIT`/`OFFSET`. Paging has to go through
`ContentResolver.QUERY_ARG_LIMIT`/`QUERY_ARG_OFFSET` in a `Bundle` instead —
see `queryPaged` in `MediaStoreSource.kt`.

The failure is easy to miss: the query throws per page, `MediaIndexer` reports
a failed `Result`, and the UI just looks like a library with no photos. Check
`adb logcat -d | grep DatabaseUtils`.

**4. SQLDelight generated code missing**

`MediaItem`, `Source`, `PhotoHubDatabase` etc. are generated from the `.sq` files.
Run `./gradlew :shared:generateCommonMainPhotoHubDatabaseInterface` once, or just
build — IDE red squiggles before the first build are expected.

## Connecting Google Drive

The Google OAuth **client ID is not compiled in** — it identifies one
installation to Google, and hardcoding it would mean shipping the author's
credentials. Supply your own:

1. In the [Google Cloud console](https://console.cloud.google.com/), create a
   project and enable the **Google Drive API**.
2. Open **Google Auth Platform** in the left nav — a top-level section, *not*
   under "APIs & Services" any more. (The old single "OAuth consent screen" page
   is now split across its Branding / Audience / Data access / Clients tabs. On
   a fresh project you get a **GET STARTED** button instead of the tabs; fill in
   app name, support email, **External**, and a contact email.)
3. Under *Google Auth Platform → Audience*
   ([link](https://console.cloud.google.com/auth/audience)), leave the
   publishing status as **Testing** and add your own Google account under **Test
   users**. Sign-in fails until you do. (A personal account cannot use
   *Internal* — that needs Workspace.)
4. Under *Google Auth Platform → Clients*
   ([link](https://console.cloud.google.com/auth/clients)) — formerly *APIs &
   Services → Credentials* — create **two separate OAuth client IDs**. They are
   not interchangeable: each client type permits only its own redirect style, so
   using the wrong one fails with `Error 400: invalid_request` *before* the
   consent screen even appears.

   | Client type | Used by | Redirect it allows |
   |---|---|---|
   | **Desktop app** | desktop build | `http://127.0.0.1:<ephemeral port>` |
   | **Android** | the APK | `com.googleusercontent.apps.<id>:/oauth2redirect` |

   To tell which one you downloaded, look inside the JSON: a **Desktop app**
   client has a `client_secret` field, an **Android** client does not. Both use
   the same `"installed"` wrapper, so the wrapper tells you nothing.

   - **Desktop app** for the desktop build. No extra input needed.
   - **Android** for the phone build — after creating it, open the client and
     under **Advanced Settings** enable **"Enable custom URI scheme"**, then
     save. Google disables this by default and the console warns against it,
     but without it every sign-in attempt is refused with *"Access blocked …
     Custom URI scheme is not enabled for your Android client"*. Changes take a
     few minutes to propagate. Needs package name `dev.photohub.android` plus
     the debug keystore's SHA-1 fingerprint:

     ```bash
     keytool -list -v -storepass android -alias androiddebugkey \
       -keystore ~/.android/debug.keystore
     ```

     If `XDG_CONFIG_HOME` is set, the Android tools put the keystore in
     `$XDG_CONFIG_HOME/.android/debug.keystore` instead — look there if the
     path above does not exist. To read the fingerprint off a built APK
     directly: `apksigner verify --print-certs android-debug.apk`.

     The debug keystore is generated on first build and is **not** in version
     control; delete it and the regenerated one has a different SHA-1, which
     breaks this OAuth client until you update the console. Back it up.
5. Write the **Desktop app** client ID where PhotoHub looks for it. Note the
   flat `clientId` key — this is *not* the file Google gives you to download, so
   copy the id across rather than saving Google's JSON as-is:

```jsonc
// Linux:   ~/.local/share/PhotoHub/google-oauth.json
// Windows: %APPDATA%\PhotoHub\google-oauth.json
// macOS:   ~/Library/Application Support/PhotoHub/google-oauth.json
{ "clientId": "123456789-abcdef.apps.googleusercontent.com" }
```

   Keep Google's downloaded `client_secret_*.json` **outside** the repo — the
   filename alone trips GitHub's secret scanning.

Or export `PHOTOHUB_GOOGLE_CLIENT_ID` for a one-off run.

Notes on the choices here:

- **Scope is `drive.file` only.** PhotoHub can see just the files it created
  itself, which is all the `PhotoHub/YYYY/MM/` upload design needs, and it keeps
  the project clear of Google's restricted-scope review.
- **PKCE, no client secret.** Installed apps cannot keep a secret, so the flow
  uses PKCE (RFC 7636) and a loopback redirect on desktop / a reverse-DNS
  custom scheme on Android.
- **Tokens are never stored in the database.** They go to
  EncryptedSharedPreferences (Android, hardware keystore) or an AES-GCM file
  (desktop). `Source.configJson` stays non-secret.
- **In Testing mode Google expires refresh tokens after 7 days**, so you will
  re-link weekly until the consent screen is published.

### Sign-in troubleshooting

Both failures below happen *before* any consent screen appears, so they are
configuration, not code.

| Symptom | Cause | Fix |
|---|---|---|
| `Error 400: invalid_request` on desktop, no consent screen | A **Desktop app** client is required; an Android client rejects the `http://127.0.0.1:<port>` loopback redirect | Create a Desktop app client and put *its* id in `google-oauth.json` |
| **Access blocked** → *"Custom URI scheme is not enabled for your Android client"* | Google disables custom URI schemes on new Android clients by default | Client → **Advanced Settings** → enable **custom URI scheme**, save, wait a few minutes |
| Consent succeeds, then "could not exchange the code" | Desktop clients must send `client_secret` on token exchange **even with PKCE** | Add `"clientSecret"` to `google-oauth.json` |
| Browser opens, app waits forever (Android) | Missing redirect `intent-filter`, or the activity is not `singleTop` | Both are in `AndroidManifest.xml`; the scheme must match the Android client id |

The Android redirect scheme is declared literally in `AndroidManifest.xml`
because a manifest cannot read runtime configuration. Changing the Android OAuth
client means editing that scheme to match.

## Milestone status

| Milestone | Scope | Status |
|---|---|---|
| **M1** | KMP scaffold, SQLDelight schema, `expect`/`actual` stubs, both apps compile with empty timeline | **done — verified on device** |
| **M2** | Local source: MediaStore (Android) + directory walk (desktop), date-grouped grid, thumbnails | **done — 1,212 items indexed on a Galaxy S21+, 22 on desktop** |
| **M3** | Google Drive source + camera-upload pipeline | **desktop verified end-to-end: real sign-in, upload, and index against a live Drive account. Phone sign-in pending a console toggle** |
| **M4** | MEGA source | not started — approach decided, see "MEGA decision" |
| **M5** | OneDrive source | not started |
| **M6** | Takeout import, NAS archive source, installers, polish | not started |

## MEGA decision

The original choice was "write the MEGA protocol client by hand" vs. "shell out
to rclone on desktop only". A third option turned out to exist and win outright:
[`kmp-mega`](https://github.com/acarlsen/kmp-mega) (`dev.carlsen.mega:mega`,
MIT) — a Kotlin Multiplatform reimplementation of `go-mega`.

Verified by actually adding it to this project, not from the README:

- **Resolves and compiles** against our exact Kotlin 2.4.21 / AGP 9.4.0 toolchain.
- **Both targets build**: `:shared:compileKotlinDesktop` and
  `:android:assembleDebug` succeeded, and `dev.carlsen.mega.*` classes were
  confirmed present in the APK's dex.
- **No dependency conflicts.** It pulls Ktor, coroutines, kotlinx-serialization
  and okio — all libraries PhotoHub already uses, so they dedupe. The debug APK
  went *down* slightly (17.57 → 17.25 MB); the genuinely new weight is the
  crypto stack (`cryptography-provider-jdk`, `bignum`), ~700 KB.
- **Covers the whole feature set PhotoHub needs**: login (incl. 2FA), quota,
  list, download, upload, `createDir`, move/rename/delete.
- **`dumpSession()` / `fastLogin()` means no stored password.** This was the
  deciding detail: PhotoHub persists an opaque session token in the encrypted
  credential store rather than the user's MEGA password. MEGA has no OAuth, so
  the alternative would have been keeping a reusable password on disk.

Why not the alternatives:

- **Hand-rolled client** — MEGA's protocol is end-to-end encrypted, so this
  means implementing its key derivation and AES/CCM file encryption correctly.
  Getting it subtly wrong corrupts uploads in ways only visible much later.
- **rclone subprocess** — desktop-only by nature, and the phone is the daily
  driver, so camera upload (the point of M1/M3) could never target MEGA.

Risks accepted, with mitigation:

- **Beta, 7 stars, one maintainer.** Active (releases through Nov 2025, commits
  into Oct 2026) but not something to depend on blindly. MEGA sits behind the
  existing `MediaSource` + `UploadTarget` interfaces, so it is swappable — the
  upload queue, dedupe, retry and foldering logic are already source-agnostic
  and shared with Drive.
- Shared folders and setting a remote modified time are unimplemented upstream.
  Neither is needed: PhotoHub uploads to its own `PhotoHub/YYYY/MM/` tree and
  keeps capture time in its own database.
- The README advertises `1.0.0-beta09` but Maven Central only has **beta08**;
  pin beta08.

## Open decisions

- ~~**MEGA implementation**~~ — **decided: use the `kmp-mega` library**
  (`dev.carlsen.mega:mega`, MIT). See "MEGA decision" below.
- **Drive OAuth scope:** `drive.file` (only PhotoHub's own uploads — matches the
  dedicated `PhotoHub/YYYY/MM/` folder plan) vs. full `drive`.
