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
    speedTestResult: String = "",
    onCreateSession: (sessionName: String) -> Unit = {},
    onJoinSession: (session: DiscoveredSession, pin: String) -> Unit = { _, _ -> },
    onDisconnect: () -> Unit = {},
    onMediaControl: (MediaAction) -> Unit = {},
    onSelectMusicRequest: () -> Unit = {},
    onToggleMicMute: (Boolean) -> Unit = {},
    onSelectAudioRoute: (AudioRoute) -> Unit = {},
    onSetPeerVolume: (Byte, Float) -> Unit = { _, _ -> },
    onSetLocalMusicVolume: (Float) -> Unit = {},
    onVolumeStep: () -> Unit = {},
    onRescan: () -> Unit = {},
    onRunSpeedTest: (Byte) -> Unit = {},
    onScanQrCodeRequest: () -> Unit = {}
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
            speedTestResult = speedTestResult,
            onCreateSession = onCreateSession,
            onJoinSession = onJoinSession,
            onDisconnect = onDisconnect,
            onMediaControl = onMediaControl,
            onSelectMusicRequest = onSelectMusicRequest,
            onToggleMicMute = onToggleMicMute,
            onSelectAudioRoute = onSelectAudioRoute,
            onSetPeerVolume = onSetPeerVolume,
            onSetLocalMusicVolume = onSetLocalMusicVolume,
            onVolumeStep = onVolumeStep,
            onRescan = onRescan,
            onRunSpeedTest = onRunSpeedTest,
            onScanQrCodeRequest = onScanQrCodeRequest
        )
    }
}
