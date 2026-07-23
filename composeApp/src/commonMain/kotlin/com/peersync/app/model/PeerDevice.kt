package com.peersync.app.model

import kotlinx.serialization.Serializable

/**
 * Represents a peer device in the local network session.
 */
@Serializable
data class PeerDevice(
    val originId: Byte,
    val deviceAddress: String, // MAC or Wi-Fi Direct P2P address
    val deviceName: String,
    val ipAddress: String,
    val isGroupOwner: Boolean = false,
    val isMediaHost: Boolean = false,
    val isSpeaking: Boolean = false,
    val audioLevel: Float = 0.0f
)
