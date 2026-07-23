#ifndef PEERSYNC_AUDIO_ENGINE_H
#define PEERSYNC_AUDIO_ENGINE_H

#include "ring_buffer.h"
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
    void setMyOriginId(uint8_t id) { myOriginId_.store(id); }
    void setMicMuted(bool muted) { micMuted_.store(muted); }

    // Callbacks for JNI
    typedef void (*AudioFrameCallback)(uint8_t flag, const uint8_t* encodedData, size_t size);
    void setFrameCallback(AudioFrameCallback cb) { frameCallback_ = cb; }

    // Notifies the Kotlin layer (via JNI bridge) that a stream errored/disconnected.
    typedef void (*StreamErrorCallback)(const char* errorMessage);
    void setStreamErrorCallback(StreamErrorCallback cb) { streamErrorCallback_ = cb; }

    static void OnStreamError(AAudioStream* stream, void* userData, aaudio_result_t error);

    aaudio_data_callback_result_t onAudioInput(const int16_t* audioData, int32_t numFrames);
    aaudio_data_callback_result_t onAudioOutput(int16_t* audioData, int32_t numFrames);

private:
    AAudioStream* inputStream_{nullptr};
    AAudioStream* outputStream_{nullptr};

    std::atomic<bool> isRunning_{false};
    std::atomic<uint8_t> myOriginId_{255}; // 255 = unset; set before audio starts
    std::atomic<bool> micMuted_{false};

    AudioMixer mixer_;
    WebRtcVadEngine vad_;
    OpusCodecEngine voiceCodec_{OpusMode::VOICE_16K_MONO};

    // Per-client lock-free ring buffers: 1 second at 16 kHz = 16000 samples.
    // Keyed by originId. Insertions happen only on the JNI/network thread;
    // reads happen on the AAudio output thread. The mutex guards map mutation
    // only — RingBuffer::write/read themselves are lock-free (SPSC atomics).
    static constexpr size_t RING_CAPACITY = 16000;
    std::map<uint8_t, RingBuffer*> clientRingBuffers_;
    std::mutex ringMapMutex_;

    AudioFrameCallback frameCallback_{nullptr};
    StreamErrorCallback streamErrorCallback_{nullptr};
};

#endif // PEERSYNC_AUDIO_ENGINE_H
