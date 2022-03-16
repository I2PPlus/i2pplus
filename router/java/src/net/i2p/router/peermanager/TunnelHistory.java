package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.router.RouterContext;
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
    private RateStat _rejectRate;
    private RateStat _failRate;
    private final String _statGroup;

    /** probabalistic tunnel rejection due to a flood of requests - essentially unused */
    public static final int TUNNEL_REJECT_PROBABALISTIC_REJECT = 10;
    /** tunnel rejection due to temporary cpu/job/tunnel overload - essentially unused */
    public static final int TUNNEL_REJECT_TRANSIENT_OVERLOAD = 20;
    /** tunnel rejection due to excess bandwidth usage */
    public static final int TUNNEL_REJECT_BANDWIDTH = 30;
    /** tunnel rejection due to system failure - essentially unused */
    public static final int TUNNEL_REJECT_CRIT = 50;

    private static final long[] RATES = { 60*60*1000l, 24*60*60*1000 };

    public TunnelHistory(RouterContext context, String statGroup) {
        _context = context;
        _log = context.logManager().getLog(TunnelHistory.class);
        _statGroup = statGroup;
        createRates(statGroup);
    }

    private void createRates(String statGroup) {
        _rejectRate = new RateStat("tunnelHistory.rejectRate", "How often does this peer reject a tunnel request?", statGroup, RATES);
        _failRate = new RateStat("tunnelHistory.failRate", "How often do tunnels this peer accepts fail?", statGroup, RATES);
    }

    /** total tunnels the peer has agreed to participate in */
    public long getLifetimeAgreedTo() { return _lifetimeAgreedTo.get(); }
    /** total tunnels the peer has refused to participate in */
    public long getLifetimeRejected() { return _lifetimeRejected.get(); }
    /** total tunnels the peer has agreed to participate in that were later marked as failed prematurely */
    public long getLifetimeFailed() { return _lifetimeFailed.get(); }
    /** when the peer last agreed to participate in a tunnel */
    public long getLastAgreedTo() { return _lastAgreedTo; }
    /** when the peer last refused to participate in a tunnel with level of critical */
    public long getLastRejectedCritical() { return _lastRejectedCritical; }
    /** when the peer last refused to participate in a tunnel complaining of bandwidth overload */
    public long getLastRejectedBandwidth() { return _lastRejectedBandwidth; }
    /** when the peer last refused to participate in a tunnel complaining of transient overload */
    public long getLastRejectedTransient() { return _lastRejectedTransient; }
    /** when the peer last refused to participate in a tunnel probabalistically */
    public long getLastRejectedProbabalistic() { return _lastRejectedProbabalistic; }
    /** when the last tunnel the peer participated in failed */
    public long getLastFailed() { return _lastFailed; }

    public void incrementProcessed(int processedSuccessfully, int failedProcessing) {
        // old strict speed calculator
    }

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
        if (severity >= TUNNEL_REJECT_CRIT) {
            _lastRejectedCritical = _context.clock().now();
            _rejectRate.addData(1);
        } else if (severity >= TUNNEL_REJECT_BANDWIDTH) {
            _lastRejectedBandwidth = _context.clock().now();
            _rejectRate.addData(1);
        } else if (severity >= TUNNEL_REJECT_TRANSIENT_OVERLOAD) {
            _lastRejectedTransient = _context.clock().now();
            // dont increment the reject rate in this case
        } else if (severity >= TUNNEL_REJECT_PROBABALISTIC_REJECT) {
            _lastRejectedProbabalistic = _context.clock().now();
            // dont increment the reject rate in this case
        }
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

/*****  all unused
    public void setLifetimeAgreedTo(long num) { _lifetimeAgreedTo = num; }
    public void setLifetimeRejected(long num) { _lifetimeRejected = num; }
    public void setLifetimeFailed(long num) { _lifetimeFailed = num; }
    public void setLastAgreedTo(long when) { _lastAgreedTo = when; }
    public void setLastRejectedCritical(long when) { _lastRejectedCritical = when; }
    public void setLastRejectedBandwidth(long when) { _lastRejectedBandwidth = when; }
    public void setLastRejectedTransient(long when) { _lastRejectedTransient = when; }
    public void setLastRejectedProbabalistic(long when) { _lastRejectedProbabalistic = when; }
    public void setLastFailed(long when) { _lastFailed = when; }
******/

    public RateStat getRejectionRate() { return _rejectRate; }
    public RateStat getFailedRate() { return _failRate; }

    public void coalesceStats() {
        if (_log.shouldDebug())
            _log.debug("Coalescing Profile Manager stats");
        _rejectRate.coalesceStats();
        _failRate.coalesceStats();
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
        if (_lastAgreedTo != 0)
            addDate(buf, addComments, "lastAgreedTo", _lastAgreedTo, "Last time peer agreed to participate in a tunnel:");
        if (_lastFailed != 0)
            addDate(buf, addComments, "lastFailed", _lastFailed, "Last time of participating tunnel failure for peer:");
        if (_lastRejectedCritical != 0)
            addDate(buf, addComments, "lastRejectedCritical", _lastRejectedCritical, "Last time peer refused tunnel participation (Critical):");
        if (_lastRejectedBandwidth != 0)
            addDate(buf, addComments, "lastRejectedBandwidth", _lastRejectedBandwidth, "Last time peer refused tunnel participation (Bandwidth):");
        if (_lastRejectedTransient != 0)
            addDate(buf, addComments, "lastRejectedTransient", _lastRejectedTransient, "Last time peer refused tunnel participation (Transient):");
        if (_lastRejectedProbabalistic != 0)
            addDate(buf, addComments, "lastRejectedProbabalistic", _lastRejectedProbabalistic, "Last time peer refused tunnel participation (Probabalistic):");
        add(buf, addComments, "lifetimeAgreedTo", _lifetimeAgreedTo.get(), "Total tunnels peer agreed to participate in: " + _lifetimeAgreedTo.get());
        add(buf, addComments, "lifetimeFailed", _lifetimeFailed.get(), "Total failed tunnels peer agreed to participate in: " + _lifetimeFailed.get());
        add(buf, addComments, "lifetimeRejected", _lifetimeRejected.get(), "Total tunnels peer refused to participate in: " + _lifetimeRejected.get());
        out.write(buf.toString().getBytes("UTF-8"));
        _rejectRate.store(out, "tunnelHistory.rejectRate", addComments);
        _failRate.store(out, "tunnelHistory.failRate", addComments);
    }

    private static void addDate(StringBuilder buf, boolean addComments, String name, long val, String description) {
        if (addComments) {
            String when = val > 0 ? (new Date(val)).toString() : "Never";
            add(buf, true, name, val, description + ' ' + when);
//            buf.append("# ").append(description).append(' ').append(when).append(NL);
        } else {
            add(buf, false, name, val, description);
        }
    }

    private static void add(StringBuilder buf, boolean addComments, String name, long val, String description) {
        if (addComments)
            buf.append("# ").append(description).append(NL);
        else
            buf.append("tunnels.").append(name).append('=').append(val).append(NL);
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
        try {
            _rejectRate.load(props, "tunnelHistory.rejectRate", true);
            if (_log.shouldDebug())
                _log.debug("Loading tunnelHistory.rejectRate");
            _failRate.load(props, "tunnelHistory.failRate", true);
            if (_log.shouldDebug())
                _log.debug("Loading tunnelHistory.failRate");
        } catch (IllegalArgumentException iae) {
            _log.warn("TunnelHistory rates are corrupt, resetting...", iae);
            createRates(_statGroup);
        }
    }

    private final static long getLong(Properties props, String key) {
        return ProfilePersistenceHelper.getLong(props, key);
    }
}
