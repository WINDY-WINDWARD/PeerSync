#include "jitter_buffer.h"
#include <algorithm>

JitterBuffer::JitterBuffer(size_t depthInPackets)
    : targetDepth_(depthInPackets), nextExpectedSeq_(0), initialized_(false) {}

void JitterBuffer::pushFrame(uint16_t seqIndex, uint8_t flag, const int16_t* samples, size_t sampleCount) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (!initialized_) {
        nextExpectedSeq_ = seqIndex;
        initialized_ = true;
    }

    // Drop late packets
    int16_t diff = static_cast<int16_t>(seqIndex - nextExpectedSeq_);
    if (diff < 0) {
        return; // Drop late packet
    }

    AudioFrame frame;
    frame.sequenceIndex = seqIndex;
    frame.payloadFlag = flag;
    frame.pcmSamples.assign(samples, samples + sampleCount);

    buffer_[seqIndex] = std::move(frame);

    // Limit maximum buffer size to prevent memory inflation
    while (buffer_.size() > targetDepth_ * 4) {
        buffer_.erase(buffer_.begin());
    }
}

bool JitterBuffer::popFrame(int16_t* outSamples, size_t sampleCount, uint8_t& outFlag) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (!initialized_ || buffer_.empty()) {
        std::fill(outSamples, outSamples + sampleCount, static_cast<int16_t>(0));
        outFlag = 0x00;
        return false;
    }

    // Wait until buffer reaches target depth before starting pop
    if (buffer_.size() < targetDepth_ && nextExpectedSeq_ == buffer_.begin()->first) {
        std::fill(outSamples, outSamples + sampleCount, static_cast<int16_t>(0));
        outFlag = 0x00;
        return false;
    }

    auto it = buffer_.find(nextExpectedSeq_);
    if (it != buffer_.end()) {
        const auto& frame = it->second;
        size_t copyCount = std::min(sampleCount, frame.pcmSamples.size());
        std::copy(frame.pcmSamples.begin(), frame.pcmSamples.begin() + copyCount, outSamples);
        if (copyCount < sampleCount) {
            std::fill(outSamples + copyCount, outSamples + sampleCount, static_cast<int16_t>(0));
        }
        outFlag = frame.payloadFlag;
        buffer_.erase(it);
        nextExpectedSeq_++;
        return true;
    } else {
        // Missing frame (packet loss) -> advance expected seq & output concealment silence
        nextExpectedSeq_++;
        std::fill(outSamples, outSamples + sampleCount, static_cast<int16_t>(0));
        outFlag = 0x00;
        return false;
    }
}

void JitterBuffer::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    buffer_.clear();
    nextExpectedSeq_ = 0;
    initialized_ = false;
}
