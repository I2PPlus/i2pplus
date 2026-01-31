package net.i2p.router.util;

import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.stat.RateConstants;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * CoDel (Controlled Delay) implementation of Active Queue Management.
 * <p>
 * Implements the CoDel algorithm from RFC 8289 for managing queue
 * congestion and controlling latency through packet dropping.
 * Provides active queue management with automatic dropping
 * when queue delay exceeds configurable thresholds.
 * <p>
 * Code and comments are derived from RFC 8289 appendix,
 * implementing the standard CoDel algorithm with configurable
 * target delay and interval parameters. Monitors queue sojourn
 * time and drops packets when above target to maintain
 * optimal throughput and latency characteristics.
 * <p>
 * Input methods are overridden to add timestamps for delay
 * calculation. Output methods implement AQM behavior with
 * comprehensive statistics tracking for performance monitoring.
 *
 * @param <E>  type of elements in this queue
 * @since 0.9.3
 */
public class CoDelBlockingQueue<E extends CDQEntry> extends LinkedBlockingQueue<E> {

    private static final long serialVersionUID = 1L;
    private transient final I2PAppContext _context;
    private transient final Log _log;
    private final String _name;
    private final int _capacity;

    // following 4 are state variables defined by sample code, locked by this
    /** Time when we'll declare we're above target (0 if below) */
    private long _first_above_time;
    /** Time to drop next packet */
    private long _drop_next;
    /** Packets dropped since going into drop state */
    private int _count;
    /** true if in drop state */
    private boolean _dropping;

    /** following is a per-request global for ease of use, locked by this */
    private long _now;

    /** debugging */
    private static final AtomicLong __id = new AtomicLong();
    private final long _id;

    private static final long[] CODEL_RATES = RateConstants.SHORT_TERM_RATES;

    public static final String PROP_CODEL_TARGET = "router.codelTarget";
    public static final String PROP_CODEL_INTERVAL = "router.codelInterval";

    /**
     *  Quote:
     *  Below a target of 5 ms, utilization suffers for some conditions and traffic loads;
     *  above 5 ms there is very little or no improvement in utilization.
     *
     *  I2P: Raise to 15 due to multithreading environment
     *
     */
    private static final int DEFAULT_CODEL_TARGET = 10;
    private final long _target;

    /**
     *  Quote:
     *  A setting of 100 ms works well across a range of RTTs from 10 ms to 1 second
     *
     */
    private static final int DEFAULT_CODEL_INTERVAL = 100;
    private final long _interval;
    private final String STAT_DROP;
    private final String STAT_DELAY;
    private static final long BACKLOG_TIME = SystemVersion.isSlow() ? 1000 : 500;

    /**
     *  Target 15, interval 100
     *
     *  @param name for stats
     */

//    public CoDelBlockingQueue(I2PAppContext ctx, String name, int capacity) {
//        this(ctx, name, capacity, TARGET, INTERVAL);
//    }

    public CoDelBlockingQueue(I2PAppContext ctx, String name, int capacity) {
        this(ctx, name, capacity, ctx.getProperty(PROP_CODEL_TARGET, DEFAULT_CODEL_TARGET),
                                  ctx.getProperty(PROP_CODEL_INTERVAL, DEFAULT_CODEL_INTERVAL));
    }

    /**
     *  @param target the target max latency (ms)
     *  @param interval how long above target to start dropping (ms)
     *  @param name for stats
     *  @since 0.9.50
     */
    public CoDelBlockingQueue(I2PAppContext ctx, String name, int capacity, int target, int interval) {
        super(capacity);
        _context = ctx;
        _log = ctx.logManager().getLog(CoDelBlockingQueue.class);
        _name = name;
        _capacity = capacity;
        _target = target;
        _interval = interval;
        STAT_DROP = ("codel." + name + ".drop").intern();
        STAT_DELAY = ("codel." + name + ".delay").intern();
        ctx.statManager().createRateStat(STAT_DROP, "Queue delay of dropped items (ms)", "Router [CoDel]", CODEL_RATES);
        ctx.statManager().createRateStat(STAT_DELAY, "Average queue delay (ms)", "Router [CoDel]", CODEL_RATES);
        _id = __id.incrementAndGet();
    }

    @Override
    public boolean add(E o) {
        o.setEnqueueTime(_context.clock().now());
        return super.add(o);
    }

    @Override
    public boolean offer(E o) {
        o.setEnqueueTime(_context.clock().now());
        return super.offer(o);
    }

    @Override
    public boolean offer(E o, long timeout, TimeUnit unit) throws InterruptedException {
        o.setEnqueueTime(_context.clock().now());
        return super.offer(o, timeout, unit);
    }

    /**
     * Queue fullness threshold for early drop (90% of capacity).
     * When queue reaches this level, new packets are dropped to prevent blocking.
     */
    private static final double QUEUE_FULL_THRESHOLD = 0.90;

    /**
     * Check if the queue is nearly full (above threshold).
     * Used for early drop decisions before blocking.
     *
     * @return true if queue is above 90% capacity
     */
    public boolean isNearlyFull() {
        return size() >= _capacity * QUEUE_FULL_THRESHOLD;
    }

    @Override
    public void put(E o) throws InterruptedException {
        o.setEnqueueTime(_context.clock().now());
        // Hard limit: if queue is nearly full, drop immediately rather than blocking
        // This prevents memory buildup and keeps latency under control
        if (isNearlyFull()) {
            if (_log.shouldWarn()) {
                _log.warn("Dropping packet due to queue near capacity: " + size() + "/" + _capacity);
            }
            _context.statManager().addRateData("codel." + _name + ".earlyDrop", 1);
            o.drop();
            return;
        }
        super.put(o);
    }

    @Override
    public void clear() {
        super.clear();
        synchronized(this) {
            _first_above_time = 0;
            _drop_next = 0;
            _count = 0;
            _dropping = false;
        }
    }

    @Override
    public E take() throws InterruptedException {
        E rv;
        do {
            rv = deque();
        } while (rv == null);
        return rv;
    }

    @Override
    public E poll() {
        E rv = super.poll();
        return codel(rv);
    }

    /**
     *  Updates stats and possibly drops while draining.
     */
    @Override
    public int drainTo(Collection<? super E> c) {
        int rv = 0;
        E e;
        while ((e = poll()) != null) {
            c.add(e);
            rv++;
        }
        return rv;
    }

    /**
     *  Updates stats and possibly drops while draining.
     */
    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        int rv = 0;
        E e;
        while ((e = poll()) != null && rv++ < maxElements) {
            c.add(e);
        }
        return rv;
    }

    /**
     *  Drains all, without updating stats or dropping.
     */
    public int drainAllTo(Collection<? super E> c) {
        return super.drainTo(c);
    }

    /**
     *  Has the head of the queue been waiting too long,
     *  or is the queue almost full?
     */
    public synchronized boolean isBacklogged() {
        E e = peek();
        if (e == null)
            return false;
        return _dropping ||
               _context.clock().now() - e.getEnqueueTime() >= BACKLOG_TIME ||
               remainingCapacity() < _capacity / 4;
    }

    /////// private below here

    /**
     *  Caller must synch on this
     *  @param entry may be null
     */
    private boolean updateVars(E entry) {
        // This is a helper routine that tracks whether the sojourn time
        // is above or below target and, if above, if it has remained above continuously for at least interval.
        // It returns a boolean indicating whether it is OK to drop (sojourn time above target
        // for at least interval)
        if (entry == null) {
            _first_above_time = 0;
            return false;
        }
        _now = _context.clock().now();
        boolean ok_to_drop = false;
        long sojurn = _now - entry.getEnqueueTime();
        _context.statManager().addRateData(STAT_DELAY, sojurn);
        // I2P use isEmpty instead of size() < MAXPACKET
        if (sojurn < _target || isEmpty()) {
            _first_above_time = 0;
        } else {
            if (_first_above_time == 0) {
                // just went above from below. if we stay above
                // for at least _interval we'll say it's ok to drop
                _first_above_time = _now + _interval;
            } else if (_now >= _first_above_time) {
                ok_to_drop = true;
            }
        }
        return ok_to_drop;
    }

    /**
     *  @return if null, call again
     */
    private E deque() throws InterruptedException {
        E rv = super.take();
        return codel(rv);
    }


    /**
     *  @param rv may be null
     *  @return rv or a subequent entry or null if dropped
     */
    private E codel(E rv) {
        synchronized (this) {
            // non-blocking inside this synchronized block

            boolean ok_to_drop = updateVars(rv);
            // All of the work of CoDel is done here.
            // There are two branches: if we're in packet-dropping state (meaning that the queue-sojourn
            // time has gone above target and hasn't come down yet), then we need to check if it's time
            // to leave or if it's time for the next drop(s); if we're not in dropping state, then we need
            // to decide if it's time to enter and do the initial drop.
            if (_dropping) {
                if (!ok_to_drop) {
                    // sojurn time below target - leave dropping state
                    _dropping = false;
                } else {
                    // It's time for the next drop. Drop the current packet and dequeue the next.
                    // The dequeue might take us out of dropping state. If not, schedule the next drop.
                    // A large backlog might result in drop rates so high that the next drop should happen now;
                    // hence, the while loop.
                    while (_now >= _drop_next && _dropping) {
                        drop(rv);
                        _count++;
                        // I2P - we poll here instead of lock so we don't get stuck
                        // inside the lock. If empty, deque() will be called again.
                        rv = super.poll();
                        ok_to_drop = updateVars(rv);
                        if (!ok_to_drop) {
                            // leave dropping state
                            _dropping = false;
                        } else {
                            // schedule the next drop
                            control_law(_drop_next);
                        }
                    }
                }
            } else if (ok_to_drop &&
                       (_now - _drop_next < _interval || _now - _first_above_time >= _interval)) {
                // If we get here, then we're not in dropping state. If the sojourn time has been above
                // target for interval, then we decide whether it's time to enter dropping state.
                // We do so if we've been either in dropping state recently or above target for a relatively
                // long time. The "recently" check helps ensure that when we're successfully controlling
                // the queue we react quickly (in one interval) and start with the drop rate that controlled
                // the queue last time rather than relearn the correct rate from scratch. If we haven't been
                // dropping recently, the "long time above" check adds some hysteresis to the state entry
                // so we don't drop on a slightly bigger-than-normal traffic pulse into an otherwise quiet queue.
                drop(rv);
                // I2P - we poll here instead of lock so we don't get stuck
                // inside the lock. If empty, deque() will be called again.
                rv = super.poll();
                updateVars(rv);
                _dropping = true;
                // If we're in a drop cycle, the drop rate that controlled the queue
                // on the last cycle is a good starting point to control it now.
                if (_now - _drop_next < _interval)
                    _count = _count > 2 ? _count - 2 : 1;
                else
                    _count = 1;
                control_law(_now);
            }
        }
        return rv;
    }

    private void drop(E entry) {
        long delay = _context.clock().now() - entry.getEnqueueTime();
        _context.statManager().addRateData(STAT_DROP, delay);
        if (_log.shouldWarn())
            _log.warn("CDQ #" + _id + ' ' + _name + " dropped item with " + delay + "ms delay \n* " +
                      DataHelper.formatDuration(_context.clock().now() - _first_above_time) + " since first above, " +
                      DataHelper.formatDuration(_context.clock().now() - _drop_next) + " since drop next, " +
                      (_count+1) + " dropped in this phase, " +
                      size() + " remaining in queue " + entry);
        entry.drop();
    }

    /**
     *  Caller must synch on this
     */
    private void control_law(long t) {
        _drop_next = t + (long) (_interval / Math.sqrt(_count));
    }
}
