package com.peersync.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.peersync.app.model.ConnectionState
import com.peersync.app.model.MediaAction
import com.peersync.app.model.SessionInfo
import com.peersync.app.model.AudioRoute
import com.peersync.app.navigation.PeerSyncNavGraph
import com.peersync.app.model.DiscoveredSession

@Composable
fun App(
    connectionState: ConnectionState = ConnectionState.Disconnected,
    discoveredSessions: List<DiscoveredSession> = emptyList(),
    sessionInfo: SessionInfo? = null,
    myOriginId: Byte = 0,
    isMicMuted: Boolean = false,
    audioRoute: AudioRoute = AudioRoute.LOUDSPEAKER,
    peerVolumes: Map<Byte, Float> = emptyMap(),
    onCreateSession: (sessionName: String) -> Unit = {},
    onJoinSession: (session: DiscoveredSession, pin: String) -> Unit = { _, _ -> },
    onDisconnect: () -> Unit = {},
    onMediaControl: (MediaAction) -> Unit = {},
    onRequestMediaHost: () -> Unit = {},
    onSelectMusicRequest: () -> Unit = {},
    onToggleMicMute: (Boolean) -> Unit = {},
    onSelectAudioRoute: (AudioRoute) -> Unit = {},
    onSetPeerVolume: (Byte, Float) -> Unit = { _, _ -> },
    onVolumeStep: () -> Unit = {},
    onRescan: () -> Unit = {}
) {
    MaterialTheme {
        PeerSyncNavGraph(
            connectionState = connectionState,
            discoveredSessions = discoveredSessions,
            sessionInfo = sessionInfo,
            myOriginId = myOriginId,
            isMicMuted = isMicMuted,
            audioRoute = audioRoute,
            peerVolumes = peerVolumes,
            onCreateSession = onCreateSession,
            onJoinSession = onJoinSession,
            onDisconnect = onDisconnect,
            onMediaControl = onMediaControl,
            onRequestMediaHost = onRequestMediaHost,
            onSelectMusicRequest = onSelectMusicRequest,
            onToggleMicMute = onToggleMicMute,
            onSelectAudioRoute = onSelectAudioRoute,
            onSetPeerVolume = onSetPeerVolume,
            onVolumeStep = onVolumeStep,
            onRescan = onRescan
        )
    }
}
