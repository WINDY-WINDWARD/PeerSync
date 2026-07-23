package com.peersync.app.model

import kotlinx.serialization.Serializable

/**
 * Active session metadata.
 */
@Serializable
data class SessionInfo(
    val sessionName: String,
    val groupOwnerId: Byte = 0,
    val pin: String,
    val saltNonce: String,
    val members: List<PeerDevice> = emptyList(),
    val mediaHostId: Byte? = null
)
