#pragma once
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <sys/eventfd.h>
#include <unistd.h>

namespace bubble {
// Many producers, one consumer. Clear pending BEFORE inspecting the work queues.
// A notification merged into the current wake is therefore observed by that pass;
// notifications after the clear install another event. No timeout/idle polling.
class RelayWake {
    int fd_ = -1;
    std::atomic<bool> pending_{false};
 public:
    RelayWake() = default;
    RelayWake(const RelayWake&) = delete;
    RelayWake& operator=(const RelayWake&) = delete;
    ~RelayWake() { if (fd_ >= 0) close(fd_); }
    bool open() { fd_ = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC); return fd_ >= 0; }
    int fd() const { return fd_; }
    bool notify() {
        if (fd_ < 0) return false;
        if (pending_.exchange(true)) return true;
        const uint64_t one = 1;
        ssize_t result;
        do { result = write(fd_, &one, sizeof(one)); } while (result < 0 && errno == EINTR);
        // EAGAIN means the descriptor is already readable, which is sufficient.
        if (result == sizeof(one) || (result < 0 && errno == EAGAIN)) return true;
        pending_.store(false);
        return false;
    }
    bool beginPass() {
        uint64_t count = 0;
        ssize_t result;
        do { result = read(fd_, &count, sizeof(count)); } while (result < 0 && errno == EINTR);
        if (result != sizeof(count)) return false;
        pending_.store(false);
        return true;
    }
};
} // namespace bubble
