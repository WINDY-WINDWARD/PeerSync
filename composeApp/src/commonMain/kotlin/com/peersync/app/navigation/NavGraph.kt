package com.peersync.app.navigation

import androidx.compose.runtime.Composable
import com.peersync.app.model.ConnectionState
import com.peersync.app.model.DiscoveredSession
import com.peersync.app.model.MediaAction
import com.peersync.app.model.SessionInfo
import com.peersync.app.ui.activesession.ActiveSessionScreen
import com.peersync.app.ui.sessionlist.SessionListScreen
import com.peersync.app.model.AudioRoute
import com.peersync.app.model.AudioDeviceModel

@Composable
fun PeerSyncNavGraph(
    connectionState: ConnectionState,
    discoveredSessions: List<DiscoveredSession>,
    sessionInfo: SessionInfo?,
    myOriginId: Byte,
    isMicMuted: Boolean,
    audioRoute: AudioRoute,
    peerVolumes: Map<Byte, Float>,
    peerLatencies: Map<Byte, Long> = emptyMap(),
    availableBluetoothDevices: List<AudioDeviceModel> = emptyList(),
    selectedBluetoothDeviceId: Int? = null,
    onCreateSession: (String) -> Unit,
    onJoinSession: (DiscoveredSession, String) -> Unit,
    onDisconnect: () -> Unit,
    onMediaControl: (MediaAction) -> Unit,
    onSelectMusicRequest: () -> Unit,
    onToggleMicMute: (Boolean) -> Unit,
    onSelectAudioRoute: (AudioRoute) -> Unit,
    onSelectBluetoothDevice: (Int) -> Unit = {},
    onSetPeerVolume: (Byte, Float) -> Unit,
    onSetLocalMusicVolume: (Float) -> Unit,
    onVolumeStep: () -> Unit,
    onRescan: () -> Unit,
    onRunSpeedTest: (Byte) -> Unit = {},
    onScanQrCodeRequest: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    when (connectionState) {
        ConnectionState.ConnectedGroupOwner, ConnectionState.ConnectedClient -> {
            ActiveSessionScreen(
                connectionState = connectionState,
                sessionInfo = sessionInfo,
                isGroupOwner = connectionState == ConnectionState.ConnectedGroupOwner,
                myOriginId = myOriginId,
                isMicMuted = isMicMuted,
                audioRoute = audioRoute,
                peerVolumes = peerVolumes,
                peerLatencies = peerLatencies,
                availableBluetoothDevices = availableBluetoothDevices,
                selectedBluetoothDeviceId = selectedBluetoothDeviceId,
                onDisconnect = onDisconnect,
                onMediaControl = onMediaControl,
                onSelectMusicRequest = onSelectMusicRequest,
                onToggleMicMute = onToggleMicMute,
                onSelectAudioRoute = onSelectAudioRoute,
                onSelectBluetoothDevice = onSelectBluetoothDevice,
                onSetPeerVolume = onSetPeerVolume,
                onSetLocalMusicVolume = onSetLocalMusicVolume,
                onVolumeStep = onVolumeStep,
                onRunSpeedTest = onRunSpeedTest,
                onOpenSettings = onOpenSettings
            )
        }
        else -> {
            SessionListScreen(
                discoveredSessions = discoveredSessions,
                connectionState = connectionState,
                onCreateSession = onCreateSession,
                onJoinSession = onJoinSession,
                onRescan = onRescan,
                onScanQrCodeRequest = onScanQrCodeRequest,
                onOpenSettings = onOpenSettings
            )
        }
    }
}

