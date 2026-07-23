#ifndef PEERSYNC_AUDIO_ENGINE_H
#define PEERSYNC_AUDIO_ENGINE_H

#include "ring_buffer.h"
#include "jitter_buffer.h"
#include "mixer.h"
#include "webrtc_vad.h"
#include "opus_codec.h"

#include <aaudio/AAudio.h>
#include <map>
#include <mutex>
#include <atomic>

class AudioEngine {
public:
    AudioEngine();
    ~AudioEngine();

    bool start();
    void stop();

    void feedReceivedPacket(uint8_t originId, uint8_t flag, const uint8_t* payload, size_t payloadSize);
    void setVadMode(int mode);
    void setMusicDucking(bool enabled);

    // Callbacks for JNI
    typedef void (*AudioFrameCallback)(uint8_t flag, const uint8_t* encodedData, size_t size);
    void setFrameCallback(AudioFrameCallback cb) { frameCallback_ = cb; }

    aaudio_data_callback_result_t onAudioInput(const int16_t* audioData, int32_t numFrames);
    aaudio_data_callback_result_t onAudioOutput(int16_t* audioData, int32_t numFrames);

private:
    AAudioStream* inputStream_{nullptr};
    AAudioStream* outputStream_{nullptr};

    std::atomic<bool> isRunning_{false};
    uint16_t sequenceIndex_{0};

    RingBuffer inputRingBuffer_{16000 * 2};
    AudioMixer mixer_;
    WebRtcVadEngine vad_;
    OpusCodecEngine voiceCodec_{OpusMode::VOICE_16K_MONO};

    std::map<uint8_t, JitterBuffer> clientJitterBuffers_;
    std::mutex jitterMutex_;

    AudioFrameCallback frameCallback_{nullptr};
};

#endif // PEERSYNC_AUDIO_ENGINE_H
