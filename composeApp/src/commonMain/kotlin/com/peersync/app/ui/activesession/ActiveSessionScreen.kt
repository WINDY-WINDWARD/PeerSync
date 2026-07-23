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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveSessionScreen(
    sessionInfo: SessionInfo?,
    isGroupOwner: Boolean,
    isMicMuted: Boolean,
    audioRoute: AudioRoute,
    onDisconnect: () -> Unit,
    onMediaControl: (MediaAction) -> Unit,
    onRequestMediaHost: () -> Unit,
    onToggleMicMute: (Boolean) -> Unit,
    onSelectAudioRoute: (AudioRoute) -> Unit
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

            // Shared Media Controls Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Shared Music Controls", fontWeight = FontWeight.Bold)
                        Button(onClick = onRequestMediaHost) {
                            Text("Request Host")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(onClick = { onMediaControl(MediaAction.SKIP_PREVIOUS) }) {
                            Text("⏮", fontSize = 24.sp)
                        }
                        IconButton(onClick = { onMediaControl(MediaAction.PLAY) }) {
                            Text("▶", fontSize = 24.sp)
                        }
                        IconButton(onClick = { onMediaControl(MediaAction.PAUSE) }) {
                            Text("⏸", fontSize = 24.sp)
                        }
                        IconButton(onClick = { onMediaControl(MediaAction.SKIP_NEXT) }) {
                            Text("⏭", fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}
