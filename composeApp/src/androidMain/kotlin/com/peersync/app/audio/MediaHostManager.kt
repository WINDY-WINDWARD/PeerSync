package com.peersync.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.peersync.app.model.AudioPacketHeader
import com.peersync.app.model.MediaAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

class MediaHostManager(
    private val context: Context,
    private val onSendMusicPacket: (AudioPacketHeader, ByteArray) -> Unit
) {

    companion object {
        private const val TAG = "MediaHostManager"
        private const val FRAME_SAMPLES_STEREO = 882 // 20ms at 44.1kHz Stereo
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playbackJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTrackUri = MutableStateFlow<Uri?>(null)
    val currentTrackUri: StateFlow<Uri?> = _currentTrackUri.asStateFlow()

    private var myOriginId: Byte = 0
    private var musicSeqIndex: UShort = 0u

    fun setOriginId(originId: Byte) {
        this.myOriginId = originId
    }

    fun selectAndPlayFile(fileUri: Uri) {
        stopPlayback()
        _currentTrackUri.value = fileUri
        _isPlaying.value = true

        playbackJob = scope.launch {
            decodeAndStreamAudio(fileUri)
        }
    }

    fun handleMediaAction(action: MediaAction) {
        when (action) {
            MediaAction.PLAY -> resumePlayback()
            MediaAction.PAUSE -> pausePlayback()
            MediaAction.SKIP_NEXT -> Log.d(TAG, "Skip next requested")
            MediaAction.SKIP_PREVIOUS -> Log.d(TAG, "Skip previous requested")
        }
    }

    fun pausePlayback() {
        _isPlaying.value = false
    }

    fun resumePlayback() {
        _isPlaying.value = true
    }

    fun stopPlayback() {
        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
    }

    private suspend fun decodeAndStreamAudio(uri: Uri) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex < 0 || format == null) {
                Log.e(TAG, "No audio track found in selected URI")
                _isPlaying.value = false
                return
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false

            while (currentCoroutineContext().isActive && _currentTrackUri.value == uri) {
                if (!_isPlaying.value) {
                    delay(100L)
                    continue
                }

                if (!isEOS) {
                    val inputBufIdx = decoder.dequeueInputBuffer(10_000L)
                    if (inputBufIdx >= 0) {
                        val inputBuf = decoder.getInputBuffer(inputBufIdx) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            val timeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inputBufIdx, 0, sampleSize, timeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputBufIdx = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outputBufIdx >= 0) {
                    val outputBuf = decoder.getOutputBuffer(outputBufIdx) ?: continue
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuf.get(chunk)
                    outputBuf.clear()

                    decoder.releaseOutputBuffer(outputBufIdx, false)

                    // Send 20ms music packets (Flag 0x02)
                    val header = AudioPacketHeader(myOriginId, AudioPacketHeader.FLAG_MUSIC, musicSeqIndex++)
                    onSendMusicPacket(header, chunk)

                    delay(20L) // 20ms frame pacing
                } else if (outputBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Decoder output format changed: ${decoder.outputFormat}")
                }

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "Reached end of audio file")
                    break
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio file", e)
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            _isPlaying.value = false
        }
    }
}
