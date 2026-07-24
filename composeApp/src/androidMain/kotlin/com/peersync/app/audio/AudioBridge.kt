package com.peersync.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.peersync.app.model.AudioPacketHeader
import com.peersync.app.model.AudioRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioFocusRequest: AudioFocusRequest? = null
    private var routeReapplyJob: Job? = null
    private var currentRoute: AudioRoute = AudioRoute.LOUDSPEAKER

    private val _outgoingFrames = MutableSharedFlow<EncodedAudioFrame>(extraBufferCapacity = 64)
    val outgoingFrames: SharedFlow<EncodedAudioFrame> = _outgoingFrames.asSharedFlow()

    private val _streamErrors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val streamErrors: SharedFlow<String> = _streamErrors.asSharedFlow()

    private external fun nativeInit(): Boolean
    private external fun nativeStartAudio(sessionId: Int): Boolean
    private external fun nativeStopAudio()
    private external fun nativeFeedReceivedPacket(originId: Byte, flag: Byte, payload: ByteArray)
    private external fun nativeFeedLocalMusic(pcmData: ByteArray)
    private external fun nativeGetLocalMusicFreeSpace(): Int
    private external fun nativeSetVadMode(mode: Int)
    private external fun nativeSetMusicDucking(enabled: Boolean)
    private external fun nativeSetMyOriginId(originId: Byte)
    private external fun nativeSetMicMuted(muted: Boolean)
    private external fun nativeSetLocalMusicGain(gain: Float)
    private external fun nativeSetPeerVolume(originId: Byte, volume: Float)

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            updateAudioRouteAutomatically()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            updateAudioRouteAutomatically()
        }
    }

    private fun isBluetoothDevice(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            type == AudioDeviceInfo.TYPE_HEARING_AID
    }

    private fun isWiredDevice(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_DEVICE
    }

    private fun listAvailableOutputTypes(): String {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .joinToString(prefix = "[", postfix = "]") { "${it.type}:${it.productName}" }
    }

    private fun updateAudioRouteAutomatically() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBluetooth = devices.any { isBluetoothDevice(it.type) }
        val hasWired = devices.any { isWiredDevice(it.type) }

        val autoRoute = when {
            hasBluetooth -> AudioRoute.BLUETOOTH
            else -> AudioRoute.LOUDSPEAKER
        }

        val shouldFallback = currentRoute == AudioRoute.BLUETOOTH && !hasBluetooth
        val routeToApply = if (shouldFallback) autoRoute else currentRoute

        Log.i(
            TAG,
            "Auto-route eval: requested=$currentRoute, chosen=$routeToApply, hasBluetooth=$hasBluetooth, hasWired=$hasWired, outputs=${listAvailableOutputTypes()}"
        )

        if (routeToApply != currentRoute || shouldFallback) {
            applyAudioRoute(routeToApply, source = "auto")
        } else {
            // Re-assert route in case OEM policy changed it after stream restart.
            applyAudioRoute(routeToApply, source = "auto-reassert")
        }
    }

    fun getLocalMusicFreeSpace(): Int {
        return nativeGetLocalMusicFreeSpace()
    }

    fun feedLocalMusic(pcmData: ByteArray) {
        nativeFeedLocalMusic(pcmData)
    }

    fun initialize(): Boolean {
        return nativeInit()
    }

    fun start(): Boolean {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        requestCommunicationFocus()
        val sessionId = audioManager.generateAudioSessionId()
        Log.i(TAG, "Generated Audio Session ID: $sessionId")
        val success = nativeStartAudio(sessionId)
        if (success) {
            applyAudioRoute(currentRoute, source = "start")
            routeReapplyJob?.cancel()
            routeReapplyJob = scope.launch {
                delay(300)
                applyAudioRoute(currentRoute, source = "start-delayed")
            }
        }
        if (success) {
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    aec = AcousticEchoCanceler.create(sessionId)
                    aec?.enabled = true
                    Log.i(TAG, "Hardware AcousticEchoCanceler successfully created and enabled for session $sessionId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create AcousticEchoCanceler", e)
                }
            } else {
                Log.w(TAG, "Hardware AcousticEchoCanceler is NOT available on this device!")
            }

            if (NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(sessionId)
                    ns?.enabled = true
                    Log.i(TAG, "Hardware NoiseSuppressor successfully created and enabled for session $sessionId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create NoiseSuppressor", e)
                }
            } else {
                Log.w(TAG, "Hardware NoiseSuppressor is NOT available on this device!")
            }
        }
        return success
    }

    fun stop() {
        routeReapplyJob?.cancel()
        routeReapplyJob = null
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        aec?.release()
        aec = null
        ns?.release()
        ns = null
        nativeStopAudio()
        abandonCommunicationFocus()
    }

    fun setMyOriginId(originId: Byte) {
        nativeSetMyOriginId(originId)
    }

    fun setMicMuted(muted: Boolean) {
        nativeSetMicMuted(muted)
    }

    fun setLocalMusicGain(gain: Float) {
        nativeSetLocalMusicGain(gain)
    }

    fun setPeerVolume(originId: Byte, volume: Float) {
        nativeSetPeerVolume(originId, volume)
    }

    fun setAudioRoute(route: AudioRoute) {
        applyAudioRoute(route, source = "manual")
    }

    private fun applyAudioRoute(route: AudioRoute, source: String) {
        currentRoute = route
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val available = audioManager.availableCommunicationDevices
            val targetTypes = when (route) {
                AudioRoute.LOUDSPEAKER -> listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                AudioRoute.EARPIECE -> listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                AudioRoute.BLUETOOTH -> listOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                )
            }

            val target = available.firstOrNull { it.type in targetTypes }
            val setSuccess = if (target != null) {
                audioManager.setCommunicationDevice(target)
            } else {
                false
            }

            when (route) {
                AudioRoute.LOUDSPEAKER -> {
                    try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
                    try { audioManager.isSpeakerphoneOn = true } catch (_: Exception) {}
                    if (!setSuccess) {
                        audioManager.clearCommunicationDevice()
                    }
                }
                AudioRoute.EARPIECE -> {
                    try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
                    try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
                    if (!setSuccess) {
                        audioManager.clearCommunicationDevice()
                    }
                }
                AudioRoute.BLUETOOTH -> {
                    try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
                    try { audioManager.startBluetoothSco() } catch (_: Exception) {}
                    if (!setSuccess) {
                        Log.w(TAG, "Bluetooth route requested but no communication Bluetooth device found")
                    }
                }
            }

            val activeType = audioManager.communicationDevice?.type
            Log.i(
                TAG,
                "applyAudioRoute[$source]: route=$route, target=${target?.type}, setSuccess=$setSuccess, active=$activeType, available=${available.joinToString(prefix = "[", postfix = "]") { it.type.toString() }}"
            )
            return
        }

        when (route) {
            AudioRoute.LOUDSPEAKER -> {
                try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
                try { audioManager.isSpeakerphoneOn = true } catch (_: Exception) {}
            }
            AudioRoute.EARPIECE -> {
                try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
                try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
            }
            AudioRoute.BLUETOOTH -> {
                try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
                try { audioManager.startBluetoothSco() } catch (_: Exception) {}
            }
        }
        Log.i(TAG, "applyAudioRoute[$source]: route=$route (legacy API path)")
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

        // Auto-detect currently connected devices to set initial route
        updateAudioRouteAutomatically()

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

