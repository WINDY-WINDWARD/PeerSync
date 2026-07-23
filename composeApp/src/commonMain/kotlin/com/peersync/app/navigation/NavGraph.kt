package com.peersync.app.navigation

import androidx.compose.runtime.Composable
import com.peersync.app.model.ConnectionState
import com.peersync.app.model.DiscoveredSession
import com.peersync.app.model.MediaAction
import com.peersync.app.model.SessionInfo
import com.peersync.app.ui.activesession.ActiveSessionScreen
import com.peersync.app.ui.sessionlist.SessionListScreen
import com.peersync.app.model.AudioRoute

@Composable
fun PeerSyncNavGraph(
    connectionState: ConnectionState,
    discoveredSessions: List<DiscoveredSession>,
    sessionInfo: SessionInfo?,
    isMicMuted: Boolean,
    audioRoute: AudioRoute,
    onCreateSession: (String) -> Unit,
    onJoinSession: (DiscoveredSession, String) -> Unit,
    onDisconnect: () -> Unit,
    onMediaControl: (MediaAction) -> Unit,
    onRequestMediaHost: () -> Unit,
    onToggleMicMute: (Boolean) -> Unit,
    onSelectAudioRoute: (AudioRoute) -> Unit
) {
    when (connectionState) {
        ConnectionState.ConnectedGroupOwner, ConnectionState.ConnectedClient -> {
            ActiveSessionScreen(
                sessionInfo = sessionInfo,
                isGroupOwner = connectionState == ConnectionState.ConnectedGroupOwner,
                isMicMuted = isMicMuted,
                audioRoute = audioRoute,
                onDisconnect = onDisconnect,
                onMediaControl = onMediaControl,
                onRequestMediaHost = onRequestMediaHost,
                onToggleMicMute = onToggleMicMute,
                onSelectAudioRoute = onSelectAudioRoute
            )
        }
        else -> {
            SessionListScreen(
                discoveredSessions = discoveredSessions,
                connectionState = connectionState,
                onCreateSession = onCreateSession,
                onJoinSession = onJoinSession
            )
        }
    }
}
