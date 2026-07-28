package com.peersync.app.ui.activesession

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peersync.app.model.AudioRoute
import com.peersync.app.model.AudioDeviceModel
import com.peersync.app.model.MediaAction
import com.peersync.app.model.PeerDevice
import com.peersync.app.model.SessionInfo
import com.peersync.app.model.ConnectionState
import com.peersync.app.ui.util.generateQrBitmap

import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveSessionScreen(
    connectionState: ConnectionState = ConnectionState.Disconnected,
    sessionInfo: SessionInfo?,
    isGroupOwner: Boolean,
    myOriginId: Byte,
    isMicMuted: Boolean,
    audioRoute: AudioRoute,
    peerVolumes: Map<Byte, Float>,
    peerLatencies: Map<Byte, Long> = emptyMap(),
    availableBluetoothDevices: List<AudioDeviceModel> = emptyList(),
    selectedBluetoothDeviceId: Int? = null,
    onDisconnect: () -> Unit,
    onMediaControl: (MediaAction) -> Unit,
    onSelectMusicRequest: () -> Unit,
    onToggleMicMute: (Boolean) -> Unit,
    onSelectAudioRoute: (AudioRoute) -> Unit,
    onSelectBluetoothDevice: (Int) -> Unit = {},
    onSetPeerVolume: (Byte, Float) -> Unit,
    onSetLocalMusicVolume: (Float) -> Unit,
    onVolumeStep: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    var showQrCodeDialog by remember { mutableStateOf(false) }
    var showBluetoothDeviceDropdown by remember { mutableStateOf(false) }
    var isAudioControlsExpanded by remember { mutableStateOf(false) }
    var isMusicControlsExpanded by remember { mutableStateOf(false) }
    
    Scaffold(
        containerColor = Color.Transparent,
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
                    IconButton(onClick = onOpenSettings) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                    }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Title
                Text(
                    text = "Connected Peers (${sessionInfo?.members?.size ?: 0})",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

            // Peers Grid - Takes up available space
            val members = sessionInfo?.members ?: emptyList()
            if (members.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(members) { peer ->
                        // Determine card color based on latency
                        val latency = peerLatencies[peer.originId]
                        val cardColor = when {
                            latency == null -> MaterialTheme.colorScheme.surface
                            latency < 100 -> Color(0xFF81C784) // Green
                            latency <= 200 -> Color(0xFFFFF176) // Yellow
                            else -> Color(0xFFE57373) // Red
                        }
                        
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = cardColor
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
                                
                                // Display latency if available
                                if (latency != null) {
                                    Text(
                                        text = "${latency}ms",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }

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
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No connected peers yet")
                }
            }

            // Bottom Control Cards - Scrollable
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Audio Controls Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Clickable Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isAudioControlsExpanded = !isAudioControlsExpanded }
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Audio Controls", fontWeight = FontWeight.Bold)
                            Icon(
                                imageVector = if (isAudioControlsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isAudioControlsExpanded) "Collapse" else "Expand"
                            )
                        }
                        
                        // Animated Content
                        AnimatedVisibility(
                            visible = isAudioControlsExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column {
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
                                
                                // Bluetooth Device Selection Dropdown
                                if (audioRoute == AudioRoute.BLUETOOTH && availableBluetoothDevices.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            onClick = { showBluetoothDeviceDropdown = !showBluetoothDeviceDropdown },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                        ) {
                                            val selectedDeviceName = if (selectedBluetoothDeviceId != null) {
                                                availableBluetoothDevices
                                                    .firstOrNull { it.id == selectedBluetoothDeviceId }
                                                    ?.productName ?: "Select Device"
                                            } else {
                                                "Select Bluetooth Device"
                                            }
                                            Text(selectedDeviceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        
                                        // Dropdown menu
                                        DropdownMenu(
                                            expanded = showBluetoothDeviceDropdown,
                                            onDismissRequest = { showBluetoothDeviceDropdown = false },
                                            modifier = Modifier.fillMaxWidth(0.9f)
                                        ) {
                                            availableBluetoothDevices.forEach { device ->
                                                DropdownMenuItem(
                                                    text = { Text(device.productName) },
                                                    onClick = {
                                                        onSelectBluetoothDevice(device.id)
                                                        showBluetoothDeviceDropdown = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                
                                // Show QR code button for host
                                if (isGroupOwner) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = { showQrCodeDialog = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text("Show QR Code / PIN")
                                    }
                                }
                            }
                        }
                    }
                }

                // Shared Music Controls Card (for owner)
                if (myOriginId == 0.toByte()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Clickable Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isMusicControlsExpanded = !isMusicControlsExpanded }
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Shared Music Controls", fontWeight = FontWeight.Bold)
                                Icon(
                                    imageVector = if (isMusicControlsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isMusicControlsExpanded) "Collapse" else "Expand"
                                )
                            }
                            
                            // Animated Content
                            AnimatedVisibility(
                                visible = isMusicControlsExpanded,
                                enter = expandVertically(),
                                exit = shrinkVertically()
                            ) {
                                Column {
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
                                        IconButton(onClick = { onMediaControl(MediaAction.SKIP_PREVIOUS) }) { 
                                            Text("PREV", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                                        }
                                        IconButton(onClick = { onMediaControl(MediaAction.PLAY) }) { 
                                            Text("PLAY", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                                        }
                                        IconButton(onClick = { onMediaControl(MediaAction.PAUSE) }) { 
                                            Text("PAUSE", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                                        }
                                        IconButton(onClick = { onMediaControl(MediaAction.SKIP_NEXT) }) { 
                                            Text("NEXT", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                                        }
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
                
                // Add bottom spacer to prevent content from being hidden
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Reconnecting Overlay
            if (connectionState == ConnectionState.Reconnecting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f))
                        .pointerInput(Unit) { /* Block all pointer events */ },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Connection Lost. Reconnecting...", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Waiting for host (up to 5 mins)", color = Color.LightGray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onDisconnect, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Text("Disconnect Now", color = Color.White)
                        }
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
            containerColor = MaterialTheme.colorScheme.surface,
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
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
}
