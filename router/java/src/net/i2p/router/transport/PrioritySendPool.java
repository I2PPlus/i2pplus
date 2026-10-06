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
 * on a private lock; contention is minimal because the thread that
 * offers almost always polls immediately (handoff pattern).
 *
 * <p>The live region is {@code [_head, _messages.size())}: dequeue is a plain
 * index bump, and the drained prefix is dropped wholesale once it is at least
 * half the array, which keeps the insertion shift proportional to the live
 * backlog rather than to capacity — at the tuned cap (cores * 512) a dequeue
 * used to copy the whole array under the lock.  Ordering is unchanged: the live
 * region is still sorted by descending priority then ascending sequence number,
 * so its first element is the highest-priority message and its last is the
 * eviction victim.
 *
 * @since 0.9.70+
 */
public final class PrioritySendPool {

    /**
     * Guards every field below.  Deliberately not {@code _messages}: compaction
     * swaps the backing list, and a monitor has to outlive the object it guards.
     */
    private final Object _lock = new Object();
    /**
     * Sorted live region is {@code [_head, size)}; {@code [0, _head)} is
     * dequeued-but-not-yet-discarded.  Replaced wholesale by compaction.
     */
    private ArrayList<OutNetMessage> _messages;
    /** Index of the highest-priority live message; guarded by {@link #_lock}. */
    private int _head;
    /** Dead prefix length above which the backing array is compacted. */
    private static final int COMPACT_MIN_HEAD = 64;
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
        synchronized (_lock) {
            int sz = _messages.size();
            if (sz - _head < _capacity) {
                _messages.add(insertPosition(msg, _head, sz), msg);
                _addedCount++;
                return true;
            }
            // Pool full — evict lowest if incoming is strictly higher priority
            OutNetMessage lowest = _messages.get(sz - 1);
            if (msg.getPriority() > lowest.getPriority()) {
                _messages.remove(sz - 1);
                _messages.add(insertPosition(msg, _head, sz - 1), msg);
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
        synchronized (_lock) {
            if (_head >= _messages.size()) {
                // Fully drained. The prefix below _head is all dead entries, so
                // it must go before the head can return to zero, otherwise the
                // next offer would resurrect already-sent messages.
                if (_head > 0) {
                    _messages.clear();
                    _head = 0;
                }
                return null;
            }
            OutNetMessage rv = _messages.get(_head++);
            // Discard the dead prefix once it dominates the array, so a long run
            // of dequeue-then-enqueue does not degrade every insert into a
            // full-capacity shift. ArrayList.removeRange(0, n) cannot be used
            // here: it truncates the tail without shifting the survivors down.
            if (_head >= COMPACT_MIN_HEAD && _head * 2 >= _messages.size()) {
                compact();
            }
            return rv;
        }
    }

    /**
     * Move the live region to the front of a freshly sized backing list.
     * Only called with {@link #_lock} held and the live region non-empty.
     */
    private void compact() {
        int live = _messages.size() - _head;
        ArrayList<OutNetMessage> fresh = new ArrayList<>(live);
        fresh.addAll(_messages.subList(_head, _messages.size()));
        _messages = fresh;
        _head = 0;
    }

    /**
     * Drain all messages into the target list (for resize).
     */
    public void drainTo(ArrayList<OutNetMessage> target) {
        synchronized (_lock) {
            target.addAll(_messages.subList(_head, _messages.size()));
            _messages.clear();
            _head = 0;
        }
    }

    /**
     * Number of messages currently in the pool.
     */
    public int size() {
        synchronized (_lock) {
            return _messages.size() - _head;
        }
    }

    /**
     * Free capacity remaining in the pool.
     */
    public int remainingCapacity() {
        synchronized (_lock) {
            return _capacity - (_messages.size() - _head);
        }
    }

    /**
     * The configured pool capacity.
     * @return the capacity
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
     * @return the added count
     */
    public int getAddedCount() { return _addedCount; }
    /**
     * The number of messages evicted since construction.
     * @return the evicted count
     */
    public int getEvictedCount() { return _evictedCount; }
    /**
     * The number of messages dropped since construction.
     * @return the dropped count
     */
    public int getDroppedCount() { return _droppedCount; }

    /**
     * Binary search for the insertion point — highest priority first, FIFO
     * within same priority (lower seqnum first).  The caller performs the
     * insertion, so both call sites share the comparison but keep their own
     * bookkeeping.
     *
     * @return the index within {@code [from, to)} at which msg must be inserted
     */
    private int insertPosition(OutNetMessage msg, int from, int to) {
        int lo = from;
        int hi = to;
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
        return lo;
    }
}
