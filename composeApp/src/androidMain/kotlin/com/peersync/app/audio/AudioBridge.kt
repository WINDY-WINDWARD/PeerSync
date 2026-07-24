package com.peersync.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.peersync.app.model.AudioPacketHeader
import com.peersync.app.model.AudioRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class EncodedAudioFrame(
    val flag: Byte,
    val payload: ByteArray
)

class AudioBridge(private val context: Context) {

    companion object {
        private const val TAG = "AudioBridge"

        init {
            try {
                System.loadLibrary("peersync_audio")
                Log.d(TAG, "Native library 'peersync_audio' loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library 'peersync_audio'", e)
            }
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _outgoingFrames = MutableSharedFlow<EncodedAudioFrame>(extraBufferCapacity = 64)
    val outgoingFrames: SharedFlow<EncodedAudioFrame> = _outgoingFrames.asSharedFlow()

    private val _streamErrors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val streamErrors: SharedFlow<String> = _streamErrors.asSharedFlow()

    private external fun nativeInit(): Boolean
    private external fun nativeStartAudio(): Boolean
    private external fun nativeStopAudio()
    private external fun nativeFeedReceivedPacket(originId: Byte, flag: Byte, payload: ByteArray)
    private external fun nativeSetVadMode(mode: Int)
    private external fun nativeSetMusicDucking(enabled: Boolean)
    private external fun nativeSetMyOriginId(originId: Byte)
    private external fun nativeSetMicMuted(muted: Boolean)
    private external fun nativeSetPeerVolume(originId: Byte, volume: Float)

    fun initialize(): Boolean {
        return nativeInit()
    }

    fun start(): Boolean {
        requestCommunicationFocus()
        return nativeStartAudio()
    }

    fun stop() {
        nativeStopAudio()
        abandonCommunicationFocus()
    }

    fun setMyOriginId(originId: Byte) {
        nativeSetMyOriginId(originId)
    }

    fun setMicMuted(muted: Boolean) {
        nativeSetMicMuted(muted)
    }

    fun setPeerVolume(originId: Byte, volume: Float) {
        nativeSetPeerVolume(originId, volume)
    }

    fun setAudioRoute(route: AudioRoute) {
        // isSpeakerphoneOn is the reliable hardware gate in MODE_IN_COMMUNICATION.
        // It must be set on every API level — setCommunicationDevice (API 31+) only
        // affects VOICE_COMMUNICATION streams and can silently fail if the device
        // isn't currently enumerated. Set the low-level flag first so the hardware
        // routing takes effect immediately regardless of API level.
        when (route) {
            AudioRoute.LOUDSPEAKER -> {
                audioManager.stopBluetoothSco()
                try { audioManager.isSpeakerphoneOn = true } catch (_: Exception) {}
            }
            AudioRoute.EARPIECE -> {
                audioManager.stopBluetoothSco()
                try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
            }
            AudioRoute.BLUETOOTH -> {
                try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
                audioManager.startBluetoothSco()
            }
        }

        // On API 31+ also attempt the modern communication device API as a
        // best-effort enhancement for future-compatibility.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val targetType = when (route) {
                AudioRoute.LOUDSPEAKER -> android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                AudioRoute.EARPIECE    -> android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                AudioRoute.BLUETOOTH   -> {
                    val devices = audioManager.availableCommunicationDevices
                    devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.type
                        ?: devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET }?.type
                        ?: android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
            if (device != null) {
                audioManager.setCommunicationDevice(device)
                Log.d(TAG, "Routed audio to ${route.name} via setCommunicationDevice")
            } else {
                Log.d(TAG, "Routed audio to ${route.name} via isSpeakerphoneOn (device type $targetType not in communication devices)")
            }
        } else {
            Log.d(TAG, "Routed audio to ${route.name} via isSpeakerphoneOn (legacy path)")
        }
    }

    fun feedReceivedPacket(originId: Byte, flag: Byte, payload: ByteArray) {
        nativeFeedReceivedPacket(originId, flag, payload)
    }

    fun setVadMode(mode: Int) {
        nativeSetVadMode(mode)
    }

    fun setMusicDucking(enabled: Boolean) {
        nativeSetMusicDucking(enabled)
    }

    // Called from C++ JNI bridge when microphone captures audio frame
    fun onNativeAudioFrame(flag: Byte, payload: ByteArray) {
        _outgoingFrames.tryEmit(EncodedAudioFrame(flag, payload))
    }

    // Called from C++ JNI bridge when an AAudio stream errors or disconnects
    fun onStreamError(errorMessage: String) {
        Log.e(TAG, "Native AAudio stream error: $errorMessage")
        // Launch on a separate thread to break away from the AAudio callback thread
        // This prevents a fatal SIGABRT when PeerSyncEngine attempts to restart the stream.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            _streamErrors.emit(errorMessage)
        }
    }

    private fun requestCommunicationFocus() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Set default route to Speaker
        setAudioRoute(AudioRoute.LOUDSPEAKER)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attr)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            Log.w(TAG, "Audio focus lost")
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            Log.d(TAG, "Audio focus gained")
                        }
                    }
                }
                .build()
            audioFocusRequest = focusReq
            audioManager.requestAudioFocus(focusReq)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonCommunicationFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
            audioManager.stopBluetoothSco()
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
