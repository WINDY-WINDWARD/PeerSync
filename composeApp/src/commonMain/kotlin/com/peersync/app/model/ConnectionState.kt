package com.peersync.app.model

/**
 * Global application connection state machine enum.
 */
enum class ConnectionState {
    Disconnected,
    Discovering,
    Connecting,
    ConnectedGroupOwner,
    ConnectedClient,
    Reconnecting
}
