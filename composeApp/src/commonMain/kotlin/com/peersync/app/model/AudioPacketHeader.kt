package com.peersync.app.model

/**
 * 4-byte header for UDP audio packets (Voice, Music, Keep-Alive).
 * Structure per SRS §4.1 & Implementation Guidelines §4.1:
 * - Byte 0: User Origin ID (0 = Group Owner, 1..255 = Client Spoke)
 * - Byte 1: Payload Flag (0x00 = Keep-Alive, 0x01 = Voice 16kHz Mono, 0x02 = Music 44.1kHz Stereo)
 * - Bytes 2-3: Sequence Index (UShort, 0..65535 big-endian packet ordering for Jitter Buffer)
 */
data class AudioPacketHeader(
    val originId: Byte,
    val payloadFlag: Byte,
    val sequenceIndex: UShort
) {
    fun toByteArray(): ByteArray {
        val bytes = ByteArray(HEADER_SIZE)
        bytes[0] = originId
        bytes[1] = payloadFlag
        bytes[2] = (sequenceIndex.toInt() shr 8).toByte()
        bytes[3] = (sequenceIndex.toInt() and 0xFF).toByte()
        return bytes
    }

    companion object {
        const val HEADER_SIZE = 4

        const val FLAG_KEEPALIVE: Byte = 0x00
        const val FLAG_VOICE: Byte = 0x01
        const val FLAG_MUSIC: Byte = 0x02

        fun fromByteArray(data: ByteArray, offset: Int = 0): AudioPacketHeader {
            require(data.size >= offset + HEADER_SIZE) { "Insufficient byte buffer length for AudioPacketHeader" }
            val originId = data[offset]
            val payloadFlag = data[offset + 1]
            val seqHigh = (data[offset + 2].toInt() and 0xFF) shl 8
            val seqLow = data[offset + 3].toInt() and 0xFF
            val seqIndex = (seqHigh or seqLow).toUShort()
            return AudioPacketHeader(originId, payloadFlag, seqIndex)
        }
    }
}
