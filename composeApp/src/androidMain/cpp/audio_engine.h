#ifndef PEERSYNC_AUDIO_ENGINE_H
#define PEERSYNC_AUDIO_ENGINE_H

#include "ring_buffer.h"
#include "mixer.h"
#include "webrtc_vad.h"
#include "opus_codec.h"

#include <aaudio/AAudio.h>
#include <mutex>
#include <atomic>
#include <vector>

class AudioEngine {
public:
    AudioEngine();
    ~AudioEngine();

    bool start(int sessionId);
    void stop();

    void setDeviceIds(int inputDeviceId, int outputDeviceId);
    bool isRunning() const { return isRunning_.load(); }

    void feedReceivedPacket(uint8_t originId, uint8_t flag, uint16_t sequenceIndex, const uint8_t* payload, size_t payloadSize);
    
    // Feed locally decoded 16kHz mono music to be mixed into the microphone stream
    size_t feedLocalMusic(const int16_t* pcm, size_t sampleCount);
    size_t getLocalMusicFreeSpace();
    void clearLocalMusicBuffers();

    void setVadMode(int mode);
    void setMusicDucking(bool enabled);
    void setMyOriginId(uint8_t id) { myOriginId_.store(id); }
    void setMicMuted(bool muted) { micMuted_.store(muted); }
    void setLocalMusicGain(float gain) { localMusicGain_.store(gain, std::memory_order_relaxed); }
    void setPeerGain(uint8_t originId, float gain) {
        peerGains_[originId].store(gain, std::memory_order_relaxed);
    }

    // Callbacks for JNI
    typedef void (*AudioFrameCallback)(uint8_t flag, const uint8_t* encodedData, size_t size);
    void setFrameCallback(AudioFrameCallback cb) { frameCallback_ = cb; }

    // Notifies the Kotlin layer (via JNI bridge) that a stream errored/disconnected.
    typedef void (*StreamErrorCallback)(const char* errorMessage);
    void setStreamErrorCallback(StreamErrorCallback cb) { streamErrorCallback_ = cb; }

    // Called from Kotlin when a peer leaves or rejoins (endpoint changed) so
    // a restarted sender's sequence (from 0) is accepted as a fresh stream.
    void resetPeerSeq(uint8_t originId) { hasSeq_[originId].store(false, std::memory_order_relaxed); }

    static void OnStreamError(AAudioStream* stream, void* userData, aaudio_result_t error);

    aaudio_data_callback_result_t onAudioInput(const int16_t* audioData, int32_t numFrames);
    aaudio_data_callback_result_t onAudioOutput(int16_t* audioData, int32_t numFrames);

private:
    static constexpr size_t VOICE_FRAME_SAMPLES = 320; // 20ms @ 16kHz mono, network-safe packet size

    // Upper bound for one AAudio callback burst at 16kHz (typical: 160-480).
    static constexpr size_t MAX_CALLBACK_FRAMES = 2048;
    // Max simultaneous peers mixed per callback (pre-allocated scratch slots).
    static constexpr size_t MAX_MIX_PEERS = 16;
    // Max decodable samples per received packet (40ms @ 16kHz).
    static constexpr size_t MAX_DECODE_SAMPLES = VOICE_FRAME_SAMPLES * 4;
    // Fixed circular capture accumulator capacity (200ms @ 16kHz).
    static constexpr size_t CAPTURE_RING_SAMPLES = VOICE_FRAME_SAMPLES * 10;

    AAudioStream* inputStream_{nullptr};
    AAudioStream* outputStream_{nullptr};

    int inputChannelCount_{1};
    int outputChannelCount_{1};
    int inputSampleRate_{16000};
    int outputSampleRate_{16000};

    std::atomic<bool> isRunning_{false};
    std::atomic<uint8_t> myOriginId_{255}; // 255 = unset; set before audio starts
    std::atomic<bool> micMuted_{false};
    std::atomic<int> preferredInputDeviceId_{0};
    std::atomic<int> preferredOutputDeviceId_{0};

    AudioMixer mixer_;
    WebRtcVadEngine vad_;
    OpusCodecEngine voiceCodec_{OpusMode::VOICE_16K_MONO};

    // Per-client lock-free ring buffers: 1 second at 16 kHz = 16000 samples.
    // Keyed by originId. Insertions happen only on the JNI/network thread;
    // reads happen on the AAudio output thread. We use fixed arrays of atomic
    // pointers to ensure 100% thread-safety without locks during audio processing.
    static constexpr size_t RING_CAPACITY = 16000;

    // 60ms jitter cushion @ 16kHz mono = 960 samples.
    // onAudioOutput waits until this many samples are buffered before first drain.
    // After that, we read whatever is available and zero-pad; we only re-arm the
    // cushion after VOICE_STARVATION_LIMIT consecutive all-empty callbacks
    // (sustained dropout, not a single late packet).
    static constexpr size_t VOICE_JITTER_CUSHION   = 960;
    // ~200ms of consecutive silence (AAudio typically fires every 5-10ms +' 20-40 callbacks).
    static constexpr int    VOICE_STARVATION_LIMIT  = 30;

    std::atomic<RingBuffer*> clientRingBuffers_[256];
    // true = waiting for initial cushion; false = draining. Audio-thread only.
    bool clientBuffering_[256];
    // Consecutive all-empty callback counter per client. Audio-thread only.
    int  clientStarveCount_[256];
    std::mutex ringMapMutex_; // Guards ring lifecycle ONLY (create/clear/delete). Never held on the audio thread or the per-packet write path.
    
    // Holds the locally decoded music (16kHz mono) to be mixed into the mic stream and speaker.
    // Capacity is 2 seconds (32000 samples).
    RingBuffer localMusicInRingBuffer_{32000};
    RingBuffer localMusicOutRingBuffer_{32000};

    std::atomic<float> localMusicGain_{1.0f};

    std::atomic<float> peerGains_[256];

    std::vector<int16_t> inputMonoScratch_;
    std::vector<int16_t> outputMonoScratch_;
    std::vector<int16_t> localMusicInScratch_;
    std::vector<int16_t> localMusicOutScratch_;
    
    // Pre-allocated per-peer mixing scratch — the AAudio output thread must
    // never allocate. Slot count is fixed; samples per slot bounded by
    // MAX_CALLBACK_FRAMES.
    int16_t peerMixScratch_[MAX_MIX_PEERS][MAX_CALLBACK_FRAMES];
    // Network-thread decode scratch (replaces per-packet heap vector).
    int16_t decodeScratch_[MAX_DECODE_SAMPLES];
    // Drain target for active peers beyond MAX_MIX_PEERS (prevents ring wedge).
    int16_t drainScratch_[MAX_CALLBACK_FRAMES];
    bool mixTruncationLogged_{false}; // audio-thread only
    // Duplicate/late packet filtering (UDP delivers unordered). Written only
    // on the network thread; reset under ringMapMutex_ in stop().
    std::atomic<uint16_t> lastSeq_[256];
    std::atomic<bool>     hasSeq_[256];
    // Fixed circular capture accumulator (input thread only — no atomics).
    int16_t captureRing_[CAPTURE_RING_SAMPLES];
    size_t captureHead_{0};  // index of oldest stored sample
    size_t captureCount_{0}; // number of samples currently stored

    AudioFrameCallback frameCallback_{nullptr};
    StreamErrorCallback streamErrorCallback_{nullptr};
};

#endif // PEERSYNC_AUDIO_ENGINE_H
