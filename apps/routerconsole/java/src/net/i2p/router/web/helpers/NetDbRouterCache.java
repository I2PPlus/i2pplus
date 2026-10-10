package net.i2p.router.web.helpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;

/**
 * Cached snapshot of the network database sorted by RouterInfoComparator.
 *
 * The /netdb page auto-refreshes every 10 seconds (netdb.js
 * REFRESH_INTERVAL_SHORT), so a snapshot up to this age is never
 * visible to a client; caching it avoids re-sorting the full
 * network database on every render.
 *
 * @since 0.9.71+
 */
class NetDbRouterCache {
    private static final long NETDB_REFRESH_PERIOD = 10 * 1000L;
    private static volatile long _routersCachedUntil;
    private static volatile List<RouterInfo> _cachedRouters;

    private final RouterContext _context;

    /**
     * Create an empty cache.
     *
     * @param ctx the router context
     */
    public NetDbRouterCache(RouterContext ctx) {
        _context = ctx;
    }

    /**
     * Snapshot of the network database sorted by RouterInfoComparator,
     * cached for the /netdb auto-refresh period.
     *
     * @return every known RouterInfo in comparator order; never null, but may be
     *         empty before the router has learned about any peers
     */
    public List<RouterInfo> getSortedRouters() {
        long now = _context.clock().now();
        List<RouterInfo> cached = _cachedRouters;
        if (cached != null && now < _routersCachedUntil) {
            return cached;
        }
        List<RouterInfo> routers = new ArrayList<>(_context.netDb().getRouters());
        Collections.sort(routers, RouterInfoComparator.getInstance());
        _cachedRouters = routers;
        _routersCachedUntil = now + NETDB_REFRESH_PERIOD;
        return routers;
    }
}
