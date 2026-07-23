package com.peersync.app.navigation

import androidx.compose.runtime.Composable
import com.peersync.app.model.ConnectionState
import com.peersync.app.model.DiscoveredSession
import com.peersync.app.model.MediaAction
import com.peersync.app.model.SessionInfo
import com.peersync.app.ui.activesession.ActiveSessionScreen
import com.peersync.app.ui.sessionlist.SessionListScreen

@Composable
fun PeerSyncNavGraph(
    connectionState: ConnectionState,
    discoveredSessions: List<DiscoveredSession>,
    sessionInfo: SessionInfo?,
    onCreateSession: (sessionName: String) -> Unit,
    onJoinSession: (session: DiscoveredSession, pin: String) -> Unit,
    onDisconnect: () -> Unit,
    onMediaControl: (MediaAction) -> Unit,
    onRequestMediaHost: () -> Unit
) {
    when (connectionState) {
        ConnectionState.ConnectedGroupOwner, ConnectionState.ConnectedClient -> {
            ActiveSessionScreen(
                sessionInfo = sessionInfo,
                isGroupOwner = connectionState == ConnectionState.ConnectedGroupOwner,
                onDisconnect = onDisconnect,
                onMediaControl = onMediaControl,
                onRequestMediaHost = onRequestMediaHost
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
