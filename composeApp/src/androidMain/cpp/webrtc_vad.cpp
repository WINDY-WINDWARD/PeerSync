#include "webrtc_vad.h"
#include <cmath>
#include <numeric>

WebRtcVadEngine::WebRtcVadEngine() : mode_(2), energyThreshold_(500.0f) {}

void WebRtcVadEngine::setMode(int mode) {
    mode_ = mode;
    // Map mode 0..3 to energy & zero-crossing sensitivity thresholds
    switch (mode) {
        case 0: energyThreshold_ = 200.0f; break;
        case 1: energyThreshold_ = 400.0f; break;
        case 2: energyThreshold_ = 650.0f; break;
        case 3: energyThreshold_ = 1000.0f; break;
        default: energyThreshold_ = 650.0f; break;
    }
}

bool WebRtcVadEngine::isSpeech(const int16_t* pcmFrame, size_t sampleCount, int sampleRate) {
    if (pcmFrame == nullptr || sampleCount == 0) return false;

    // Calculate RMS energy of the 20ms frame
    double sumSquares = 0.0;
    int zeroCrossings = 0;

    for (size_t i = 0; i < sampleCount; ++i) {
        double sample = pcmFrame[i];
        sumSquares += sample * sample;

        if (i > 0) {
            if ((pcmFrame[i] ^ pcmFrame[i - 1]) < 0) {
                zeroCrossings++;
            }
        }
    }

    double rmsEnergy = std::sqrt(sumSquares / sampleCount);

    // Speech classification: RMS energy above threshold with typical vocal zero-crossing rate
    return (rmsEnergy >= energyThreshold_) && (zeroCrossings > 5) && (zeroCrossings < static_cast<int>(sampleCount / 2));
}
