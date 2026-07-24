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
    myOriginId: Byte,
    isMicMuted: Boolean,
    audioRoute: AudioRoute,
    peerVolumes: Map<Byte, Float>,
    onCreateSession: (String) -> Unit,
    onJoinSession: (DiscoveredSession, String) -> Unit,
    onDisconnect: () -> Unit,
    onMediaControl: (MediaAction) -> Unit,
    onSelectMusicRequest: () -> Unit,
    onToggleMicMute: (Boolean) -> Unit,
    onSelectAudioRoute: (AudioRoute) -> Unit,
    onSetPeerVolume: (Byte, Float) -> Unit,
    onSetLocalMusicVolume: (Float) -> Unit,
    onVolumeStep: () -> Unit,
    onRescan: () -> Unit
) {
    when (connectionState) {
        ConnectionState.ConnectedGroupOwner, ConnectionState.ConnectedClient -> {
            ActiveSessionScreen(
                sessionInfo = sessionInfo,
                isGroupOwner = connectionState == ConnectionState.ConnectedGroupOwner,
                myOriginId = myOriginId,
                isMicMuted = isMicMuted,
                audioRoute = audioRoute,
                peerVolumes = peerVolumes,
                onDisconnect = onDisconnect,
                onMediaControl = onMediaControl,
                onSelectMusicRequest = onSelectMusicRequest,
                onToggleMicMute = onToggleMicMute,
                onSelectAudioRoute = onSelectAudioRoute,
                onSetPeerVolume = onSetPeerVolume,
                onSetLocalMusicVolume = onSetLocalMusicVolume,
                onVolumeStep = onVolumeStep
            )
        }
        else -> {
            SessionListScreen(
                discoveredSessions = discoveredSessions,
                connectionState = connectionState,
                onCreateSession = onCreateSession,
                onJoinSession = onJoinSession,
                onRescan = onRescan
            )
        }
    }
}

