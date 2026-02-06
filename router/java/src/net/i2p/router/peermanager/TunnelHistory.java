package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import net.i2p.router.RouterContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * Tunnel related history information
 *
 */
public class TunnelHistory {
    private final RouterContext _context;
    private final Log _log;
    private final AtomicLong _lifetimeAgreedTo = new AtomicLong();
    private final AtomicLong _lifetimeRejected = new AtomicLong();
    private volatile long _lastAgreedTo;
    private volatile long _lastRejectedCritical;
    private volatile long _lastRejectedBandwidth;
    private volatile long _lastRejectedTransient;
    private volatile long _lastRejectedProbabalistic;
    private final AtomicLong _lifetimeFailed = new AtomicLong();
    private volatile long _lastFailed;
    private final RateStat _rejectRate;
    private final RateStat _failRate;
    private final String _statGroup;
    static final long[] RATES = new long[] {RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR, RateConstants.ONE_DAY };

    /** probabalistic tunnel rejection due to a flood of requests - infrequent */
    public static final int TUNNEL_REJECT_PROBABALISTIC_REJECT = 10;
    /** tunnel rejection due to temporary cpu/job/tunnel overload - rare */
    public static final int TUNNEL_REJECT_TRANSIENT_OVERLOAD = 20;
    /** tunnel rejection due to excess bandwidth usage - used for most rejections even if not really for bandwidth */
    public static final int TUNNEL_REJECT_BANDWIDTH = 30;
    /** tunnel rejection due to system failure - not currently used */
    public static final int TUNNEL_REJECT_CRIT = 50;

    public TunnelHistory(RouterContext context, String statGroup) {
        _context = context;
        _log = context.logManager().getLog(TunnelHistory.class);
        _statGroup = statGroup;
        _rejectRate = new RateStat("tunnelHistory.rejectRate", "How often peer rejects a tunnel request?", statGroup, RATES);
        _failRate = new RateStat("tunnelHistory.failRate", "How often do tunnels this peer accepts fail?", statGroup, RATES);
    }

    /** total tunnels the peer has agreed to participate in */
    public long getLifetimeAgreedTo() {return _lifetimeAgreedTo.get();}
    /** total tunnels the peer has refused to participate in */
    public long getLifetimeRejected() {return _lifetimeRejected.get();}
    /** total tunnels the peer has agreed to participate in that were later marked as failed prematurely */
    public long getLifetimeFailed() {return _lifetimeFailed.get();}
    /** when the peer last agreed to participate in a tunnel */
    public long getLastAgreedTo() {return _lastAgreedTo;}
    /** when the peer last refused to participate in a tunnel with level of critical */
    public long getLastRejectedCritical() {return _lastRejectedCritical;}
    /** when the peer last refused to participate in a tunnel complaining of bandwidth overload */
    public long getLastRejectedBandwidth() {return _lastRejectedBandwidth;}
    /** when the peer last refused to participate in a tunnel complaining of transient overload */
    public long getLastRejectedTransient() {return _lastRejectedTransient;}
    /** when the peer last refused to participate in a tunnel probabalistically */
    public long getLastRejectedProbabalistic() {return _lastRejectedProbabalistic;}
    /** when the last tunnel the peer participated in failed */
    public long getLastFailed() {return _lastFailed;}

    public void incrementProcessed(int processedSuccessfully, int failedProcessing) {} // old strict speed calculator

    public void incrementAgreedTo() {
        _lifetimeAgreedTo.incrementAndGet();
        _lastAgreedTo = _context.clock().now();
    }

    /**
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
     * Define this rate as the probability it really failed
     * @param pct = probability * 100
     */
    public void incrementFailed(int pct) {
        _lifetimeFailed.incrementAndGet();
        _failRate.addData(pct);
        _lastFailed = _context.clock().now();
    }

    public RateStat getRejectionRate() {return _rejectRate;}
    public RateStat getFailedRate() {return _failRate;}

    public void coalesceStats() {
        if (_log.shouldDebug()) {_log.debug("Coalescing Profile Manager stats...");}
        _rejectRate.coalesceStats();
        _failRate.coalesceStats();
    }

    /**
     * Reset all tunnel history counters and timestamps.
     * Used to give ghost peers a fresh start.
     * @since 0.9.68+
     */
    public void reset() {
        _lifetimeAgreedTo.set(0);
        _lifetimeRejected.set(0);
        _lifetimeFailed.set(0);
        _lastAgreedTo = 0;
        _lastRejectedCritical = 0;
        _lastRejectedBandwidth = 0;
        _lastRejectedTransient = 0;
        _lastRejectedProbabalistic = 0;
        _lastFailed = 0;
        // RateStats will naturally decay over time
    }

    private final static String NL = System.getProperty("line.separator");
    private final static String HR = "# ----------------------------------------------------------------------------------------";

    public void store(OutputStream out) throws IOException {
        store(out, true);
    }

    /**
     * write out the data from the profile to the stream
     * @param addComments add comment lines to the output
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
        if (_lifetimeFailed.get() > 0) {add(buf, addComments, "lifetimeFailed", _lifetimeFailed.get(), "Total failed tunnels peer agreed to participate in: " + _lifetimeFailed.get());}
        out.write(buf.toString().getBytes("UTF-8"));
        _rejectRate.store(out, "tunnelHistory.rejectRate", addComments);
        _failRate.store(out, "tunnelHistory.failRate", addComments);
    }

    private static void addDate(StringBuilder buf, boolean addComments, String name, long val, String description) {
        if (addComments) {
            String when = val > 0 ? (new Date(val)).toString() : "Never";
            add(buf, true, name, val, description + ' ' + when);
            //buf.append("# ").append(description).append(' ').append(when).append(NL);
        } else {add(buf, false, name, val, description);}
    }

    private static void add(StringBuilder buf, boolean addComments, String name, long val, String description) {
        if (addComments) {buf.append("# ").append(description).append(NL);}
        else {buf.append("tunnels.").append(name).append('=').append(val).append(NL);}
    }

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

        // TODO: work out why this is causing errors at startup when loading profiles
/**
        try {
            _rejectRate.load(props, "tunnelHistory.rejectRate", true);
            _failRate.load(props, "tunnelHistory.failRate", true);
        } catch (IllegalArgumentException iae) {
            _log.warn("TunnelHistory rates are corrupt, resetting...", iae);
        }
**/
    }

    private final static long getLong(Properties props, String key) {
        return ProfilePersistenceHelper.getLong(props, key);
    }
}
