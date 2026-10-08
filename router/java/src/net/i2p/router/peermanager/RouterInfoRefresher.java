package net.i2p.router.peermanager;

/**
 *  Per-cycle state for the promotion deferral on a stale RouterInfo.
 *
 *  <p>Promotion into the fast or high-capacity tier reads whatever RouterInfo the
 *  network database currently holds. A RouterInfo older than an hour is likely
 *  superseded — routers republish hourly — so promoting on one buys a tier slot
 *  that may not survive a build. {@link ProfileOrganizer} therefore holds such a
 *  promotion back and simply leaves the peer out of the tier; nothing is fetched
 *  and no network work is done on the peer's behalf.
 *
 *  <p>What a cycle needs to remember is therefore only its own bounds and its two
 *  counters: the staleness threshold, the tier sizes the starvation guard compares
 *  against, how many promotions were held, and how many went ahead on a RouterInfo
 *  already known to be stale.
 *
 *  <p>Locking: confined to the reorganisation write lock, which is also the only
 *  caller. Nothing here is written from a job or a selection thread.
 *
 *  @see ProfileOrganizer#needsRouterInfoRefresh
 *  @see ProfileOrganizer#shouldDeferPromotionOnStaleRouterInfo
 *  @since 0.9.72
 */
class RouterInfoRefresher {

    /** Staleness threshold in force for the current cycle, in ms. */
    private long _refreshAgeMs;

    /** Tier size at or above which a fast-tier promotion may be held back. */
    private int _fastFloor;

    /** Tier size at or above which a high-capacity promotion may be held back. */
    private int _highCapFloor;

    /** Whether the current cycle defers at all.  False when the property is disabled. */
    private boolean _deferring;

    /** Promotions held back this cycle, across both tiers. */
    private int _deferred;

    /** Promotions let through on a stale RouterInfo this cycle, by the starvation guard. */
    private int _promotedStale;

    /**
     *  Open a cycle, resetting the per-cycle counters.
     *
     *  <p>There is nothing to stagger here. The earlier startup grace period, quota
     *  and queue rotation only ever throttled how many lookups a cycle could issue;
     *  with no lookup to issue, each cycle now judges every candidate, and a fresh
     *  cycle resets the counters so they report one cycle rather than the life of
     *  the router.
     *
     *  @param refreshAgeMs staleness threshold for this cycle, or &lt;= 0 to disable
     *  @param fastFloor    fast-tier size at or above which a promotion may be held back
     *  @param highCapFloor high-capacity tier size at or above which a promotion may be held back
     *  @return whether promotions are being held back this cycle
     *  @since 0.9.72
     */
    boolean beginCycle(long refreshAgeMs, int fastFloor, int highCapFloor) {
        _refreshAgeMs = refreshAgeMs;
        _fastFloor = Math.max(0, fastFloor);
        _highCapFloor = Math.max(0, highCapFloor);
        _deferring = refreshAgeMs > 0;
        _deferred = 0;
        _promotedStale = 0;
        return _deferring;
    }

    /**
     *  Record a promotion held back because of a stale RouterInfo.  One integer
     *  increment: this runs on the promotion path, once per held tier insert, and
     *  has to stay cheap enough for the loop that calls it.
     *
     *  @since 0.9.72
     */
    void noteDeferral() {
        if (!_deferring) return;
        _deferred++;
    }

    /**
     *  Record a promotion the starvation guard let through on a RouterInfo that
     *  is already known to be stale.  This is the population the
     *  {@code peer.promotedStaleRouterInfo} stat reports.
     *
     *  @since 0.9.72
     */
    void noteStalePromotion() {
        _promotedStale++;
    }

    /** @return the staleness threshold in force this cycle, in ms @since 0.9.72 */
    long refreshAgeMs() { return _refreshAgeMs; }

    /** @return whether promotions are being held back this cycle @since 0.9.72 */
    boolean isDeferring() { return _deferring; }

    /** @return fast-tier size at or above which a promotion may be held back @since 0.9.72 */
    int fastFloor() { return _fastFloor; }

    /** @return high-capacity size at or above which a promotion may be held back @since 0.9.72 */
    int highCapFloor() { return _highCapFloor; }

    /** @return promotions held back this cycle, counted across both tiers @since 0.9.72 */
    int deferredCount() { return _deferred; }

    /**
     *  @return promotions let through on a stale RouterInfo this cycle, by the
     *          starvation guard
     *  @since 0.9.72
     */
    int promotedStaleCount() { return _promotedStale; }

    /** Most peers whose RouterInfo refresh may be requested in one drain. */
    static final int MAX_ADDRESS_REFRESHES = 16;
    /**
     *  Peers looked up per drain when catching up on profiles loaded without a
     *  RouterInfo, giving roughly 64 lookups a minute.
     *
     *  <p>Faster than {@link #MAX_ADDRESS_REFRESHES} on purpose. That cap is sized for
     *  held promotion candidates, a trickle of a few per reorganize; a router coming
     *  back from downtime may hold thousands of profiles whose RouterInfo has since
     *  expired, and at 16 a minute they would stay unusable for hours. Each lookup is a
     *  fire-and-forget {@code lookupRouterInfo}, and the netdb negatively caches
     *  known-dead keys, so peers that never come back cost little on repeat.
     *
     *  @since 0.9.72
     */
    static final int MISSING_ROUTERINFO_BATCH = 32;
    /**
     *  Minimum gap between missing-RouterInfo drains, in ms.
     *
     *  <p>Sets the rate at {@link #MISSING_ROUTERINFO_BATCH} per interval, about 64 a
     *  minute, so a couple of thousand profiles take roughly half an hour to recover.
     *
     *  @since 0.9.72
     */
    static final long MISSING_ROUTERINFO_MIN_INTERVAL_MS = 30 * 1000L;
    /**
     *  Ceiling on remembered peers missing a RouterInfo.
     *
     *  <p>The set is a recovery list, not a backlog to be worked off exhaustively. Past
     *  this size the extra peers are not worth the memory and the lookups: they are
     *  peers the netdb has already stopped telling us about.
     *
     *  @since 0.9.72
     */
    static final int MAX_MISSING_ROUTERINFO = 4096;

    /** Minimum gap between drains, so repeated reorgs cannot stack requests. */
    static final long ADDRESS_REFRESH_MIN_INTERVAL_MS = 60 * 1000L;

    /**
     *  Peers whose promotion was held for want of a usable address, awaiting a lookup.
     *
     *  <p>Enqueued from the promotion scan, which runs under the reorganize write lock, and
     *  drained after that lock is released, so adds and the poll can overlap across threads.
     *  The methods below are therefore synchronized: the critical sections are a single add or
     *  a bounded poll, with no network work and no callbacks inside, so the lock is cheap and
     *  carries no ordering relationship with the reorganize lock.
     */
    private final java.util.ArrayDeque<net.i2p.data.Hash> _addressRefreshQueue =
        new java.util.ArrayDeque<net.i2p.data.Hash>();

    /** Lookups requested and dropped because the queue or the interval was full. */
    private int _addressRefreshDropped;

    /** When the last drain ran. */
    private long _lastAddressRefreshDrain;

    /**
     *  Ask for a RouterInfo lookup for a peer held out of the tiers for want of a usable
     *  address.
     *
     *  <p>A held peer cannot be refreshed directly - that is precisely why it is held, since
     *  it has no address to send to - so the only way to re-qualify it is an iterative lookup
     *  via other routers. This only records the request; {@link #takeAddressRefreshBatch(long)}
     *  issues it, because the scan that fills this queue runs under the reorganize write lock
     *  and must not do network work there.
     *
     *  <p>Bounded by {@link #MAX_ADDRESS_REFRESHES}, so a scan over thousands of profiles
     *  cannot enqueue thousands of lookups.
     *
     *  @param peer the held peer
     *  @return true if the request was recorded
     */
    synchronized boolean requestAddressRefresh(net.i2p.data.Hash peer) {
        if (peer == null || _addressRefreshQueue.size() >= MAX_ADDRESS_REFRESHES) {
            _addressRefreshDropped++;
            return false;
        }
        if (_addressRefreshQueue.contains(peer)) { return false; }
        _addressRefreshQueue.add(peer);
        return true;
    }

    /**
     *  Whether enough time has passed since the last drain to issue another batch.
     *
     *  @param now current time in milliseconds
     *  @return true if a drain may run
     */
    boolean mayDrainAddressRefreshes(long now) {
        // Zero means "never drained". Without this test the first drain is refused for the
        // first interval after startup, because a zero sentinel reads as a real timestamp.
        if (_lastAddressRefreshDrain == 0) { return true; }
        return now - _lastAddressRefreshDrain >= ADDRESS_REFRESH_MIN_INTERVAL_MS;
    }

    /**
     *  Hand over the queued peers, oldest first, clearing the queue.
     *
     *  @param now current time in milliseconds
     *  @return the peers to look up, possibly empty
     */
    synchronized java.util.List<net.i2p.data.Hash> takeAddressRefreshBatch(long now) {
        java.util.List<net.i2p.data.Hash> batch =
            new java.util.ArrayList<net.i2p.data.Hash>(MAX_ADDRESS_REFRESHES);
        while (!_addressRefreshQueue.isEmpty() && batch.size() < MAX_ADDRESS_REFRESHES) {
            batch.add(_addressRefreshQueue.poll());
        }
        _lastAddressRefreshDrain = (now == 0) ? 1 : now;
        return batch;
    }

    /** Peers dropped because the queue or the interval was full. */
    synchronized int getAddressRefreshDropped() { return _addressRefreshDropped; }

    /** Peers waiting for a lookup. */
    synchronized int getAddressRefreshPending() { return _addressRefreshQueue.size(); }

    // ---- profiles loaded with no RouterInfo ---------------------------------

    /** Peers whose stored profile was loaded while the netdb held no RouterInfo for them. */
    private final java.util.Set<net.i2p.data.Hash> _missingRouterInfo = new java.util.HashSet<>(256);
    /** Peers dropped because the missing-RouterInfo set was already at its cap. */
    private int _missingRouterInfoDropped;
    /** When the set was last drained, to hold the batch to its interval. */
    private long _lastMissingDrain;

    /**
     *  Note that a stored profile was loaded for a peer the netdb cannot resolve.
     *
     *  <p>Until the RouterInfo comes back the profile is inert: peer selection needs an
     *  address to send to, and {@link ProfileOrganizer#isExcludedFromProfiling(Hash)}
     *  declines to profile a peer with no RouterInfo. The capacity, latency and
     *  acceptance history in the file is then retained but unused, which only pays off
     *  if something goes and fetches the RouterInfo again.
     *
     *  @param peer the peer whose profile was loaded
     *  @return true if the peer was recorded, false if the set was already full
     *  @since 0.9.72
     */
    synchronized boolean noteMissingRouterInfo(net.i2p.data.Hash peer) {
        if (peer == null) {return false;}
        if (_missingRouterInfo.size() >= MAX_MISSING_ROUTERINFO) {
            _missingRouterInfoDropped++;
            return false;
        }
        return _missingRouterInfo.add(peer);
    }

    /**
     *  Whether enough time has passed to drain the missing-RouterInfo set.
     *
     *  @param now current time in milliseconds
     *  @return true if the drain interval has elapsed
     *  @since 0.9.72
     */
    synchronized boolean mayDrainMissingRouterInfo(long now) {
        return now - _lastMissingDrain >= MISSING_ROUTERINFO_MIN_INTERVAL_MS;
    }

    /**
     *  Hand over up to one batch of peers to look up, removing them from the set.
     *
     *  @param now current time in milliseconds
     *  @return the peers to look up, possibly empty
     *  @since 0.9.72
     */
    synchronized java.util.List<net.i2p.data.Hash> takeMissingRouterInfoBatch(long now) {
        java.util.List<net.i2p.data.Hash> batch = new java.util.ArrayList<>(MISSING_ROUTERINFO_BATCH);
        for (java.util.Iterator<net.i2p.data.Hash> it = _missingRouterInfo.iterator();
             it.hasNext() && batch.size() < MISSING_ROUTERINFO_BATCH; ) {
            batch.add(it.next());
            it.remove();
        }
        _lastMissingDrain = (now == 0 ? 1 : now);
        return batch;
    }

    /** Peers dropped because the missing-RouterInfo set was already at its cap. */
    synchronized int getMissingRouterInfoDropped() { return _missingRouterInfoDropped; }

    /** Peers still waiting for their RouterInfo to be requested. */
    synchronized int getMissingRouterInfoPending() { return _missingRouterInfo.size(); }
}
