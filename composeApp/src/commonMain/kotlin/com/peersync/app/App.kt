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
    isMicMuted: Boolean = false,
    audioRoute: AudioRoute = AudioRoute.LOUDSPEAKER,
    onCreateSession: (sessionName: String) -> Unit = {},
    onJoinSession: (session: DiscoveredSession, pin: String) -> Unit = { _, _ -> },
    onDisconnect: () -> Unit = {},
    onMediaControl: (MediaAction) -> Unit = {},
    onRequestMediaHost: () -> Unit = {},
    onToggleMicMute: (Boolean) -> Unit = {},
    onSelectAudioRoute: (AudioRoute) -> Unit = {}
) {
    MaterialTheme {
        PeerSyncNavGraph(
            connectionState = connectionState,
            discoveredSessions = discoveredSessions,
            sessionInfo = sessionInfo,
            isMicMuted = isMicMuted,
            audioRoute = audioRoute,
            onCreateSession = onCreateSession,
            onJoinSession = onJoinSession,
            onDisconnect = onDisconnect,
            onMediaControl = onMediaControl,
            onRequestMediaHost = onRequestMediaHost,
            onToggleMicMute = onToggleMicMute,
            onSelectAudioRoute = onSelectAudioRoute
        )
    }
}
