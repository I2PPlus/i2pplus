package net.i2p.router.util;

/**
 * Interface for priority queue entries with sequence tracking.
 * <p>
 * Defines the contract for elements stored in priority-based
 * blocking queues. Provides methods for accessing priority
 * levels and sequence numbers to ensure proper FIFO ordering
 * within same priority levels.
 * <p>
 * Used by PriBlockingQueue and CoDelPriorityBlockingQueue
 * to maintain insertion order while supporting priority-based
 * selection and removal operations.
 *
 * @since 0.9.3
 */
public interface PQEntry {

    /**
     *  Higher is higher priority
     */
    public int getPriority();

    /**
     *  To be set by the queue
     */
    public void setSeqNum(long num);

    /**
     *  Needed to ensure FIFO ordering within a single priority
     */
    public long getSeqNum();
}
