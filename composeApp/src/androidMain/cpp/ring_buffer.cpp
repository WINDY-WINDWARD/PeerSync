#include "ring_buffer.h"
#include <algorithm>
#include <cstring>

RingBuffer::RingBuffer(size_t capacity)
    : capacity_(capacity + 1), buffer_(capacity + 1) {}

size_t RingBuffer::write(const int16_t* data, size_t count) {
    size_t head = head_.load(std::memory_order_relaxed);
    size_t tail = tail_.load(std::memory_order_acquire);

    size_t available = (tail > head) ? (tail - head - 1) : (capacity_ - head + tail - 1);
    size_t toWrite = std::min(count, available);

    if (toWrite == 0) return 0;

    size_t firstPart = std::min(toWrite, capacity_ - head);
    std::memcpy(&buffer_[head], data, firstPart * sizeof(int16_t));

    size_t secondPart = toWrite - firstPart;
    if (secondPart > 0) {
        std::memcpy(&buffer_[0], data + firstPart, secondPart * sizeof(int16_t));
    }

    head_.store((head + toWrite) % capacity_, std::memory_order_release);
    return toWrite;
}

size_t RingBuffer::read(int16_t* data, size_t count) {
    size_t head = head_.load(std::memory_order_acquire);
    size_t tail = tail_.load(std::memory_order_relaxed);

    size_t available = (head >= tail) ? (head - tail) : (capacity_ - tail + head);
    size_t toRead = std::min(count, available);

    if (toRead == 0) return 0;

    size_t firstPart = std::min(toRead, capacity_ - tail);
    std::memcpy(data, &buffer_[tail], firstPart * sizeof(int16_t));

    size_t secondPart = toRead - firstPart;
    if (secondPart > 0) {
        std::memcpy(data + firstPart, &buffer_[0], secondPart * sizeof(int16_t));
    }

    tail_.store((tail + toRead) % capacity_, std::memory_order_release);
    return toRead;
}

size_t RingBuffer::availableRead() const {
    size_t head = head_.load(std::memory_order_relaxed);
    size_t tail = tail_.load(std::memory_order_relaxed);
    return (head >= tail) ? (head - tail) : (capacity_ - tail + head);
}

size_t RingBuffer::availableWrite() const {
    return capacity_ - 1 - availableRead();
}

void RingBuffer::clear() {
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
}
