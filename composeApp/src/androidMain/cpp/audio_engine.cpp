#include "audio_engine.h"
#include <android/log.h>
#include <vector>
#include <cstring>

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

AudioEngine::AudioEngine() {}

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start() {
    if (isRunning_.load()) return true;

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
    AAudioStreamBuilder_setDataCallback(builder, inCallback, this);
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, this);

    if (AAudioStreamBuilder_openStream(builder, &inputStream_) != AAUDIO_OK) {
        LOGE("Failed to open AAudio input stream");
        AAudioStreamBuilder_delete(builder);
        return false;
    }

    // Output Stream (Speaker - 16kHz Mono VOICE_COMMUNICATION)
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setSampleRate(builder, 16000);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_VOICE_COMMUNICATION);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_SPEECH);
    AAudioStreamBuilder_setDataCallback(builder, outCallback, this);
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, this);

    if (AAudioStreamBuilder_openStream(builder, &outputStream_) != AAUDIO_OK) {
        LOGE("Failed to open AAudio output stream");
        AAudioStream_close(inputStream_);
        AAudioStreamBuilder_delete(builder);
        return false;
    }

    AAudioStreamBuilder_delete(builder);

    // Set running BEFORE requestStart: the data callbacks fire on AAudio threads
    // immediately and check isRunning_ — setting it after requestStart is a race
    // that makes onAudioInput/onAudioOutput return CALLBACK_RESULT_STOP on the
    // first callback, silently killing the stream on fast devices.
    isRunning_.store(true);

    AAudioStream_requestStart(inputStream_);
    AAudioStream_requestStart(outputStream_);

    LOGI("AAudio Input and Output streams started successfully");
    return true;
}

void AudioEngine::stop() {
    if (!isRunning_.load()) return;
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

    // Clean up per-client ring buffers
    std::lock_guard<std::mutex> lock(ringMapMutex_);
    for (auto& kv : clientRingBuffers_) {
        delete kv.second;
    }
    clientRingBuffers_.clear();

    LOGI("AAudio Engine stopped");
}

aaudio_data_callback_result_t AudioEngine::onAudioInput(const int16_t* audioData, int32_t numFrames) {
    if (!isRunning_.load()) return AAUDIO_CALLBACK_RESULT_STOP;
    if (micMuted_.load()) return AAUDIO_CALLBACK_RESULT_CONTINUE;

    uint8_t flag = 0x01;
    // Stub codec: memcpy PCM bytes directly into encoded buffer
    uint8_t encodedBuffer[1024];
    int encodedBytes = voiceCodec_.encode(audioData, numFrames, encodedBuffer, sizeof(encodedBuffer));

    if (frameCallback_ && encodedBytes > 0) {
        frameCallback_(flag, encodedBuffer, static_cast<size_t>(encodedBytes));
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t AudioEngine::onAudioOutput(int16_t* audioData, int32_t numFrames) {
    if (!isRunning_.load()) return AAUDIO_CALLBACK_RESULT_STOP;

    // Build voice stream map for mixer: one temp buffer per active client.
    // We do NOT lock ringMapMutex_ here — the audio thread must never block.
    // Map insertions (from feedReceivedPacket) are serialised by the mutex on
    // the JNI thread, and std::map iteration here is safe as long as no erase
    // happens concurrently (erases only happen in stop(), which sets isRunning_
    // to false before acquiring the mutex, so this callback has already returned).
    std::map<uint8_t, const int16_t*> voiceStreams;
    std::vector<std::vector<int16_t>> tempBuffers;

    for (auto& kv : clientRingBuffers_) {
        RingBuffer* rb = kv.second;
        if (rb->availableRead() == 0) continue;

        std::vector<int16_t> buf(numFrames, 0);
        size_t got = rb->read(buf.data(), static_cast<size_t>(numFrames));
        // Zero-pad any shortfall (underrun) — already initialised to 0 above
        (void)got;
        tempBuffers.push_back(std::move(buf));
        voiceStreams[kv.first] = tempBuffers.back().data();
    }

    mixer_.mixFrame(voiceStreams, nullptr, audioData, static_cast<size_t>(numFrames));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AudioEngine::feedReceivedPacket(uint8_t originId, uint8_t flag, const uint8_t* payload, size_t payloadSize) {
    if (flag != 0x01) return; // only voice packets

    // Drop packets that originated from this device — they must never be played
    // back locally (self-echo / feedback).
    if (originId == myOriginId_.load()) return;

    // Decode: stub codec is a memcpy, payloadSize bytes → payloadSize/2 samples
    size_t sampleCount = payloadSize / sizeof(int16_t);
    if (sampleCount == 0) return;

    std::vector<int16_t> pcm(sampleCount);
    voiceCodec_.decode(payload, payloadSize, pcm.data(), sampleCount);

    // Get or create the ring buffer for this client (mutex only for map access)
    RingBuffer* rb = nullptr;
    {
        std::lock_guard<std::mutex> lock(ringMapMutex_);
        auto it = clientRingBuffers_.find(originId);
        if (it == clientRingBuffers_.end()) {
            rb = new RingBuffer(RING_CAPACITY);
            clientRingBuffers_[originId] = rb;
            LOGI("Created ring buffer for client originId=%u", originId);
        } else {
            rb = it->second;
        }
    }

    // Write decoded PCM into the ring buffer. If the buffer is full (extreme
    // backlog), drop the oldest data by clearing and rewriting — this prevents
    // a growing delay if the output thread stalls temporarily.
    size_t written = rb->write(pcm.data(), sampleCount);
    if (written < sampleCount) {
        // Buffer was full: clear stale data and write fresh packet
        rb->clear();
        rb->write(pcm.data(), sampleCount);
        LOGD("Ring buffer full for originId=%u — cleared stale data", originId);
    }
}

void AudioEngine::setVadMode(int mode) {
    vad_.setMode(mode);
}

void AudioEngine::setMusicDucking(bool enabled) {
    mixer_.setDuckingEnabled(enabled);
}
