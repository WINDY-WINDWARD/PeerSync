package com.peersync.app.ui.sessionlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peersync.app.model.ConnectionState
import com.peersync.app.model.DiscoveredSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    discoveredSessions: List<DiscoveredSession>,
    connectionState: ConnectionState,
    onCreateSession: (sessionName: String) -> Unit,
    onJoinSession: (session: DiscoveredSession, pin: String) -> Unit,
    onRescan: () -> Unit,
    onScanQrCodeRequest: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedSession by remember { mutableStateOf<DiscoveredSession?>(null) }
    var newSessionName by remember { mutableStateOf("PeerSync Intercom") }
    var pinInput by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("PeerSync Intercom", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Text("+ Create Session", fontWeight = FontWeight.Bold)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Connection Status Banner
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Status: ", fontWeight = FontWeight.Bold)
                    Text(connectionState.name, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Discovered Sessions",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = onScanQrCodeRequest,
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("QR", fontSize = 12.sp)
                    }
                    IconButton(onClick = onRescan) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                }
            }

            if (discoveredSessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Scanning for nearby sessions via Wi-Fi Direct...",
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(discoveredSessions) { session ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedSession = session },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(session.sessionName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("Host: ${session.deviceName}", fontSize = 12.sp, color = Color.Gray)
                                }
                                Button(
                                    onClick = { selectedSession = session },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Join")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Create Session Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Intercom Session") },
            containerColor = MaterialTheme.colorScheme.surface,
            text = {
                OutlinedTextField(
                    value = newSessionName,
                    onValueChange = { newSessionName = it },
                    label = { Text("Session Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    showCreateDialog = false
                    onCreateSession(newSessionName)
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Join Session PIN Dialog
    selectedSession?.let { session ->
        AlertDialog(
            onDismissRequest = { selectedSession = null },
            title = { Text("Join '${session.sessionName}'") },
            containerColor = MaterialTheme.colorScheme.surface,
            text = {
                Column {
                    Text("Enter 8-digit PIN provided by Group Owner:", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 8) pinInput = it },
                        label = { Text("PIN") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pin = pinInput
                        selectedSession = null
                        pinInput = ""
                        onJoinSession(session, pin)
                    },
                    enabled = pinInput.length == 8,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedSession = null }) { Text("Cancel") }
            }
        )
    }
}
