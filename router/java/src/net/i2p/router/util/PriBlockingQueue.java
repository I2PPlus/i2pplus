package net.i2p.router.util;

import java.io.Serializable;
import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;
import net.i2p.stat.RateConstants;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Priority blocking queue with bounded capacity and FIFO ordering within priority levels.
 * <p>
 * Extends PriorityBlockingQueue to provide bounded capacity with
 * FIFO ordering for elements sharing the same priority. Entries
 * extend PQEntry to include sequence numbers for insertion ordering.
 * <p>
 * Applies sequence timestamps on insertion to ensure FIFO behavior
 * within same priority level while maintaining priority-based ordering.
 * Capacity is controlled by configuration properties to prevent
 * unbounded growth and enable backpressure management.
 * <p>
 * Thread-safe implementation suitable for concurrent producer-consumer
 * scenarios with comprehensive statistics tracking and performance monitoring.
 *
 * @param <E>  type of elements held in this queue, extending {@link PQEntry}
 *
 * @since 0.9.3
 */
public class PriBlockingQueue<E extends PQEntry> extends PriorityBlockingQueue<E> {

    private static final long serialVersionUID = 1L;

    protected transient final I2PAppContext _context;
    protected transient final Log _log;
    protected final String _name;

    /**
     * Atomic sequence number for entries to ensure insertion order among equals
     * in priority.
     */
    private final AtomicLong _seqNum = new AtomicLong();

    /**
     * Cached maximum queue size limit to avoid repeated config property lookups.
     */
    private final int _maxSize;

    /**
     * Stat name to track full queue events.
     */
    private final String STAT_FULL;

    /**
     * Time windows for rate statistics in milliseconds.
     */
    protected static final long[] RATES = RateConstants.STANDARD_RATES;

    /**
     * Default backlog and max size depending on system speed.
     */
    protected static final int DEFAULT_BACKLOG_SIZE = SystemVersion.isSlow() ? 256 : 384;
    protected static final int DEFAULT_MAX_SIZE = SystemVersion.isSlow() ? 512 : 1024;

    /**
     * Configuration property keys for max size and backlog thresholds.
     */
    public static final String PROP_MAX_SIZE = "router.codelMaxQueue";
    public static final String PROP_BACKLOG_SIZE = "router.codelBacklog";

    /**
     * Singleton comparator instance to avoid repeated creation.
     */
    private static final PriorityComparator<?> PRIORITY_COMPARATOR = new PriorityComparator<>();

    /**
     * Constructs a new priority blocking queue with the given initial capacity.
     * The queue is bounded by a configurable max size read once at construction.
     *
     * @param ctx             the I2P application context
     * @param name            a name for this queue instance (used in stats)
     * @param initialCapacity the initial capacity for the priority queue
     */
    @SuppressWarnings("unchecked")
    public PriBlockingQueue(I2PAppContext ctx, String name, int initialCapacity) {
        super(initialCapacity, (Comparator<E>) PRIORITY_COMPARATOR);
        _context = ctx;
        _log = ctx.logManager().getLog(PriBlockingQueue.class);
        _name = name;
        STAT_FULL = ("pbq." + name + ".full").intern();
        ctx.statManager().createRateStat(STAT_FULL, "Priority Blocking Queue full", "Router [PriorityBlockingQueue]", RATES);

        // Cache max size on construction to avoid repeated property lookups
        _maxSize = ctx.getProperty(PROP_MAX_SIZE, DEFAULT_MAX_SIZE);
    }

    /**
     * Inserts the specified element into this queue if it is not full,
     * applying sequence timestamp to ensure FIFO ordering within priority.
     * Returns false if the queue has reached its maximum size.
     *
     * @param o the element to add
     * @return true if successfully added, false if queue is full
     */
    @Override
    public boolean offer(E o) {
        timestamp(o);
        if (size() >= _maxSize) {
            _context.statManager().addRateData(STAT_FULL, 1);
            return false;
        }
        return super.offer(o);
    }

    /**
     * Checks if the queue size is above the backlog threshold, indicating
     * potential congestion or overload.
     *
     * @return true if queue size is above backlog size threshold, false otherwise
     */
    public boolean isBacklogged() {
        return size() >= _context.getProperty(PROP_BACKLOG_SIZE, DEFAULT_BACKLOG_SIZE);
    }

    /**
     * Assigns a unique sequence number to the element to maintain FIFO ordering
     * among elements of the same priority.
     *
     * @param o the element to timestamp
     */
    protected void timestamp(E o) {
        o.setSeqNum(_seqNum.incrementAndGet());
    }

    /**
     * Comparator used to order queue elements by descending priority, then
     * ascending sequence number to preserve FIFO inside priority.
     *
     * @param <E> type of elements extending PQEntry
     */
    private static class PriorityComparator<E extends PQEntry> implements Comparator<E>, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public int compare(E l, E r) {
            int priorityCompare = Integer.compare(r.getPriority(), l.getPriority());
            if (priorityCompare != 0)
                return priorityCompare;
            return Long.compare(l.getSeqNum(), r.getSeqNum());
        }
    }
}
