package com.peersync.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.peersync.app.model.AudioPacketHeader
import com.peersync.app.model.MediaAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaHostManager(
    private val context: Context,
    private val onSendMusicPacket: (AudioPacketHeader, ByteArray) -> Unit
) {

    companion object {
        private const val TAG = "MediaHostManager"
        private val AUDIO_EXTENSIONS = setOf("mp3", "aac", "m4a", "wav", "flac", "ogg", "opus")

        // Max payload bytes per UDP datagram. Must be <= (UDP MTU - IP header - UDP header
        // - AudioPacketHeader). Using 1392 = 348 stereo int16 frames; safe for any 1500-byte MTU.
        private const val MAX_UDP_PAYLOAD = 1392
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateLock = Any()
    private var playbackJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTrackUri = MutableStateFlow<Uri?>(null)
    val currentTrackUri: StateFlow<Uri?> = _currentTrackUri.asStateFlow()

    private val playlist = mutableListOf<Uri>()
    private var currentTrackIndex: Int = -1

    private var myOriginId: Byte = 0
    private var musicSeqIndex: UShort = 0u

    fun setOriginId(originId: Byte) {
        this.myOriginId = originId
    }

    fun selectAndPlayFolder(folderUri: Uri) {
        playbackJob?.cancel()
        scope.launch {
            val tracks = buildPlaylistFromFolder(folderUri)
            if (tracks.isEmpty()) {
                Log.w(TAG, "No playable audio files found in selected folder")
                stopPlayback(clearPlaylist = true)
                return@launch
            }

            synchronized(stateLock) {
                playlist.clear()
                playlist.addAll(tracks)
            }

            Log.i(TAG, "Loaded music folder with ${tracks.size} track(s)")
            playTrackAt(0)
        }
    }

    private fun playTrackAt(index: Int) {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            var targetIndex = index

            while (isActive) {
                val uri = synchronized(stateLock) {
                    if (targetIndex !in playlist.indices) return@launch
                    currentTrackIndex = targetIndex
                    playlist[targetIndex]
                }

                _currentTrackUri.value = uri
                _isPlaying.value = true

                val completedNaturally = decodeAndStreamAudio(uri)
                if (!completedNaturally || !isActive) {
                    break
                }

                val nextIndex = synchronized(stateLock) {
                    val next = currentTrackIndex + 1
                    if (next in playlist.indices) next else -1
                }

                if (nextIndex < 0) {
                    _isPlaying.value = false
                    Log.d(TAG, "Reached end of playlist")
                    break
                }

                targetIndex = nextIndex
            }
        }
    }

    private fun playNext() {
        val nextIndex = synchronized(stateLock) {
            if (playlist.isEmpty()) return
            val next = currentTrackIndex + 1
            if (next in playlist.indices) next else 0
        }
        playTrackAt(nextIndex)
    }

    private fun playPrevious() {
        val prevIndex = synchronized(stateLock) {
            if (playlist.isEmpty()) return
            val prev = currentTrackIndex - 1
            if (prev in playlist.indices) prev else (playlist.size - 1)
        }
        playTrackAt(prevIndex)
    }

    fun handleMediaAction(action: MediaAction) {
        when (action) {
            MediaAction.PLAY -> resumePlayback()
            MediaAction.PAUSE -> pausePlayback()
            MediaAction.SKIP_NEXT -> playNext()
            MediaAction.SKIP_PREVIOUS -> playPrevious()
        }
    }

    fun pausePlayback() {
        _isPlaying.value = false
    }

    fun resumePlayback() {
        if (_currentTrackUri.value == null) {
            val firstIndex = synchronized(stateLock) { if (playlist.isNotEmpty()) 0 else -1 }
            if (firstIndex >= 0) {
                playTrackAt(firstIndex)
                return
            }
        }
        _isPlaying.value = true
    }

    fun stopPlayback(clearPlaylist: Boolean = false) {
        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
        musicSeqIndex = 0u

        if (clearPlaylist) {
            synchronized(stateLock) {
                playlist.clear()
                currentTrackIndex = -1
            }
            _currentTrackUri.value = null
        }
    }

    private fun buildPlaylistFromFolder(folderUri: Uri): List<Uri> {
        val root = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
        val files = mutableListOf<DocumentFile>()

        fun walk(node: DocumentFile) {
            if (!node.canRead()) return
            if (node.isDirectory) {
                node.listFiles().forEach { walk(it) }
                return
            }
            if (!node.isFile) return

            val mime = node.type.orEmpty()
            val lowerName = node.name.orEmpty().lowercase()
            val ext = lowerName.substringAfterLast('.', "")

            if (mime.startsWith("audio/") || ext in AUDIO_EXTENSIONS) {
                files.add(node)
            }
        }

        walk(root)
        return files
            .sortedBy { it.name.orEmpty().lowercase() }
            .map { it.uri }
    }

    private suspend fun decodeAndStreamAudio(uri: Uri): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var reachedEnd = false

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
                return false
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
                    val chunk = ByteArray(bufferInfo.size.coerceAtLeast(0))
                    if (chunk.isNotEmpty()) {
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuf.get(chunk)
                    }
                    outputBuf.clear()

                    decoder.releaseOutputBuffer(outputBufIdx, false)

                    // Track when we started processing this codec buffer so we can
                    // pace the sender at exactly real-time without drifting behind.
                    val frameStartMs = System.currentTimeMillis()

                    if (chunk.isNotEmpty()) {
                        // Slice decoded PCM into MTU-safe UDP payloads (<=MAX_UDP_PAYLOAD bytes).
                        // All slices from one codec buffer are sent back-to-back with no delay
                        // between them — they belong to the same 20ms audio frame and must arrive
                        // together. The real-time pacing delay comes AFTER all slices are sent.
                        var offset = 0
                        while (offset < chunk.size) {
                            val end = minOf(offset + MAX_UDP_PAYLOAD, chunk.size)
                            val slice = chunk.copyOfRange(offset, end)
                            val header = AudioPacketHeader(myOriginId, AudioPacketHeader.FLAG_MUSIC, musicSeqIndex++)
                            onSendMusicPacket(header, slice)
                            offset = end
                        }
                    }

                    // Real-time pacing: sleep for however much of the 20ms budget remains
                    // after encoding + sending. This keeps the sender locked to exactly 20ms
                    // per frame regardless of processing overhead, preventing the ring buffer
                    // from draining faster than it fills (which causes the rhythmic pauses).
                    val elapsed = System.currentTimeMillis() - frameStartMs
                    val remaining = 20L - elapsed
                    if (remaining > 0) delay(remaining)
                } else if (outputBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Decoder output format changed: ${decoder.outputFormat}")
                }

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "Reached end of audio file")
                    reachedEnd = true
                    break
                }
            }

            return reachedEnd
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio file", e)
            return false
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }

        return false
    }
}
