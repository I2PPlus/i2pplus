package net.i2p.router.util;

/**
 * Interface for CoDel queue entries with enqueue time tracking.
 * <p>
 * Defines the contract for elements stored in CoDel
 * (Controlled Delay) queues. Provides methods for accessing
 * and managing enqueue timestamps for delay calculation.
 * <p>
 * Used by CoDelBlockingQueue and CoDelPriorityBlockingQueue
 * to implement Active Queue Management with timestamp-based
 * delay monitoring and packet dropping functionality.
 *
 * @since 0.9.3
 */
public interface CDQEntry {

    /**
     *  To be set by the queue
     */
    public void setEnqueueTime(long time);

    public long getEnqueueTime();

    /**
     *  Implement any reclaimation of resources here
     */
    public void drop();
}
