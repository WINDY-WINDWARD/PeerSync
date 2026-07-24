package com.peersync.app.network

import android.util.Log
import com.peersync.app.model.ControlMessage
import com.peersync.app.model.MediaAction
import com.peersync.app.model.PeerDevice
import com.peersync.app.model.SessionInfo
import com.peersync.app.security.PinManager
import com.peersync.app.security.PinValidationResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class TcpControlPlane {

    companion object {
        private const val TAG = "TcpControlPlane"
        const val TCP_PORT = 8888
        private const val HEARTBEAT_INTERVAL_MS = 1500L
        private const val HEARTBEAT_TIMEOUT_MS = 5000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isRunning = false

    private val _sessionInfo = MutableStateFlow<SessionInfo?>(null)
    val sessionInfo: StateFlow<SessionInfo?> = _sessionInfo.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<ControlMessage>()
    val incomingMessages: SharedFlow<ControlMessage> = _incomingMessages.asSharedFlow()

    private val _goLossEvent = MutableSharedFlow<Byte>() // Emits highest remaining originId on GO loss
    val goLossEvent: SharedFlow<Byte> = _goLossEvent.asSharedFlow()

    private val connectedClients = mutableMapOf<Byte, ClientHandler>()
    private var nextOriginId: Byte = 1

    private var heartbeatJob: Job? = null
    private var heartbeatCheckJob: Job? = null
    private var lastGoHeartbeatMs: Long = System.currentTimeMillis()

    private var myOriginId: Byte = 0
    private var targetPin: String = ""
    private var sessionName: String = ""

    private var connectionJob: Job? = null

    // ------------------------------------------------------------------------
    // GROUP OWNER (SERVER) MODE
    // ------------------------------------------------------------------------

    fun startServer(sessionName: String, pin: String, localDeviceName: String, localP2pAddress: String) {
        stop()
        this.sessionName = sessionName
        this.targetPin = pin
        this.myOriginId = 0
        this.isRunning = true

        val goDevice = PeerDevice(
            originId = 0,
            deviceAddress = localP2pAddress,
            deviceName = localDeviceName,
            ipAddress = "127.0.0.1",
            isGroupOwner = true
        )
        _sessionInfo.value = SessionInfo(
            sessionName = sessionName,
            groupOwnerId = 0,
            pin = pin,
            saltNonce = "",
            members = listOf(goDevice)
        )

        scope.launch {
            try {
                serverSocket = ServerSocket(TCP_PORT)
                Log.d(TAG, "TCP Server started on port $TCP_PORT")

                startServerHeartbeats()

                while (isRunning && isActive) {
                    val socket = serverSocket?.accept() ?: break
                    val clientIp = socket.inetAddress.hostAddress ?: ""
                    Log.d(TAG, "New client connection from $clientIp")
                    handleIncomingClientConnection(socket, clientIp)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Server socket error", e)
                }
            }
        }
    }

    private fun handleIncomingClientConnection(socket: Socket, clientIp: String) {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val firstLine = reader.readLine() ?: return@launch
                val message = json.decodeFromString<ControlMessage>(firstLine)

                if (message is ControlMessage.JoinRequest) {
                    val validation = PinManager.validatePin(message.pin, targetPin, message.deviceAddress)
                    when (validation) {
                        is PinValidationResult.Success -> {
                            val assignedId = synchronized(connectedClients) { nextOriginId++ }
                            val newPeer = PeerDevice(
                                originId = assignedId,
                                deviceAddress = message.deviceAddress,
                                deviceName = message.deviceName,
                                ipAddress = clientIp,
                                isGroupOwner = false
                            )

                            val response: ControlMessage = ControlMessage.JoinResponse(
                                success = true,
                                assignedOriginId = assignedId,
                                sessionName = sessionName
                            )
                            writer.println(json.encodeToString(response))

                            val handler = ClientHandler(assignedId, newPeer, socket, reader, writer)
                            synchronized(connectedClients) {
                                // Deduplicate: If this device was already connected (e.g. stale socket
                                // from brief disconnection), remove the old zombie connection first.
                                val staleEntries = connectedClients.entries.filter { 
                                    it.value.peerDevice.deviceAddress == message.deviceAddress 
                                }
                                staleEntries.forEach { 
                                    it.value.close()
                                    connectedClients.remove(it.key) 
                                }
                                
                                connectedClients[assignedId] = handler
                            }

                            updateAndBroadcastMemberList()
                            handler.startListening()
                        }
                        is PinValidationResult.InvalidPin -> {
                            val response: ControlMessage = ControlMessage.JoinResponse(
                                success = false,
                                errorMessage = "Invalid PIN. ${validation.attemptsRemaining} attempts remaining."
                            )
                            writer.println(json.encodeToString(response))
                            socket.close()
                        }
                        is PinValidationResult.RateLimited -> {
                            val secs = (validation.cooldownRemainingMs / 1000) + 1
                            val response: ControlMessage = ControlMessage.JoinResponse(
                                success = false,
                                errorMessage = "Too many failed attempts. Try again in ${secs}s."
                            )
                            writer.println(json.encodeToString(response))
                            socket.close()
                        }
                    }
                } else {
                    socket.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client handshake", e)
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun startServerHeartbeats() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isRunning && isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val heartbeat = ControlMessage.Heartbeat(0, System.currentTimeMillis())
                broadcastMessage(heartbeat)
            }
        }
    }

    private fun updateAndBroadcastMemberList() {
        val currentInfo = _sessionInfo.value ?: return
        val membersList = mutableListOf<PeerDevice>()
        // Add GO
        currentInfo.members.firstOrNull { it.isGroupOwner }?.let { membersList.add(it) }
        // Add clients
        synchronized(connectedClients) {
            membersList.addAll(connectedClients.values.map { it.peerDevice })
        }

        val activeHostId = currentInfo.mediaHostId
        val hostStillPresent = activeHostId != null && membersList.any { it.originId == activeHostId }
        val updatedInfo = currentInfo.copy(
            members = membersList,
            mediaHostId = if (hostStillPresent) activeHostId else null
        )
        _sessionInfo.value = updatedInfo
        val memberListMsg = ControlMessage.MemberListUpdate(updatedInfo)
        broadcastMessage(memberListMsg)
        // Also emit locally so the GO's PeerSyncEngine.observeControlPlaneMessages()
        // can populate udpDataPlane.clientIpMap with client IPs for outbound UDP.
        scope.launch { _incomingMessages.emit(memberListMsg) }
    }

    fun requestMediaHost(requestingId: Byte) {
        val currentInfo = _sessionInfo.value ?: return
        if (currentInfo.mediaHostId == requestingId) {
            return
        }
        
        if (serverSocket != null) { // I am GO
            val updatedInfo = currentInfo.copy(mediaHostId = requestingId)
            _sessionInfo.value = updatedInfo
            val memberListMsg = ControlMessage.MemberListUpdate(updatedInfo)
            broadcastMessage(memberListMsg)
            scope.launch { _incomingMessages.emit(memberListMsg) }
        } else { // I am Client
            sendMessage(ControlMessage.MediaTokenRequest(requestingId))
        }
    }

    fun broadcastMessage(message: ControlMessage) {
        val jsonStr = json.encodeToString(message)
        synchronized(connectedClients) {
            connectedClients.values.forEach { handler ->
                handler.sendRaw(jsonStr)
            }
        }
    }

    // ------------------------------------------------------------------------
    // CLIENT (SPOKE) MODE
    // ------------------------------------------------------------------------

    fun connectToGroupOwner(
        goIp: String,
        pin: String,
        deviceName: String,
        p2pAddress: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        stop()
        this.isRunning = true
        this.lastGoHeartbeatMs = System.currentTimeMillis()

        connectionJob?.cancel()
        connectionJob = scope.launch {
            var socket: Socket? = null
            var lastErr: Exception? = null

            for (attempt in 1..6) {
                try {
                    Log.d(TAG, "Attempting TCP connection to GO $goIp:$TCP_PORT (attempt $attempt/6)")
                    socket = Socket()
                    socket.connect(java.net.InetSocketAddress(goIp, TCP_PORT), 3000)
                    break
                } catch (e: Exception) {
                    lastErr = e
                    Log.w(TAG, "Attempt $attempt connecting to GO failed: ${e.message}")
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                    delay(500)
                }
            }

            if (socket == null) {
                Log.e(TAG, "Failed all attempts to connect to GO at $goIp", lastErr)
                onResult(false, lastErr?.localizedMessage ?: "Network error connecting to GO")
                return@launch
            }

            clientSocket = socket
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val joinReq = ControlMessage.JoinRequest(deviceName, p2pAddress, pin)
                writer.println(json.encodeToString<ControlMessage>(joinReq))

                val responseLine = reader.readLine()
                if (responseLine == null) {
                    onResult(false, "No response from Group Owner")
                    socket.close()
                    return@launch
                }

                val response = json.decodeFromString<ControlMessage>(responseLine) as? ControlMessage.JoinResponse
                if (response?.success == true) {
                    myOriginId = response.assignedOriginId ?: 1
                    val clientPeer = PeerDevice(
                        originId = myOriginId,
                        deviceAddress = p2pAddress,
                        deviceName = deviceName,
                        ipAddress = "127.0.0.1",
                        isGroupOwner = false
                    )
                    _sessionInfo.value = SessionInfo(
                        sessionName = response.sessionName ?: "PeerSync Intercom",
                        groupOwnerId = 0,
                        pin = pin,
                        saltNonce = "",
                        members = listOf(clientPeer)
                    )
                    onResult(true, null)

                    startClientHeartbeatMonitor()
                    startClientListening(reader)
                } else {
                    onResult(false, response?.errorMessage ?: "Connection rejected by GO")
                    socket.close()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to GO", e)
                onResult(false, e.localizedMessage ?: "Network error connecting to GO")
            }
        }
    }

    private fun startClientListening(reader: BufferedReader) {
        scope.launch {
            try {
                while (isRunning && isActive) {
                    val line = reader.readLine() ?: break
                    val msg = json.decodeFromString<ControlMessage>(line)

                    when (msg) {
                        is ControlMessage.Heartbeat -> {
                            lastGoHeartbeatMs = System.currentTimeMillis()
                        }
                        is ControlMessage.MemberListUpdate -> {
                            _sessionInfo.value = msg.sessionInfo
                        }
                        else -> {}
                    }
                    _incomingMessages.emit(msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Client socket read error", e)
            } finally {
                if (isRunning) {
                    handleGoDisconnect()
                }
            }
        }
    }

    private fun startClientHeartbeatMonitor() {
        heartbeatCheckJob?.cancel()
        heartbeatCheckJob = scope.launch {
            while (isRunning && isActive) {
                delay(1000L)
                val elapsed = System.currentTimeMillis() - lastGoHeartbeatMs
                if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, "GO Heartbeat timeout ($elapsed ms > $HEARTBEAT_TIMEOUT_MS ms)!")
                    handleGoDisconnect()
                    break
                }
            }
        }
    }

    private fun handleGoDisconnect() {
        val currentInfo = _sessionInfo.value ?: return
        val remainingMembers = currentInfo.members.filter { !it.isGroupOwner }
        val highestOriginId = remainingMembers.maxOfOrNull { it.originId } ?: -1

        if (highestOriginId >= 0) {
            scope.launch {
                _goLossEvent.emit(highestOriginId.toByte())
            }
        }
    }

    fun sendMessage(message: ControlMessage) {
        val jsonStr = json.encodeToString(message)
        scope.launch {
            try {
                clientSocket?.getOutputStream()?.let { out ->
                    val writer = PrintWriter(out, true)
                    writer.println(jsonStr)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending TCP message", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        heartbeatJob?.cancel()
        heartbeatCheckJob?.cancel()

        synchronized(connectedClients) {
            connectedClients.values.forEach { it.close() }
            connectedClients.clear()
        }

        try { serverSocket?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}

        serverSocket = null
        clientSocket = null
        _sessionInfo.value = null
        nextOriginId = 1
    }

    // Handler inner class for server client sockets
    private inner class ClientHandler(
        val originId: Byte,
        val peerDevice: PeerDevice,
        private val socket: Socket,
        private val reader: BufferedReader,
        private val writer: PrintWriter
    ) {
        fun startListening() {
            scope.launch {
                try {
                    while (isRunning && isActive) {
                        val line = reader.readLine() ?: break
                        val msg = json.decodeFromString<ControlMessage>(line)

                        when (msg) {
                            is ControlMessage.MediaTokenRequest -> {
                                val currentInfo = _sessionInfo.value
                                if (currentInfo != null) {
                                    if (currentInfo.mediaHostId == msg.requestingOriginId) {
                                        continue
                                    }
                                    val updatedInfo = currentInfo.copy(mediaHostId = msg.requestingOriginId)
                                    _sessionInfo.value = updatedInfo
                                    val memberListMsg = ControlMessage.MemberListUpdate(updatedInfo)
                                    broadcastMessage(memberListMsg)
                                    scope.launch { _incomingMessages.emit(memberListMsg) }
                                }
                            }
                            is ControlMessage.MediaControl,
                            is ControlMessage.MediaTokenGrant -> {
                                broadcastMessage(msg)
                            }
                            else -> {}
                        }
                        _incomingMessages.emit(msg)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Client $originId disconnected (${e.message})")
                } finally {
                    onClientDisconnected()
                }
            }
        }

        fun sendRaw(jsonStr: String) {
            try {
                writer.println(jsonStr)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to client $originId", e)
            }
        }

        private fun onClientDisconnected() {
            synchronized(connectedClients) {
                connectedClients.remove(originId)
            }
            close()
            updateAndBroadcastMemberList()
            scope.launch {
                _incomingMessages.emit(ControlMessage.DisconnectNotice(originId))
            }
        }

        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
