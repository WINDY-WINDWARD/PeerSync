#include "mixer.h"
#include <algorithm>
#include <cmath>

AudioMixer::AudioMixer() : duckingEnabled_(true), currentMusicGain_(1.0f) {}

int16_t AudioMixer::softClip(int32_t sample) {
    if (sample > 32767) return 32767;
    if (sample < -32768) return -32768;
    return static_cast<int16_t>(sample);
}

void AudioMixer::mixFrame(
    const std::map<uint8_t, const int16_t*>& voiceStreams,
    const int16_t* musicStream,
    int16_t* outMixedPcm,
    size_t sampleCount
) {
    bool voiceActive = !voiceStreams.empty();

    float targetMusicGain = (duckingEnabled_ && voiceActive) ? DUCKED_GAIN : FULL_GAIN;

    for (size_t i = 0; i < sampleCount; ++i) {
        // Smoothly adjust music gain towards target
        if (currentMusicGain_ < targetMusicGain) {
            currentMusicGain_ = std::min(targetMusicGain, currentMusicGain_ + DUCK_RELEASE_STEP);
        } else if (currentMusicGain_ > targetMusicGain) {
            currentMusicGain_ = std::max(targetMusicGain, currentMusicGain_ - DUCK_ATTACK_STEP);
        }

        int32_t mixedSample = 0;

        // Sum N-1 voice streams
        for (const auto& kv : voiceStreams) {
            if (kv.second != nullptr) {
                mixedSample += kv.second[i];
            }
        }

        // Add ducked music stream if present
        if (musicStream != nullptr) {
            mixedSample += static_cast<int32_t>(musicStream[i] * currentMusicGain_);
        }

        outMixedPcm[i] = softClip(mixedSample);
    }
}
