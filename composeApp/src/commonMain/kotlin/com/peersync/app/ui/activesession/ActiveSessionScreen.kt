package com.peersync.app.ui.activesession

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peersync.app.model.AudioRoute
import com.peersync.app.model.MediaAction
import com.peersync.app.model.PeerDevice
import com.peersync.app.model.SessionInfo
import com.peersync.app.ui.util.generateQrBitmap

import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveSessionScreen(
    sessionInfo: SessionInfo?,
    isGroupOwner: Boolean,
    myOriginId: Byte,
    isMicMuted: Boolean,
    audioRoute: AudioRoute,
    peerVolumes: Map<Byte, Float>,
    speedTestResult: String = "",
    onDisconnect: () -> Unit,
    onMediaControl: (MediaAction) -> Unit,
    onSelectMusicRequest: () -> Unit,
    onToggleMicMute: (Boolean) -> Unit,
    onSelectAudioRoute: (AudioRoute) -> Unit,
    onSetPeerVolume: (Byte, Float) -> Unit,
    onSetLocalMusicVolume: (Float) -> Unit,
    onVolumeStep: () -> Unit,
    onRunSpeedTest: (Byte) -> Unit = {}
) {
    var showQrCodeDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(sessionInfo?.sessionName ?: "Active Session", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (isGroupOwner) "Role: Group Owner | PIN: ${sessionInfo?.pin ?: "------"}" else "Role: Client Spoke",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Disconnect")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "Connected Peers (${sessionInfo?.members?.size ?: 0})",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            val members = sessionInfo?.members ?: emptyList()
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(members) { peer ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (peer.isSpeaking) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            var lastHapticValue by remember(peer.originId) { mutableStateOf(peerVolumes[peer.originId] ?: 1.0f) }

                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        color = if (peer.isSpeaking) Color(0xFF4CAF50) else Color.Gray,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = peer.deviceName.take(1).uppercase(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(peer.deviceName, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (peer.isGroupOwner) "GO" else "Peer ID ${peer.originId}",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )

                            if (peer.originId != myOriginId) {
                                val currentVol = peerVolumes[peer.originId] ?: 1.0f
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Volume: ${(currentVol * 100).roundToInt()}%", fontSize = 10.sp)
                                Slider(
                                    value = currentVol,
                                    onValueChange = { newValue ->
                                        onSetPeerVolume(peer.originId, newValue)
                                        if (newValue != lastHapticValue) {
                                            onVolumeStep()
                                            lastHapticValue = newValue
                                        }
                                    },
                                    valueRange = 0f..2f,
                                    steps = 39,
                                    modifier = Modifier.fillMaxWidth().height(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Speed Test Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Speed Test", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    
                    // Show results or testing state
                    if (speedTestResult.isNotEmpty()) {
                        Text(
                            text = speedTestResult,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                    
                    // Speed test buttons for each peer
                    val peers = sessionInfo?.members?.filter { it.originId != myOriginId } ?: emptyList()
                    if (peers.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            peers.forEach { peer ->
                                Button(
                                    onClick = { onRunSpeedTest(peer.originId) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                                    )
                                ) {
                                    Text("Test ${peer.deviceName.take(3)}", fontSize = 10.sp)
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "No other peers connected",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Audio Controls", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (isMicMuted) "Microphone Muted" else "Microphone Active")
                        Switch(
                            checked = isMicMuted,
                            onCheckedChange = { onToggleMicMute(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Audio Route:", fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AudioRoute.values().forEach { route ->
                            FilterChip(
                                selected = audioRoute == route,
                                onClick = { onSelectAudioRoute(route) },
                                label = { Text(route.displayName) }
                            )
                        }
                    }
                    
                    // Show QR code button for host
                    if (isGroupOwner) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showQrCodeDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Text("Show QR Code / PIN")
                        }
                    }
                }
            }
            if (myOriginId == 0.toByte()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Shared Music Controls", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onSelectMusicRequest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Select Music Folder")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            IconButton(onClick = { onMediaControl(MediaAction.SKIP_PREVIOUS) }) { Text("PREV") }
                            IconButton(onClick = { onMediaControl(MediaAction.PLAY) }) { Text("PLAY") }
                            IconButton(onClick = { onMediaControl(MediaAction.PAUSE) }) { Text("PAUSE") }
                            IconButton(onClick = { onMediaControl(MediaAction.SKIP_NEXT) }) { Text("NEXT") }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        var musicVolume by remember { mutableStateOf(1.0f) }
                        Text("Music Volume: ${(musicVolume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = musicVolume,
                            onValueChange = { 
                                musicVolume = it
                                onSetLocalMusicVolume(it)
                            },
                            valueRange = 0f..3f,
                            steps = 59,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
    
    // QR Code Dialog
    if (showQrCodeDialog && isGroupOwner && sessionInfo != null) {
        val qrPayload = remember(sessionInfo.sessionName, sessionInfo.pin) {
            "peersync://join?session=${sessionInfo.sessionName}&pin=${sessionInfo.pin}"
        }
        val qrBitmap = remember(qrPayload) {
            generateQrBitmap(qrPayload, 512)
        }
        
        AlertDialog(
            onDismissRequest = { showQrCodeDialog = false },
            title = { Text("Invite Guests") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Share this QR code or PIN with guests to join:", modifier = Modifier.padding(bottom = 16.dp))
                    
                    // Render actual QR code or fallback to placeholder
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "QR Code for joining ${sessionInfo.sessionName}",
                            modifier = Modifier
                                .size(300.dp)
                                .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(300.dp)
                                .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                .background(Color.White, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("QR Code\n(rendering failed)", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // PIN Display
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("PIN", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                text = sessionInfo.pin,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Session: ${sessionInfo.sessionName}",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showQrCodeDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
