package com.peersync.app.model

import kotlinx.serialization.Serializable

/**
 * Polymorphic TCP Control Plane messages between GO and Client Spokes.
 */
@Serializable
sealed class ControlMessage {

    @Serializable
    data class JoinRequest(
        val deviceName: String,
        val deviceAddress: String,
        val pin: String
    ) : ControlMessage()

    @Serializable
    data class JoinResponse(
        val success: Boolean,
        val assignedOriginId: Byte? = null,
        val errorMessage: String? = null,
        val sessionName: String? = null
    ) : ControlMessage()

    @Serializable
    data class MemberListUpdate(
        val sessionInfo: SessionInfo
    ) : ControlMessage()

    @Serializable
    data class Heartbeat(
        val senderOriginId: Byte,
        val timestampMs: Long
    ) : ControlMessage()

    @Serializable
    data class MediaControl(
        val action: MediaAction,
        val senderOriginId: Byte
    ) : ControlMessage()

    @Serializable
    data class MediaTokenRequest(
        val requestingOriginId: Byte
    ) : ControlMessage()

    @Serializable
    data class MediaTokenGrant(
        val newMediaHostOriginId: Byte
    ) : ControlMessage()

    @Serializable
    data class FailoverNotice(
        val oldGoOriginId: Byte,
        val newGoOriginId: Byte,
        val newGoAddress: String
    ) : ControlMessage()

    @Serializable
    data class DisconnectNotice(
        val leavingOriginId: Byte,
        val reason: String = "User disconnected"
    ) : ControlMessage()
}
