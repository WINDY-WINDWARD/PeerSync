package com.peersync.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.peersync.app.engine.PeerSyncEngine
import com.peersync.app.model.ControlMessage
import com.peersync.app.model.DiscoveredSession

class MainActivity : ComponentActivity() {

    private lateinit var engine: PeerSyncEngine

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            engine.startDiscovery()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = PeerSyncEngine.getInstance(this)

        val requiredPermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val allGranted = requiredPermissions.all {
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            engine.startDiscovery()
        } else {
            requestPermissionLauncher.launch(requiredPermissions.toTypedArray())
        }

        setContent {
            val connectionState by engine.connectionState.collectAsState()
            val sessionsMap by engine.discoveredSessions.collectAsState()
            val sessionInfo by engine.sessionInfo.collectAsState()

            App(
                connectionState = connectionState,
                discoveredSessions = sessionsMap.values.toList(),
                sessionInfo = sessionInfo,
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
                    val myId = sessionInfo?.members?.find { it.deviceName == Build.MODEL }?.originId ?: 0
                    engine.tcpControlPlane.broadcastMessage(ControlMessage.MediaControl(action, myId))
                },
                onRequestMediaHost = {
                    val myId = sessionInfo?.members?.find { it.deviceName == Build.MODEL }?.originId ?: 0
                    engine.tcpControlPlane.broadcastMessage(ControlMessage.MediaTokenRequest(myId))
                }
            )
        }
    }
}
