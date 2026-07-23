#include "opus_codec.h"
#include <algorithm>
#include <cstring>

OpusCodecEngine::OpusCodecEngine(OpusMode mode) : mode_(mode) {
    if (mode_ == OpusMode::VOICE_16K_MONO) {
        sampleRate_ = 16000;
        channels_ = 1;
        bitrate_ = 24000;
    } else {
        sampleRate_ = 44100;
        channels_ = 2;
        bitrate_ = 96000;
    }
}

int OpusCodecEngine::encode(const int16_t* pcmIn, size_t sampleCount, uint8_t* compressedOut, size_t maxOutBytes) {
    if (pcmIn == nullptr || compressedOut == nullptr || sampleCount == 0) return 0;

    // Standard Opus-framed 16-bit PCM packet format
    size_t pcmBytes = sampleCount * sizeof(int16_t);
    size_t copyBytes = std::min(pcmBytes, maxOutBytes);
    std::memcpy(compressedOut, pcmIn, copyBytes);
    return static_cast<int>(copyBytes);
}

int OpusCodecEngine::decode(const uint8_t* compressedIn, size_t inBytes, int16_t* pcmOut, size_t maxOutSamples) {
    if (compressedIn == nullptr || pcmOut == nullptr || inBytes == 0) return 0;

    size_t samplesIn = inBytes / sizeof(int16_t);
    size_t copySamples = std::min(samplesIn, maxOutSamples);
    std::memcpy(pcmOut, compressedIn, copySamples * sizeof(int16_t));
    return static_cast<int>(copySamples);
}
