#include "audio_engine.h"
#include <android/log.h>
#include <vector>
#include <cstring>
#include <algorithm>

#define LOG_TAG "PeerSyncNativeAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static aaudio_data_callback_result_t inCallback(
    AAudioStream* stream, void* userData, void* audioData, int32_t numFrames
) {
    auto* engine = static_cast<AudioEngine*>(userData);
    return engine->onAudioInput(static_cast<const int16_t*>(audioData), numFrames);
}

static aaudio_data_callback_result_t outCallback(
    AAudioStream* stream, void* userData, void* audioData, int32_t numFrames
) {
    auto* engine = static_cast<AudioEngine*>(userData);
    return engine->onAudioOutput(static_cast<int16_t*>(audioData), numFrames);
}

static void errorCallback(AAudioStream* stream, void* userData, aaudio_result_t error) {
    AudioEngine::OnStreamError(stream, userData, error);
}

void AudioEngine::OnStreamError(AAudioStream* stream, void* userData, aaudio_result_t error) {
    auto* engine = static_cast<AudioEngine*>(userData);
    const char* direction = "UNKNOWN";
    if (engine != nullptr) {
        if (stream == engine->inputStream_) direction = "INPUT";
        else if (stream == engine->outputStream_) direction = "OUTPUT";
    }
    LOGE("AAudio stream %s error/disconnected: %s (%d)", direction, AAudio_convertResultToText(error), error);
    if (engine != nullptr) {
        engine->isRunning_.store(false);
        if (engine->streamErrorCallback_ != nullptr) {
            char msg[128];
            snprintf(msg, sizeof(msg), "%s stream: %s", direction, AAudio_convertResultToText(error));
            engine->streamErrorCallback_(msg);
        }
    }
}

AudioEngine::AudioEngine() {
    for (int i = 0; i < 256; ++i) {
        clientRingBuffers_[i].store(nullptr, std::memory_order_relaxed);
        peerGains_[i].store(1.0f, std::memory_order_relaxed);
        clientBuffering_[i]   = true; // wait for initial 60ms cushion before draining
        clientStarveCount_[i] = 0;
        lastSeq_[i].store(0, std::memory_order_relaxed);
        hasSeq_[i].store(false, std::memory_order_relaxed);
    }
    inputMonoScratch_.reserve(VOICE_FRAME_SAMPLES * 4);
    outputMonoScratch_.reserve(VOICE_FRAME_SAMPLES * 4);
    localMusicInScratch_.reserve(VOICE_FRAME_SAMPLES * 4);
    localMusicOutScratch_.reserve(VOICE_FRAME_SAMPLES * 4);
}

AudioEngine::~AudioEngine() {
    stop();

    std::lock_guard<std::mutex> lock(ringMapMutex_);
    for (int i = 0; i < 256; ++i) {
        RingBuffer* rb = clientRingBuffers_[i].exchange(nullptr, std::memory_order_acq_rel);
        if (rb) {
            delete rb;
        }
    }
}

bool AudioEngine::start(int sessionId) {
    if (isRunning_.load()) return true;

    // Ensure stale state from previous runs is cleared.
    localMusicInRingBuffer_.clear();
    localMusicOutRingBuffer_.clear();
    captureHead_ = 0;
    captureCount_ = 0;

    AAudioStreamBuilder* builder = nullptr;
    AAudio_createStreamBuilder(&builder);
    if (!builder) return false;

    // Input Stream (Microphone - 16kHz Mono VOICE_COMMUNICATION)
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setInputPreset(builder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
    AAudioStreamBuilder_setSampleRate(builder, 16000);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSessionId(builder, sessionId);
     AAudioStreamBuilder_setDataCallback(builder, inCallback, this);
     AAudioStreamBuilder_setErrorCallback(builder, errorCallback, this);

     int preferredInputId = preferredInputDeviceId_.load();
     if (preferredInputId > 0) {
         AAudioStreamBuilder_setDeviceId(builder, preferredInputId);
     }

     if (AAudioStreamBuilder_openStream(builder, &inputStream_) != AAUDIO_OK) {
         LOGE("Failed to open AAudio input stream");
         // If a preferred device was requested but failed, retry with AAUDIO_UNSPECIFIED
         if (preferredInputId > 0) {
             LOGD("Retrying input stream without preferred device ID");
             AAudioStreamBuilder_setDeviceId(builder, AAUDIO_UNSPECIFIED);
             if (AAudioStreamBuilder_openStream(builder, &inputStream_) != AAUDIO_OK) {
                 LOGE("Failed to open AAudio input stream (retry without device ID)");
                 AAudioStreamBuilder_delete(builder);
                 return false;
             }
         } else {
             AAudioStreamBuilder_delete(builder);
             return false;
         }
     }
    inputChannelCount_ = AAudioStream_getChannelCount(inputStream_);
    inputSampleRate_ = AAudioStream_getSampleRate(inputStream_);

    // Output Stream (Speaker - 16kHz Mono VOICE_COMMUNICATION)
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSessionId(builder, sessionId);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setSampleRate(builder, 16000);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_VOICE_COMMUNICATION);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_SPEECH);
     AAudioStreamBuilder_setDataCallback(builder, outCallback, this);
     AAudioStreamBuilder_setErrorCallback(builder, errorCallback, this);

     int preferredOutputId = preferredOutputDeviceId_.load();
     if (preferredOutputId > 0) {
         AAudioStreamBuilder_setDeviceId(builder, preferredOutputId);
     }

     if (AAudioStreamBuilder_openStream(builder, &outputStream_) != AAUDIO_OK) {
         LOGE("Failed to open AAudio output stream");
         // If a preferred device was requested but failed, retry with AAUDIO_UNSPECIFIED
         if (preferredOutputId > 0) {
             LOGD("Retrying output stream without preferred device ID");
             AAudioStreamBuilder_setDeviceId(builder, AAUDIO_UNSPECIFIED);
             if (AAudioStreamBuilder_openStream(builder, &outputStream_) != AAUDIO_OK) {
                 LOGE("Failed to open AAudio output stream (retry without device ID)");
                 AAudioStream_close(inputStream_);
                 inputStream_ = nullptr;
                 AAudioStreamBuilder_delete(builder);
                 return false;
             }
         } else {
             AAudioStream_close(inputStream_);
             inputStream_ = nullptr;
             AAudioStreamBuilder_delete(builder);
             return false;
         }
     }
    outputChannelCount_ = AAudioStream_getChannelCount(outputStream_);
    outputSampleRate_ = AAudioStream_getSampleRate(outputStream_);

    // Removed Music Output Stream

    AAudioStreamBuilder_delete(builder);

    // Set running BEFORE requestStart: the data callbacks fire on AAudio threads
    isRunning_.store(true);

    // Belt-and-braces: clear all client rings and reset state, wiping any
    // residue from previous sessions before playback resumes.
    {
        std::lock_guard<std::mutex> lock(ringMapMutex_);
        for (int i = 0; i < 256; ++i) {
            RingBuffer* rb = clientRingBuffers_[i].load(std::memory_order_acquire);
            if (rb) {
                rb->clear();
            }
            clientBuffering_[i] = true;
            clientStarveCount_[i] = 0;
            hasSeq_[i].store(false, std::memory_order_relaxed);
        }
    }

    AAudioStream_requestStart(inputStream_);
    AAudioStream_requestStart(outputStream_);
    
    LOGI("AAudio Input and Output streams started successfully");
    LOGI("Voice stream format: in=%dHz/%dch, out=%dHz/%dch", inputSampleRate_, inputChannelCount_, outputSampleRate_, outputChannelCount_);
    return true;
}

void AudioEngine::stop() {
    // Always attempt full teardown even if isRunning_ is already false
    // (e.g. after AAudio error callback), otherwise stale streams remain open.
    isRunning_.store(false);

    if (inputStream_) {
        AAudioStream_requestStop(inputStream_);
        AAudioStream_close(inputStream_);
        inputStream_ = nullptr;
    }

    if (outputStream_) {
        AAudioStream_requestStop(outputStream_);
        AAudioStream_close(outputStream_);
        outputStream_ = nullptr;
    }

    // Output stream stopped
    
    // Reset per-client ring buffers (do not delete here; output callbacks may
    // still be winding down while streams are stopping).
    std::lock_guard<std::mutex> lock(ringMapMutex_);
    for (int i = 0; i < 256; ++i) {
        RingBuffer* rb = clientRingBuffers_[i].load(std::memory_order_acquire);
        if (rb) {
            rb->clear();
        }
        clientBuffering_[i] = true; // reset cushion for next session
        clientStarveCount_[i] = 0;
        hasSeq_[i].store(false, std::memory_order_relaxed);
    }
    localMusicInRingBuffer_.clear();
    localMusicOutRingBuffer_.clear();
    captureHead_ = 0;
    captureCount_ = 0;

    LOGI("AAudio Engine stopped");
}

void AudioEngine::setDeviceIds(int inputDeviceId, int outputDeviceId) {
    preferredInputDeviceId_.store(inputDeviceId);
    preferredOutputDeviceId_.store(outputDeviceId);
    LOGD("Device IDs set: input=%d, output=%d", inputDeviceId, outputDeviceId);
}

aaudio_data_callback_result_t AudioEngine::onAudioInput(const int16_t* audioData, int32_t numFrames) {
    if (!isRunning_.load()) return AAUDIO_CALLBACK_RESULT_STOP;

    if (audioData == nullptr || numFrames <= 0) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    // Pathological burst guard: reset accumulator rather than underflow size_t.
    if (numFrames >= static_cast<int32_t>(CAPTURE_RING_SAMPLES)) {
        captureHead_ = 0;
        captureCount_ = 0;
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    const int channels = (inputChannelCount_ > 0) ? inputChannelCount_ : 1;
    bool isMuted = micMuted_.load();
    float musicGain = localMusicGain_.load(std::memory_order_relaxed);

    localMusicInScratch_.assign(static_cast<size_t>(numFrames), 0);
    localMusicInRingBuffer_.read(localMusicInScratch_.data(), static_cast<size_t>(numFrames));

    // Downmix to mono if the device provides more than one channel, and mix music.
    const int16_t* monoPtr = nullptr;
    inputMonoScratch_.resize(static_cast<size_t>(numFrames));
    if (channels > 1) {
        for (int32_t i = 0; i < numFrames; ++i) {
            int32_t sum = 0;
            if (!isMuted) {
                for (int c = 0; c < channels; ++c) {
                    sum += audioData[static_cast<size_t>(i) * channels + c];
                }
                sum /= channels;
            }
            int32_t musicSample = static_cast<int32_t>(localMusicInScratch_[static_cast<size_t>(i)] * musicGain);
            int32_t mixed = sum + musicSample;
            if (mixed > 32767) mixed = 32767;
            if (mixed < -32768) mixed = -32768;
            inputMonoScratch_[static_cast<size_t>(i)] = static_cast<int16_t>(mixed);
        }
    } else {
        for (int32_t i = 0; i < numFrames; ++i) {
            int32_t micSample = isMuted ? 0 : audioData[static_cast<size_t>(i)];
            int32_t musicSample = static_cast<int32_t>(localMusicInScratch_[static_cast<size_t>(i)] * musicGain);
            int32_t mixed = micSample + musicSample;
            if (mixed > 32767) mixed = 32767;
            if (mixed < -32768) mixed = -32768;
            inputMonoScratch_[static_cast<size_t>(i)] = static_cast<int16_t>(mixed);
        }
    }
    monoPtr = inputMonoScratch_.data();

    // Accumulate into a fixed circular buffer and emit fixed 20ms voice
    // packets. No heap allocation, no O(n) erase — input-thread only.
    // If a stall would overflow the ring, drop the OLDEST samples first.
    if (captureCount_ + static_cast<size_t>(numFrames) > CAPTURE_RING_SAMPLES) {
        size_t overflow = captureCount_ + static_cast<size_t>(numFrames) - CAPTURE_RING_SAMPLES;
        captureHead_ = (captureHead_ + overflow) % CAPTURE_RING_SAMPLES;
        captureCount_ -= overflow;
    }
    {
        size_t writePos = (captureHead_ + captureCount_) % CAPTURE_RING_SAMPLES;
        size_t firstPart = std::min(static_cast<size_t>(numFrames), CAPTURE_RING_SAMPLES - writePos);
        std::memcpy(captureRing_ + writePos, monoPtr, firstPart * sizeof(int16_t));
        size_t secondPart = static_cast<size_t>(numFrames) - firstPart;
        if (secondPart > 0) {
            std::memcpy(captureRing_, monoPtr + firstPart, secondPart * sizeof(int16_t));
        }
        captureCount_ += static_cast<size_t>(numFrames);
    }

    uint8_t flag = 0x01;
    uint8_t encodedBuffer[VOICE_FRAME_SAMPLES * sizeof(int16_t)];
    int16_t frame[VOICE_FRAME_SAMPLES];

    while (captureCount_ >= VOICE_FRAME_SAMPLES) {
        size_t firstPart = std::min(VOICE_FRAME_SAMPLES, CAPTURE_RING_SAMPLES - captureHead_);
        std::memcpy(frame, captureRing_ + captureHead_, firstPart * sizeof(int16_t));
        size_t secondPart = VOICE_FRAME_SAMPLES - firstPart;
        if (secondPart > 0) {
            std::memcpy(frame + firstPart, captureRing_, secondPart * sizeof(int16_t));
        }

        int encodedBytes = voiceCodec_.encode(
            frame,
            VOICE_FRAME_SAMPLES,
            encodedBuffer,
            sizeof(encodedBuffer)
        );

        if (frameCallback_ && encodedBytes > 0) {
            frameCallback_(flag, encodedBuffer, static_cast<size_t>(encodedBytes));
        }

        captureHead_ = (captureHead_ + VOICE_FRAME_SAMPLES) % CAPTURE_RING_SAMPLES;
        captureCount_ -= VOICE_FRAME_SAMPLES;
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t AudioEngine::onAudioOutput(int16_t* audioData, int32_t numFrames) {
    if (!isRunning_.load()) return AAUDIO_CALLBACK_RESULT_STOP;

    if (audioData == nullptr || numFrames <= 0) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    // Build flat mix-input array from pre-allocated scratch. Zero heap
    // allocations, zero locks on the audio thread. Iterating fixed arrays
    // of atomics is thread-safe.
    const int16_t* mixInputs[MAX_MIX_PEERS];
    size_t mixCount = 0;

    if (numFrames <= static_cast<int32_t>(MAX_CALLBACK_FRAMES)) {
        for (int i = 0; i < 256; ++i) {
            RingBuffer* rb = clientRingBuffers_[i].load(std::memory_order_acquire);
            if (!rb) continue;

            size_t avail = rb->availableRead();

            // Initial jitter cushion (unchanged semantics)
            if (clientBuffering_[i]) {
                if (avail < VOICE_JITTER_CUSHION) {
                    continue;
                }
                clientBuffering_[i]   = false;
                clientStarveCount_[i] = 0;
            }

            if (avail == 0) {
                // Starvation tracking (unchanged semantics)
                clientStarveCount_[i]++;
                if (clientStarveCount_[i] >= VOICE_STARVATION_LIMIT) {
                    clientBuffering_[i]   = true;
                    clientStarveCount_[i] = 0;
                }
                continue;
            }

            clientStarveCount_[i] = 0;

            if (mixCount < MAX_MIX_PEERS) {
                int16_t* scratch = peerMixScratch_[mixCount];
                size_t got = rb->read(scratch, static_cast<size_t>(numFrames));
                // Zero-pad any shortfall (ring shorter than numFrames) — soft
                // glitch instead of a hard mute, same as before.
                if (got < static_cast<size_t>(numFrames)) {
                    std::memset(scratch + got, 0,
                                (static_cast<size_t>(numFrames) - got) * sizeof(int16_t));
                }

                // Apply peer-specific gain in place
                float gain = peerGains_[i].load(std::memory_order_relaxed);
                if (gain != 1.0f) {
                    for (int32_t j = 0; j < numFrames; ++j) {
                        float sample = scratch[j] * gain;
                        if (sample > 32767.0f) sample = 32767.0f;
                        else if (sample < -32768.0f) sample = -32768.0f;
                        scratch[j] = static_cast<int16_t>(sample);
                    }
                }

                mixInputs[mixCount++] = scratch;
            } else {
                // Beyond mixing capacity: drain so the ring can't fill and wedge
                // (which would cause network-side drops), but don't mix.
                if (!mixTruncationLogged_) {
                    mixTruncationLogged_ = true;
                    LOGE("Active peers exceed MAX_MIX_PEERS=%d — extra peers drained, not mixed", (int)MAX_MIX_PEERS);
                }
                rb->read(drainScratch_, static_cast<size_t>(numFrames));
            }
        }
    } else {
        LOGE("onAudioOutput: numFrames=%d exceeds MAX_CALLBACK_FRAMES, peers muted this callback", numFrames);
    }

    float musicGain = localMusicGain_.load(std::memory_order_relaxed);

    localMusicOutScratch_.assign(static_cast<size_t>(numFrames), 0);
    localMusicOutRingBuffer_.read(localMusicOutScratch_.data(), static_cast<size_t>(numFrames));
    for (size_t i = 0; i < localMusicOutScratch_.size(); ++i) {
        localMusicOutScratch_[i] = static_cast<int16_t>(localMusicOutScratch_[i] * musicGain);
    }

    const int outChannels = (outputChannelCount_ > 0) ? outputChannelCount_ : 1;
    if (outChannels == 1) {
        mixer_.mixFrame(mixInputs, mixCount, localMusicOutScratch_.data(), audioData, static_cast<size_t>(numFrames));
    } else {
        outputMonoScratch_.assign(static_cast<size_t>(numFrames), 0);
        mixer_.mixFrame(mixInputs, mixCount, localMusicOutScratch_.data(), outputMonoScratch_.data(), static_cast<size_t>(numFrames));

        for (int32_t i = 0; i < numFrames; ++i) {
            const int16_t s = outputMonoScratch_[static_cast<size_t>(i)];
            const size_t base = static_cast<size_t>(i) * outChannels;
            for (int c = 0; c < outChannels; ++c) {
                audioData[base + c] = s;
            }
        }
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}



size_t AudioEngine::feedLocalMusic(const int16_t* pcm, size_t sampleCount) {
    size_t inWritten = localMusicInRingBuffer_.write(pcm, sampleCount);
    localMusicOutRingBuffer_.write(pcm, sampleCount);
    return inWritten;
}

size_t AudioEngine::getLocalMusicFreeSpace() {
    size_t inFree = localMusicInRingBuffer_.availableWrite();
    size_t outFree = localMusicOutRingBuffer_.availableWrite();
    
    // Resync if they drift apart by more than 0.5 seconds (8000 samples)
    if (inFree > outFree + 8000) {
        localMusicOutRingBuffer_.clear();
        outFree = localMusicOutRingBuffer_.availableWrite();
    } else if (outFree > inFree + 8000) {
        localMusicInRingBuffer_.clear();
        inFree = localMusicInRingBuffer_.availableWrite();
    }
    
    return inFree < outFree ? inFree : outFree;
}

void AudioEngine::clearLocalMusicBuffers() {
    localMusicInRingBuffer_.clear();
    localMusicOutRingBuffer_.clear();
}

void AudioEngine::feedReceivedPacket(uint8_t originId, uint8_t flag, uint16_t sequenceIndex, const uint8_t* payload, size_t payloadSize) {
    if (flag != 0x01) return; // only voice packets

    // Drop packets that originated from this device (self-echo).
    if (originId == myOriginId_.load()) return;

    // Duplicate / late-packet drop (UDP is unordered). Wrap-aware diff:
    // diff <= 0 means already-seen or older than last accepted. A forward
    // jump (loss or sender restart) is accepted immediately.
    if (hasSeq_[originId].load(std::memory_order_relaxed)) {
        int32_t diff = static_cast<int32_t>(static_cast<int16_t>(
            static_cast<uint16_t>(sequenceIndex - lastSeq_[originId].load(std::memory_order_relaxed))));
        if (diff <= 0) return;
    }
    lastSeq_[originId].store(sequenceIndex, std::memory_order_relaxed);
    hasSeq_[originId].store(true, std::memory_order_relaxed);

    // Decode into fixed network-thread scratch (stub codec is a memcpy).
    size_t sampleCount = payloadSize / sizeof(int16_t);
    if (sampleCount == 0) return;
    if (sampleCount > MAX_DECODE_SAMPLES) sampleCount = MAX_DECODE_SAMPLES;
    voiceCodec_.decode(payload, payloadSize, decodeScratch_, sampleCount);

    // Get or create the ring buffer for this client (mutex = lifecycle only).
    RingBuffer* rb = clientRingBuffers_[originId].load(std::memory_order_acquire);
    if (rb == nullptr) {
        std::lock_guard<std::mutex> lock(ringMapMutex_);
        rb = clientRingBuffers_[originId].load(std::memory_order_acquire);
        if (rb == nullptr) {
            rb = new RingBuffer(RING_CAPACITY);
            clientRingBuffers_[originId].store(rb, std::memory_order_release);
            clientBuffering_[originId]   = true;
            clientStarveCount_[originId] = 0;
            LOGI("Created ring buffer for client originId=%u", originId);
        }
    }

    // Lock protects against concurrent clear() from start()/stop()
    std::lock_guard<std::mutex> lock(ringMapMutex_);
    size_t written = rb->write(decodeScratch_, sampleCount);
    if (written < sampleCount) {
        // Ring full (sender/receiver clock drift) — hard resync, same as the
        // original design: drop the backlog, keep playback latency bounded.
        rb->clear();
        rb->write(decodeScratch_, sampleCount);
        LOGD("Ring buffer full for originId=%u — cleared stale data (drift resync)", originId);
    }
}

void AudioEngine::setVadMode(int mode) {
    vad_.setMode(mode);
}

void AudioEngine::setMusicDucking(bool enabled) {
    mixer_.setDuckingEnabled(enabled);
}

