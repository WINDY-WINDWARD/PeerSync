#ifndef PEERSYNC_MIXER_H
#define PEERSYNC_MIXER_H

#include <cstdint>
#include <cstddef>

class AudioMixer {
public:
    AudioMixer();
    ~AudioMixer() = default;

    // voiceStreams: contiguous array of pointers to per-peer PCM buffers,
    // each holding sampleCount samples. No std::map — built per-callback
    // by the audio thread from pre-allocated scratch memory.
    void mixFrame(
        const int16_t* const* voiceStreams,
        size_t streamCount,
        const int16_t* musicStream,
        int16_t* outMixedPcm,
        size_t sampleCount
    );

    void setDuckingEnabled(bool enabled) { duckingEnabled_ = enabled; }

private:
    bool duckingEnabled_{true};
    float currentMusicGain_{1.0f};

    // Smooth gain interpolation limits per sample
    static constexpr float DUCKED_GAIN = 0.40f; // 60% attenuation (-8 dB)
    static constexpr float FULL_GAIN = 1.0f;
    static constexpr float DUCK_ATTACK_STEP = 0.005f; // ~50ms attack
    static constexpr float DUCK_RELEASE_STEP = 0.001f; // ~250ms release

    static int16_t softClip(int32_t sample);
};

#endif // PEERSYNC_MIXER_H
