package com.peersync.app.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Polymorphic TCP Control Plane messages between GO and Client Spokes.
 * All messages support mesh deduplication via messageId.
 */
@Serializable
sealed class ControlMessage {
    abstract val messageId: String

    @Serializable
    data class JoinRequest(
        val deviceName: String,
        val deviceId: String,
        val pin: String,
        override val messageId: String = Random.nextLong().toString()
    ) : ControlMessage()

    @Serializable
    data class JoinResponse(
        val success: Boolean,
        val assignedOriginId: Byte? = null,
        val errorMessage: String? = null,
        val sessionName: String? = null,
        val targetDeviceId: String = "",
        override val messageId: String = Random.nextLong().toString()
    ) : ControlMessage()

    @Serializable
    data class MemberListUpdate(
        val sessionInfo: SessionInfo,
        override val messageId: String = Random.nextLong().toString()
    ) : ControlMessage()

    @Serializable
    data class Heartbeat(
        val senderOriginId: Byte,
        val timestampMs: Long,
        override val messageId: String = Random.nextLong().toString()
    ) : ControlMessage()

    @Serializable
    data class MediaControl(
        val action: MediaAction,
        val senderOriginId: Byte,
        override val messageId: String = Random.nextLong().toString()
    ) : ControlMessage()

    @Serializable
    data class MediaTokenRequest(
        val requestingOriginId: Byte,
        override val messageId: String = Random.nextLong().toString()
    ) : ControlMessage()

    @Serializable
    data class MediaTokenGrant(
        val newMediaHostOriginId: Byte,
        override val messageId: String = Random.nextLong().toString()
    ) : ControlMessage()

    @Serializable
    data class FailoverNotice(
        val oldGoOriginId: Byte,
        val newGoOriginId: Byte,
        val newGoAddress: String,
        override val messageId: String = Random.nextLong().toString()
    ) : ControlMessage()

    @Serializable
    data class DisconnectNotice(
        val leavingOriginId: Byte,
        val reason: String = "User disconnected",
        override val messageId: String = Random.nextLong().toString()
    ) : ControlMessage()

    @Serializable
    data class SpeedTestPing(
        val senderOriginId: Byte,
        val timestamp: Long,
        override val messageId: String = Random.nextLong().toString()
    ) : ControlMessage()

    @Serializable
    data class SpeedTestPong(
        val senderOriginId: Byte,
        val originalTimestamp: Long,
        override val messageId: String = Random.nextLong().toString()
    ) : ControlMessage()
}
