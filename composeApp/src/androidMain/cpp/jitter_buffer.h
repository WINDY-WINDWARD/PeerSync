#ifndef PEERSYNC_JITTER_BUFFER_H
#define PEERSYNC_JITTER_BUFFER_H

#include <vector>
#include <map>
#include <mutex>
#include <cstdint>

struct AudioFrame {
    uint16_t sequenceIndex;
    uint8_t payloadFlag;
    std::vector<int16_t> pcmSamples;
};

class JitterBuffer {
public:
    explicit JitterBuffer(size_t depthInPackets = 3);
    ~JitterBuffer() = default;

    void pushFrame(uint16_t seqIndex, uint8_t flag, const int16_t* samples, size_t sampleCount);
    bool popFrame(int16_t* outSamples, size_t sampleCount, uint8_t& outFlag);
    void reset();

private:
    size_t targetDepth_;
    uint16_t nextExpectedSeq_{0};
    bool initialized_{false};
    std::map<uint16_t, AudioFrame> buffer_;
    std::mutex mutex_;
};

#endif // PEERSYNC_JITTER_BUFFER_H
