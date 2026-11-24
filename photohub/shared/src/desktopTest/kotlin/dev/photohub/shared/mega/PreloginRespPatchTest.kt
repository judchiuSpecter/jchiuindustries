package dev.photohub.shared.mega

import dev.carlsen.mega.dto.PreloginResp
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression for the upstream kmp-mega bug that blocked login to older MEGA
 * accounts: their `us0` reply has `"v":1` and no `s` salt, but the SDK declared
 * `salt` non-null, so login failed before any credentials were checked.
 */
class PreloginRespPatchTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun versionOneAccountNeedsNoSalt() {
        val response = json.decodeFromString<List<PreloginResp>>(
            """[{"csid":"abc","v":1}]""",
        ).first()

        assertEquals(1, response.version)
        assertNull(response.salt)
    }

    @Test
    fun versionTwoAccountStillDecodesItsSalt() {
        val response = json.decodeFromString<List<PreloginResp>>(
            """[{"csid":"abc","v":2,"s":"c2FsdA"}]""",
        ).first()

        assertEquals(2, response.version)
        assertEquals("c2FsdA", response.salt)
    }
}
