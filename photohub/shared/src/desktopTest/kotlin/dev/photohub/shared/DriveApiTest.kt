package dev.photohub.shared

import dev.photohub.shared.auth.CredentialStore
import dev.photohub.shared.auth.GoogleOAuthClient
import dev.photohub.shared.auth.GoogleOAuthConfig
import dev.photohub.shared.auth.OAuthTokenStore
import dev.photohub.shared.auth.OAuthTokens
import dev.photohub.shared.auth.TokenProvider
import dev.photohub.shared.model.SourceConfig
import dev.photohub.shared.model.SourceType
import dev.photohub.shared.source.drive.DriveApi
import dev.photohub.shared.source.drive.DriveApiException
import dev.photohub.shared.source.drive.DriveSourceConfig
import dev.photohub.shared.source.drive.GoogleDriveSource
import dev.photohub.shared.source.drive.parseExifDateTime
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Captures requests so assertions can be made about what was actually sent. */
private class RecordingEngine(
    private val handler: (HttpRequestData) -> Pair<HttpStatusCode, String>,
) {
    val requests = mutableListOf<HttpRequestData>()

    fun client(): HttpClient = HttpClient(
        MockEngine { request ->
            requests += request
            val (status, body) = handler(request)
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )
}

private fun tokenProvider(): TokenProvider {
    val store = object : CredentialStore {
        val v = mutableMapOf<String, String>()
        override fun get(key: String) = v[key]
        override fun put(key: String, value: String) { v[key] = value }
        override fun remove(key: String) { v.remove(key) }
        override fun removeAll(keyPrefix: String) {
            v.keys.filter { it.startsWith(keyPrefix) }.forEach(v::remove)
        }
    }
    val tokens = OAuthTokenStore(store, "google_drive")
    tokens.save(OAuthTokens("test-access-token", "rt", expiresAtEpochMs = Long.MAX_VALUE))
    return TokenProvider(
        GoogleOAuthClient(HttpClient(MockEngine { respond("{}") }), GoogleOAuthConfig("cid"), { 0L }),
        tokens,
        now = { 0L },
    )
}

class DriveApiTest {

    @Test
    fun listMediaSendsAuthAndFiltersToMediaTypes() = runTest {
        val body = """
            {"files":[{"id":"f1","name":"a.jpg","mimeType":"image/jpeg","size":"1024"}]}
        """.trimIndent()
        val engine = RecordingEngine { HttpStatusCode.OK to body }
        val api = DriveApi(engine.client(), tokenProvider())

        val result = api.listMedia(folderId = "folder1", pageToken = null)

        assertEquals(1, result.files.size)
        val request = engine.requests.single()
        assertEquals("Bearer test-access-token", request.headers[HttpHeaders.Authorization])

        val url = request.url.toString()
        // Trashed files would otherwise reappear in the timeline after deletion.
        assertTrue(url.contains("trashed+%3D+false") || url.contains("trashed%20%3D%20false"), url)
        // Dimensions and EXIF time must be requested explicitly or Drive omits them.
        assertTrue(url.contains("imageMediaMetadata"), url)
        assertTrue(url.contains("folder1"), url)
    }

    @Test
    fun folderNamesWithApostrophesCannotBreakTheQuery() = runTest {
        val engine = RecordingEngine { HttpStatusCode.OK to """{"files":[]}""" }
        val api = DriveApi(engine.client(), tokenProvider())

        // An unescaped apostrophe would terminate Drive's quoted literal and
        // change the query's meaning.
        api.findFolder("Jud's Photos", parentId = null)

        val url = engine.requests.single().url.toString()
        assertTrue(url.contains("%5C%27") || url.contains("\\'"), "apostrophe must be escaped: $url")
    }

    @Test
    fun ensureFolderPathReusesExistingFoldersAndCreatesOnlyMissingOnes() = runTest {
        // PhotoHub exists; 2026 and 11 do not.
        var createCount = 0
        val engine = RecordingEngine { request ->
            if (request.method.value == "POST") {
                createCount++
                HttpStatusCode.OK to """{"id":"new-$createCount","name":"seg","mimeType":"${DriveApi.FOLDER_MIME}"}"""
            } else {
                val url = request.url.toString()
                if (url.contains("PhotoHub")) {
                    HttpStatusCode.OK to
                        """{"files":[{"id":"root-id","name":"PhotoHub","mimeType":"${DriveApi.FOLDER_MIME}"}]}"""
                } else {
                    HttpStatusCode.OK to """{"files":[]}"""
                }
            }
        }
        val api = DriveApi(engine.client(), tokenProvider())

        val folderId = api.ensureFolderPath("PhotoHub/2026/11")

        // Only the two missing segments are created; the existing root is reused,
        // which is what stops duplicate PhotoHub folders piling up in Drive.
        assertEquals(2, createCount)
        assertEquals("new-2", folderId)
    }

    @Test
    fun rateLimitAndServerErrorsAreRetryableButClientErrorsAreNot() = runTest {
        suspend fun statusFor(code: HttpStatusCode): DriveApiException {
            val engine = RecordingEngine { code to """{"error":"boom"}""" }
            val api = DriveApi(engine.client(), tokenProvider())
            return assertFailsWith { api.listMedia(null, null) }
        }

        // Transient: the upload queue should back off and try again.
        assertTrue(statusFor(HttpStatusCode.TooManyRequests).retryable)
        assertTrue(statusFor(HttpStatusCode.InternalServerError).retryable)
        assertTrue(statusFor(HttpStatusCode.ServiceUnavailable).retryable)
        // Permanent: retrying a bad request would loop forever.
        assertTrue(!statusFor(HttpStatusCode.NotFound).retryable)
        assertTrue(!statusFor(HttpStatusCode.Forbidden).retryable)
    }

    @Test
    fun resumableUploadReturnsTheSessionUrlFromLocationHeader() = runTest {
        val sessionUrl = "https://upload.example/session/abc123"
        val client = HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.Location, sessionUrl),
                )
            },
        )
        val api = DriveApi(client, tokenProvider())

        assertEquals(sessionUrl, api.startResumableUpload("a.jpg", "folder", "image/jpeg"))
    }

    @Test
    fun missingSessionUrlIsRetryableRatherThanSilentlySucceeding() = runTest {
        val client = HttpClient(
            MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) },
        )
        val api = DriveApi(client, tokenProvider())

        val failure = assertFailsWith<DriveApiException> {
            api.startResumableUpload("a.jpg", "f", "image/jpeg")
        }
        assertTrue(failure.retryable)
    }
}

class DriveSourceTest {

    private fun source(client: HttpClient): GoogleDriveSource {
        val api = DriveApi(client, tokenProvider())
        return GoogleDriveSource(
            config = SourceConfig(
                id = DriveSourceConfig.SOURCE_ID,
                type = SourceType.GOOGLE_DRIVE,
                displayName = "Google Drive",
                enabled = true,
                isUploadTarget = true,
                configJson = Json.encodeToString(DriveSourceConfig(rootFolderId = "root1")),
            ),
            api = api,
        )
    }

    @Test
    fun listingIsNotScopedToTheRootFolderBecauseDriveParentsIsNotRecursive() = runTest {
        // Verified against the live API: `'<id>' in parents` matches only direct
        // children. Uploads are filed into PhotoHub/YYYY/MM, so a root-scoped
        // query returned zero files and uploaded photos never reached the
        // timeline. Under `drive.file` the app only sees its own files, so an
        // unscoped listing is already limited to PhotoHub's media.
        var requestedUrl = ""
        val client = HttpClient(
            MockEngine { request ->
                requestedUrl = request.url.toString()
                respond(
                    ByteReadChannel("""{"files":[]}"""),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        source(client).list(null).getOrThrow()

        assertTrue(
            !requestedUrl.contains("in+parents") && !requestedUrl.contains("in%20parents"),
            "listing must not filter by parent folder: $requestedUrl",
        )
        assertTrue(requestedUrl.contains("trashed"), "query should still exclude trashed: $requestedUrl")
    }

    @Test
    fun mapsDriveFilesToRemoteMediaWithExifTimePreferred() = runTest {
        val body = """
            {"files":[
              {"id":"f1","name":"photo.jpg","mimeType":"image/jpeg","size":"204800",
               "createdTime":"2026-11-20T10:00:00.000Z",
               "thumbnailLink":"https://thumb/1",
               "imageMediaMetadata":{"width":4000,"height":3000,"time":"2024:05:04 13:14:15"}},
              {"id":"f2","name":"clip.mp4","mimeType":"video/mp4","size":"10485760",
               "createdTime":"2026-11-19T08:00:00.000Z",
               "videoMediaMetadata":{"width":1920,"height":1080,"durationMillis":"5000"}}
            ],"nextPageToken":"page2"}
        """.trimIndent()
        val client = HttpClient(
            MockEngine {
                respond(
                    ByteReadChannel(body),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val page = source(client).list(null).getOrThrow()

        assertEquals(2, page.items.size)
        assertEquals("page2", page.nextCursor)

        val photo = page.items[0]
        assertEquals("photo.jpg", photo.displayName)
        assertEquals(204800L, photo.sizeBytes)
        assertEquals(4000, photo.width)
        assertTrue(!photo.isVideo)
        assertEquals("https://thumb/1", photo.thumbnailUrl)
        // EXIF capture time must win over createdTime, otherwise a migrated
        // library all lands on its upload date.
        val exif = parseExifDateTime("2024:05:04 13:14:15")!!
        assertEquals(exif, photo.dateTakenEpochMs)

        val video = page.items[1]
        assertTrue(video.isVideo)
        assertEquals(1920, video.width)
    }

    @Test
    fun downloadsOriginalToTempThenRenamesAndReportsProgress() = runTest {
        val payload = ByteArray(50_000) { (it % 251).toByte() }
        val client = HttpClient(
            MockEngine { respond(ByteReadChannel(payload), HttpStatusCode.OK) },
        )
        val dir = Files.createTempDirectory("drive-dl").toFile()
        val target = okio.Path.Companion.let { with(it) { "${dir.absolutePath}/out.jpg".toPath() } }

        val item = dev.photohub.shared.model.RemoteMedia(
            remoteId = "f1",
            displayName = "out.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            sizeBytes = payload.size.toLong(),
            dateTakenEpochMs = 0,
        )

        val progress = mutableListOf<Float>()
        source(client).downloadOriginal(item, target) { progress += it }.getOrThrow()

        val written = java.io.File(target.toString())
        assertEquals(payload.size.toLong(), written.length())
        assertTrue(written.readBytes().contentEquals(payload))
        // No .part file may survive a successful download.
        assertNull(dir.listFiles()?.firstOrNull { it.name.endsWith(".part") })
        assertEquals(1f, progress.last())
    }

    @Test
    fun failedDownloadLeavesNoPartialFile() = runTest {
        val client = HttpClient(
            MockEngine { respond(ByteReadChannel("nope"), HttpStatusCode.NotFound) },
        )
        val dir = Files.createTempDirectory("drive-dl-fail").toFile()
        val target = with(okio.Path.Companion) { "${dir.absolutePath}/out.jpg".toPath() }
        val item = dev.photohub.shared.model.RemoteMedia(
            remoteId = "missing", displayName = "out.jpg", mimeType = "image/jpeg",
            isVideo = false, sizeBytes = 10, dateTakenEpochMs = 0,
        )

        assertTrue(source(client).downloadOriginal(item, target).isFailure)
        // A truncated file would later be mistaken for a valid cached original.
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }
}

class DriveDateTest {

    @Test
    fun parsesExifColonFormatInLocalZone() {
        val utc = parseExifDateTime("2024:05:04 13:14:15", TimeZone.UTC)
        assertNotNull(utc)
        // 2024-05-04T13:14:15Z
        assertEquals(1714828455000L, utc)
    }

    @Test
    fun rejectsUnsetCameraClocksAndGarbage() {
        // Cameras with an unset clock write zeros; that must not become year 0.
        assertNull(parseExifDateTime("0000:00:00 00:00:00"))
        assertNull(parseExifDateTime("not a date"))
        assertNull(parseExifDateTime(""))
        assertNull(parseExifDateTime("2024:13:01 00:00:00"))
    }

    @Test
    fun rfc3339AndExifBothParseThroughTheCommonEntryPoint() {
        assertEquals(
            1763632800000L,
            dev.photohub.shared.source.drive.parseRfc3339("2025-11-20T10:00:00Z"),
        )
        assertNotNull(dev.photohub.shared.source.drive.parseRfc3339("2024:05:04 13:14:15"))
        assertNull(dev.photohub.shared.source.drive.parseRfc3339(null))
    }
}
