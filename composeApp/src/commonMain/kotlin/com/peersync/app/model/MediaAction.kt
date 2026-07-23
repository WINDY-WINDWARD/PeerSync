package com.peersync.app.model

import kotlinx.serialization.Serializable

/**
 * Controls for music playback relayed across peers.
 */
@Serializable
enum class MediaAction {
    PLAY,
    PAUSE,
    SKIP_NEXT,
    SKIP_PREVIOUS
}
