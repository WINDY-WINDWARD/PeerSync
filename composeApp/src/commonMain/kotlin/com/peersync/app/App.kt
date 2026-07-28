package com.peersync.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import peersync.composeapp.generated.resources.Res
import peersync.composeapp.generated.resources.logo
import org.jetbrains.compose.resources.painterResource
import com.peersync.app.model.ConnectionState
import com.peersync.app.model.MediaAction
import com.peersync.app.model.SessionInfo
import com.peersync.app.model.AudioRoute
import com.peersync.app.model.AudioDeviceModel
import com.peersync.app.navigation.PeerSyncNavGraph
import com.peersync.app.model.DiscoveredSession
import com.peersync.app.ui.settings.SettingsScreen

// Color Palette - Material 3 Dark Theme
private val PrimaryColor = Color(0xFF4DD4E0)           // Lighter Cyan for Dark Theme
private val SecondaryColor = Color(0xFFB0B0B0)         // Light Grey for Dark Theme
private val TertiaryColor = Color(0xFF92dce5)          // Frosted Blue
private val BackgroundColor = Color(0xFF0F1419)        // Very Dark Background
private val SurfaceColor = Color(0xFF1A1F28)           // Dark Surface
private val ErrorColor = Color(0xFFFF7B6B)             // Bright Red for visibility on dark bg
private val OnColor = Color(0xFFFFFFFF)                // White (for all On* colors)

@Composable
fun App(
    connectionState: ConnectionState = ConnectionState.Disconnected,
    discoveredSessions: List<DiscoveredSession> = emptyList(),
    sessionInfo: SessionInfo? = null,
    myOriginId: Byte = 0,
    isMicMuted: Boolean = false,
    audioRoute: AudioRoute = AudioRoute.LOUDSPEAKER,
    peerVolumes: Map<Byte, Float> = emptyMap(),
    peerLatencies: Map<Byte, Long> = emptyMap(),
    availableBluetoothDevices: List<AudioDeviceModel> = emptyList(),
    selectedBluetoothDeviceId: Int? = null,
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
    onSelectBluetoothDevice: (Int) -> Unit = {},
    onSetPeerVolume: (Byte, Float) -> Unit = { _, _ -> },
    onSetLocalMusicVolume: (Float) -> Unit = {},
    onVolumeStep: () -> Unit = {},
    onRescan: () -> Unit = {},
    onScanQrCodeRequest: () -> Unit = {}
) {
    var showSettings by remember { mutableStateOf(false) }
    
    // Custom Material 3 Color Scheme - Dark Theme
    val customColorScheme = darkColorScheme(
        primary = PrimaryColor,
        secondary = SecondaryColor,
        tertiary = TertiaryColor,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundColor)
        ) {
            // Logo watermark - positioned behind all content
            Image(
                painter = painterResource(Res.drawable.logo),
                contentDescription = "Logo watermark",
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.08f),
                contentScale = ContentScale.Fit
            )

            // Main content
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
                    peerLatencies = peerLatencies,
                    availableBluetoothDevices = availableBluetoothDevices,
                    selectedBluetoothDeviceId = selectedBluetoothDeviceId,
                    onCreateSession = onCreateSession,
                    onJoinSession = onJoinSession,
                    onDisconnect = onDisconnect,
                    onMediaControl = onMediaControl,
                    onSelectMusicRequest = onSelectMusicRequest,
                    onToggleMicMute = onToggleMicMute,
                    onSelectAudioRoute = onSelectAudioRoute,
                    onSelectBluetoothDevice = onSelectBluetoothDevice,
                    onSetPeerVolume = onSetPeerVolume,
                    onSetLocalMusicVolume = onSetLocalMusicVolume,
                    onVolumeStep = onVolumeStep,
                    onRescan = onRescan,
                    onScanQrCodeRequest = onScanQrCodeRequest,
                    onOpenSettings = { showSettings = true }
                )
            }
        }
    }
}
