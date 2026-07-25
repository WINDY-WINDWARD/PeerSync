package com.peersync.app.model

import kotlinx.serialization.Serializable

/**
 * Represents a peer device in the local network session.
 */
@Serializable
data class PeerDevice(
    val originId: Byte,
    val deviceId: String, // Nearby Connections endpoint ID (was MAC/Wi-Fi P2P address)
    val deviceName: String,
    val isGroupOwner: Boolean = false,
    val isMediaHost: Boolean = false,
    val isSpeaking: Boolean = false,
    val audioLevel: Float = 0.0f
)
