package com.peersync.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.peersync.app.model.AudioPacketHeader
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

    private external fun nativeInit(): Boolean
    private external fun nativeStartAudio(): Boolean
    private external fun nativeStopAudio()
    private external fun nativeFeedReceivedPacket(originId: Byte, flag: Byte, payload: ByteArray)
    private external fun nativeSetVadMode(mode: Int)
    private external fun nativeSetMusicDucking(enabled: Boolean)

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

    private fun requestCommunicationFocus() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
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
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonCommunicationFocus() {
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
