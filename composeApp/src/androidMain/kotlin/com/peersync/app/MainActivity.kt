package com.peersync.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.peersync.app.engine.PeerSyncEngine
import com.peersync.app.model.ControlMessage
import com.peersync.app.model.DiscoveredSession

data class PermissionState(
    val name: String,
    val isGranted: Boolean
)

class MainActivity : ComponentActivity() {

    private lateinit var engine: PeerSyncEngine
    
    // Permission states
    private var locationPermissionGranted = mutableStateOf(false)
    private var microphonePermissionGranted = mutableStateOf(false)
    private var cameraPermissionGranted = mutableStateOf(false)
    private var notificationsPermissionGranted = mutableStateOf(false)
    private var batteryOptimizationExempt = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        updatePermissionStates()
        if (allGranted) {
            engine.startDiscovery()
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        updatePermissionStates()
    }

    private val musicFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Permission is best-effort and may already be granted.
            }
            engine.selectAndPlayMusicFolder(it)
        }
    }

    private val qrScannerLauncher = registerForActivityResult(
        ScanContract()
    ) { result ->
        result.contents?.let { qrContent ->
            if (qrContent.startsWith("peersync://join")) {
                try {
                    val uri = Uri.parse(qrContent)
                    val sessionName = uri.getQueryParameter("session") ?: return@let
                    val pin = uri.getQueryParameter("pin") ?: return@let
                    
                    // Create a discovered session and join
                    val discoveredSession = DiscoveredSession(
                        sessionName = sessionName,
                        deviceName = sessionName,
                        deviceAddress = sessionName,
                        token = "",
                        nonce = ""
                    )
                    engine.joinSession(discoveredSession, pin, Build.MODEL)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to parse QR code: ${e.message}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = PeerSyncEngine.getInstance(this)
        
        updatePermissionStates()

        val requiredPermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val allGranted = requiredPermissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            engine.startDiscovery()
        } else {
            requestPermissionLauncher.launch(requiredPermissions.toTypedArray())
        }

        setContent {
            val connectionState by engine.connectionState.collectAsState()
            val discoveredSessions by engine.discoveredSessions.collectAsState()
            val sessionInfo by engine.sessionInfo.collectAsState()
            val myOriginId by engine.myOriginId.collectAsState()
            val isMicMuted by engine.isMicMuted.collectAsState()
            val audioRoute by engine.audioRoute.collectAsState()
            val peerVolumes by engine.peerVolumes.collectAsState()
            val peerLatencies by engine.peerLatencies.collectAsState()
            val availableBluetoothDevices by engine.availableBluetoothDevices.collectAsState()
            val selectedBluetoothDeviceId by engine.selectedBluetoothDeviceId.collectAsState()
            
            // Permission states
            val locPermissionGranted by remember { locationPermissionGranted }
            val micPermissionGranted by remember { microphonePermissionGranted }
            val camPermissionGranted by remember { cameraPermissionGranted }
            val notifPermissionGranted by remember { notificationsPermissionGranted }
            val batteryExempt by remember { batteryOptimizationExempt }

            App(
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
                locationPermissionGranted = locPermissionGranted,
                microphonePermissionGranted = micPermissionGranted,
                cameraPermissionGranted = camPermissionGranted,
                notificationsPermissionGranted = notifPermissionGranted,
                batteryOptimizationExempt = batteryExempt,
                onGrantLocationPermission = {
                    requestPermissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                },
                onGrantMicrophonePermission = {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                },
                onGrantCameraPermission = {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                },
                onGrantNotificationsPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                },
                onGrantBatteryOptimizationExemption = {
                    requestBatteryOptimizationExemption()
                },
                onCreateSession = { name ->
                    engine.createSession(name, Build.MODEL)
                },
                onJoinSession = { session, pin ->
                    engine.joinSession(session, pin, Build.MODEL)
                },
                onDisconnect = {
                    engine.disconnect()
                },
                onMediaControl = { action ->
                    engine.handleMediaAction(action)
                },
                onSelectMusicRequest = {
                    musicFolderPickerLauncher.launch(null)
                },
                onToggleMicMute = { muted ->
                    engine.setMicMuted(muted)
                },
                onSelectAudioRoute = { route ->
                    engine.setAudioRoute(route)
                },
                onSelectBluetoothDevice = { deviceId ->
                    engine.selectBluetoothDevice(deviceId)
                },
                onSetPeerVolume = { originId, volume ->
                    engine.setPeerVolume(originId, volume)
                },
                onSetLocalMusicVolume = { volume ->
                    engine.setLocalMusicVolume(volume)
                },
                onVolumeStep = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                        val vibrator = vibratorManager.defaultVibrator
                        vibrator.vibrate(VibrationEffect.createOneShot(15, 64)) // 15ms duration, ~25% amplitude
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        @Suppress("DEPRECATION")
                        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        vibrator.vibrate(VibrationEffect.createOneShot(15, 64))
                    } else {
                        @Suppress("DEPRECATION")
                        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        vibrator.vibrate(15) // No amplitude control on very old APIs
                    }
                },
                onRescan = {
                    engine.rescan()
                },
                onRunSpeedTest = { originId ->
                    engine.runSpeedTest(originId)
                },
                onScanQrCodeRequest = {
                    qrScannerLauncher.launch(ScanOptions().apply {
                        setOrientationLocked(false)
                        setPrompt("Scan PeerSync QR Code")
                    })
                }
            )
        }
    }
    
    private fun updatePermissionStates() {
        locationPermissionGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        microphonePermissionGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        cameraPermissionGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        notificationsPermissionGranted.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Always granted on older versions
        }
        
        batteryOptimizationExempt.value = isBatteryOptimizationExempt()
    }
    
    private fun isBatteryOptimizationExempt(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return powerManager.isIgnoringBatteryOptimizations(packageName)
        }
        return false
    }
    
    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            batteryOptimizationLauncher.launch(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to open battery optimization settings: ${e.message}")
        }
    }
}
