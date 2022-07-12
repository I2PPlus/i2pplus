package net.i2p.router;

import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Minor extention of the router throttle to handle some DoS events and
 * throttle accordingly.
 *
 * @deprecated unused
 */
@Deprecated
class RouterDoSThrottle extends RouterThrottleImpl {
    public RouterDoSThrottle(RouterContext context) {
        super(context);
        context.statManager().createRateStat("router.throttleNetDbDoS", "NetDb lookup messages received during detected DoS", "Router [Throttle]", new long[] { 60*1000, 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _log = context.logManager().getLog(RouterDoSThrottle.class);
    }

    private volatile long _currentLookupPeriod;
    private final AtomicInteger _currentLookupCount = new AtomicInteger();
    // if we receive over 20 netDb lookups in 10 seconds, someone is acting up
    private static final long LOOKUP_THROTTLE_PERIOD = 10*1000;
    private static final long LOOKUP_THROTTLE_MAX = 20;
    private final Log _log;

    @Override
    public boolean acceptNetDbLookupRequest(Hash key) {
        // if we were going to refuse it anyway, drop it
        boolean shouldAccept = super.acceptNetDbLookupRequest(key);
        if (!shouldAccept) return false;

        // now lets check for DoS
        long now = _context.clock().now();
        if (_currentLookupPeriod + LOOKUP_THROTTLE_PERIOD > now) {
            // same period, check for DoS
            int cnt = _currentLookupCount.incrementAndGet();
            if (cnt >= LOOKUP_THROTTLE_MAX) {
                _context.statManager().addRateData("router.throttleNetDbDoS", cnt);
                _context.banlist().banlistRouter(key, "Excessive NetDB lookups", null, null, now + 15*60*1000);
                if (_log.shouldWarn())
                    _log.warn("Temp banning router [" + key.toBase64().substring(0,6) + "] for 15m for excessive NetDB lookups " +
                              "(Limit (over 10m period): " + LOOKUP_THROTTLE_MAX + " -> Requests: " + cnt + ")");
                int rand = _context.random().nextInt(cnt);
                if (rand > LOOKUP_THROTTLE_MAX) {
                    return false;
                } else {
                    return true;
                }
            } else {
                // no DoS, at least, not yet
                return true;
            }
        } else {
            // on to the next period, reset counter, no DoS
            // (no, I'm not worried about concurrency here)
            _currentLookupPeriod = now;
            _currentLookupCount.set(1);
            return true;
        }
    }

}
