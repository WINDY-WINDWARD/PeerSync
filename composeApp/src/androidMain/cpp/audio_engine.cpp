#include "audio_engine.h"
#include <android/log.h>

#define LOG_TAG "PeerSyncNativeAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

    if (AAudioStreamBuilder_openStream(builder, &inputStream_) != AAUDIO_OK) {
        LOGE("Failed to open AAudio input stream");
        AAudioStreamBuilder_delete(builder);
        return false;
    }

    // Output Stream (Speaker - 16kHz Mono VOICE_COMMUNICATION)
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_VOICE_COMMUNICATION);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_SPEECH);
    AAudioStreamBuilder_setDataCallback(builder, outCallback, this);

    if (AAudioStreamBuilder_openStream(builder, &outputStream_) != AAUDIO_OK) {
        LOGE("Failed to open AAudio output stream");
        AAudioStream_close(inputStream_);
        AAudioStreamBuilder_delete(builder);
        return false;
    }

    AAudioStreamBuilder_delete(builder);

    AAudioStream_requestStart(inputStream_);
    AAudioStream_requestStart(outputStream_);

    isRunning_.store(true);
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
    LOGI("AAudio Engine stopped");
}

aaudio_data_callback_result_t AudioEngine::onAudioInput(const int16_t* audioData, int32_t numFrames) {
    if (!isRunning_.load()) return AAUDIO_CALLBACK_RESULT_STOP;

    // Process Voice Frame (flag 0x01 = Voice)
    uint8_t flag = 0x01;

    uint8_t encodedBuffer[512];
    int encodedBytes = voiceCodec_.encode(audioData, numFrames, encodedBuffer, sizeof(encodedBuffer));

    if (frameCallback_ && encodedBytes > 0) {
        frameCallback_(flag, encodedBuffer, static_cast<size_t>(encodedBytes));
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t AudioEngine::onAudioOutput(int16_t* audioData, int32_t numFrames) {
    if (!isRunning_.load()) return AAUDIO_CALLBACK_RESULT_STOP;

    std::lock_guard<std::mutex> lock(jitterMutex_);
    std::map<uint8_t, const int16_t*> voiceStreams;
    std::vector<std::vector<int16_t>> decodedBuffers;

    for (auto& kv : clientJitterBuffers_) {
        std::vector<int16_t> framePcm(numFrames);
        uint8_t flag = 0;
        if (kv.second.popFrame(framePcm.data(), numFrames, flag) && flag == 0x01) {
            decodedBuffers.push_back(std::move(framePcm));
            voiceStreams[kv.first] = decodedBuffers.back().data();
        }
    }

    mixer_.mixFrame(voiceStreams, nullptr, audioData, numFrames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AudioEngine::feedReceivedPacket(uint8_t originId, uint8_t flag, const uint8_t* payload, size_t payloadSize) {
    std::lock_guard<std::mutex> lock(jitterMutex_);
    auto& jb = clientJitterBuffers_[originId];

    std::vector<int16_t> pcmSamples(payloadSize / sizeof(int16_t));
    voiceCodec_.decode(payload, payloadSize, pcmSamples.data(), pcmSamples.size());

    jb.pushFrame(sequenceIndex_++, flag, pcmSamples.data(), pcmSamples.size());
}

void AudioEngine::setVadMode(int mode) {
    vad_.setMode(mode);
}

void AudioEngine::setMusicDucking(bool enabled) {
    mixer_.setDuckingEnabled(enabled);
}
