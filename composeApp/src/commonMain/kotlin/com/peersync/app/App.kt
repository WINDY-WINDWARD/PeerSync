package com.peersync.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.peersync.app.model.ConnectionState
import com.peersync.app.model.MediaAction
import com.peersync.app.model.SessionInfo
import com.peersync.app.model.AudioRoute
import com.peersync.app.navigation.PeerSyncNavGraph
import com.peersync.app.model.DiscoveredSession
import com.peersync.app.ui.settings.SettingsScreen

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
    locationPermissionGranted: Boolean = false,
    microphonePermissionGranted: Boolean = false,
    cameraPermissionGranted: Boolean = false,
    notificationsPermissionGranted: Boolean = false,
    batteryOptimizationExempt: Boolean = false,
    onGrantLocationPermission: () -> Unit = {},
    onGrantMicrophonePermission: () -> Unit = {},
    onGrantCameraPermission: () -> Unit = {},
    onGrantNotificationsPermission: () -> Unit = {},
    onGrantBatteryOptimizationExemption: () -> Unit = {},
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
    var showSettings by remember { mutableStateOf(false) }
    
    MaterialTheme {
        if (showSettings) {
            // Show Settings Screen
            SettingsScreen(
                locationPermissionGranted = locationPermissionGranted,
                microphonePermissionGranted = microphonePermissionGranted,
                cameraPermissionGranted = cameraPermissionGranted,
                notificationsPermissionGranted = notificationsPermissionGranted,
                batteryOptimizationExempt = batteryOptimizationExempt,
                onGrantLocationPermission = onGrantLocationPermission,
                onGrantMicrophonePermission = onGrantMicrophonePermission,
                onGrantCameraPermission = onGrantCameraPermission,
                onGrantNotificationsPermission = onGrantNotificationsPermission,
                onGrantBatteryOptimizationExemption = onGrantBatteryOptimizationExemption,
                onBackPressed = { showSettings = false }
            )
        } else {
            // Show Main Navigation Graph
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
                onScanQrCodeRequest = onScanQrCodeRequest,
                onOpenSettings = { showSettings = true }
            )
        }
    }
