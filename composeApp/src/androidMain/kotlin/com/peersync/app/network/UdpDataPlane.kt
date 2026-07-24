package com.peersync.app.network

import android.util.Log
import com.peersync.app.model.AudioPacketHeader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

data class ReceivedAudioPacket(
    val header: AudioPacketHeader,
    val payload: ByteArray,
    val senderIp: String
)

class UdpDataPlane {

    companion object {
        private const val TAG = "UdpDataPlane"
        const val UDP_PORT = 8889
        // Receive buffer: must be >= header (4) + max music payload (1392) = 1396.
        // Use 1500 to also accommodate future voice packets and keep alignment.
        private const val MAX_PACKET_SIZE = 1500
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private var isRunning = false

    private val clientIpMap = ConcurrentHashMap<Byte, String>() // originId -> IP
    private val clientLastSeenMap = ConcurrentHashMap<Byte, Long>() // originId -> timestamp

    private val _incomingPackets = MutableSharedFlow<ReceivedAudioPacket>(extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<ReceivedAudioPacket> = _incomingPackets.asSharedFlow()

    private var isGroupOwner: Boolean = false
    private var myOriginId: Byte = 0
    private var targetGoIp: String? = null

    fun startGroupOwner(myOriginId: Byte = 0) {
        stop()
        this.isGroupOwner = true
        this.myOriginId = myOriginId
        this.isRunning = true

        scope.launch {
            try {
                socket = DatagramSocket(UDP_PORT)
                Log.d(TAG, "UDP Data Plane started as GO on port $UDP_PORT")
                listenLoop()
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "GO UDP socket error", e)
            }
        }
    }

    fun startClient(myOriginId: Byte, goIp: String) {
        stop()
        this.isGroupOwner = false
        this.myOriginId = myOriginId
        this.targetGoIp = goIp
        this.isRunning = true

        scope.launch {
            try {
                socket = DatagramSocket(UDP_PORT)
                Log.d(TAG, "UDP Data Plane started as Client on port $UDP_PORT")
                listenLoop()
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Client UDP socket error", e)
            }
        }
    }

    fun updateClientDestination(originId: Byte, ipAddress: String) {
        clientIpMap[originId] = ipAddress
    }

    fun removeClientDestination(originId: Byte) {
        clientIpMap.remove(originId)
        clientLastSeenMap.remove(originId)
    }

    private suspend fun listenLoop() {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)

        while (isRunning && scope.isActive) {
            try {
                val sock = socket ?: break
                sock.receive(packet)

                val length = packet.length
                if (length < AudioPacketHeader.HEADER_SIZE) continue

                val data = packet.data.copyOfRange(0, length)
                val senderIp = packet.address.hostAddress ?: ""
                val header = AudioPacketHeader.fromByteArray(data, 0)

                // 1. Ignore if loopback packet from self
                if (header.originId == myOriginId) continue

                // 2. Track keep-alive / liveness
                clientLastSeenMap[header.originId] = System.currentTimeMillis()

                val payload = data.copyOfRange(AudioPacketHeader.HEADER_SIZE, length)

                // 3. If GO, forward to all other connected clients
                if (isGroupOwner) {
                    forwardPacketToOtherClients(header.originId, data)
                }

                // 4. Emit to local audio pipeline if voice or music
                if (header.payloadFlag == AudioPacketHeader.FLAG_VOICE ||
                    header.payloadFlag == AudioPacketHeader.FLAG_MUSIC
                ) {
                    _incomingPackets.emit(ReceivedAudioPacket(header, payload, senderIp))
                }

            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "UDP receive error", e)
                }
            }
        }
    }

    private fun forwardPacketToOtherClients(originId: Byte, rawData: ByteArray) {
        clientIpMap.forEach { (clientId, ipAddress) ->
            if (clientId != originId) {
                sendRawToIp(ipAddress, rawData)
            }
        }
    }

    fun sendAudioPacket(header: AudioPacketHeader, payload: ByteArray) {
        val headerBytes = header.toByteArray()
        val fullData = ByteArray(headerBytes.size + payload.size)
        System.arraycopy(headerBytes, 0, fullData, 0, headerBytes.size)
        System.arraycopy(payload, 0, fullData, headerBytes.size, payload.size)

        if (isGroupOwner) {
            // GO sends directly to all connected clients
            clientIpMap.values.forEach { clientIp ->
                sendRawToIp(clientIp, fullData)
            }
        } else {
            // Client sends directly to GO
            targetGoIp?.let { goIp ->
                sendRawToIp(goIp, fullData)
            }
        }
    }

    private fun sendRawToIp(ipAddress: String, data: ByteArray) {
        scope.launch {
            try {
                val sock = socket ?: return@launch
                val inetAddr = InetAddress.getByName(ipAddress)
                val datagram = DatagramPacket(data, data.size, inetAddr, UDP_PORT)
                sock.send(datagram)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP packet to $ipAddress", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        clientIpMap.clear()
        clientLastSeenMap.clear()
    }
}
