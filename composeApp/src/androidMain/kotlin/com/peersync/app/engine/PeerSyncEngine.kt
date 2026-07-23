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

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val discoveredSessions: StateFlow<List<DiscoveredSession>> = wifiP2pController.discoveredSessions
    val sessionInfo: StateFlow<SessionInfo?> = tcpControlPlane.sessionInfo

    private var myDeviceName: String = "PeerDevice"
    private var myP2pAddress: String = "02:00:00:00:00:00"
    private var currentPin: String = ""

    init {
        wifiP2pController.registerReceiver()
        observeP2pState()
        observeControlPlaneMessages()
        observeGoLossEvents()
    }

    private fun observeP2pState() {
        scope.launch {
            wifiP2pController.p2pState.collect { p2pState ->
                when (p2pState) {
                    is P2pState.GroupCreated -> {
                        Log.d(TAG, "GroupCreated received. Transitioning to ConnectedGroupOwner.")
                        _connectionState.value = ConnectionState.ConnectedGroupOwner
                        udpDataPlane.startGroupOwner(0)
                    }
                    is P2pState.Connected -> {
                        val info = p2pState.info
                        if (info.isGroupOwner) {
                            _connectionState.value = ConnectionState.ConnectedGroupOwner
                            udpDataPlane.startGroupOwner(0)
                        } else {
                            val goIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                            if (_connectionState.value != ConnectionState.ConnectedClient) {
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
            if (success) {
                val assignedId = tcpControlPlane.sessionInfo.value?.members?.find { it.deviceName == myDeviceName }?.originId ?: 1
                udpDataPlane.startClient(assignedId, goIp)
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

        wifiP2pController.disconnect()
        tcpControlPlane.stop()
        udpDataPlane.stop()

        _connectionState.value = ConnectionState.Connecting
        wifiP2pController.startLocalService(sessionName, pin, nonce)
        tcpControlPlane.startServer(sessionName, pin, myDeviceName, myP2pAddress)
    }

    fun sendAudioPacket(header: AudioPacketHeader, payload: ByteArray) {
        udpDataPlane.sendAudioPacket(header, payload)
    }

    fun disconnect() {
        tcpControlPlane.stop()
        udpDataPlane.stop()
        wifiP2pController.disconnect()
        PeerSyncService.stopService(context)
        _connectionState.value = ConnectionState.Disconnected
    }
}
