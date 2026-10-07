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
}
