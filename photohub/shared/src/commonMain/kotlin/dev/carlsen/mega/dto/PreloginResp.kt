package dev.carlsen.mega.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
data class PreloginResp(
    @SerialName("v") val version: Int,
    // PhotoHub patch: MEGA only sends a salt for version-2 accounts. The
    // upstream SDK declared this non-null, which made older accounts fail to
    // log in with "field 's' is required ... but it was missing".
    @SerialName("s") val salt: String? = null,
)