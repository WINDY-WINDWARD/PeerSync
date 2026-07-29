#ifndef PEERSYNC_RING_BUFFER_H
#define PEERSYNC_RING_BUFFER_H

#include <vector>
#include <atomic>
#include <cstdint>
#include <cstddef>

/**
 * Lock-free, thread-safe Single-Producer Single-Consumer (SPSC) Ring Buffer for 16-bit PCM audio samples.
 */
class RingBuffer {
public:
    explicit RingBuffer(size_t capacity);
    ~RingBuffer() = default;

    size_t write(const int16_t* data, size_t count);
    size_t read(int16_t* data, size_t count);

    size_t availableRead() const;
    size_t availableWrite() const;
    void clear();

private:
    std::vector<int16_t> buffer_;
    size_t capacity_;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
    std::atomic<uint64_t> generation_{0};  // For synchronizing concurrent clear()
};

#endif // PEERSYNC_RING_BUFFER_H
