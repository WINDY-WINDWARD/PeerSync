#ifndef PEERSYNC_OPUS_CODEC_H
#define PEERSYNC_OPUS_CODEC_H

#include <cstdint>
#include <cstddef>
#include <vector>

enum class OpusMode {
    VOICE_16K_MONO,
    MUSIC_44K_STEREO
};

class OpusCodecEngine {
public:
    explicit OpusCodecEngine(OpusMode mode);
    ~OpusCodecEngine() = default;

    int encode(const int16_t* pcmIn, size_t sampleCount, uint8_t* compressedOut, size_t maxOutBytes);
    int decode(const uint8_t* compressedIn, size_t inBytes, int16_t* pcmOut, size_t maxOutSamples);

private:
    OpusMode mode_;
    int sampleRate_{16000};
    int channels_{1};
    int bitrate_{24000};
};

#endif // PEERSYNC_OPUS_CODEC_H
