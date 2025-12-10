package net.i2p.router.networkdb.kademlia;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.Hash;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;

/**
 * Base class for flood-based search operations with fallback to Kademlia.
 * <p>
 * Implements flood-based search strategy sending queries to floodfill peers.
 * If no successful match is received within half the allowed timeout,
 * falls back to standard Kademlia iterative search through normal channels.
 * This reduces spurious lookups caused by response delays from floodfill peers.
 * <p>
 * Note: Unused directly - see FloodOnlySearchJob extension which overrides
 * almost everything. This does NOT extend SearchJob.
 */
abstract class FloodSearchJob extends JobImpl {
    protected final Log _log;
    protected final FloodfillNetworkDatabaseFacade _facade;
    protected final Hash _key;
    protected final List<Job> _onFind;
    protected final List<Job> _onFailed;
    protected long _expiration;
    protected int _timeoutMs;
    protected final boolean _isLease;
    protected final AtomicInteger _lookupsRemaining = new AtomicInteger();
    protected volatile boolean _dead;
    protected final long _created;
    protected boolean _success;

    /**
     *  @param onFind may be null
     *  @param onFailed may be null
     */
    public FloodSearchJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade, Hash key, Job onFind, Job onFailed, int timeoutMs, boolean isLease) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _facade = facade;
        _key = key;
        _onFind = new CopyOnWriteArrayList<Job>();
        if (onFind != null) {_onFind.add(onFind);}
        _onFailed = new CopyOnWriteArrayList<Job>();
        if (onFailed != null) {_onFailed.add(onFailed);}
        int timeout = timeoutMs / FLOOD_SEARCH_TIME_FACTOR;
        if (timeout < timeoutMs) {timeout = timeoutMs;}
        _timeoutMs = timeout;
        _expiration = timeout + ctx.clock().now();
        _isLease = isLease;
        _created = System.currentTimeMillis();
    }

    /** System time, NOT context time */
    public long getCreated() { return _created; }

    /**
     *  Add jobs to an existing search
     *  @param onFind may be null
     *  @param onFailed may be null
     *  @param timeoutMs ignored
     *  @param isLease ignored
     */
    void addDeferred(Job onFind, Job onFailed, long timeoutMs, boolean isLease) {
        boolean success;
        synchronized (this) {
            if (!_dead) {
                if (onFind != null)
                    _onFind.add(onFind);
                if (onFailed != null)
                    _onFailed.add(onFailed);
                return;
            }
            success = _success;
        }
        // outside synch to avoid deadlock with job queue
        if (success && onFind != null)
            getContext().jobQueue().addJob(onFind);
        else if (!success && onFailed != null)
            getContext().jobQueue().addJob(onFailed);
    }

    /** using context clock */
    public long getExpiration() { return _expiration; }

//    protected static final int CONCURRENT_SEARCHES = 2;
    protected static final int CONCURRENT_SEARCHES = SystemVersion.isSlow() ? 3 : 5;
    private static final int FLOOD_SEARCH_TIME_FACTOR = 2;
    /**
     *  Deprecated, unused, see FOSJ override
     */
    public void runJob() {
        throw new UnsupportedOperationException("use override");
    }

    /**
     *  Deprecated, unused, see FOSJ override
     */
    public String getName() { return "NetDb Search (phase 1)"; }

    public Hash getKey() { return _key; }

    /**
     *  @return number remaining after decrementing
     */
    protected int decrementRemaining() {
        // safe decrement
        for (;;) {
            int n = _lookupsRemaining.get();
            if (n <= 0)
                return 0;
            if (_lookupsRemaining.compareAndSet(n, n - 1))
                return n - 1;
        }
    }

    protected int getLookupsRemaining() { return _lookupsRemaining.get(); }

    /**
     *  Deprecated, unused, see FOSJ override
     */
    void failed() {
        throw new UnsupportedOperationException("use override");
    }

    /**
     *  Deprecated, unused, see FOSJ override
     */
    void success() {
        synchronized(this) {
            _success = true;
        }
    }

}
