package com.peersync.app.network

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.peersync.app.model.AudioPacketHeader
import com.peersync.app.model.ControlMessage
import com.peersync.app.model.DiscoveredSession
import com.peersync.app.security.PinManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed class WifiSocketState {
    object Idle : WifiSocketState()
    data class HostingP2p(val sessionName: String, val pin: String) : WifiSocketState()
    data class ConnectedClient(val hostAddress: String) : WifiSocketState()
    data class Error(val message: String) : WifiSocketState()
}

data class ReceivedControlMessage(
    val message: ControlMessage,
    val senderEndpointId: String
)

data class ReceivedAudioPacket(
    val header: AudioPacketHeader,
    val payload: ByteArray,
    val senderEndpointId: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ReceivedAudioPacket
        if (header != other.header) return false
        if (!payload.contentEquals(other.payload)) return false
        if (senderEndpointId != other.senderEndpointId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + senderEndpointId.hashCode()
        return result
    }
}

/**
 * Wi-Fi Direct P2P + TCP Socket-based network controller.
 * Replaces Google Nearby Connections with native Wi-Fi Direct and raw sockets.
 * Host: Creates Wi-Fi P2P group with PIN as WPA2 password, runs TCP server.
 * Client: Connects to Wi-Fi Direct network, connects to host socket server.
 * Data: Framed using [4-byte Length][1-byte Type][Payload]
 */
class WifiSocketController(private val context: Context) {

    companion object {
        private const val TAG = "WifiSocketController"
        private const val HOST_PORT = 5005
        private const val HOST_ADDRESS = "192.168.49.1"  // Wi-Fi Direct GO default IP
        private const val WIFI_DIRECT_SSID_PREFIX = "DIRECT-PS-"
        
        // Frame types
        private const val FRAME_TYPE_CONTROL: Byte = 0x01
        private const val FRAME_TYPE_AUDIO: Byte = 0x02
    }

    private val wifiP2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val connectivityManager: ConnectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val json = Json { ignoreUnknownKeys = true }
    // UDP data plane for real-time audio (control stays on this TCP plane).
    private val udpAudioPlane = UdpAudioPlane { packet ->
        _incomingAudioPackets.tryEmit(packet)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State flows (compatible with PeerSyncEngine expectations)
    private val _wifiSocketState = MutableStateFlow<WifiSocketState>(WifiSocketState.Idle)
    val wifiSocketState: StateFlow<WifiSocketState> = _wifiSocketState.asStateFlow()

    // Message flows
    private val _incomingMessages = MutableSharedFlow<ReceivedControlMessage>()
    val incomingMessages: SharedFlow<ReceivedControlMessage> = _incomingMessages.asSharedFlow()

    private val _incomingAudioPackets = MutableSharedFlow<ReceivedAudioPacket>(extraBufferCapacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val incomingAudioPackets: SharedFlow<ReceivedAudioPacket> = _incomingAudioPackets.asSharedFlow()

    private val _endpointDisconnected = MutableSharedFlow<String>()
    val endpointDisconnected: SharedFlow<String> = _endpointDisconnected.asSharedFlow()

    // Discovery flows
    private val _discoveredSessions = MutableStateFlow<List<DiscoveredSession>>(emptyList())
    val discoveredSessions: StateFlow<List<DiscoveredSession>> = _discoveredSessions.asStateFlow()
    
    private val wifiManager: WifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var scanBroadcastReceiver: BroadcastReceiver? = null
    private var isScanning = false

     // Host-side state
    private var serverSocket: ServerSocket? = null
    private val clientSockets = ConcurrentHashMap<String, ClientSocketHandler>()
    private var isHosting = false
    private var p2pChannel: WifiP2pManager.Channel? = null
    
    // Endpoint to Origin ID mapping (for routing)
    private val endpointToOriginId = mutableMapOf<String, Byte>()
    private val originIdToEndpoint = mutableMapOf<Byte, String>()
    
     // Client-side state
     private var clientSocket: Socket? = null
     private var hostSocketHandler: ClientSocketHandler? = null
     private var boundNetwork: Network? = null
     private var isNetworkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Wi-Fi network available: $network")
            boundNetwork = network
            scope.launch {
                connectToHostSocket(network)
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Wi-Fi network lost: $network")
            boundNetwork = null
            disconnectFromHost()
        }
    }

    /**
     * Host: Start hosting a session via Wi-Fi Direct P2P.
     * Creates a Group Owner that acts like a hotspot.
     */
    fun startHosting(sessionName: String, pin: String) {
        // Attempt to clean up any zombie P2P groups from previous crashes
        if (wifiP2pManager != null) {
            if (p2pChannel == null) {
                p2pChannel = wifiP2pManager!!.initialize(context, android.os.Looper.getMainLooper(), null)
            }
            wifiP2pManager!!.removeGroup(p2pChannel, null)
        }
        if (isHosting) {
            Log.w(TAG, "Already hosting")
            return
        }

        isHosting = true
        val ssid = "$WIFI_DIRECT_SSID_PREFIX$sessionName"
        
        Log.d(TAG, "Starting Wi-Fi P2P hosting: SSID=$ssid, PIN=$pin")
        
        scope.launch {
            try {
                // Start TCP server
                serverSocket = ServerSocket(HOST_PORT)
                Log.d(TAG, "TCP Server listening on port $HOST_PORT")
                
                // Start UDP audio data plane alongside the TCP control plane
                udpAudioPlane.startHost()
                
                // Listen for incoming client connections
                while (isHosting && serverSocket != null) {
                    try {
                        val clientSock = serverSocket!!.accept()
                        clientSock.tcpNoDelay = true  // Disable Nagle: control latency + no small-write stalls
                        val clientId = "${clientSock.inetAddress.hostAddress}:${clientSock.port}"
                        Log.d(TAG, "Client connected: $clientId")
                        
                        val handler = ClientSocketHandler(clientId, clientSock, this@WifiSocketController, isHost = true)
                        clientSockets[clientId] = handler
                        handler.start()
                    } catch (e: Exception) {
                        if (isHosting) {
                            Log.e(TAG, "Error accepting client connection: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket error: ${e.message}")
                _wifiSocketState.value = WifiSocketState.Error("Failed to start server: ${e.message}")
            }
        }

        // Update state
        _wifiSocketState.value = WifiSocketState.HostingP2p(sessionName, pin)

        // Create Wi-Fi P2P group with custom SSID and PIN
        if (wifiP2pManager != null) {
            scope.launch {
                try {
                    if (p2pChannel == null) {
                        p2pChannel = wifiP2pManager!!.initialize(context, Looper.getMainLooper(), null)
                    }
                    
                    val config = WifiP2pConfig.Builder()
                        .setNetworkName(ssid)
                        .setPassphrase(pin)
                        .build()
                    
                    wifiP2pManager!!.createGroup(p2pChannel!!, config, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d(TAG, "Wi-Fi P2P group created successfully")
                        }

                        override fun onFailure(reason: Int) {
                            Log.e(TAG, "Failed to create Wi-Fi P2P group: $reason")
                            _wifiSocketState.value = WifiSocketState.Error("Failed to create P2P group: $reason")
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating P2P group: ${e.message}")
                    _wifiSocketState.value = WifiSocketState.Error("Error: ${e.message}")
                }
            }
        }
    }

    /**
     * Client: Connect to the host's Wi-Fi Direct network and socket server.
     * First checks if the user has already manually connected via Android OS settings,
     * and if so, skips the WifiNetworkSpecifier and uses the existing connection.
     */
    fun connectToHost(sessionName: String, pin: String) {
        Log.d(TAG, "Connecting to host: SSID=$WIFI_DIRECT_SSID_PREFIX$sessionName, PIN=$pin")
        
        scope.launch {
            try {
                val ssid = "$WIFI_DIRECT_SSID_PREFIX$sessionName"
                
                // Check if already connected to the target SSID via manual OS connection
                val currentConnectionInfo = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // On Android 12+, use getConnectionInfo() 
                        wifiManager.connectionInfo
                    } else {
                        @Suppress("DEPRECATION")
                        wifiManager.connectionInfo
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get connection info: ${e.message}")
                    null
                }
                
                val currentSsid = currentConnectionInfo?.ssid?.removeSurrounding("\"") ?: ""
                val targetSsidWithoutQuotes = ssid.removeSurrounding("\"")
                
                if (currentSsid == targetSsidWithoutQuotes || currentSsid == ssid) {
                    // Already connected to the target network via manual OS connection
                    Log.d(TAG, "Already manually connected to $ssid via OS settings, using existing network")
                    
                    // Get the current active network
                    val activeNetwork = connectivityManager.activeNetwork
                    if (activeNetwork != null) {
                        connectToHostSocket(activeNetwork)
                    } else {
                        Log.w(TAG, "Active network is null, fallback to programmatic connection")
                        requestNetworkConnection(ssid, pin)
                    }
                } else {
                    // Not connected, use WifiNetworkSpecifier to programmatically connect
                    Log.d(TAG, "Current SSID=$currentSsid, target=$ssid, requesting programmatic connection")
                    requestNetworkConnection(ssid, pin)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in connectToHost: ${e.message}")
                _wifiSocketState.value = WifiSocketState.Error("Connection error: ${e.message}")
            }
        }
    }
    
     private fun unregisterNetworkCallbackInternal() {
         if (isNetworkCallbackRegistered) {
             try {
                 connectivityManager.unregisterNetworkCallback(networkCallback)
                 Log.d(TAG, "Unregistered network callback")
             } catch (e: Exception) {
                 Log.w(TAG, "Error unregistering network callback: ${e.message}")
             } finally {
                 isNetworkCallbackRegistered = false
             }
         }
     }

     /**
      * Internal: Request network connection using WifiNetworkSpecifier.
      */
     private fun requestNetworkConnection(ssid: String, pin: String) {
         try {
             // Unregister callback if already registered to prevent duplicate registration
             unregisterNetworkCallbackInternal()
             
             val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
                 .setSsid(ssid)
                 .setWpa2Passphrase(pin)
                 .build()
             
             val request = NetworkRequest.Builder()
                 .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                 .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                 .setNetworkSpecifier(specifier)
                 .build()
             
             connectivityManager.requestNetwork(request, networkCallback)
             isNetworkCallbackRegistered = true
             Log.d(TAG, "Network request submitted for $ssid")
         } catch (e: Exception) {
             Log.e(TAG, "Error requesting network: ${e.message}")
             _wifiSocketState.value = WifiSocketState.Error("Network error: ${e.message}")
             isNetworkCallbackRegistered = false
         }
     }

    /**
     * Internal: Connect socket to host once Wi-Fi is available.
     */
    private suspend fun connectToHostSocket(network: Network) {
        try {
            // Bind socket to the specific network
            val socket = Socket()
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(HOST_ADDRESS, HOST_PORT), 10000)
            socket.tcpNoDelay = true  // Disable Nagle on the control channel
            
            clientSocket = socket
            Log.d(TAG, "Connected to host socket at $HOST_ADDRESS:$HOST_PORT")
            
            _wifiSocketState.value = WifiSocketState.ConnectedClient(HOST_ADDRESS)
            
            val handler = ClientSocketHandler("host", socket, this, isHost = false)
            hostSocketHandler = handler
            handler.start()
            
            // Start UDP audio data plane bound to the same P2P network
            udpAudioPlane.startClient(network)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to host socket: ${e.message}")
            _wifiSocketState.value = WifiSocketState.Error("Failed to connect: ${e.message}")
        }
    }

    /**
     * Disconnect client from host.
     */
    private fun disconnectFromHost() {
        scope.launch {
            try {
                udpAudioPlane.stop()
                clientSocket?.close()
                clientSocket = null
                hostSocketHandler?.stop()
                hostSocketHandler = null
                unregisterNetworkCallbackInternal()
                _wifiSocketState.value = WifiSocketState.Idle
                _endpointDisconnected.emit("host")
                Log.d(TAG, "Disconnected from host")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting: ${e.message}")
            }
        }
    }

    /**
     * Helper to reliably tear down Wi-Fi P2P group with retries.
     */
    private fun tearDownP2pGroup() {
        val manager = wifiP2pManager ?: return
        val channel = p2pChannel ?: return
        
        try {
            manager.stopPeerDiscovery(channel, null)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping peer discovery: ${e.message}")
        }

        fun attemptRemove(attempt: Int) {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Wi-Fi P2P group successfully removed (attempt $attempt)")
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Failed to remove P2P group (attempt $attempt, reason=$reason)")
                    if (attempt < 3) {
                        scope.launch {
                            delay(400)
                            attemptRemove(attempt + 1)
                        }
                    }
                }
            })
        }

        attemptRemove(1)
        clearPersistentGroups()
    }

    private fun clearPersistentGroups() {
        val manager = wifiP2pManager ?: return
        val channel = p2pChannel ?: return
        try {
            val methods = manager.javaClass.methods
            for (method in methods) {
                if (method.name == "deletePersistentGroup") {
                    for (netId in 0..32) {
                        method.invoke(manager, channel, netId, null)
                    }
                }
            }
            Log.d(TAG, "Cleared persistent P2P groups")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear persistent groups: ${e.message}")
        }
    }

    /**
     * Stop hosting.
     */
    fun stopHosting() {
        isHosting = false
        try {
            udpAudioPlane.stop()
            serverSocket?.close()
            serverSocket = null
            clientSockets.values.forEach { it.stop() }
            clientSockets.clear()
            tearDownP2pGroup()
            _wifiSocketState.value = WifiSocketState.Idle
            Log.d(TAG, "Hosting stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping hosting: ${e.message}")
        }
    }

    /**
     * Start scanning for available PeerSync sessions (Wi-Fi Direct networks).
     * Periodically scans for DIRECT-PS-* SSIDs and emits discovered sessions.
     */
    fun startDiscovery() {
        if (isScanning) {
            Log.w(TAG, "Already scanning for sessions")
            return
        }
        
        isScanning = true
        Log.d(TAG, "Starting discovery scan")
        
        // Register broadcast receiver for scan results
        scanBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    handleScanResults()
                }
            }
        }
        
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scanBroadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(scanBroadcastReceiver, intentFilter)
        }
        
        // Start periodic scanning
        scope.launch {
            while (isScanning) {
                try {
                    wifiManager.startScan()
                    Log.d(TAG, "Wi-Fi scan initiated")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initiate Wi-Fi scan: ${e.message}")
                }
                delay(5000)  // Scan every 5 seconds
            }
        }
    }
    
    /**
     * Stop discovery scanning.
     */
    fun stopDiscovery() {
        if (!isScanning) {
            return
        }
        
        isScanning = false
        try {
            scanBroadcastReceiver?.let {
                context.unregisterReceiver(it)
            }
            scanBroadcastReceiver = null
            Log.d(TAG, "Discovery scan stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery: ${e.message}")
        }
    }
    
    /**
     * Process Wi-Fi scan results and extract PeerSync sessions.
     */
    private fun handleScanResults() {
        try {
            val scanResults = wifiManager.scanResults ?: return
            val sessions = mutableListOf<DiscoveredSession>()
            val nowNanos = android.os.SystemClock.elapsedRealtimeNanos()
            val maxAgeNanos = 15_000_000_000L // 15 seconds max age for valid AP beacons
            
            for (result in scanResults) {
                // Filter for DIRECT-PS-* SSIDs
                val ssid = result.SSID
                if (!ssid.isNullOrEmpty() && ssid.startsWith(WIFI_DIRECT_SSID_PREFIX)) {
                    // Ignore stale scan results cached in Android OS
                    val timestampNanos = result.timestamp * 1000L
                    val ageNanos = nowNanos - timestampNanos
                    if (result.timestamp > 0 && ageNanos > maxAgeNanos) {
                        Log.d(TAG, "Ignoring stale scan result for $ssid (age: ${ageNanos / 1_000_000_000L}s)")
                        continue
                    }
                    
                    // Extract session name by removing DIRECT-PS- prefix
                    val sessionName = ssid.removePrefix(WIFI_DIRECT_SSID_PREFIX)
                    
                    val discoveredSession = DiscoveredSession(
                        sessionName = sessionName,
                        deviceName = sessionName,
                        deviceAddress = sessionName,
                        token = "",
                        nonce = "",
                        lastSeenMs = System.currentTimeMillis()
                    )
                    sessions.add(discoveredSession)
                    Log.d(TAG, "Discovered session: $sessionName")
                }
            }
            
            _discoveredSessions.value = sessions
            Log.d(TAG, "Found ${sessions.size} PeerSync sessions")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing scan results: ${e.message}")
        }
    }

    /**
     * Send a control message to all connected peers.
     */
    fun sendControlMessage(message: ControlMessage, targetEndpointId: String? = null, excludeEndpointId: String? = null) {
        scope.launch {
            try {
                val jsonStr = json.encodeToString(message)
                val payload = jsonStr.toByteArray(Charsets.UTF_8)
                
                if (isHosting) {
                    // Host: broadcast to all clients (optionally excluding one)
                    clientSockets.values.forEach { handler ->
                        if (excludeEndpointId == null || handler.clientId != excludeEndpointId) {
                            handler.sendFrame(FRAME_TYPE_CONTROL, payload)
                        }
                    }
                } else {
                    // Client: send to host
                    hostSocketHandler?.sendFrame(FRAME_TYPE_CONTROL, payload)
                }
                
                Log.d(TAG, "Sent control message (size=${payload.size})")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending control message: ${e.message}")
            }
        }
    }

    /**
     * Send audio packet to all connected peers.
     */
    fun sendAudioPacket(header: AudioPacketHeader, payload: ByteArray, targetEndpointId: String? = null, excludeEndpointId: String? = null) {
        // Audio rides the UDP data plane (fire-and-forget). targetEndpointId/
        // excludeEndpointId are unused in the star topology — host broadcast
        // excludes the origin naturally at the relay (sender key) level.
        udpAudioPlane.sendAudio(header, payload)
    }

    /**
     * Handle incoming message from a client socket.
     */
    internal suspend fun handleIncomingFrame(clientId: String, frameType: Byte, framePayload: ByteArray) {
        try {
            when (frameType) {
                FRAME_TYPE_CONTROL -> {
                    val jsonStr = String(framePayload, Charsets.UTF_8)
                    val message = json.decodeFromString<ControlMessage>(jsonStr)
                    _incomingMessages.emit(ReceivedControlMessage(message, clientId))
                    
                    // If host, broadcast to other clients
                    if (isHosting) {
                        clientSockets.values.forEach { handler ->
                            if (handler.clientId != clientId) {
                                handler.sendFrame(FRAME_TYPE_CONTROL, framePayload)
                            }
                        }
                    }
                }
                FRAME_TYPE_AUDIO -> {
                    val header = AudioPacketHeader.fromByteArray(framePayload)
                    val payload = framePayload.drop(AudioPacketHeader.HEADER_SIZE).toByteArray()
                    // Legacy fallback only — audio normally arrives via UDP.
                    // tryEmit: a stalled collector must never block the socket read loop.
                    _incomingAudioPackets.tryEmit(ReceivedAudioPacket(header, payload, clientId))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming frame: ${e.message}")
        }
    }

    /**
     * Client socket handler - manages reading/writing to a single socket.
     */
    private inner class ClientSocketHandler(
        val clientId: String,
        private val socket: Socket,
        private val controller: WifiSocketController,
        private val isHost: Boolean
    ) {
        private var isRunning = false
        private val dataOut = DataOutputStream(socket.getOutputStream())
        private val dataIn = DataInputStream(socket.getInputStream())

        fun start() {
            isRunning = true
            scope.launch {
                try {
                    while (isRunning) {
                        // Read frame length
                        val frameLength = dataIn.readInt()
                        if (frameLength <= 0 || frameLength > 10_000_000) {
                            Log.e(TAG, "Invalid frame length: $frameLength")
                            break
                        }
                        
                        // Read frame type
                        val frameType = dataIn.readByte()
                        
                        // Read payload
                        val payload = ByteArray(frameLength)
                        dataIn.readFully(payload)
                        
                        // Handle the incoming frame
                        controller.handleIncomingFrame(clientId, frameType, payload)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.d(TAG, "Socket read ended: ${e.message}")
                    }
                } finally {
                    stop()
                }
            }
        }

        fun sendFrame(frameType: Byte, payload: ByteArray) {
            try {
                synchronized(dataOut) {
                    dataOut.writeInt(payload.size)  // 4-byte length
                    dataOut.writeByte(frameType.toInt())  // 1-byte type
                    dataOut.write(payload)  // Payload
                    dataOut.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending frame to $clientId: ${e.message}")
            }
        }

        fun stop() {
            isRunning = false
            try {
                socket.close()
                if (isHost) {
                    clientSockets.remove(clientId)
                    // Forget learned UDP addresses for this client (clientId is "ip:tcpPort")
                    udpAudioPlane.removeClientsWithIp(clientId.substringBeforeLast(':'))
                    scope.launch {
                        _endpointDisconnected.emit(clientId)
                    }
                    Log.d(TAG, "Client socket closed: $clientId")
                } else {
                    hostSocketHandler = null
                    scope.launch {
                        _endpointDisconnected.emit("host")
                    }
                    Log.d(TAG, "Host socket closed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket: ${e.message}")
            }
        }
    }

    /**
     * Get connected endpoints for compatibility.
     */
    fun getConnectedEndpoints(): Set<String> {
        return if (isHosting) {
            clientSockets.keys
        } else {
            if (hostSocketHandler != null) setOf("host") else emptySet()
        }
    }

    /**
     * Get endpoint for origin ID (stub for compatibility).
     */
    fun getEndpointForOriginId(originId: Byte): String? {
        return getConnectedEndpoints().firstOrNull()
    }

    /**
     * Register endpoint ID to origin ID mapping.
     */
    fun registerEndpointOriginId(endpointId: String, originId: Byte) {
        endpointToOriginId[endpointId] = originId
        originIdToEndpoint[originId] = endpointId
        Log.d(TAG, "Registered endpoint $endpointId -> originId $originId")
    }

    /**
     * Unregister endpoint ID.
     */
    fun unregisterEndpoint(endpointId: String) {
        val originId = endpointToOriginId.remove(endpointId)
        if (originId != null) {
            originIdToEndpoint.remove(originId)
            Log.d(TAG, "Unregistered endpoint $endpointId (was originId $originId)")
        }
    }

    /**
     * Close and remove a specific client socket handler (host side).
     * Used to proactively kill zombie connections when a device rejoins.
     */
    fun closeClientEndpoint(endpointId: String) {
        if (isHosting) {
            val handler = clientSockets.remove(endpointId)
            if (handler != null) {
                Log.d(TAG, "Closing zombie endpoint: $endpointId")
                handler.stop()
            }
        }
    }

    /**
     * Broadcast control message to all connected endpoints.
     */
    fun broadcastControlMessage(message: ControlMessage) {
        sendControlMessage(message)
    }

    /**
     * Disconnect from host (client) or stop hosting (host).
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        scope.launch {
            try {
                if (isHosting) {
                    // Stop hosting: close all client connections
                    udpAudioPlane.stop()
                    clientSockets.values.forEach { handler ->
                        handler.stop()
                    }
                    clientSockets.clear()
                    
                    // Close server socket
                    serverSocket?.close()
                    serverSocket = null
                    
                    // Tear down Wi-Fi P2P group
                    tearDownP2pGroup()
                    
                    isHosting = false
                    _wifiSocketState.value = WifiSocketState.Idle
                    Log.d(TAG, "Stopped hosting")
                } else {
                    // Disconnect from host
                    udpAudioPlane.stop()
                    hostSocketHandler?.stop()
                    hostSocketHandler = null
                    clientSocket?.close()
                    clientSocket = null
                    
                    // Cancel Wi-Fi network request
                    unregisterNetworkCallbackInternal()
                    
                    boundNetwork = null
                    _wifiSocketState.value = WifiSocketState.Idle
                    Log.d(TAG, "Disconnected from host")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect: ${e.message}")
            }
        }
    }
}
