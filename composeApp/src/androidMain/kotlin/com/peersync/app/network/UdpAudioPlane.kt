package com.peersync.app.network

import android.net.Network
import android.util.Log
import com.peersync.app.model.AudioPacketHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * UDP data plane for real-time audio. Control messages stay on TCP
 * (WifiSocketController); audio is fire-and-forget UDP — a lost 20ms frame
 * is replaced by silence/concealment instead of stalling the stream
 * (no TCP head-of-line blocking, no retransmission stalls).
 *
 * Packet format: [4-byte AudioPacketHeader][PCM payload].
 * Datagram boundaries preserve framing; no length prefix is needed.
 *
 * Host: binds HOST_UDP_PORT, learns each client's UDP address from the
 *       source of its incoming datagrams, relays every received datagram
 *       to all other known clients (DatagramSocket.send is non-blocking,
 *       so sequential fan-out costs microseconds, not network I/O waits).
 * Client: binds an ephemeral port on the P2P Network and sends to the host.
 */
class UdpAudioPlane(
    private val onPacket: (ReceivedAudioPacket) -> Unit
) {
    companion object {
        private const val TAG = "UdpAudioPlane"
        const val HOST_UDP_PORT = 5006
        private const val HOST_ADDRESS = "192.168.49.1" // Wi-Fi Direct GO default IP
        private const val MAX_DATAGRAM_BYTES = 2048
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var isHost = false

    // Host-side routing table: "ip:port" -> address. Learned/refreshed from
    // the source of every received datagram (handles client port changes).
    private val clientAddresses = ConcurrentHashMap<String, InetSocketAddress>()

    /** Host: start listening for client audio datagrams. */
    fun startHost() {
        stop()
        isHost = true
        scope.launch {
            try {
                val s = DatagramSocket(HOST_UDP_PORT)
                socket = s
                Log.d(TAG, "UDP host listening on port $HOST_UDP_PORT")
                receiveLoop(s)
            } catch (e: Exception) {
                if (socket != null) Log.e(TAG, "UDP host error: ${e.message}")
            }
        }
    }

    /** Client: bind an ephemeral UDP socket on the P2P network and register with the host. */
    fun startClient(network: Network?) {
        stop()
        isHost = false
        scope.launch {
            try {
                val s = DatagramSocket()
                network?.bindSocket(s)
                socket = s
                Log.d(TAG, "UDP client bound, target=$HOST_ADDRESS:$HOST_UDP_PORT")

                // Registration datagram: lets the host learn our address
                // immediately, even before the first mic frame. Receivers
                // drop non-voice flags, so relaying it is harmless.
                val ka = AudioPacketHeader(0, AudioPacketHeader.FLAG_KEEPALIVE, 0u).toByteArray()
                try {
                    s.send(DatagramPacket(ka, ka.size, InetSocketAddress(InetAddress.getByName(HOST_ADDRESS), HOST_UDP_PORT)))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send UDP registration: ${e.message}")
                }

                receiveLoop(s)
            } catch (e: Exception) {
                if (socket != null) Log.e(TAG, "UDP client error: ${e.message}")
            }
        }
    }

    /** Fire-and-forget send. Host: broadcast to all known clients. Client: send to host. */
    fun sendAudio(header: AudioPacketHeader, payload: ByteArray) {
        val s = socket ?: return
        val headerBytes = header.toByteArray()
        val data = ByteArray(headerBytes.size + payload.size)
        System.arraycopy(headerBytes, 0, data, 0, headerBytes.size)
        System.arraycopy(payload, 0, data, headerBytes.size, payload.size)
        try {
            if (isHost) {
                for ((_, addr) in clientAddresses) {
                    s.send(DatagramPacket(data, data.size, addr))
                }
            } else {
                s.send(DatagramPacket(data, data.size, InetSocketAddress(InetAddress.getByName(HOST_ADDRESS), HOST_UDP_PORT)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "UDP send failed: ${e.message}")
        }
    }

    /** Host: forget all UDP addresses for a disconnected client IP (called on TCP socket teardown). */
    fun removeClientsWithIp(ip: String) {
        val prefix = "$ip:"
        val removed = clientAddresses.keys.filter { it.startsWith(prefix) }
        removed.forEach { clientAddresses.remove(it) }
        if (removed.isNotEmpty()) Log.d(TAG, "Removed ${removed.size} UDP address(es) for $ip")
    }

    fun stop() {
        val s = socket
        socket = null
        clientAddresses.clear()
        try { s?.close() } catch (_: Exception) {}
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(MAX_DATAGRAM_BYTES)
        while (scope.isActive && socket === s && !s.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val len = packet.length
                if (len < AudioPacketHeader.HEADER_SIZE) continue

                val data = packet.data.copyOfRange(packet.offset, packet.offset + len)
                val header = AudioPacketHeader.fromByteArray(data)
                val payload = data.copyOfRange(AudioPacketHeader.HEADER_SIZE, len)
                val senderKey = "${packet.address.hostAddress}:${packet.port}"

                if (isHost) {
                    // Learn/refresh sender address, then relay to everyone else.
                    clientAddresses[senderKey] = InetSocketAddress(packet.address, packet.port)
                    for ((key, addr) in clientAddresses) {
                        if (key != senderKey) {
                            try {
                                s.send(DatagramPacket(data, data.size, addr))
                            } catch (e: Exception) {
                                Log.w(TAG, "UDP relay to $key failed: ${e.message}")
                            }
                        }
                    }
                    onPacket(ReceivedAudioPacket(header, payload, senderKey))
                } else {
                    onPacket(ReceivedAudioPacket(header, payload, "host"))
                }
            } catch (e: Exception) {
                if (socket === s && !s.isClosed) {
                    Log.w(TAG, "UDP receive error: ${e.message}")
                }
                // Socket closed during stop() -> exit loop
                if (s.isClosed || socket !== s) break
            }
        }
        Log.d(TAG, "UDP receive loop exited (isHost=$isHost)")
    }
}
