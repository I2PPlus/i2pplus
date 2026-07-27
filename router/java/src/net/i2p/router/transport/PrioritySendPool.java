package net.i2p.router.transport;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.router.OutNetMessage;

/**
 * Priority-aware bounded send pool for NTCP transport.
 *
 * <p>Client messages (PRIORITY_MY_DATA=1000) are always dequeued before
 * transit messages (PRIORITY_PARTICIPATING=200). When the pool is full
 * and a higher-priority message arrives, the lowest-priority message
 * is evicted. Non-blocking: never stalls the caller.
 *
 * <p>Sorted array with binary search insertion — optimal for the small
 * bounded sizes (64–8192) used by the NTCP send path. Synchronized
 * on the backing list; contention is minimal because the thread that
 * offers almost always polls immediately (handoff pattern).
 *
 * @since 0.9.70+
 */
public final class PrioritySendPool {

    private final ArrayList<OutNetMessage> _messages;
    private final AtomicLong _seqNum = new AtomicLong();
    private volatile int _capacity;

    // Monotonically increasing counters — callers read diff over interval
    private volatile int _addedCount;
    private volatile int _evictedCount;
    private volatile int _droppedCount;

    /**
     * PrioritySendPool.
     */
    public PrioritySendPool(int capacity) {
        _capacity = Math.max(1, capacity);
        _messages = new ArrayList<>(_capacity);
    }

    /**
     * Non-blocking offer with priority eviction.
     *
     * <p>If the pool has room, the message is inserted in sorted position
     * and the method returns {@code true}. If the pool is full and the
     * incoming message has strictly higher priority than the lowest-priority
     * message in the pool, the lowest is evicted and the new message takes
     * its place. Otherwise the incoming message is dropped.
     *
     * @param msg the outbound message to enqueue
     * @return {@code true} if added, {@code false} if dropped
     */
    public boolean offer(OutNetMessage msg) {
        msg.setSeqNum(_seqNum.incrementAndGet());
        synchronized (_messages) {
            int sz = _messages.size();
            if (sz < _capacity) {
                insertSorted(msg, sz);
                _addedCount++;
                return true;
            }
            // Pool full — evict lowest if incoming is strictly higher priority
            OutNetMessage lowest = _messages.get(sz - 1);
            if (msg.getPriority() > lowest.getPriority()) {
                _messages.remove(sz - 1);
                insertSorted(msg, sz - 1);
                _evictedCount++;
                _addedCount++;
                return true;
            }
            _droppedCount++;
            return false;
        }
    }

    /**
     * Return the highest-priority message, or null if empty.
     * Highest priority first, FIFO within same priority.
     */
    public OutNetMessage poll() {
        synchronized (_messages) {
            if (_messages.isEmpty()) {
                return null;
            }
            return _messages.remove(0);
        }
    }

    /**
     * Drain all messages into the target list (for resize).
     */
    public void drainTo(ArrayList<OutNetMessage> target) {
        synchronized (_messages) {
            target.addAll(_messages);
            _messages.clear();
        }
    }

    /**
     * size.
     */
    public int size() {
        synchronized (_messages) {
            return _messages.size();
        }
    }

    /**
     * remainingCapacity.
     */
    public int remainingCapacity() {
        synchronized (_messages) {
            return _capacity - _messages.size();
        }
    }

    /**
     * getCapacity.
     */
    public int getCapacity() {
        return _capacity;
    }

    /**
     * Resize the pool. New capacity takes effect immediately;
     * if the pool currently exceeds the new capacity, excess
     * low-priority messages are evicted on the next offer().
     */
    public void setCapacity(int newCapacity) {
        _capacity = Math.max(1, newCapacity);
    }

    /**
     * Snapshot of added/evicted/dropped counters since construction.
     * Callers compute deltas over a time window.
     */
    public int getAddedCount() { return _addedCount; }
    /**
     * getEvictedCount.
     */
    public int getEvictedCount() { return _evictedCount; }
    /**
     * getDroppedCount.
     */
    public int getDroppedCount() { return _droppedCount; }

    /**
     * Binary search insertion — highest priority at index 0, FIFO within
     * same priority (lower seqnum first).
     */
    private void insertSorted(OutNetMessage msg, int sz) {
        int lo = 0;
        int hi = sz;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            OutNetMessage existing = _messages.get(mid);
            int cmp = Integer.compare(existing.getPriority(), msg.getPriority());
            if (cmp < 0) {
                // existing < msg  → msg goes before (lower index)
                hi = mid;
            } else if (cmp > 0) {
                // existing > msg  → msg goes after
                lo = mid + 1;
            } else {
                // same priority — FIFO: lower seqnum first
                cmp = Long.compare(existing.getSeqNum(), msg.getSeqNum());
                if (cmp <= 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
        }
        _messages.add(lo, msg);
    }
}
