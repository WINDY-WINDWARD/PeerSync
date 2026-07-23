package com.peersync.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.peersync.app.model.*
import com.peersync.app.network.*
import com.peersync.app.security.PinManager
import com.peersync.app.service.PeerSyncService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

import com.peersync.app.audio.AudioBridge

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

    val wifiP2pController = WifiP2pController(context)
    val tcpControlPlane = TcpControlPlane()
    val udpDataPlane = UdpDataPlane()
    val audioBridge = AudioBridge(context)

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val discoveredSessions: StateFlow<List<DiscoveredSession>> = wifiP2pController.discoveredSessions
    val sessionInfo: StateFlow<SessionInfo?> = tcpControlPlane.sessionInfo

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _audioRoute = MutableStateFlow(AudioRoute.LOUDSPEAKER)
    val audioRoute: StateFlow<AudioRoute> = _audioRoute.asStateFlow()

    private var myDeviceName: String = "PeerDevice"
    private var myP2pAddress: String = "02:00:00:00:00:00"
    private var currentPin: String = ""
    private var audioSeqNum: Short = 0

    // Diagnostic frame counters (debug)
    private val outgoingFrames = java.util.concurrent.atomic.AtomicLong(0)
    private val incomingPackets = java.util.concurrent.atomic.AtomicLong(0)
    private var firstOutgoingLogged = false
    private var firstIncomingLogged = false
    private var isTcpConnecting = false

    init {
        wifiP2pController.registerReceiver()
        audioBridge.initialize()
        observeP2pState()
        observeControlPlaneMessages()
        observeGoLossEvents()
        observeAudioBridgeOutgoingFrames()
        observeUdpIncomingAudioPackets()
        observeStreamErrors()
        startFrameCounters()
    }

    private fun observeP2pState() {
        scope.launch {
            wifiP2pController.p2pState.collect { p2pState ->
                when (p2pState) {
                    is P2pState.GroupCreated -> {
                        if (_connectionState.value != ConnectionState.ConnectedGroupOwner) {
                            Log.d(TAG, "GroupCreated received. Transitioning to ConnectedGroupOwner.")
                            _connectionState.value = ConnectionState.ConnectedGroupOwner
                            audioBridge.setMyOriginId(0)
                            udpDataPlane.startGroupOwner(0)
                            audioBridge.start()
                        }
                    }
                    is P2pState.Connected -> {
                        val info = p2pState.info
                        if (info.isGroupOwner) {
                            if (_connectionState.value != ConnectionState.ConnectedGroupOwner) {
                                Log.d(TAG, "P2P Connected as GO. Transitioning to ConnectedGroupOwner.")
                                _connectionState.value = ConnectionState.ConnectedGroupOwner
                                audioBridge.setMyOriginId(0)
                                udpDataPlane.startGroupOwner(0)
                                audioBridge.start()
                            }
                        } else {
                            val goIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                            if (_connectionState.value != ConnectionState.ConnectedClient && !isTcpConnecting) {
                                isTcpConnecting = true
                                connectTcpToGroupOwner(goIp)
                            }
                        }
                    }
                    is P2pState.Error -> {
                        Log.e(TAG, "P2P Error: ${p2pState.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun connectTcpToGroupOwner(goIp: String) {
        tcpControlPlane.connectToGroupOwner(
            goIp = goIp,
            pin = currentPin,
            deviceName = myDeviceName,
            p2pAddress = myP2pAddress
        ) { success, error ->
            isTcpConnecting = false
            if (success) {
                val assignedId = tcpControlPlane.sessionInfo.value?.members?.find { it.deviceName == myDeviceName }?.originId ?: 1
                audioBridge.setMyOriginId(assignedId)
                udpDataPlane.startClient(assignedId, goIp)
                audioBridge.start()
                _connectionState.value = ConnectionState.ConnectedClient
            } else {
                Log.e(TAG, "Failed TCP join: $error")
                disconnect()
            }
        }
    }

    private fun observeControlPlaneMessages() {
        scope.launch {
            tcpControlPlane.incomingMessages.collect { msg ->
                when (msg) {
                    is ControlMessage.MemberListUpdate -> {
                        val members = msg.sessionInfo.members
                        members.forEach { peer ->
                            if (!peer.isGroupOwner && peer.ipAddress.isNotBlank()) {
                                udpDataPlane.updateClientDestination(peer.originId, peer.ipAddress)
                            }
                        }
                    }
                    is ControlMessage.DisconnectNotice -> {
                        udpDataPlane.removeClientDestination(msg.leavingOriginId)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeGoLossEvents() {
        scope.launch {
            tcpControlPlane.goLossEvent.collect { highestOriginId ->
                val myOriginId = tcpControlPlane.sessionInfo.value?.members?.find { it.deviceName == myDeviceName }?.originId ?: -1
                if (myOriginId == highestOriginId) {
                    Log.i(TAG, "GO loss detected! This device (ID $myOriginId) is highest remaining ID. Re-electing as GO...")
                    reElectAsGroupOwner()
                } else {
                    Log.i(TAG, "GO loss detected! ID $highestOriginId elected. Reconnecting as client...")
                    _connectionState.value = ConnectionState.Reconnecting
                }
            }
        }
    }

    private fun observeAudioBridgeOutgoingFrames() {
        scope.launch {
            audioBridge.outgoingFrames.collect { frame ->
                val myId = tcpControlPlane.sessionInfo.value?.members?.find { it.deviceName == myDeviceName }?.originId ?: 0
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
                udpDataPlane.sendAudioPacket(header, frame.payload)
            }
        }
    }

    private fun observeUdpIncomingAudioPackets() {
        scope.launch {
            udpDataPlane.incomingPackets.collect { packet ->
                if (!firstIncomingLogged) {
                    firstIncomingLogged = true
                    Log.i(TAG, "AUDIO FLOW: first incoming UDP packet (originId=${packet.header.originId}, flag=${packet.header.payloadFlag}, bytes=${packet.payload.size})")
                }
                incomingPackets.incrementAndGet()
                audioBridge.feedReceivedPacket(
                    originId = packet.header.originId,
                    flag = packet.header.payloadFlag,
                    payload = packet.payload
                )
            }
        }
    }

    private fun observeStreamErrors() {
        scope.launch {
            audioBridge.streamErrors.collect { errorMessage ->
                Log.e(TAG, "AUDIO ENGINE STREAM ERROR: $errorMessage — restarting audio")
                // AAudio streams can die when the Wi-Fi Direct network interface
                // changes (e.g. during GO re-election). Restart them automatically.
                audioBridge.stop()
                kotlinx.coroutines.delay(300)
                audioBridge.start()
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
                    Log.i(TAG, "AUDIO FLOW: outgoing frames=$out, incoming UDP packets=$inc")
                }
            }
        }
    }

    fun startDiscovery() {
        _connectionState.value = ConnectionState.Discovering
        wifiP2pController.startDiscovery()
    }

    fun createSession(sessionName: String, localDeviceName: String) {
        this.myDeviceName = localDeviceName
        val pin = PinManager.generatePin()
        val nonce = PinManager.generateNonce()
        this.currentPin = pin
        Log.i(TAG, "Created session '$sessionName' with PIN: $pin")

        PeerSyncService.startService(context)
        _connectionState.value = ConnectionState.Connecting

        wifiP2pController.startLocalService(sessionName, pin, nonce)
        tcpControlPlane.startServer(sessionName, pin, localDeviceName, myP2pAddress)
    }

    fun joinSession(session: DiscoveredSession, pin: String, localDeviceName: String) {
        this.myDeviceName = localDeviceName
        this.currentPin = pin

        PeerSyncService.startService(context)
        _connectionState.value = ConnectionState.Connecting
        wifiP2pController.connectToPeerAddress(session.deviceAddress)
    }

    private fun reElectAsGroupOwner() {
        val currentSession = tcpControlPlane.sessionInfo.value
        val sessionName = currentSession?.sessionName ?: "PeerSync Restored"
        val pin = currentSession?.pin ?: currentPin
        val nonce = PinManager.generateNonce()

        isTcpConnecting = false
        wifiP2pController.disconnect()
        tcpControlPlane.stop()
        udpDataPlane.stop()
        audioBridge.stop()

        _connectionState.value = ConnectionState.Connecting
        wifiP2pController.startLocalService(sessionName, pin, nonce)
        tcpControlPlane.startServer(sessionName, pin, myDeviceName, myP2pAddress)
    }

    fun sendAudioPacket(header: AudioPacketHeader, payload: ByteArray) {
        udpDataPlane.sendAudioPacket(header, payload)
    }

    fun setMicMuted(muted: Boolean) {
        _isMicMuted.value = muted
        audioBridge.setMicMuted(muted)
    }

    fun setAudioRoute(route: AudioRoute) {
        _audioRoute.value = route
        audioBridge.setAudioRoute(route)
    }

    fun disconnect() {
        isTcpConnecting = false
        audioBridge.stop()
        tcpControlPlane.stop()
        udpDataPlane.stop()
        wifiP2pController.disconnect()
        PeerSyncService.stopService(context)
        _connectionState.value = ConnectionState.Disconnected
        startDiscovery()
    }
}
