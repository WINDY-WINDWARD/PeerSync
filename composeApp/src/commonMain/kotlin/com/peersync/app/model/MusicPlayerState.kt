package com.peersync.app.model

/**
 * Represents the current state of the music player.
 * Carried from the engine (androidMain) to the UI (commonMain).
 * Not @Serializable as this is owner-only local state for now.
 */
data class MusicPlayerState(
    val isPlaying: Boolean = false,
    val trackTitle: String = "",
    val trackArtist: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val trackIndex: Int = -1,  // 0-based; -1 = nothing loaded
    val trackCount: Int = 0
)

// Maximum music volume multiplier (100% to avoid hard clipping distortion at native layer)
const val MAX_MUSIC_VOLUME = 1.0f
