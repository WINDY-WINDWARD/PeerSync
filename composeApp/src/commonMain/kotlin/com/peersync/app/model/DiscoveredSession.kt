package com.peersync.app.model

/**
 * Represents a discovered nearby session over Wi-Fi Direct NSD.
 */
data class DiscoveredSession(
    val sessionName: String,
    val deviceName: String,
    val deviceAddress: String,
    val token: String,
    val nonce: String
)
