package com.peersync.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import com.peersync.app.model.*
import com.peersync.app.network.*
import com.peersync.app.security.PinManager
import com.peersync.app.security.PinValidationResult
import com.peersync.app.service.PeerSyncService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

import com.peersync.app.audio.AudioBridge
import com.peersync.app.audio.MediaHostManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class PeerSyncEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PeerSyncEngine"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: PeerSyncEngine? = null

        fun getInstance(context: Context): PeerSyncEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PeerSyncEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val wifiSocketController = WifiSocketController(context)
    val audioBridge = AudioBridge(context)
    val mediaHostManager = MediaHostManager(
        context,
        { payload ->
            // Suspend until C++ has enough free space in the 16kHz mix buffer.
            // This perfectly locks decoding pacing to the hardware audio clock!
            while (audioBridge.getLocalMusicFreeSpace() < payload.size) {
                kotlinx.coroutines.delay(10)
            }
            audioBridge.feedLocalMusic(payload)
        },
        { audioBridge.getLocalMusicFreeSpace() },
        { audioBridge.clearLocalMusicBuffers() }
    )

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _myOriginId = MutableStateFlow<Byte>(0)
    val myOriginId: StateFlow<Byte> = _myOriginId.asStateFlow()

    private val _discoveredSessions = MutableStateFlow<List<DiscoveredSession>>(emptyList())
    val discoveredSessions: StateFlow<List<DiscoveredSession>> = _discoveredSessions.asStateFlow()
    
    private val _sessionInfo = MutableStateFlow<SessionInfo?>(null)
    val sessionInfo: StateFlow<SessionInfo?> = _sessionInfo.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _audioRoute = MutableStateFlow(AudioRoute.LOUDSPEAKER)
    val audioRoute: StateFlow<AudioRoute> = _audioRoute.asStateFlow()

    private val _availableBluetoothDevices = MutableStateFlow<List<AudioDeviceModel>>(emptyList())
    val availableBluetoothDevices: StateFlow<List<AudioDeviceModel>> = _availableBluetoothDevices.asStateFlow()

    private val _selectedBluetoothDeviceId = MutableStateFlow<Int?>(null)
    val selectedBluetoothDeviceId: StateFlow<Int?> = _selectedBluetoothDeviceId.asStateFlow()

    private val _peerVolumes = MutableStateFlow<Map<Byte, Float>>(emptyMap())
    val peerVolumes: StateFlow<Map<Byte, Float>> = _peerVolumes.asStateFlow()

    private val _peerLatencies = MutableStateFlow<Map<Byte, Long>>(emptyMap())
    val peerLatencies: StateFlow<Map<Byte, Long>> = _peerLatencies.asStateFlow()

    // Music player state proxy from MediaHostManager
    val musicPlayerState: StateFlow<MusicPlayerState> get() = mediaHostManager.musicPlayerState

    private var myDeviceName: String = "PeerDevice"
    private var currentPin: String = ""
    private var audioSeqNum: Short = 0
    private var activeSessionName: String = ""
    private var activePin: String = ""

    // Speed test tracking: timestamp -> payload size for calculating RTT
    private val pendingSpeedTests = ConcurrentHashMap<Long, Int>()
    private var speedTestJob: Job? = null

    // Audio recovery: serializes stream restart operations to prevent coroutine conflicts
    private val audioRestartMutex = Mutex()

    // Disconnect flag: prevents reconnection fallback when user manually disconnects
    @Volatile private var manualDisconnectRequested = false

    // Session state management (replaces TcpControlPlane logic)
    private var nextOriginId: Byte = 1  // Host: next ID to assign to clients
    private var hostEndpointId: String? = null  // Client: endpoint ID of the host
    private var isHost: Boolean = false  // Track if this device is the host
    private val connectedClientEndpoints = mutableMapOf<String, Byte>()  // endpointId -> originId

    // Diagnostic frame counters (debug)
    private val outgoingFrames = java.util.concurrent.atomic.AtomicLong(0)
    private val incomingPackets = java.util.concurrent.atomic.AtomicLong(0)
    private var firstOutgoingLogged = false
    private var firstIncomingLogged = false

    init {
        audioBridge.initialize()
        observeNearbyState()
        observeControlPlaneMessages()
        observeEndpointDisconnects()
        observeMediaHostOwnership()
        observeAudioBridgeOutgoingFrames()
        observeNearbyIncomingAudioPackets()
        observeStreamErrors()
        observeDiscoveredSessions()
        startFrameCounters()
    }

    private fun observeNearbyState() {
        scope.launch {
            wifiSocketController.wifiSocketState.collect { socketState ->
                when (socketState) {
                    is WifiSocketState.HostingP2p -> {
                        if (_connectionState.value != ConnectionState.ConnectedGroupOwner) {
                            Log.d(TAG, "Started hosting session: ${socketState.sessionName}")
                            isHost = true
                            connectedClientEndpoints.clear()
                            _connectionState.value = ConnectionState.ConnectedGroupOwner
                            setMyOriginId(0)
                            
                            val existingSession = _sessionInfo.value
                            if (existingSession != null && existingSession.members.isNotEmpty()) {
                                // Recovery Mode: Preserve members, just update the Group Owner status
                                Log.d(TAG, "Recovering existing session state for Host Handoff")
                                val updatedMembers = existingSession.members.map {
                                    if (it.originId == _myOriginId.value) it.copy(isGroupOwner = true)
                                    else it.copy(isGroupOwner = false)
                                }
                                _sessionInfo.value = existingSession.copy(
                                    groupOwnerId = _myOriginId.value,
                                    members = updatedMembers
                                )
                                // Ensure new clients don't collide with existing origin IDs
                                nextOriginId = ((updatedMembers.maxOfOrNull { it.originId } ?: 0) + 1).toByte()
                            } else {
                                // Brand New Session: Create initial session info with just the host
                                nextOriginId = 1
                                val hostPeer = PeerDevice(
                                    originId = 0,
                                    deviceId = myDeviceName,
                                    deviceName = myDeviceName,
                                    isGroupOwner = true
                                )
                                _sessionInfo.value = SessionInfo(
                                    sessionName = socketState.sessionName,
                                    groupOwnerId = 0,
                                    pin = socketState.pin,
                                    saltNonce = "",
                                    members = listOf(hostPeer),
                                    mediaHostId = 0
                                )
                            }
                            
                            audioBridge.start()
                            startAutoSpeedTest()
                        }
                    }
                    is WifiSocketState.ConnectedClient -> {
                        if (hostEndpointId == null) {
                            Log.d(TAG, "Connected to host at: ${socketState.hostAddress}")
                            hostEndpointId = socketState.hostAddress
                            
                            // Send JoinRequest with PIN to the host
                            val joinRequest = ControlMessage.JoinRequest(
                                deviceName = myDeviceName,
                                deviceId = myDeviceName,  // Use device name as identifier (no mesh)
                                pin = currentPin
                            )
                            wifiSocketController.broadcastControlMessage(joinRequest)
                            Log.d(TAG, "Sent JoinRequest to host with PIN: $currentPin")
                        }
                    }
                    is WifiSocketState.Error -> {
                        Log.e(TAG, "WiFi Socket Error: ${socketState.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeControlPlaneMessages() {
        scope.launch {
            wifiSocketController.incomingMessages.collect { receivedMsg ->
                val msg = receivedMsg.message
                val senderEndpointId = receivedMsg.senderEndpointId

                when (msg) {
                    is ControlMessage.JoinRequest -> {
                        // Host receives JoinRequest from client (clients don't relay in star topology)
                        if (isHost) {
                            handleClientJoinRequest(msg, senderEndpointId)
                        }
                    }
                    is ControlMessage.MemberListUpdate -> {
                        _sessionInfo.value = msg.sessionInfo
                        // Register endpoint-to-originId mappings from the session info
                        msg.sessionInfo.members.forEach { peer ->
                            wifiSocketController.registerEndpointOriginId(peer.deviceId, peer.originId)
                        }
                        setMyOriginId(resolveMyOriginId(msg.sessionInfo))
                    }
                    is ControlMessage.DisconnectNotice -> {
                        Log.d(TAG, "Peer disconnected: originId=${msg.leavingOriginId}")
                    }
                    is ControlMessage.JoinResponse -> {
                        // Check if this response is for us
                        if (msg.targetDeviceId == myDeviceName && msg.success && msg.assignedOriginId != null) {
                            Log.d(TAG, "Join successful! Assigned originId: ${msg.assignedOriginId}")
                            setMyOriginId(msg.assignedOriginId)
                            _connectionState.value = ConnectionState.ConnectedClient
                            audioBridge.start()
                            startAutoSpeedTest()
                        } else if (msg.targetDeviceId != myDeviceName) {
                            Log.d(TAG, "JoinResponse not for us, ignoring (target=${msg.targetDeviceId}, mine=$myDeviceName)")
                        } else {
                            Log.e(TAG, "Join failed: ${msg.errorMessage}")
                            disconnect()
                        }
                    }
                    is ControlMessage.MediaTokenRequest -> {
                        // Host grants or denies media hosting permission
                        if (isHost) {
                            val requestingOriginId = msg.requestingOriginId
                            val currentSession = _sessionInfo.value
                            if (currentSession != null) {
                                Log.d(TAG, "Received media hosting request from originId=$requestingOriginId")
                                // Update session with new media host
                                val updatedSession = currentSession.copy(mediaHostId = requestingOriginId)
                                _sessionInfo.value = updatedSession
                                // Broadcast updated session to all clients
                                val memberListMsg = ControlMessage.MemberListUpdate(updatedSession)
                                wifiSocketController.broadcastControlMessage(memberListMsg)
                            }
                        }
                    }
                    is ControlMessage.SpeedTestPing -> {
                        Log.d(TAG, "Received SpeedTestPing from originId=${msg.senderOriginId}, timestamp=${msg.timestamp}")
                        // Immediately reply with SpeedTestPong
                        val pong = ControlMessage.SpeedTestPong(
                            senderOriginId = _myOriginId.value,
                            originalTimestamp = msg.timestamp
                        )
                        // Broadcast pong to all endpoints
                        wifiSocketController.broadcastControlMessage(pong)
                    }
                    is ControlMessage.SpeedTestPong -> {
                        val rttMs = System.currentTimeMillis() - msg.originalTimestamp
                        pendingSpeedTests.remove(msg.originalTimestamp)
                        Log.d(TAG, "Speed test completed: Ping=${rttMs}ms from originId=${msg.senderOriginId}")
                        
                        // Update peer latencies map
                        val currentMap = _peerLatencies.value.toMutableMap()
                        currentMap[msg.senderOriginId] = rttMs
                        _peerLatencies.value = currentMap
                    }
                    else -> {
                        Log.d(TAG, "Received message type: ${msg::class.simpleName}")
                    }
                }
            }
        }
    }

    private fun handleClientJoinRequest(request: ControlMessage.JoinRequest, senderEndpointId: String) {
        Log.d(TAG, "Host received JoinRequest from ${request.deviceName} (deviceId=${request.deviceId}) with PIN: ${request.pin}")
        val currentSession = _sessionInfo.value ?: return

        // Validate PIN
        val validation = PinManager.validatePin(request.pin, currentSession.pin, request.deviceId)
        when (validation) {
            is PinValidationResult.Success -> {
                val newClient = senderEndpointId
                
                // Check for rejoin: look for existing non-GO member with the same device name
                val existingMember = currentSession.members.firstOrNull { 
                    it.deviceName == request.deviceName && !it.isGroupOwner 
                }
                
                if (existingMember != null) {
                    // REJOIN PATH: Device is reconnecting with new endpoint
                    val reusingOriginId = existingMember.originId
                    Log.d(TAG, "Device ${request.deviceName} is rejoining (reusing originId=$reusingOriginId, old endpoint=${existingMember.deviceId}, new endpoint=$newClient)")
                    
                    // Step 1: Remove stale endpoint mapping FIRST (so late disconnect of old socket becomes no-op)
                    // Only close old endpoint if it's different from the new one (paranoia check: same socket re-join)
                    if (existingMember.deviceId != newClient) {
                        connectedClientEndpoints.remove(existingMember.deviceId)
                        wifiSocketController.unregisterEndpoint(existingMember.deviceId)
                        
                        // Step 2: Proactively close the zombie socket on the host
                        wifiSocketController.closeClientEndpoint(existingMember.deviceId)
                    }
                    
                    // Step 3: Register new endpoint with reused originId
                    wifiSocketController.registerEndpointOriginId(newClient, reusingOriginId)
                    connectedClientEndpoints[newClient] = reusingOriginId
                    
                    // Step 4: Update member's endpoint ID to the new connection
                    val updatedMembers = currentSession.members.map { member ->
                        if (member.originId == reusingOriginId) {
                            member.copy(deviceId = newClient)
                        } else {
                            member
                        }
                    }
                    val updatedSession = currentSession.copy(members = updatedMembers)
                    _sessionInfo.value = updatedSession
                    
                    // Step 5: Send JoinResponse with the reused originId
                    val response = ControlMessage.JoinResponse(
                        success = true,
                        assignedOriginId = reusingOriginId,
                        sessionName = currentSession.sessionName,
                        targetDeviceId = request.deviceId
                    )
                    wifiSocketController.sendControlMessage(response, newClient)
                    
                    // Step 6: Broadcast updated member list to all clients
                    val memberListMsg = ControlMessage.MemberListUpdate(updatedSession)
                    wifiSocketController.broadcastControlMessage(memberListMsg)
                    
                    Log.d(TAG, "Rejoin successful for ${request.deviceName} with reused originId=$reusingOriginId")
                } else {
                    // FRESH JOIN PATH: New device joining the session
                    // Assign next origin ID
                    val assignedId = synchronized(this) { nextOriginId++ }

                    Log.d(TAG, "PIN validated! Assigning originId=$assignedId to ${request.deviceName}")

                    // Register the endpoint
                    wifiSocketController.registerEndpointOriginId(newClient, assignedId)
                    connectedClientEndpoints[newClient] = assignedId

                    // Create new peer
                    val newPeer = PeerDevice(
                        originId = assignedId,
                        deviceId = newClient,
                        deviceName = request.deviceName,
                        isGroupOwner = false
                    )

                    // Update session with new member
                    val updatedMembers = currentSession.members.toMutableList()
                    updatedMembers.add(newPeer)
                    val updatedSession = currentSession.copy(members = updatedMembers)
                    _sessionInfo.value = updatedSession

                    // Send JoinResponse to client with targetDeviceId to route to the correct device
                    val response = ControlMessage.JoinResponse(
                        success = true,
                        assignedOriginId = assignedId,
                        sessionName = currentSession.sessionName,
                        targetDeviceId = request.deviceId
                    )
                    wifiSocketController.sendControlMessage(response, newClient)

                    // Broadcast updated member list to all clients
                    val memberListMsg = ControlMessage.MemberListUpdate(updatedSession)
                    wifiSocketController.broadcastControlMessage(memberListMsg)

                    Log.d(TAG, "Sent JoinResponse and MemberListUpdate to all endpoints")
                }
            }
            is PinValidationResult.InvalidPin -> {
                Log.w(TAG, "Invalid PIN from ${request.deviceName}. Attempts remaining: ${validation.attemptsRemaining}")
                val response = ControlMessage.JoinResponse(
                    success = false,
                    errorMessage = "Invalid PIN. ${validation.attemptsRemaining} attempts remaining.",
                    targetDeviceId = request.deviceId
                )
                wifiSocketController.sendControlMessage(response, senderEndpointId)
            }
            is PinValidationResult.RateLimited -> {
                val secs = (validation.cooldownRemainingMs / 1000) + 1
                Log.w(TAG, "Rate limited for ${request.deviceName}. Cooldown: ${secs}s")
                val response = ControlMessage.JoinResponse(
                    success = false,
                    errorMessage = "Too many failed attempts. Try again in ${secs}s.",
                    targetDeviceId = request.deviceId
                )
                wifiSocketController.sendControlMessage(response, senderEndpointId)
            }
        }
    }

    private fun resolveMyOriginId(info: SessionInfo?): Byte {
        if (info == null) return _myOriginId.value

        if (info.members.any { it.originId == _myOriginId.value }) {
            return _myOriginId.value
        }

        if (info.members.size == 1) {
            return info.members.first().originId
        }

        val nonGo = info.members.firstOrNull { !it.isGroupOwner }
        if (nonGo != null && _connectionState.value == ConnectionState.ConnectedClient) {
            return nonGo.originId
        }

        return if (_connectionState.value == ConnectionState.ConnectedGroupOwner) 0 else _myOriginId.value
    }

    private fun observeMediaHostOwnership() {
        scope.launch {
            combine(
                sessionInfo.map { it?.mediaHostId }.distinctUntilChanged(),
                myOriginId
            ) { hostId, myId ->
                hostId == myId
            }
                .distinctUntilChanged()
                .collect { amHost ->
                    if (!amHost) {
                        mediaHostManager.stopPlayback()
                    }
                }
        }
    }

    private var lastMusicSeqIndices = mutableMapOf<Byte, UShort>()

    private fun observeEndpointDisconnects() {
        scope.launch {
            wifiSocketController.endpointDisconnected.collect { endpointId ->
                Log.d(TAG, "Endpoint disconnected: $endpointId")
                
                if (isHost) {
                    // Host removes the disconnected client from member list
                    val currentSession = _sessionInfo.value ?: return@collect
                    val originId = connectedClientEndpoints.remove(endpointId)
                    
                    if (originId != null) {
                        Log.d(TAG, "Removing client with originId=$originId from session")
                        
                        // Remove the member from session info
                        val updatedMembers = currentSession.members.filter { it.originId != originId }
                        val updatedSession = currentSession.copy(members = updatedMembers)
                        _sessionInfo.value = updatedSession
                        
                        // Broadcast updated member list
                        val memberListMsg = ControlMessage.MemberListUpdate(updatedSession)
                        wifiSocketController.broadcastControlMessage(memberListMsg)
                        
                        // Emit disconnect notice
                        val disconnectMsg = ControlMessage.DisconnectNotice(originId)
                        wifiSocketController.broadcastControlMessage(disconnectMsg)
                    }
                } else {
                    // Client got disconnected from host - Initiate 5-minute fallback reconnection
                    if (endpointId == "host" || endpointId == hostEndpointId) {
                        // Guard: skip if user manually disconnected
                        if (manualDisconnectRequested) {
                            Log.d(TAG, "Manual disconnect — skipping reconnection fallback")
                            return@collect
                        }
                        
                        // Guard: skip if already attempting to reconnect (prevents duplicate fallback loops)
                        if (_connectionState.value == ConnectionState.Reconnecting) {
                            Log.d(TAG, "Already in Reconnecting state, skipping duplicate fallback loop")
                            return@collect
                        }
                        
                        Log.w(TAG, "Host disconnected! Initiating 5-minute fallback reconnection...")
                        
                        // Set reconnecting state
                        _connectionState.value = ConnectionState.Reconnecting
                        
                        // Clear active socket states but preserve session info
                        audioBridge.stop()
                        wifiSocketController.disconnect()
                        hostEndpointId = null
                        
                        // Play disconnect ping sound
                        try {
                            val resourceId = context.resources.getIdentifier("disconnect_ping", "raw", context.packageName)
                            if (resourceId != 0) {
                                val mediaPlayer = MediaPlayer.create(context, resourceId)
                                mediaPlayer.setOnCompletionListener { mp ->
                                    mp.release()
                                }
                                mediaPlayer.start()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to play disconnect ping: ${e.message}")
                        }
                        
                        // Start 5-minute fallback loop
                        scope.launch {
                            val fallbackStartTime = System.currentTimeMillis()
                            val fallbackTimeoutMs = 300000L  // 5 minutes
                            val retryIntervalMs = 5000L       // 5 seconds
                            
                            while (isActive && _connectionState.value == ConnectionState.Reconnecting) {
                                val elapsedMs = System.currentTimeMillis() - fallbackStartTime
                                
                                if (elapsedMs >= fallbackTimeoutMs) {
                                    // Timeout reached, disconnect fully
                                    Log.w(TAG, "5-minute fallback timeout reached. Disconnecting.")
                                    disconnect()
                                    break
                                }
                                
                                // Try to reconnect to host
                                Log.d(TAG, "Attempting to reconnect to host (${elapsedMs / 1000}s elapsed)...")
                                wifiSocketController.connectToHost(activeSessionName, activePin)
                                
                                // Wait before next retry
                                kotlinx.coroutines.delay(retryIntervalMs)
                            }
                            
                            // If successfully reconnected before timeout
                            if (_connectionState.value == ConnectionState.ConnectedClient) {
                                Log.i(TAG, "Successfully reconnected to host!")
                                audioBridge.start()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun observeAudioBridgeOutgoingFrames() {
        scope.launch {
            audioBridge.outgoingFrames.collect { frame ->
                val myId = _myOriginId.value
                val header = AudioPacketHeader(
                    originId = myId,
                    payloadFlag = frame.flag,
                    sequenceIndex = (audioSeqNum++).toUShort()
                )
                if (!firstOutgoingLogged) {
                    firstOutgoingLogged = true
                    Log.i(TAG, "AUDIO FLOW: first outgoing frame (originId=$myId, flag=${frame.flag}, bytes=${frame.payload.size})")
                }
                outgoingFrames.incrementAndGet()
                wifiSocketController.sendAudioPacket(header, frame.payload)
            }
        }
    }

    private fun observeNearbyIncomingAudioPackets() {
        scope.launch {
            wifiSocketController.incomingAudioPackets.collect { packet ->
                if (!firstIncomingLogged) {
                    firstIncomingLogged = true
                    Log.i(TAG, "AUDIO FLOW: first incoming WiFi Socket packet (originId=${packet.header.originId}, flag=${packet.header.payloadFlag}, bytes=${packet.payload.size})")
                }
                incomingPackets.incrementAndGet()

                // Drop packets that originated from this device — the host already
                // loopbacks music directly in the MediaHostManager lambda, and voice
                // self-echo is filtered in C++. Filtering here prevents double-play
                // when the host echoes our own packets back to us.
                if (packet.header.originId == _myOriginId.value) return@collect

                // Check music sequence ordering
                if (packet.header.payloadFlag == AudioPacketHeader.FLAG_MUSIC) {
                    val originId = packet.header.originId
                    val seq = packet.header.sequenceIndex
                    val lastSeq = lastMusicSeqIndices[originId]
                    if (lastSeq != null) {
                        val diff = (seq.toInt() - lastSeq.toInt()).toShort().toInt()
                        if (diff < 0 && diff > -30000) {
                            // Drop out of order packet for this specific originId
                            return@collect
                        }
                    }
                    lastMusicSeqIndices[originId] = seq
                }

                audioBridge.feedReceivedPacket(
                    originId = packet.header.originId,
                    flag = packet.header.payloadFlag,
                    payload = packet.payload
                )
                
                // Star topology: host automatically relays audio to other clients at socket layer
                // Clients never relay; they only send and receive
            }
        }
    }

    private fun observeStreamErrors() {
        scope.launch {
            audioBridge.streamErrors.collect { errorMessage ->
                Log.e(TAG, "AUDIO ENGINE STREAM ERROR: $errorMessage — attempting recovery")
                
                audioRestartMutex.withLock {
                    // Gate: only recover in connected states or reconnecting
                    val connState = connectionState.value
                    if (connState != ConnectionState.ConnectedGroupOwner && 
                        connState != ConnectionState.ConnectedClient && 
                        connState != ConnectionState.Reconnecting) {
                        Log.d(TAG, "Stream error in disconnected state — ignoring recovery attempt")
                        return@withLock
                    }

                    // Coalesce: skip if audio is already running (second error from same teardown after successful restart)
                    if (audioBridge.isAudioRunning()) {
                        Log.d(TAG, "Audio already running — skipping redundant restart")
                        return@withLock
                    }

                    // Bounded settle wait for Bluetooth
                    if (_audioRoute.value == AudioRoute.BLUETOOTH) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val selectedId = _selectedBluetoothDeviceId.value
                                val BT_TYPES = setOf(
                                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                                    AudioDeviceInfo.TYPE_HEARING_AID,
                                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                )
                                withTimeoutOrNull(2500L) {
                                    audioBridge.communicationDevice.first { dev ->
                                        dev != null && (selectedId == null || dev.id == selectedId) && dev.type in BT_TYPES
                                    }
                                } ?: Log.w(TAG, "Bluetooth device settle timeout — continuing anyway")
                            } else {
                                // API 30: wait for SCO connection state
                                withTimeoutOrNull(2500L) {
                                    audioBridge.scoState.first { it == AudioManager.SCO_AUDIO_STATE_CONNECTED }
                                } ?: Log.w(TAG, "SCO handshake timeout — continuing anyway")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error during Bluetooth settle wait: ${e.message}")
                        }
                    }
                    delay(300)

                    // Bounded retry: up to 3 attempts
                    var restartSuccess = false
                    for (attempt in 1..3) {
                        val success = audioBridge.restartStreams()
                        if (success) {
                            Log.i(TAG, "Audio stream restart succeeded on attempt $attempt")
                            restartSuccess = true
                            break
                        }
                        Log.w(TAG, "Audio stream restart failed on attempt $attempt — retrying...")
                        delay(500)
                    }
                    if (!restartSuccess) {
                        Log.e(TAG, "Audio stream restart failed after 3 attempts")
                    }
                }
            }
        }
    }

    private fun observeDiscoveredSessions() {
        scope.launch {
            wifiSocketController.discoveredSessions.collect { sessions ->
                _discoveredSessions.value = sessions
                Log.d(TAG, "Updated discovered sessions: ${sessions.size} found")
            }
        }
    }

    private fun startFrameCounters() {
        scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(2000)
                val out = outgoingFrames.get()
                val inc = incomingPackets.get()
                if (out > 0 || inc > 0) {
                    Log.i(TAG, "AUDIO FLOW: outgoing frames=$out, incoming WiFi Socket packets=$inc")
                }
            }
        }
    }

    fun startDiscovery() {
        _connectionState.value = ConnectionState.Discovering
        Log.d(TAG, "Starting Wi-Fi Direct session discovery")
        wifiSocketController.startDiscovery()
    }

    fun rescan() {
        _connectionState.value = ConnectionState.Discovering
        Log.d(TAG, "Rescanning for Wi-Fi Direct sessions")
        // Scan results will be handled by the discovery mechanism
    }

    fun createSession(sessionName: String, localDeviceName: String) {
        manualDisconnectRequested = false
        this.myDeviceName = localDeviceName
        val pin = PinManager.generatePin()
        this.currentPin = pin
        this.activeSessionName = sessionName
        this.activePin = pin
        Log.i(TAG, "Created session '$sessionName' with PIN: $pin")

        PeerSyncService.startService(context)
        _connectionState.value = ConnectionState.Connecting

        wifiSocketController.startHosting(sessionName, pin)
    }

    fun joinSession(session: DiscoveredSession, pin: String, localDeviceName: String) {
        manualDisconnectRequested = false
        this.myDeviceName = localDeviceName
        this.currentPin = pin
        this.activeSessionName = session.deviceAddress
        this.activePin = pin

        PeerSyncService.startService(context)
        _connectionState.value = ConnectionState.Connecting
        
        // WiFi Direct: connect to host by SSID (session.deviceAddress contains SSID)
        wifiSocketController.connectToHost(sessionName = session.deviceAddress, pin = pin)
    }

    fun sendAudioPacket(header: AudioPacketHeader, payload: ByteArray) {
        wifiSocketController.sendAudioPacket(header, payload)
    }

    fun setMicMuted(muted: Boolean) {
        _isMicMuted.value = muted
        audioBridge.setMicMuted(muted)
    }

    fun setPeerVolume(originId: Byte, volume: Float) {
        val newMap = _peerVolumes.value.toMutableMap()
        newMap[originId] = volume
        _peerVolumes.value = newMap
        audioBridge.setPeerVolume(originId, volume)
    }

    fun setLocalMusicVolume(volume: Float) {
        audioBridge.setLocalMusicGain(volume.coerceIn(0f, MAX_MUSIC_VOLUME))
    }

    fun setAudioRoute(route: AudioRoute) {
        _audioRoute.value = route
        audioBridge.setAudioRoute(route)
        
        // When Bluetooth is selected, fetch available devices
        if (route == AudioRoute.BLUETOOTH) {
            _availableBluetoothDevices.value = audioBridge.getAvailableBluetoothDevices()
        } else {
            // Clear Bluetooth devices when switching to other routes
            _availableBluetoothDevices.value = emptyList()
            _selectedBluetoothDeviceId.value = null
        }
    }

    fun selectBluetoothDevice(deviceId: Int) {
        _selectedBluetoothDeviceId.value = deviceId
        _audioRoute.value = AudioRoute.BLUETOOTH
        // Apply audio route to the specific Bluetooth device
        audioBridge.setAudioRoute(AudioRoute.BLUETOOTH, deviceId)
    }

    fun requestMediaHost() {
        val myId = _myOriginId.value
        if (sessionInfo.value?.mediaHostId == myId) {
            return
        }
        
        // If this device is the host, grant it to itself immediately
        if (isHost) {
            val currentSession = _sessionInfo.value
            if (currentSession != null) {
                Log.d(TAG, "Host self-granting media hosting permissions")
                val updatedSession = currentSession.copy(mediaHostId = myId)
                _sessionInfo.value = updatedSession
                // Broadcast updated session to all clients
                val memberListMsg = ControlMessage.MemberListUpdate(updatedSession)
                wifiSocketController.broadcastControlMessage(memberListMsg)
            }
        } else {
            // Send media token request to host (clients don't handle this directly)
            val message = ControlMessage.MediaTokenRequest(myId)
            wifiSocketController.broadcastControlMessage(message)
        }
    }

    fun selectAndPlayMusicFolder(uri: Uri) {
        if (_myOriginId.value == sessionInfo.value?.mediaHostId) {
            mediaHostManager.selectAndPlayFolder(uri)
        }
    }

    fun handleMediaAction(action: MediaAction) {
        if (_myOriginId.value == sessionInfo.value?.mediaHostId) {
            mediaHostManager.handleMediaAction(action)
        }
    }

    fun seekMusicTo(positionMs: Long) {
        if (_myOriginId.value == sessionInfo.value?.mediaHostId) {
            mediaHostManager.seekTo(positionMs)
        }
    }

    private fun restoreHostSession() {
        manualDisconnectRequested = false
        this.currentPin = activePin
        Log.i(TAG, "Restoring session '$activeSessionName' with cached PIN: $activePin")
        PeerSyncService.startService(context)
        _connectionState.value = ConnectionState.Connecting
        wifiSocketController.startHosting(activeSessionName, activePin)
    }

    fun disconnect() {
        manualDisconnectRequested = true
        mediaHostManager.stopPlayback(clearPlaylist = true)
        audioBridge.stop()
        wifiSocketController.disconnect()
        PeerSyncService.stopService(context)
        
        // Cancel speed test loop
        speedTestJob?.cancel()
        speedTestJob = null
        
        // Clear session credentials to prevent auto-reconnect
        activeSessionName = ""
        activePin = ""
        
        // Reset session state
        isHost = false
        hostEndpointId = null
        nextOriginId = 1
        connectedClientEndpoints.clear()
        
        _connectionState.value = ConnectionState.Disconnected
        _sessionInfo.value = null
        startDiscovery()
    }

      fun runSpeedTest(targetOriginId: Byte) {
          val currentSession = _sessionInfo.value ?: return
          val targetPeer = currentSession.members.firstOrNull { it.originId == targetOriginId } ?: return
          
          val timestamp = System.currentTimeMillis()
          
          // Track this test
          pendingSpeedTests[timestamp] = 1  // Just track that a test is pending
          
          // Send speed test ping (lightweight: just timestamp)
          val ping = ControlMessage.SpeedTestPing(
              senderOriginId = _myOriginId.value,
              timestamp = timestamp
          )
          
          Log.d(TAG, "Starting speed test to originId=$targetOriginId with timestamp=$timestamp")
          
          // Broadcast ping to all peers (star topology: host routes to target)
          Log.d(TAG, "Broadcasting speed test ping via WiFi socket")
          wifiSocketController.broadcastControlMessage(ping)
          Log.d(TAG, "Speed test ping broadcasted successfully")
      }

     private fun startAutoSpeedTest() {
         // Cancel any existing speed test job to prevent duplicate loops
         speedTestJob?.cancel()
         
         // Launch a new speed test job and track it
         speedTestJob = scope.launch {
             while (isActive && _sessionInfo.value != null) {
                 val session = _sessionInfo.value
                 if (session != null) {
                     // Iterate through all members except self
                     session.members.forEach { peer ->
                         if (peer.originId != _myOriginId.value) {
                             runSpeedTest(peer.originId)
                         }
                     }
                 }
                 
                 // Wait 30 seconds before next round of tests
                 kotlinx.coroutines.delay(30000)
             }
         }
     }

    private fun setMyOriginId(originId: Byte) {
        _myOriginId.value = originId
        audioBridge.setMyOriginId(originId)
        mediaHostManager.setOriginId(originId)
    }
}
