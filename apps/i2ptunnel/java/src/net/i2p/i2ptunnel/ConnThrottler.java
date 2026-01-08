package net.i2p.i2ptunnel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Connection rate limiter providing basic DoS protection.
 * <p>
 * Counts events per peer and across all peers. Ban time differs from check time,
 * with separate map of throttled peers and individual timestamps.
 * <p>
 * Differs from streaming version: more precise tracking vs. lightweight
 * but "sloppy" single time bucket approach.
 *
 * @since 0.9.9
 */
class ConnThrottler {
    private int _max;
    private int _totalMax;
    private long _checkPeriod;
    private long _throttlePeriod;
    private long _totalThrottlePeriod;
    private int _currentTotal;
    private final Map<Hash, Record> _peers;
    private long _totalThrottleUntil;
    private final String _action;
    private final Log _log;
    private final SimpleTimer2.TimedEvent _cleaner;
    private boolean _isRunning;

    /*
     * Caller MUST call start()
     *
     * @param max per-peer, 0 for unlimited
     * @param totalMax for all peers, 0 for unlimited
     * @param period check window (ms)
     * @param throttlePeriod how long to ban a peer (ms)
     * @param totalThrottlePeriod how long to ban all peers (ms)
     * @param action just a name to note in the log
     */
    public ConnThrottler(int max, int totalMax, long period,
                         long throttlePeriod, long totalThrottlePeriod, String action, Log log) {
        updateLimits(max, totalMax, period, throttlePeriod, totalThrottlePeriod);
        _peers = new HashMap<Hash, Record>(4);
        _action = action;
        _log = log;
        _cleaner = new Cleaner();
    }

    /**
     *  Starts the throttler by scheduling the cleanup timer.
     * <p>
     * This method must be called to begin rate limiting. The cleanup timer
     * will run periodically to check for expired throttles and clean up
     * stale peer records.
     * </p>
     * <p>
     * If already started, this method has no effect.
     * </p>
     *
     * @since 0.9.40
     */
    public synchronized void start() {
        if (_isRunning)
            return;
        _isRunning = true;
        _cleaner.schedule(_checkPeriod);
    }

    /**
     *  Stops the throttler and resets all state.
     * <p>
     * This method cancels the cleanup timer, clears all peer records,
     * and resets the total connection counter. The throttler may be
     * restarted by calling start() again.
     * </p>
     *
     * @since 0.9.40
     */
    public synchronized void stop() {
        _isRunning = false;
        _cleaner.cancel();
        clear();
    }

    /**
     *  Updates the rate limiting configuration.
     * <p>
     * All period values are enforced with a minimum of 10 seconds.
     * </p>
     *
     * @param max the maximum number of connections per peer (0 for unlimited)
     * @param totalMax the maximum total connections from all peers (0 for unlimited)
     * @param checkPeriod the time window for counting connections, in milliseconds
     * @param throttlePeriod how long to throttle individual peers, in milliseconds
     * @param totalThrottlePeriod how long to throttle all peers, in milliseconds
     * @since 0.9.3
     */
    public synchronized void updateLimits(int max, int totalMax, long checkPeriod, long throttlePeriod, long totalThrottlePeriod) {
        _max = max;
        _totalMax = totalMax;
        _checkPeriod = Math.max(checkPeriod, 10*1000);
        _throttlePeriod = Math.max(throttlePeriod, 10*1000);
        _totalThrottlePeriod = Math.max(totalThrottlePeriod, 10*1000);
    }

    /**
     *  Checks if a peer or the total connection rate exceeds configured limits.
     * <p>
     * This method increments the connection counter for the given peer and
     * checks if either the per-peer limit or the total connection limit has
     * been exceeded. If a limit is exceeded, the peer (or all peers) is
     * throttled for the configured throttle period.
     * </p>
     * <p>
     * <b>Throttle Logic:</b>
     * <ul>
     *   <li>Individual: If max connections exceeded in checkPeriod, throttle for throttlePeriod</li>
     *   <li>Total: If total connections exceeded, throttle all for totalThrottlePeriod</li>
     * </ul>
     * </p>
     *
     * @param h the peer's destination hash to check
     * @return true if the peer should be throttled (request denied), false otherwise
     */
    public synchronized boolean shouldThrottle(Hash h) {
        // all throttled already?
        if (_totalMax > 0) {
            if (_totalThrottleUntil > 0) {
                if (_totalThrottleUntil > Clock.getInstance().now())
                    return true;
                _totalThrottleUntil = 0;
            }
        }
        // do this first, so we don't increment total if individual throttled
        if (_max > 0) {
            Record rec = _peers.get(h);
            if (rec != null) {
                // peer throttled already?
                if (rec.getUntil() > 0)
                    return true;
                rec.increment();
                long now = Clock.getInstance().now();
                if (rec.countSince(now - _checkPeriod) > _max) {
                    long until = now + _throttlePeriod;
                    String date = DataHelper.formatTime(until);
                    _log.logAlways(Log.WARN, "Throttling " + _action + " until " + date +
                                             " after exceeding max of " + _max +
                                             " in " + DataHelper.formatDuration(_checkPeriod) +
                                             "\n* Client: " + h.toBase64());
                    rec.ban(until);
                    return true;
                }
            } else {
                _peers.put(h, new Record());
            }
        }
        if (_totalMax > 0 && ++_currentTotal > _totalMax) {
            if (_totalThrottleUntil == 0) {
                _totalThrottleUntil = Clock.getInstance().now() + _totalThrottlePeriod;
                String date = DataHelper.formatTime(_totalThrottleUntil);
                _log.logAlways(Log.WARN, "*** Throttling " + _action + " from ALL peers until " + date +
                                         " after exceeding max of " + _max +
                                         " in " + DataHelper.formatDuration(_checkPeriod));
            }
            return true;
        }
        return false;
    }

    /**
     *  Resets all throttling state.
     * <p>
     * This method clears all peer records, resets the total connection counter,
     * and clears the total throttle timer. After calling this method, all
     * connections will be allowed until limits are exceeded again.
     * </p>
     */
    public synchronized void clear() {
        _currentTotal = 0;
        _totalThrottleUntil = 0;
        _peers.clear();
    }

    /**
     *  Keep a list of seen times, and a ban-until time.
     *  Caller must sync all methods.
     */
    private static class Record {
        private final List<Long> times;
        private long until;

        public Record() {
            times = new ArrayList<Long>(8);
            increment();
        }

        /** Caller must synch */
        public int countSince(long time) {
            for (Iterator<Long> iter = times.iterator(); iter.hasNext(); ) {
                if (iter.next().longValue() < time)
                    iter.remove();
                else
                    break;
            }
            return times.size();
        }

        /** Caller must synch */
        public void increment() {
            times.add(Long.valueOf(Clock.getInstance().now()));
        }

        /** Caller must synch */
        public void ban(long untilTime) {
            until = untilTime;
            // don't need to save times if banned
            times.clear();
        }

        /** Caller must synch */
        public long getUntil() {
            if (until < Clock.getInstance().now())
                until = 0;
            return until;
        }
    }

    private class Cleaner extends SimpleTimer2.TimedEvent {
        /** must call schedule() later */
        public Cleaner() {
            super(SimpleTimer2.getInstance());
        }

        /**
         *  Called by the timer to clean up expired throttles.
         */
        public void timeReached() {
            synchronized(ConnThrottler.this) {
                if (_totalMax > 0)
                    _currentTotal = 0;
                if (_max > 0 && !_peers.isEmpty()) {
                    long then = Clock.getInstance().now()  - _checkPeriod;
                    for (Iterator<Record> iter = _peers.values().iterator(); iter.hasNext(); ) {
                        Record rec = iter.next();
                        if (rec.getUntil() <= 0 && rec.countSince(then) <= 0)
                            iter.remove();
                    }
                }
            }
            long checkPeriod;
            synchronized(ConnThrottler.this) {
                checkPeriod = ConnThrottler.this._checkPeriod;
            }
            schedule(checkPeriod);
        }
    }
}
