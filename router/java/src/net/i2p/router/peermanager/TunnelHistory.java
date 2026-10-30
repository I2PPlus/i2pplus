package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.router.RouterContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Tunnel related history information.
 */
public class TunnelHistory {
    /** The router context. */
    private final RouterContext _context;
    /** Logger for the tunnel history. */
    private final Log _log;
    /** Build requests we agreed to, lifetime. */
    private final AtomicLong _lifetimeAgreedTo = new AtomicLong();
    /** Build requests we rejected, lifetime. */
    private final AtomicLong _lifetimeRejected = new AtomicLong();
    /** Builds that timed out, lifetime. */
    private final AtomicLong _lifetimeTimedOut = new AtomicLong();
    /** When we last agreed to a build request. */
    private volatile long _lastAgreedTo;
    /** When we last rejected for a critical reason. */
    private volatile long _lastRejectedCritical;
    /** When we last rejected for bandwidth. */
    private volatile long _lastRejectedBandwidth;
    /** When we last rejected for a transient reason. */
    private volatile long _lastRejectedTransient;
    /** When we last rejected probabilistically. */
    private volatile long _lastRejectedProbabalistic;
    /** When a build last timed out. */
    private volatile long _lastTimedOut;
    /** Tunnels that failed, lifetime. */
    private final AtomicLong _lifetimeFailed = new AtomicLong();
    /** When a tunnel last failed. */
    private volatile long _lastFailed;
    /** When a tunnel last passed a test. */
    private volatile long _lastTestedSuccessfully;
    /** Rate stat for the rejections. */
    private final RateStat _rejectRate;
    /** Rate stat for the failures. */
    private final RateStat _failRate;
    /** When the history was last written. */
    private volatile long _lastCoalesce = System.currentTimeMillis();
    /** Rate periods for history tracking. */
    static final long[] RATES = new long[] {RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR };

    /** Probabalistic tunnel rejection due to a flood of requests - infrequent. */
    public static final int TUNNEL_REJECT_PROBABALISTIC_REJECT = 10;
    /** Tunnel rejection due to temporary cpu/job/tunnel overload - rare. */
    public static final int TUNNEL_REJECT_TRANSIENT_OVERLOAD = 20;
    /** Tunnel rejection due to excess bandwidth usage - used for most rejections even if not really for bandwidth. */
    public static final int TUNNEL_REJECT_BANDWIDTH = 30;
    /** Tunnel rejection due to system failure - not currently used. */
    public static final int TUNNEL_REJECT_CRIT = 50;
    /** Streaming NACK - peer accepted tunnel but can't deliver data. */
    public static final int TUNNEL_REJECT_STREAMING_NACK = 100;

    /**
     * Creates the per-peer reject and fail rate stats.
     *
     * @param context the router context this history is attached to
     * @param statGroup the statistics group the rate stats are filed under
     */
    public TunnelHistory(RouterContext context, String statGroup) {
        _context = context;
        _log = context.logManager().getLog(TunnelHistory.class);
        _rejectRate = new RateStat("tunnelHistory.rejectRate", "How often peer rejects a tunnel request?", statGroup, RATES);
        _failRate = new RateStat("tunnelHistory.failRate", "How often do tunnels this peer accepts fail?", statGroup, RATES);
    }

    /**
     * Total tunnels the peer has agreed to participate in.
     *
     * @return the count since the last decay, applied every 8 hours by
     * {@link #decayLifetimeCounters(long)}
     */
    public long getLifetimeAgreedTo() {return _lifetimeAgreedTo.get();}
    /**
     * Total tunnels the peer has refused to participate in (does not include timeouts).
     *
     * @return the count since the last decay, applied every 8 hours by
     * {@link #decayLifetimeCounters(long)}
     */
    public long getLifetimeRejected() {return _lifetimeRejected.get();}
    /**
     * Total tunnel builds to this peer that timed out without a response.
     *
     * @return the count since the last decay, applied every 8 hours by
     * {@link #decayLifetimeCounters(long)}
     */
    public long getLifetimeTimedOut() {return _lifetimeTimedOut.get();}
    /**
     * Total tunnels the peer has agreed to participate in that were later marked as failed prematurely.
     *
     * @return the count since the last decay, applied every 8 hours by
     * {@link #decayLifetimeCounters(long)}
     */
    public long getLifetimeFailed() {return _lifetimeFailed.get();}
    /**
     * When the peer last agreed to participate in a tunnel.
     *
     * @return milliseconds since the epoch, or 0 if the peer has never agreed
     */
    public long getLastAgreedTo() {return _lastAgreedTo;}
    /**
     * When the peer last refused to participate in a tunnel with level of critical.
     *
     * @return milliseconds since the epoch, or 0 if the peer has never
     * rejected at this severity
     */
    public long getLastRejectedCritical() {return _lastRejectedCritical;}
    /**
     * When the peer last refused to participate in a tunnel complaining of bandwidth overload.
     *
     * @return milliseconds since the epoch, or 0 if the peer has never
     * rejected at this severity
     */
    public long getLastRejectedBandwidth() {return _lastRejectedBandwidth;}
    /**
     * When the peer last refused to participate in a tunnel complaining of transient overload.
     *
     * @return milliseconds since the epoch, or 0 if the peer has never
     * rejected at this severity
     */
    public long getLastRejectedTransient() {return _lastRejectedTransient;}
    /**
     * When the peer last refused to participate in a tunnel probabalistically.
     *
     * @return milliseconds since the epoch, or 0 if the peer has never
     * rejected at this severity
     */
    public long getLastRejectedProbabalistic() {return _lastRejectedProbabalistic;}
    /**
     * When the peer last timed out without responding to a tunnel request.
     *
     * @return milliseconds since the epoch, or 0 if the peer has never timed out
     */
    public long getLastTimedOut() {return _lastTimedOut;}
    /**
     * When the last tunnel the peer participated in failed.
     *
     * @return milliseconds since the epoch, or 0 if no participating tunnel
     * has failed
     */
    public long getLastFailed() {return _lastFailed;}

    /**
     * When the peer last took part in a build decision for us: agreed, refused at
     * any severity, timed out, or had a tunnel it joined fail.
     * <p>This is the freshness test for the lifetime accept/reject counts. Those
     * counts are decayed multiplicatively - {@link #decayLifetimeCounters(long)}
     * scales the numerator and the denominator by the same factor - so a ratio
     * built from them does not move as it ages, and integer truncation removes
     * proportionally more from the smaller side, which drifts a sub-threshold
     * ratio <em>down</em>. A ratio that has not been refreshed is therefore not a
     * measurement of the peer's present behaviour, and the timestamp is the only
     * way to tell.
     *
     * @return milliseconds since the epoch, or 0 if the peer has never been asked
     * @since 0.9.72+
     */
    public long getLastBuildActivity() {
        long last = _lastAgreedTo;
        last = Math.max(last, _lastRejectedCritical);
        last = Math.max(last, _lastRejectedBandwidth);
        last = Math.max(last, _lastRejectedTransient);
        last = Math.max(last, _lastRejectedProbabalistic);
        last = Math.max(last, _lastTimedOut);
        return Math.max(last, _lastFailed);
    }

    /**
     * When the peer last passed a tunnel test.
     *
     * @return milliseconds since the epoch, or 0 if the peer has never passed
     * a tunnel test
     */
    public long getLastTestedSuccessfully() {return _lastTestedSuccessfully;}

    /**
     * Record that a tunnel test through this peer succeeded.
     * This indicates the peer is actively participating in working tunnels.
     */
    public void incrementTestSucceeded() {
        _lastTestedSuccessfully = _context.clock().now();
    }

    /**
     * Calculate the ratio of accepted to total tunnel requests.
     * Only explicit rejections are counted; timeouts are tracked
     * separately via {@link #getLifetimeTimedOut} and the
     * time-windowed {@link #getRejectionRate} RateStat.
     *
     * @return ratio (0.0 to 1.0), or 1.0 if no data available
     */
    public double getAcceptanceRatio() {
        long agreed = _lifetimeAgreedTo.get();
        long rejected = _lifetimeRejected.get();
        long total = agreed + rejected;
        if (total <= 0) {
            return 1.0;
        }
        return (double) agreed / total;
    }

    /**
     * Processed message counter; legacy method, no longer tracked.
     *
     * @param processedSuccessfully the number of successfully processed messages, ignored
     * @param failedProcessing the number of messages that failed to process, ignored
     */
    public void incrementProcessed(int processedSuccessfully, int failedProcessing) {
        // intentionally empty - legacy method from strict speed calculator, no longer tracked
    }

    /**
     * Count a tunnel participation agreement and record its time.
     */
    public void incrementAgreedTo() {
        _lifetimeAgreedTo.incrementAndGet();
        _lastAgreedTo = _context.clock().now();
    }

    /**
     * Count a tunnel rejection at the given severity and record its time.
     *
     * @param severity how much the peer doesnt want to participate in the
     *                 tunnel (large == more severe)
     */
    public void incrementRejected(int severity) {
        _lifetimeRejected.incrementAndGet();
        long now = _context.clock().now();
        if (severity >= TUNNEL_REJECT_CRIT) {_lastRejectedCritical = now;}
        else if (severity >= TUNNEL_REJECT_BANDWIDTH) {_lastRejectedBandwidth = now;}
        else if (severity >= TUNNEL_REJECT_TRANSIENT_OVERLOAD) {_lastRejectedTransient = now;}
        else if (severity >= TUNNEL_REJECT_PROBABALISTIC_REJECT) {_lastRejectedProbabalistic = now;}
        // a rejection is always a rejection, don't factor based on severity,
        // which could impact our ability to avoid a congested peer
        _rejectRate.addData(1);
    }

    /**
     * Count a tunnel build timeout (peer did not respond in time).
     * Timeouts are tracked separately from rejections because a peer that
     * is slow to respond is different from a peer that actively refuses.
     * Timeouts do NOT count toward {@link #getLifetimeRejected} and
     * therefore do not affect {@link #getAcceptanceRatio}.
     */
    public void incrementTimedOut() {
        _lifetimeTimedOut.incrementAndGet();
        _lastTimedOut = _context.clock().now();
        _rejectRate.addData(1);
    }

    /**
     * Define this rate as the probability it really failed.
     *
     * @param pct = probability * 100
     */
    public void incrementFailed(int pct) {
        _lifetimeFailed.incrementAndGet();
        _failRate.addData(pct);
        _lastFailed = _context.clock().now();
    }

    /**
     * Rejection rate statistics for this peer.
     *
     * @return the rejection rate
     */
    public RateStat getRejectionRate() {return _rejectRate;}
    /**
     * Failure rate statistics for this peer.
     *
     * @return the failed rate
     */
    public RateStat getFailedRate() {return _failRate;}

    /**
     * How often the lifetime counters decay, as a whole interval.
     * <p>Eight hours, not fifteen minutes. At 3/4 per interval the shorter period
     * made a peer forget its own record faster than it could accumulate one: 50
     * accepted builds fell to 16 within an hour and under 1 within four, so a *good*
     * history decayed exactly as fast as a bad one. Eight hours gives a half-life
     * near 19 hours, so an established record takes about 128 hours to fall to 1%
     * and a peer's last few days of behaviour dominate its ratio.
     *
     * @since 0.9.71+
     */
    static final long DECAY_INTERVAL_MS = 8 * 60 * 60 * 1000L;
    /** Numerator of the rejection rate decay. */
    private static final long DECAY_NUMERATOR = 3;
    /** Denominator of the rejection rate decay. */
    private static final long DECAY_DENOMINATOR = 4;

    /**
     * Apply the lifetime-counter decay if its interval has elapsed.
     * <p>Deliberately does not coalesce the {@link RateStat}s. Those are folded by
     * {@link #coalesceStats} on the caller's own cadence, and folding them again
     * here would advance their windows twice as often as the statistic's consumers
     * expect. A caller that only wants the decay — the tier reorganise walking every
     * candidate — can call this cheaply: one volatile read and one comparison, with
     * real work only on the pass that actually crosses the interval.
     * <p>Must be called with the write lock held when called from the reorganise, so
     * the tier membership being read and the counters being decayed are consistent.
     *
     * @param now current time in ms
     * @since 0.9.71+
     */
    public void decayLifetimeCounters(long now) {
        if (now - _lastCoalesce < DECAY_INTERVAL_MS) {return;}
        _lastCoalesce = now;
        decayCounter(_lifetimeAgreedTo, "lifetimeAgreedTo");
        decayCounter(_lifetimeRejected, "lifetimeRejected");
        decayCounter(_lifetimeTimedOut, "lifetimeTimedOut");
        decayCounter(_lifetimeFailed, "lifetimeFailed");
    }

    /**
     * Coalesce the rate statistics and periodically decay lifetime counters.
     * <p>Lifetime counters ({@link #_lifetimeAgreedTo}, {@link #_lifetimeRejected},
     * {@link #_lifetimeTimedOut}, {@link #_lifetimeFailed}) are decayed at 75%
     * per 8 hours (matching {@link DBHistory}).  This prevents unbounded
     * growth across restarts and ensures stale history does not permanently
     * block peers from tiers.  Recency is handled by the RateStats
     * (10-minute and 1-hour windows) and by selection gates.
     */
    public void coalesceStats() {
        if (_log.shouldDebug()) {_log.debug("Coalescing Profile Manager stats...");}
        _rejectRate.coalesceStats();
        _failRate.coalesceStats();

        decayLifetimeCounters(_context.clock().now());
    }

    /**
     * Apply decay to a counter. 3/4 per 8 h = ~1% after 128 hours.
     *
     * @param counter the lifetime counter to scale down, left alone when already zero
     * @param name the counter's name, for the debug log
     */
    private void decayCounter(AtomicLong counter, String name) {
        long val = counter.get();
        if (val > 0) {
            long newVal = val * DECAY_NUMERATOR / DECAY_DENOMINATOR;
            if (newVal != val) {
                counter.set(newVal);
                if (_log.shouldDebug()) {
                    _log.debug("Decayed " + name + ": " + val + " -> " + newVal);
                }
            }
        }
    }

    /** The platform line separator. */
    private static final String NL = System.getProperty("line.separator");
    /** Comment rule line of the history file. */
    private static final String HR = "# ----------------------------------------------------------------------------------------";

    /**
     * Write the tunnel history to the stream, including comments.
     *
     * @param out the stream to write the properties-formatted history to
     * @throws IOException if the stream rejects a write
     */
    public void store(OutputStream out) throws IOException {
        store(out, true);
    }

    /**
     * Write the data from the profile to the stream.
     *
     * @param out the stream to write the properties-formatted history to
     * @param addComments add comment lines to the output
     * @throws IOException if the stream rejects a write
     * @since 0.9.41
     */
    public void store(OutputStream out, boolean addComments) throws IOException {
        StringBuilder buf = new StringBuilder(512);
        if (addComments) {
            buf.append(HR).append(NL);
            buf.append("# Tunnel History").append(NL);
            buf.append(HR).append(NL).append(NL);
        }
        if (_lastAgreedTo != 0) {addDate(buf, addComments, "lastAgreedTo", _lastAgreedTo, "Last time peer agreed to participate in a tunnel:");}
        if (_lastRejectedCritical != 0) {addDate(buf, addComments, "lastRejectedCritical", _lastRejectedCritical, "Last time peer refused tunnel participation (Critical):");}
        if (_lastRejectedBandwidth != 0) {addDate(buf, addComments, "lastRejectedBandwidth", _lastRejectedBandwidth, "Last time peer refused tunnel participation (Bandwidth):");}
        if (_lastRejectedTransient != 0) {addDate(buf, addComments, "lastRejectedTransient", _lastRejectedTransient, "Last time peer refused tunnel participation (Transient):");}
        if (_lastRejectedProbabalistic != 0) {addDate(buf, addComments, "lastRejectedProbabalistic", _lastRejectedProbabalistic, "Last time peer refused tunnel participation (Probabalistic):");}
        if (_lastFailed != 0) {addDate(buf, addComments, "lastFailed", _lastFailed, "Last time of participating tunnel failure for peer:");}
        if (_lifetimeAgreedTo.get() > 0) {add(buf, addComments, "lifetimeAgreedTo", _lifetimeAgreedTo.get(), "Total tunnels peer agreed to participate in: " + _lifetimeAgreedTo.get());}
        if (_lifetimeRejected.get() > 0) {add(buf, addComments, "lifetimeRejected", _lifetimeRejected.get(), "Total tunnels peer refused to participate in: " + _lifetimeRejected.get());}
        if (_lifetimeTimedOut.get() > 0) {add(buf, addComments, "lifetimeTimedOut", _lifetimeTimedOut.get(), "Total tunnel builds that timed out waiting for response: " + _lifetimeTimedOut.get());}
        if (_lifetimeFailed.get() > 0) {add(buf, addComments, "lifetimeFailed", _lifetimeFailed.get(), "Total failed tunnels peer agreed to participate in: " + _lifetimeFailed.get());}
        out.write(buf.toString().getBytes(StandardCharsets.UTF_8));
        _rejectRate.store(out, "tunnelHistory.rejectRate", addComments);
        _failRate.store(out, "tunnelHistory.failRate", addComments);
    }

    /**
     * Appends the date line to the history file.
     *
     * @param buf where the line is appended
     * @param addComments true to write the line as a comment
     * @param name the statistic name
     * @param val the statistic value, used as the date
     * @param description the text shown before the date
     */
    private static void addDate(StringBuilder buf, boolean addComments, String name, long val, String description) {
        if (addComments) {
            String when = val > 0 ? (new Date(val)).toString() : "Never";
            add(buf, true, name, val, description + ' ' + when);
            //buf.append("# ").append(description).append(' ').append(when).append(NL);
        } else {add(buf, false, name, val, description);}
    }

    /**
     * Appends one statistic to the history file.
     *
     * @param buf where the line is appended
     * @param addComments true to write the line as a comment
     * @param name the statistic name
     * @param val the statistic value
     * @param description the text shown with the value
     */
    private static void add(StringBuilder buf, boolean addComments, String name, long val, String description) {
        if (addComments) {buf.append("# ").append(description).append(NL);}
        else {buf.append("tunnels.").append(name).append('=').append(val).append(NL);}
    }

    /**
     * Restore the tunnel history fields from the given properties.
     *
     * @param props the persisted properties previously written by {@link #store(OutputStream, boolean)}
     */
    public void load(Properties props) {
        _lastAgreedTo = getLong(props, "tunnels.lastAgreedTo");
        _lastFailed = getLong(props, "tunnels.lastFailed");
        _lastRejectedCritical = getLong(props, "tunnels.lastRejectedCritical");
        _lastRejectedBandwidth = getLong(props, "tunnels.lastRejectedBandwidth");
        _lastRejectedTransient = getLong(props, "tunnels.lastRejectedTransient");
        _lastRejectedProbabalistic = getLong(props, "tunnels.lastRejectedProbabalistic");
        _lifetimeAgreedTo.set(getLong(props, "tunnels.lifetimeAgreedTo"));
        _lifetimeFailed.set(getLong(props, "tunnels.lifetimeFailed"));
        _lifetimeRejected.set(getLong(props, "tunnels.lifetimeRejected"));
        _lifetimeTimedOut.set(getLong(props, "tunnels.lifetimeTimedOut"));
        try {
            _rejectRate.load(props, "tunnelHistory.rejectRate", true);
        } catch (IllegalArgumentException iae) {
            _log.warn("TunnelHistory reject rate is corrupt, resetting...", iae);
        }
        try {
            _failRate.load(props, "tunnelHistory.failRate", true);
        } catch (IllegalArgumentException iae) {
            _log.warn("TunnelHistory fail rate is corrupt, resetting...", iae);
        }
    }

    /**
     * Reads a long property with a default of zero.
     *
     * @param props the properties read
     * @param key the property name
     * @return the value, zero if unparseable
     */
    private static final long getLong(Properties props, String key) {
        return ProfilePersistenceHelper.getLong(props, key);
    }
}
