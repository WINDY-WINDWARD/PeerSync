#ifndef PEERSYNC_WEBRTC_VAD_H
#define PEERSYNC_WEBRTC_VAD_H

#include <cstdint>
#include <cstddef>

class WebRtcVadEngine {
public:
    WebRtcVadEngine();
    ~WebRtcVadEngine() = default;

    void setMode(int mode); // 0, 1, 2, or 3
    bool isSpeech(const int16_t* pcmFrame, size_t sampleCount, int sampleRate = 16000);

private:
    int mode_{2};
    float energyThreshold_{500.0f};
};

#endif // PEERSYNC_WEBRTC_VAD_H
