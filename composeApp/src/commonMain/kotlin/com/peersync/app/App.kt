package com.peersync.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.peersync.app.model.ConnectionState
import com.peersync.app.model.MediaAction
import com.peersync.app.model.SessionInfo
import com.peersync.app.model.AudioRoute
import com.peersync.app.navigation.PeerSyncNavGraph
import com.peersync.app.model.DiscoveredSession
import com.peersync.app.ui.settings.SettingsScreen

// Color Palette - Material 3 Theme
private val PrimaryColor = Color(0xFF92dce5)           // Frosted Blue
private val SecondaryColor = Color(0xFF7c7c7c)         // Grey
private val BackgroundColor = Color(0xFFeee5e9)        // Lavender Blush
private val SurfaceColor = Color(0xFFeee5e9)           // Lavender Blush (same as background)
private val ErrorColor = Color(0xFFd64933)             // Burnt Tangerine
private val OnColor = Color(0xFF000000)                // Black (for all On* colors)

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
    
    // Custom Material 3 Color Scheme
    val customColorScheme = lightColorScheme(
        primary = PrimaryColor,
        secondary = SecondaryColor,
        background = BackgroundColor,
        surface = SurfaceColor,
        error = ErrorColor,
        onPrimary = OnColor,
        onSecondary = OnColor,
        onBackground = OnColor,
        onSurface = OnColor,
        onError = OnColor
    )
    
    MaterialTheme(colorScheme = customColorScheme) {
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
}
