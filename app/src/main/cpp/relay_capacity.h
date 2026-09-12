#pragma once
#include <atomic>
#include <cstdint>

namespace bubble {
/** One acquisition worker, potentially concurrent SurfaceControl callbacks.
 *
 * A MAX_IMAGES result describes the instant of the acquire call, not the state
 * when we later arm a retry. A release can occur between those two operations.
 * Keep the release epoch and waiter bit in ONE atomic word: either arming sees
 * that intervening release and requests a retry, or the releasing callback sees
 * the waiter and wakes it. Ordinary releases with no waiter do not wake the Rx
 * thread. This gates notification only; it never owns images or changes fences.
 */
class RelayCapacity {
    static constexpr uint64_t kWaiting = 1;
    std::atomic<uint64_t> state_{0}; // Upper 63 bits: release epoch; low bit: waiter.
 public:
    // A frame/rate notification may start a pass while an old capacity request
    // is still armed. That active pass supersedes the request. Stop has its own
    // unconditional eventfd notification and does not rely on this gate.
    void beginPass() { state_.fetch_and(~kWaiting); }
    uint64_t beforeAcquire() const { return state_.load() & ~kWaiting; }

    // Called after MAX_IMAGES or an acquire error, with the PRE-acquire snapshot.
    // true = a release has already intervened; schedule a bounded retry ourselves.
    bool waitAfterBlockedAcquire(uint64_t before) {
        return !state_.compare_exchange_strong(before, before | kWaiting);
    }

    // Called AFTER AImage_deleteAsync returns capacity to the reader, never when
    // a GPU fence merely exists. Clear the waiter in the same atomic update.
    // true = notify the acquisition worker; false = no capacity waiter to notify.
    bool returned() {
        uint64_t before = state_.load();
        for (;;) {
            const uint64_t after = (before & ~kWaiting) + 2;
            if (state_.compare_exchange_weak(before, after))
                return (before & kWaiting) != 0;
        }
    }
};
} // namespace bubble
