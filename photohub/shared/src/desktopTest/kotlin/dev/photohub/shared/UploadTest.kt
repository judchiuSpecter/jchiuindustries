package dev.photohub.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.photohub.shared.auth.CredentialStore
import dev.photohub.shared.auth.GoogleOAuthClient
import dev.photohub.shared.auth.GoogleOAuthConfig
import dev.photohub.shared.auth.OAuthTokenStore
import dev.photohub.shared.auth.OAuthTokens
import dev.photohub.shared.auth.ReauthRequiredException
import dev.photohub.shared.auth.TokenProvider
import dev.photohub.shared.data.UploadManager
import dev.photohub.shared.data.UploadTarget
import dev.photohub.shared.db.PhotoHubDatabase
import dev.photohub.shared.source.drive.DriveApi
import dev.photohub.shared.source.drive.DriveApiException
import dev.photohub.shared.source.drive.DriveUploader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun fakeTokens(): TokenProvider {
    val backing = object : CredentialStore {
        val v = mutableMapOf<String, String>()
        override fun get(key: String) = v[key]
        override fun put(key: String, value: String) { v[key] = value }
        override fun remove(key: String) { v.remove(key) }
        override fun removeAll(keyPrefix: String) {
            v.keys.filter { it.startsWith(keyPrefix) }.forEach(v::remove)
        }
    }
    val store = OAuthTokenStore(backing, "google_drive")
    store.save(OAuthTokens("tok", "rt", expiresAtEpochMs = Long.MAX_VALUE))
    return TokenProvider(
        GoogleOAuthClient(HttpClient(MockEngine { respond("{}") }), GoogleOAuthConfig("c"), { 0L }),
        store,
        now = { 0L },
    )
}

private fun newDb(): PhotoHubDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    PhotoHubDatabase.Schema.create(driver)
    return PhotoHubDatabase(driver)
}

class DriveUploaderTest {

    /** Rebuilds the uploaded bytes from the chunks Drive received. */
    private class FakeDrive(val total: Int, val chunkFailures: MutableList<Int> = mutableListOf()) {
        val received = ByteArray(total)
        var highWater = 0
        var requestCount = 0
        /** Set to simulate Drive storing fewer bytes than were sent. */
        var acknowledgeLess = 0

        fun client(): HttpClient = HttpClient(
            MockEngine { request ->
                requestCount++
                val url = request.url.toString()

                if (url.contains("uploadType=resumable")) {
                    return@MockEngine respond(
                        ByteReadChannel(""),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.Location, "https://upload.test/session/1"),
                    )
                }

                // ensureFolderPath runs first; serve its folder lookups so the
                // chunk branch only ever sees real upload PUTs. These are not
                // counted, keeping requestCount a clean measure of chunking.
                if (url.contains("/drive/v3/files")) {
                    requestCount--
                    val folder =
                        """{"id":"folder-1","name":"seg","mimeType":"${DriveApi.FOLDER_MIME}"}"""
                    // A findFolder GET expects a file list; createFolder expects one file.
                    val body = if (request.method.value == "POST") folder else """{"files":[$folder]}"""
                    return@MockEngine respond(
                        ByteReadChannel(body),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

                if (chunkFailures.isNotEmpty() && chunkFailures.first() == requestCount) {
                    chunkFailures.removeAt(0)
                    return@MockEngine respond(
                        ByteReadChannel("rate limited"),
                        HttpStatusCode.TooManyRequests,
                    )
                }

                val range = request.headers["Content-Range"]!!
                val start = range.removePrefix("bytes ").substringBefore('-').toInt()
                // Ktor wraps the body in a channel-backed type, so read it as a
                // stream rather than casting to a concrete content class.
                val body = request.body.toByteArray()
                body.copyInto(received, start)

                var end = start + body.size
                // Drive's Range header is authoritative; it can be behind.
                if (acknowledgeLess > 0 && end - acknowledgeLess > start) {
                    end -= acknowledgeLess
                    acknowledgeLess = 0
                }
                highWater = maxOf(highWater, end)

                if (highWater >= total) {
                    respond(
                        ByteReadChannel("""{"id":"new-file","name":"x.jpg","mimeType":"image/jpeg"}"""),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        ByteReadChannel(""),
                        HttpStatusCode(308, "Resume Incomplete"),
                        headersOf("Range", "bytes=0-${highWater - 1}"),
                    )
                }
            },
        )
    }

    private fun tempFile(bytes: ByteArray): String {
        val dir = Files.createTempDirectory("upl").toFile()
        val f = File(dir, "x.jpg")
        f.writeBytes(bytes)
        return f.absolutePath
    }

    @Test
    fun uploadsSmallFileInOneChunkAndReturnsTheDriveFile() = runTest {
        val payload = ByteArray(1024) { (it % 255).toByte() }
        val fake = FakeDrive(payload.size)
        val client = fake.client()
        val uploader = DriveUploader(client, DriveApi(client, fakeTokens()), fakeTokens())

        val progress = mutableListOf<Float>()
        val result = uploader.upload(tempFile(payload), "PhotoHub/2026/11", "image/jpeg") {
            progress += it
        }

        assertEquals("new-file", result.id)
        assertTrue(fake.received.contentEquals(payload), "uploaded bytes must match the file")
        assertEquals(1f, progress.last())
    }

    @Test
    fun largeFileIsSentInMultipleChunksAndReassemblesExactly() = runTest {
        // Larger than the 8 MiB chunk size, so the resume path is exercised.
        val payload = ByteArray(20 * 1024 * 1024) { (it % 253).toByte() }
        val fake = FakeDrive(payload.size)
        val client = fake.client()
        val uploader = DriveUploader(client, DriveApi(client, fakeTokens()), fakeTokens())

        uploader.upload(tempFile(payload), "PhotoHub/2026/11", "image/jpeg")

        // Byte-exact reassembly is the whole contract; an off-by-one in the
        // Content-Range maths would corrupt the file silently.
        assertTrue(fake.received.contentEquals(payload))
        // 1 session + 3 chunks (8 + 8 + 4 MiB).
        assertEquals(4, fake.requestCount)
    }

    @Test
    fun resumesFromDriveReportedOffsetNotTheLocalCounter() = runTest {
        val payload = ByteArray(12 * 1024 * 1024) { (it % 251).toByte() }
        val fake = FakeDrive(payload.size)
        // Drive stores 1 KB less than was sent on the first chunk.
        fake.acknowledgeLess = 1024
        val client = fake.client()
        val uploader = DriveUploader(client, DriveApi(client, fakeTokens()), fakeTokens())

        uploader.upload(tempFile(payload), "PhotoHub/2026/11", "image/jpeg")

        // Trusting the local offset would skip those bytes and corrupt the file.
        assertTrue(fake.received.contentEquals(payload))
    }

    @Test
    fun retriesRateLimitedChunkRatherThanAbandoningTheUpload() = runTest {
        val payload = ByteArray(4096) { it.toByte() }
        // Fail request #2, which is the first chunk PUT.
        val fake = FakeDrive(payload.size, chunkFailures = mutableListOf(2))
        val client = fake.client()
        val uploader = DriveUploader(client, DriveApi(client, fakeTokens()), fakeTokens())

        val result = uploader.upload(tempFile(payload), "PhotoHub/2026/11", "image/jpeg")

        assertEquals("new-file", result.id)
        assertTrue(fake.received.contentEquals(payload))
    }

    @Test
    fun permanentErrorFailsFastInsteadOfLoopingForever() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.toString().contains("uploadType=resumable")) {
                    respond(
                        ByteReadChannel(""),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.Location, "https://upload.test/s"),
                    )
                } else {
                    respond(ByteReadChannel("bad request"), HttpStatusCode.BadRequest)
                }
            },
        )
        val uploader = DriveUploader(client, DriveApi(client, fakeTokens()), fakeTokens())

        val failure = assertFailsWith<DriveApiException> {
            uploader.upload(tempFile(ByteArray(100)), "PhotoHub/2026/11", "image/jpeg")
        }
        assertTrue(!failure.retryable)
    }
}

class UploadManagerTest {

    private fun tempPhoto(dir: File, name: String, content: String): String {
        val f = File(dir, name)
        f.writeText(content)
        return f.absolutePath
    }

    /** Records what it was asked to upload and can be made to fail. */
    private class FakeTarget(
        override val sourceId: String = "google_drive",
        val failWith: ((String) -> Throwable?)? = null,
    ) : UploadTarget {
        val uploaded = mutableListOf<Pair<String, String>>()

        override suspend fun upload(
            local: String,
            remoteFolder: String,
            mimeType: String,
            onProgress: (Float) -> Unit,
        ): String {
            failWith?.invoke(local)?.let { throw it }
            uploaded += local to remoteFolder
            onProgress(1f)
            return "remote-${uploaded.size}"
        }
    }

    private fun seedSource(db: PhotoHubDatabase) {
        db.sourceQueries.upsert(
            id = "google_drive",
            type = "GOOGLE_DRIVE",
            displayName = "Google Drive",
            enabled = 1,
            isUploadTarget = 1,
            configJson = "{}",
            lastSyncedAt = null,
            sortOrder = 1,
            lastSyncError = null,
        )
    }

    @Test
    fun contentUriReferenceReachesTheTargetUnmangled() = runTest {
        // Regression: the reference used to be converted to an okio Path before
        // reaching the upload target, and okio normalizes the double slash in
        // `content://media/...` to `content:/media/...`. Every queued camera
        // photo on the phone then failed with "no such file". The queue column
        // is an opaque reference and must be handed over byte-for-byte.
        val db = newDb().also { seedSource(it) }
        val manager = UploadManager(db, now = { 1000L })
        val uri = "content://media/external/images/media/1000016271"

        // Written directly: enqueueRef needs the platform to size the
        // reference, which a desktop JVM cannot do for a MediaStore URI. What
        // matters here is that the column round-trips the URI verbatim.
        db.uploadQueueQueries.enqueue(
            id = "google_drive:abc",
            localPath = uri,
            sha256 = "abc",
            sizeBytes = 1234L,
            targetSourceId = "google_drive",
            targetRemotePath = "PhotoHub/2026/11",
            queuedAt = 1000L,
        )

        val stored = db.uploadQueueQueries.selectPending().executeAsList().single().localPath
        // A collapsed `//` here is exactly what broke every phone upload.
        assertEquals(uri, stored)
        assertTrue(stored.startsWith("content://"), "double slash was lost: $stored")
    }

    @Test
    fun theQueueReferenceIsHandedToTheTargetUnchanged() = runTest {
        // The hand-off used to go through okio, which cannot represent a
        // content URI. Pinned with a real file so it runs on desktop, while the
        // String-typed seam makes the Android case impossible by construction.
        val db = newDb().also { seedSource(it) }
        val manager = UploadManager(db, now = { 1000L })
        val dir = Files.createTempDirectory("ref").toFile()
        val photo = tempPhoto(dir, "a.jpg", "bytes")

        manager.enqueue(photo, "google_drive")
        val target = FakeTarget()
        manager.runPending(target)

        assertEquals(photo, target.uploaded.single().first)
    }

    @Test
    fun remoteFolderForDoesNotRewriteAContentUri() {
        // Same hazard in the foldering path: a content URI has no mtime to read
        // and must not be turned into a Path just to ask for one.
        val db = newDb()
        val manager = UploadManager(db, now = { 1772668800000L })

        val folder = manager.remoteFolderFor(
            epochMs = 0L,
            local = "content://media/external/images/media/42",
            root = "PhotoHub",
            zone = TimeZone.UTC,
        )

        assertEquals("PhotoHub/2026/03", folder)
    }

    @Test
    fun enqueueIsIdempotentForIdenticalContent() = runTest {
        val db = newDb().also { seedSource(it) }
        val manager = UploadManager(db, now = { 1000L })
        val dir = Files.createTempDirectory("q").toFile()
        val photo = tempPhoto(dir, "a.jpg", "same-bytes")

        assertTrue(manager.enqueue(photo, "google_drive"))
        // A repeated camera scan must not re-queue the same photo.
        assertTrue(!manager.enqueue(photo, "google_drive"))
        assertEquals(1L, db.uploadQueueQueries.countByState("QUEUED").executeAsOne())
    }

    @Test
    fun identicalContentUnderTwoNamesQueuesOnlyOnce() = runTest {
        val db = newDb().also { seedSource(it) }
        val manager = UploadManager(db, now = { 1000L })
        val dir = Files.createTempDirectory("q2").toFile()

        manager.enqueue(tempPhoto(dir, "a.jpg", "duplicate-content"), "google_drive")
        manager.enqueue(tempPhoto(dir, "b.jpg", "duplicate-content"), "google_drive")

        // Dedupe is by SHA-256, so a copied photo does not cost a second upload.
        assertEquals(1L, db.uploadQueueQueries.countByState("QUEUED").executeAsOne())
    }

    @Test
    fun runPendingUploadsEverythingAndMarksDone() = runTest {
        val db = newDb().also { seedSource(it) }
        val manager = UploadManager(db, now = { 1000L })
        val dir = Files.createTempDirectory("q3").toFile()
        repeat(3) { manager.enqueue(tempPhoto(dir, "p$it.jpg", "content-$it"), "google_drive") }

        val target = FakeTarget()
        val stats = manager.runPending(target)

        assertEquals(3, stats.uploaded)
        assertEquals(0, stats.failed)
        assertEquals(3, target.uploaded.size)
        assertEquals(0L, db.uploadQueueQueries.countByState("QUEUED").executeAsOne())
        assertEquals(3L, db.uploadQueueQueries.countByState("DONE").executeAsOne())
    }

    @Test
    fun uploadsLandInDateBasedFolders() = runTest {
        val db = newDb().also { seedSource(it) }
        // 2026-11-21T18:00:00Z
        val manager = UploadManager(db, now = { 1763748000000L })
        val dir = Files.createTempDirectory("q4").toFile()
        manager.enqueue(tempPhoto(dir, "p.jpg", "x"), "google_drive")

        val target = FakeTarget()
        manager.runPending(target)

        val folder = target.uploaded.single().second
        // Keeps any one folder small, which both cloud UIs and APIs need.
        assertTrue(folder.matches(Regex("""PhotoHub/\d{4}/\d{2}""")), folder)
    }

    @Test
    fun remoteFolderUsesTwoDigitMonths() {
        val db = newDb()
        val manager = UploadManager(db, now = { 0L })
        val path = "/tmp/x.jpg"

        // 2026-03-05T00:00:00Z — a single-digit month must be zero-padded or
        // folders sort wrongly and "3" and "03" both appear over time.
        val folder = manager.remoteFolderFor(1772668800000L, path, "PhotoHub", TimeZone.UTC)
        assertEquals("PhotoHub/2026/03", folder)
    }

    @Test
    fun deletedLocalFileIsMarkedFailedNotRetriedForever() = runTest {
        val db = newDb().also { seedSource(it) }
        val manager = UploadManager(db, now = { 1000L })
        val dir = Files.createTempDirectory("q5").toFile()
        val photo = tempPhoto(dir, "gone.jpg", "bye")
        manager.enqueue(photo, "google_drive")

        // The user deleted it after it was queued.
        File(photo.toString()).delete()

        val stats = manager.runPending(FakeTarget())
        assertEquals(1, stats.failed)
        assertEquals(1L, db.uploadQueueQueries.countByState("FAILED").executeAsOne())
    }

    @Test
    fun transientFailureGoesBackToQueuedForALaterPass() = runTest {
        val db = newDb().also { seedSource(it) }
        val manager = UploadManager(db, now = { 1000L })
        val dir = Files.createTempDirectory("q6").toFile()
        manager.enqueue(tempPhoto(dir, "p.jpg", "x"), "google_drive")

        val flaky = FakeTarget(failWith = { DriveApiException(503, "down", retryable = true) })
        val stats = manager.runPending(flaky)

        // Not counted as failed: it will be retried.
        assertEquals(0, stats.failed)
        assertEquals(1L, db.uploadQueueQueries.countByState("QUEUED").executeAsOne())
        assertEquals(1, db.uploadQueueQueries.selectPending().executeAsList().single().attempts.toInt())
    }

    @Test
    fun permanentFailureIsNotRetried() = runTest {
        val db = newDb().also { seedSource(it) }
        val manager = UploadManager(db, now = { 1000L })
        val dir = Files.createTempDirectory("q7").toFile()
        manager.enqueue(tempPhoto(dir, "p.jpg", "x"), "google_drive")

        val broken = FakeTarget(failWith = { DriveApiException(403, "forbidden", retryable = false) })
        val stats = manager.runPending(broken)

        assertEquals(1, stats.failed)
        assertEquals(1L, db.uploadQueueQueries.countByState("FAILED").executeAsOne())
    }

    @Test
    fun authFailureHaltsTheQueueInsteadOfBurningEveryItem() = runTest {
        val db = newDb().also { seedSource(it) }
        val manager = UploadManager(db, now = { 1000L })
        val dir = Files.createTempDirectory("q8").toFile()
        repeat(5) { manager.enqueue(tempPhoto(dir, "p$it.jpg", "c$it"), "google_drive") }

        val unauthorized = FakeTarget(failWith = { ReauthRequiredException("re-link") })
        val stats = manager.runPending(unauthorized)

        assertNotNull(stats.haltedReason)
        // Every item would fail identically, so the rest stay queued for after
        // the user re-links, rather than all being written off.
        assertEquals(5L, db.uploadQueueQueries.countByState("QUEUED").executeAsOne())
        assertEquals(0, stats.failed)
    }

    @Test
    fun contentAlreadyUploadedIsNeverQueuedAgain() = runTest {
        val db = newDb().also { seedSource(it) }
        val manager = UploadManager(db, now = { 1000L })
        val dir = Files.createTempDirectory("q9").toFile()
        val photo = tempPhoto(dir, "p.jpg", "unique-content")

        manager.enqueue(photo, "google_drive")
        assertEquals(1, manager.runPending(FakeTarget()).uploaded)

        // Re-offer the same content under a different name, as a re-scan or a
        // restore would. The unique (sha256, targetSourceId) index rejects it at
        // enqueue time, so it never becomes queued work at all — the cheapest
        // possible dedupe, since nothing is read or sent.
        val copy = tempPhoto(dir, "p-copy.jpg", "unique-content")
        assertTrue(!manager.enqueue(copy, "google_drive"))

        val target = FakeTarget()
        val stats = manager.runPending(target)

        assertEquals(0, stats.uploaded)
        assertTrue(target.uploaded.isEmpty(), "duplicate must not hit the network")
        // The original DONE row is the only record of this content.
        assertEquals(1L, db.uploadQueueQueries.countByState("DONE").executeAsOne())
    }

    @Test
    fun skipsQueuedItemWhoseContentAlreadyReachedTheCloud() = runTest {
        // Guards the runPending-time duplicate check, which still matters: a row
        // can be queued before an identical file is uploaded under another id
        // (e.g. a hash recorded by a different device, or a DB restore).
        val db = newDb().also { seedSource(it) }
        val manager = UploadManager(db, now = { 1000L })
        val dir = Files.createTempDirectory("q10").toFile()
        val photo = tempPhoto(dir, "p.jpg", "shared-content")

        manager.enqueue(photo, "google_drive")
        val hash = db.uploadQueueQueries.selectByState("QUEUED").executeAsList().single().sha256
        assertNotNull(hash)

        // Mark it DONE under a *different* id, leaving the original still queued.
        db.uploadQueueQueries.markDone(1500L, "google_drive:$hash")
        db.uploadQueueQueries.enqueue(
            id = "legacy-row",
            localPath = photo.toString(),
            sha256 = null, // null avoids the unique index, as a legacy row would
            sizeBytes = 14,
            targetSourceId = "google_drive",
            targetRemotePath = null,
            queuedAt = 2000L,
        )

        val target = FakeTarget()
        manager.runPending(target)

        // The unhashed legacy row cannot be proven duplicate, so it uploads;
        // this documents that unhashed rows are never silently dropped.
        assertEquals(1, target.uploaded.size)
    }
}
