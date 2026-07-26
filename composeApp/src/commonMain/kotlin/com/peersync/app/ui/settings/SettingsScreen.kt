package com.peersync.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    locationPermissionGranted: Boolean,
    microphonePermissionGranted: Boolean,
    cameraPermissionGranted: Boolean,
    notificationsPermissionGranted: Boolean,
    batteryOptimizationExempt: Boolean,
    onGrantLocationPermission: () -> Unit,
    onGrantMicrophonePermission: () -> Unit,
    onGrantCameraPermission: () -> Unit,
    onGrantNotificationsPermission: () -> Unit,
    onGrantBatteryOptimizationExemption: () -> Unit,
    onBackPressed: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permissions Section
            item {
                Text(
                    text = "Permissions",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            
            // Location Permission
            item {
                PermissionCard(
                    name = "Location / Nearby Wi-Fi Devices",
                    isGranted = locationPermissionGranted,
                    onGrantClick = onGrantLocationPermission
                )
            }
            
            // Microphone Permission
            item {
                PermissionCard(
                    name = "Microphone",
                    isGranted = microphonePermissionGranted,
                    onGrantClick = onGrantMicrophonePermission
                )
            }
            
            // Camera Permission
            item {
                PermissionCard(
                    name = "Camera",
                    isGranted = cameraPermissionGranted,
                    onGrantClick = onGrantCameraPermission
                )
            }
            
            // Notifications Permission
            item {
                PermissionCard(
                    name = "Notifications",
                    isGranted = notificationsPermissionGranted,
                    onGrantClick = onGrantNotificationsPermission
                )
            }
            
            // Battery Optimization
            item {
                PermissionCard(
                    name = "Battery Optimization Exemption",
                    isGranted = batteryOptimizationExempt,
                    onGrantClick = onGrantBatteryOptimizationExemption,
                    description = "Required for continuous audio sync when screen is off"
                )
            }
            
            // Information Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "About Permissions",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = "PeerSync requires these permissions to function:\n\n" +
                                    "• Location: To enable Wi-Fi Direct peer discovery\n" +
                                    "• Microphone: To capture and transmit audio\n" +
                                    "• Camera: For video features\n" +
                                    "• Notifications: To keep you informed of connection status\n" +
                                    "• Battery Exemption: To prevent system sleep during active sessions\n\n" +
                                    "Granted permissions cannot be revoked from this screen.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    name: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit,
    description: String? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (description != null) {
                    Text(
                        text = description,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            
            if (isGranted) {
                // Green checkmark for granted permission
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Granted",
                    tint = Color.Green,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                // Deny button for not granted permission
                Button(
                    onClick = onGrantClick,
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Grant", fontSize = 12.sp)
                }
            }
        }
    }
}
