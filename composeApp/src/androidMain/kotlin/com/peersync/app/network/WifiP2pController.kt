package com.peersync.app.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Looper
import android.util.Log
import com.peersync.app.model.DiscoveredSession
import com.peersync.app.security.PinManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class P2pState {
    object Idle : P2pState()
    data class GroupCreated(val sessionName: String, val pin: String) : P2pState()
    data class Connected(val info: WifiP2pInfo) : P2pState()
    data class Error(val message: String) : P2pState()
}

class WifiP2pController(private val context: Context) {

    companion object {
        private const val TAG = "WifiP2pController"
        const val SERVICE_TYPE = "_peersync._tcp"
        const val SERVICE_NAME = "PeerSyncSession"
    }

    private val p2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _p2pState = MutableStateFlow<P2pState>(P2pState.Idle)
    val p2pState: StateFlow<P2pState> = _p2pState.asStateFlow()

    private val sessionsMap = mutableMapOf<String, DiscoveredSession>()
    private val _discoveredSessions = MutableStateFlow<List<DiscoveredSession>>(emptyList())
    val discoveredSessions: StateFlow<List<DiscoveredSession>> = _discoveredSessions.asStateFlow()

    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var localServiceInfo: WifiP2pDnsSdServiceInfo? = null

    init {
        channel = p2pManager?.initialize(context, Looper.getMainLooper(), null)
    }

    fun registerReceiver() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            _p2pState.value = P2pState.Error("Wi-Fi Direct is disabled")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        requestPeersCheck()
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            requestConnectionInfo()
                        } else {
                            if (_p2pState.value is P2pState.Connected) {
                                _p2pState.value = P2pState.Idle
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
    }

    @SuppressLint("MissingPermission")
    private fun requestPeersCheck() {
        channel?.let { ch ->
            p2pManager?.requestPeers(ch) { peerList ->
                peerList.deviceList.forEach { device ->
                    val existing = sessionsMap[device.deviceAddress]
                    if (existing != null) {
                        val updated = existing.copy(
                            deviceName = device.deviceName ?: existing.deviceName
                        )
                        sessionsMap[device.deviceAddress] = updated
                    }
                }
            }
        }
    }

    fun unregisterReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
            receiver = null
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocalService(sessionName: String, pin: String, nonce: String) {
        val token = PinManager.computeSessionToken(pin, nonce)
        val record = mapOf(
            "sessionName" to sessionName,
            "token" to token,
            "nonce" to nonce
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(SERVICE_NAME, SERVICE_TYPE, record)
        localServiceInfo = serviceInfo

        channel?.let { ch ->
            p2pManager?.addLocalService(ch, serviceInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Local service added successfully")
                    createGroup(sessionName, pin)
                }

                override fun onFailure(reason: Int) {
                    _p2pState.value = P2pState.Error("Failed to add local service (reason: $reason)")
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    fun createGroup(sessionName: String, pin: String) {
        channel?.let { ch ->
            p2pManager?.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Wi-Fi P2P group created successfully")
                    stopDiscovery()
                    _p2pState.value = P2pState.GroupCreated(sessionName, pin)
                }

                override fun onFailure(reason: Int) {
                    if (reason == WifiP2pManager.BUSY) {
                        stopDiscovery()
                        _p2pState.value = P2pState.GroupCreated(sessionName, pin)
                    } else {
                        _p2pState.value = P2pState.Error("Failed to create group (reason: $reason)")
                    }
                }
            })
        }
    }

    private var discoveryJob: Job? = null

    private fun updateDiscoveredSession(session: DiscoveredSession) {
        val updated = session.copy(lastSeenMs = System.currentTimeMillis())
        sessionsMap[session.deviceAddress] = updated
        _discoveredSessions.value = sessionsMap.values.toList()
        Log.d(TAG, "Updated discoveredSessions list (count=${sessionsMap.size}): ${sessionsMap.values.map { it.sessionName }}")
    }

    private fun purgeStaleSessions() {
        val now = System.currentTimeMillis()
        val staleKeys = sessionsMap.filter { now - it.value.lastSeenMs > 10000L }.keys
        if (staleKeys.isNotEmpty()) {
            staleKeys.forEach { sessionsMap.remove(it) }
            _discoveredSessions.value = sessionsMap.values.toList()
            Log.d(TAG, "Purged ${staleKeys.size} stale session(s). Remaining: ${sessionsMap.size}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        channel?.let { ch ->
            p2pManager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "P2P peer discovery activated")
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "P2P peer discovery activation returned $reason")
                }
            })

            p2pManager?.setDnsSdResponseListeners(
                ch,
                { instanceName, registrationType, srcDevice ->
                    Log.d(TAG, "Discovered NSD service: $instanceName from ${srcDevice.deviceName}")
                    val existing = sessionsMap[srcDevice.deviceAddress]
                    val sessionName = existing?.sessionName ?: if (instanceName.isNotBlank() && instanceName != SERVICE_NAME) instanceName else "PeerSync Intercom"
                    val session = DiscoveredSession(
                        sessionName = sessionName,
                        deviceName = srcDevice.deviceName ?: "PeerSync Device",
                        deviceAddress = srcDevice.deviceAddress,
                        token = existing?.token ?: "",
                        nonce = existing?.nonce ?: ""
                    )
                    updateDiscoveredSession(session)
                },
                { fullDomainName, recordMap, srcDevice ->
                    Log.d(TAG, "Discovered NSD TXT record from ${srcDevice.deviceName}: $recordMap")
                    val sessionName = recordMap["sessionName"] ?: "PeerSync Session"
                    val token = recordMap["token"] ?: ""
                    val nonce = recordMap["nonce"] ?: ""
                    val session = DiscoveredSession(sessionName, srcDevice.deviceName ?: "Unknown Device", srcDevice.deviceAddress, token, nonce)
                    updateDiscoveredSession(session)
                }
            )

            val req = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
            serviceRequest = req
            p2pManager?.addServiceRequest(ch, req, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    p2pManager?.discoverServices(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d(TAG, "Service discovery started successfully")
                        }
                        override fun onFailure(reason: Int) {
                            Log.e(TAG, "Service discovery failed (reason: $reason)")
                        }
                    })

                    discoveryJob?.cancel()
                    discoveryJob = CoroutineScope(Dispatchers.Main).launch {
                        while (isActive) {
                            delay(4000)
                            p2pManager?.discoverPeers(ch, null)
                            p2pManager?.discoverServices(ch, null)
                            requestPeersCheck()
                            purgeStaleSessions()
                        }
                    }
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Add service request failed (reason: $reason)")
                }
            })
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        channel?.let { ch ->
            serviceRequest?.let { req ->
                p2pManager?.removeServiceRequest(ch, req, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeerAddress(deviceAddressStr: String) {
        val config = WifiP2pConfig().apply {
            deviceAddress = deviceAddressStr
            wps.setup = WpsInfo.PBC
        }
        channel?.let { ch ->
            p2pManager?.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Initiated connection to $deviceAddressStr (WPS PBC)")
                }

                override fun onFailure(reason: Int) {
                    _p2pState.value = P2pState.Error("Failed to connect to peer (reason: $reason)")
                }
            })
        }
    }

    fun requestConnectionInfo() {
        channel?.let { ch ->
            p2pManager?.requestConnectionInfo(ch) { info ->
                if (info != null && info.groupFormed) {
                    Log.d(TAG, "Connection info: GO=${info.isGroupOwner}, GO_IP=${info.groupOwnerAddress?.hostAddress}")
                    _p2pState.value = P2pState.Connected(info)
                }
            }
        }
    }

    fun disconnect() {
        sessionsMap.clear()
        _discoveredSessions.value = emptyList()
        channel?.let { ch ->
            localServiceInfo?.let { info ->
                p2pManager?.removeLocalService(ch, info, null)
                localServiceInfo = null
            }
            stopDiscovery()
            p2pManager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group removed successfully")
                    _p2pState.value = P2pState.Idle
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to remove group (reason: $reason)")
                    _p2pState.value = P2pState.Idle
                }
            })
        }
    }
}
