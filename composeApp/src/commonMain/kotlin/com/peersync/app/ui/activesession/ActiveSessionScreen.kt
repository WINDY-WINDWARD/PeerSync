package com.peersync.app.ui.activesession

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peersync.app.model.AudioRoute
import com.peersync.app.model.MediaAction
import com.peersync.app.model.PeerDevice
import com.peersync.app.model.SessionInfo

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
    onDisconnect: () -> Unit,
    onMediaControl: (MediaAction) -> Unit,
    onSelectMusicRequest: () -> Unit,
    onToggleMicMute: (Boolean) -> Unit,
    onSelectAudioRoute: (AudioRoute) -> Unit,
    onSetPeerVolume: (Byte, Float) -> Unit,
    onSetLocalMusicVolume: (Float) -> Unit,
    onVolumeStep: () -> Unit
) {
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

            // Audio Controls Card
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
}
