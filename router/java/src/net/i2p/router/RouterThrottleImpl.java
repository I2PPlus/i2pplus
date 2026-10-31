package net.i2p.router;

import net.i2p.data.router.RouterInfo;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;

/**
 * Simple throttle that basically stops accepting messages or nontrivial
 * requests if the jobQueue lag is too large.
 */
public class RouterThrottleImpl implements RouterThrottle {
    /** Router context, used for the job queue, tunnel counts, bandwidth limits and rates. */
    protected final RouterContext _context;
    /** Logger for the throttle. */
    private final Log _log;
    /** Tunnel status text for the reject log. */
    private volatile String _tunnelStatus;
    /** Startup time before the throttle may reject. */
    private final long _rejectStartupTime;

    /** Arbitrary hard limit - if it's taking this long to get to a job, we're congested. */
    private static final long JOB_LAG_LIMIT_NETWORK = 3*1000L;

    /** Property name for the participating tunnel cap. */
    public static final String PROP_MAX_TUNNELS = "router.maxParticipatingTunnels";
    /** Default maximum tunnels, adjusted by system speed and memory. */
    public static volatile int defaultMaxTunnels = SystemVersion.isSlow() ? 3*1000 :
                                                  SystemVersion.getMaxMemory() < 512*1024*1024L ? 5*1000 :
                                                  SystemVersion.getCores() >= 8 ? 12*1000 : 8*1000;
    /**
     * Participating tunnel cap in force when the property is unset.
     * @return the default tunnel count
     * @since 0.9.70+
     */
    public static int getDefaultMaxTunnels() { return defaultMaxTunnels; }
    /**
     * Set the default tunnel cap, clamped to 500..20000.
     * @param val participating tunnels to use when the property is unset
     * @since 0.9.70+
     */
    public static void setDefaultMaxTunnels(int val) { defaultMaxTunnels = Math.max(500, Math.min(20000, val)); }
    /** Config key for the processing time limit. */
    private static final String PROP_MAX_PROCESSINGTIME = "router.defaultProcessingTimeThrottle";
    /** Default startup delay before rejecting. */
    private static final long DEFAULT_REJECT_STARTUP_TIME = 3*60*1000L;
    /** Shortest reject startup delay allowed. */
    private static final long MIN_REJECT_STARTUP_TIME = 90*1000L;
    /** Config key for the reject startup delay. */
    private static final String PROP_REJECT_STARTUP_TIME = "router.rejectStartupTime";

    /** Config key for the minimum tunnels before throttling. */
    private static final String PROP_MIN_THROTTLE_TUNNELS = "router.minThrottleTunnels";

    /**
     * How long the 1-minute message-processing average must stay above the
     * throttle threshold before we actually reject tunnel requests. Brief lag
     * spikes (GC pauses, netdb churn, rebuild storms) that recover within this
     * window must not trip throttling. 3 minutes &gt; one 1-minute coalesce
     * period, so the condition is confirmed across multiple samples.
     *
     * @since 0.9.70+
     */
    private static final long MSG_DELAY_SUSTAIN_MS = 3*60*1000L;

    /** Timestamp (ms) the message-delay average first crossed the threshold, or -1. */
    private volatile long _msgDelayOverSince = -1;

    /** Default processing time limit, in ms. */
    private static final int DEFAULT_MAX_PROCESSINGTIME = SystemVersion.isSlow() ? 3000 : 2000;

    /** Tunnel acceptance. */
    public static final int TUNNEL_ACCEPT = 0;

    /** = TrivialPreprocessor.PREPROCESSED_SIZE. */
    private static final int PREPROCESSED_SIZE = 1024;


    /**
     * Create a throttle and schedule the reset of the "starting up" tunnel status.
     * @param context the router context
     */
    public RouterThrottleImpl(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(RouterThrottleImpl.class);
        setTunnelStatus();
        _rejectStartupTime = Math.max(MIN_REJECT_STARTUP_TIME, _context.getProperty(PROP_REJECT_STARTUP_TIME, DEFAULT_REJECT_STARTUP_TIME));
        _context.simpleTimer2().addEvent(new ResetStatus(), 5*1000L + _rejectStartupTime);

    }

    /**
     * Reset status from starting up to not-starting up,
     * in case we don't get a tunnel request soon after the 20 minutes is up.
     *
     * @since 0.8.12
     */
    private class ResetStatus extends SimpleTimer2.TimedEvent {
        /**
         * Timer event that clears the "Starting up" tunnel status later.
         */
        private ResetStatus() {}

        public void timeReached() {
            if (_tunnelStatus.contains(_x("Starting up"))) {cancelShutdownStatus();}
        }
    }

    /**
     * Should we accept any more data from the network for any sort of message,
     * taking into account our current load, or should we simply slow down?
     */
    public boolean acceptNetworkMessage() {
        long lag = _context.jobQueue().getMaxLag();
        if ((lag > JOB_LAG_LIMIT_NETWORK) && (_context.router().getUptime() > 60*1000L)) {
            if (_log.shouldWarn()) {_log.warn("Throttling Network Reader -> Job lag is " + lag + "ms");}
            return false;
        } else {return true;}
    }

    /**
     * If we should send a reject, return a nonzero reject code.
     * Anything that causes us to drop a request instead of rejecting it
     * must go in {@link net.i2p.router.tunnel.pool.BuildHandler#handleInboundRequest}, not here.
     *
     * @return 0 for accept or nonzero reject code
     */
    public int acceptTunnelRequest() {
        if (_context.router().gracefulShutdownInProgress()) {
            // One line per burst, not per request: every tunnel request that
            // arrives during a shutdown lands here.
            _log.warnThrottled("throttle-refuse-shutdown",
                               "Refusing all Tunnel Requests -> Graceful shutdown in progress...");
            setShutdownStatus();
            // Don't use CRIT because this tells everybody we are shutting down
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }

        // Don't use CRIT because we don't want peers to think we're failing
        if (_context.router().getUptime() < _rejectStartupTime && !_context.router().isHidden()) {
            setTunnelStatus("[starting]" + _x("Starting up") + "&hellip;");
        } else if (_context.router().isHidden()) {
            setTunnelStatus("[hidden]" + _x("Declining all tunnel requests" + ":<br>" + _x("Hidden Mode")));
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }

        RateAverages ra = RateAverages.getTemp();

        // TODO
        // This stat is highly dependent on transport mix.
        // For NTCP, it is queueing delay only, ~25ms
        // For SSU it is queueing + ack time, ~1000 ms.
        // (SSU acks may be delayed so it is much more than just RTT... and the delay may
        // counterintuitively be more when there is low traffic)
        // Change the stat or pick a better stat.
        int maxTunnels = getMaxTunnels();
        RateStat rs = _context.statManager().getRate("transport.sendProcessingTime");
        Rate r = null;
        if (rs != null) {r = rs.getRate(RateConstants.ONE_MINUTE);}

            //Reject tunnels if the time to process messages and send them is too large. Too much time implies congestion.
        if (r != null) {
            r.computeAverages(ra,false);

            int maxProcessingTime = getMaxProcessingTime();

            // Gate on the smoothed 1-minute average only. The instantaneous values
            // (getCurrent()/getLast()) spike on single slow messages (GC pauses, netdb
            // churn, rebuild storms) but recover within seconds; rejecting every tunnel
            // request on a transient blip sheds load blindly and, worse, signals bandwidth
            // saturation to peers (see below). A brief spike that the router quickly
            // recovers from must NOT trigger throttling.
            boolean over = ra.getAverage() > maxProcessingTime * 0.9;
            if (over) {
                // Require the average to stay elevated for a short sustain window before
                // we actually reject, so quick-recovering lag spikes pass through.
                if (_msgDelayOverSince < 0) {
                    _msgDelayOverSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - _msgDelayOverSince >= MSG_DELAY_SUSTAIN_MS) {
                    if (_log.shouldInfo()) {
                        _log.warn("Refusing Tunnel Request -> Message processing congestion" +
                                  "\n* Average: " + ((int)ra.getAverage()) + "ms" +
                                  "\n* Max time to process: " + maxProcessingTime + "ms" +
                                  "\n* Sustained for: " + (System.currentTimeMillis() - _msgDelayOverSince) + "ms");
                    } else if (_log.shouldWarn()) {
                        _log.warn("Refusing Tunnel Request -> Message processing congestion");
                    }
                    setTunnelStatus("[rejecting/overload]" + _x("Declining Tunnel Requests" + ":<br>" + _x("High message delay")));
                    // Use TRANSIENT_OVERLOAD (not BANDWIDTH) so peers retry shortly rather
                    // than deprioritizing us permanently — a lag spike is not a bandwidth
                    // saturation, and the bandwidth path (allowTunnel) already handles real
                    // saturation with graduated probabilistic rejection.
                    return TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
                }
            } else {
                _msgDelayOverSince = -1;
            }
        }

        int numTunnels = _context.tunnelManager().getParticipatingCount();
        if (numTunnels >= maxTunnels) {
            if (maxTunnels > 0) {
                if (_log.shouldWarn()) {
                    _log.warn("Refusing Tunnel Request -> Already participating in "
                              + numTunnels + " (Max: " + maxTunnels + ")");
                }
                setTunnelStatus("[rejecting/max]" + _x("Declining requests" + ": " + _x("Limit reached")));
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Refusing Tunnel Request -> Disabled by configuration");
                }
                setTunnelStatus("[disabled]" + _x("Declining Tunnel Requests" + ":<br>" + _x("Participation disabled")));
            }
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }

        /*
         * Throttle if we go above a minimum level of tunnels AND the maximum participating
         * tunnels is default or lower.
         *
         * Lag based statistics use a moving average window (of for example 10 minutes), they are therefore
         * sensitive to sudden rapid growth of load, which are not instantly detected by these metrics.
         * Reduce tunnel growth if we are growing faster than the lag based metrics can detect reliably.
         */
        long lag = _context.jobQueue().getMaxLag();
        boolean highload = lag > 1000 && SystemVersion.getCPULoadAvg() > 95;
        int minToThrottle = getMinThrottleTunnels();
        double tgf = getTunnelGrowthFactor();
        if (highload) {
            setTunnelStatus("[rejecting/overload]" + _x("Rejecting all tunnel requests" + ":<br>" + _x("High system load")));
        } else if (numTunnels > minToThrottle && defaultMaxTunnels >= maxTunnels) {
            Rate avgTunnels = _context.statManager().getRate("tunnel.participatingTunnels").getRate(RateConstants.TEN_MINUTES);
            if (avgTunnels != null) {
                double avg = avgTunnels.getAvgOrLifetimeAvg();
                double tunnelGrowthFactor = SystemVersion.isSlow() || highload ? tgf : tgf * 3 / 2;
                if (avg < minToThrottle) {avg = minToThrottle;}
                // if the current tunnel count is higher than 1.3 * the average...
                if ((avg > 0) && (avg*tunnelGrowthFactor < numTunnels)) {
                    // we're accelerating, let's try not to take on too much too fast
                    // Use linear probability instead of squared to reduce throttling aggressiveness
                    // and prevent self-reinforcing decline cycles
                    double probAccept = (avg*tunnelGrowthFactor) / numTunnels;
                    probAccept = Math.max(0.1, probAccept); // ensure minimum 10% acceptance
                    int v = _context.random().nextInt(100);
                    if (v < probAccept*100) { // ok
                        if (_log.shouldInfo()) {
                            _log.info("Probabalistically accepting Tunnel Request (p: " + probAccept
                                      + " v: " + v + " avg: " + avg
                                      + " current: " + numTunnels + ")");
                        }
                    } else {
                        if (_log.shouldWarn()) {
                            _log.warn("Probabalistically refusing Tunnel Request (avg: " + avg
                                      + " current: " + numTunnels + ")");
                        }
                        if (probAccept <= 0.5) {
                            setTunnelStatus("[rejecting/overload]" + _x("Rejecting most tunnel requests" + ":<br>" + _x("High number of requests")));
                        }
                        else if (probAccept <= 0.9) {setTunnelStatus("[accepting]" + _x("Accepting most tunnel requests"));}
                        else if (numTunnels > 0) {setTunnelStatus("[accepting]" + _x("Accepting tunnel requests"));}
                        else {setTunnelStatus("[ready]" + _x("Accepting tunnel requests"));}
                        return TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT;
                    }
                } else {
                    if (_log.shouldInfo()) {
                        _log.info("Accepting Tunnel Request -> Tunnel count average is " + avg + " and we only have " + numTunnels + ")");
                    }
                }
            }
        }

        double tunnelTestTimeGrowthFactor = getTunnelTestTimeGrowthFactor();
        Rate tunnelTestTime1m = _context.statManager().getRate("tunnel.testSuccessTime").getRate(RateConstants.ONE_MINUTE);
        Rate tunnelTestTime1h = _context.statManager().getRate("tunnel.testSuccessTime").getRate(RateConstants.ONE_HOUR);
        if ( (tunnelTestTime1m != null) && (tunnelTestTime1h != null) && (tunnelTestTime1m.getLastEventCount() > 0) ) {
            double avg1m = tunnelTestTime1m.getAverageValue();
            double avg1h = tunnelTestTime1h.getAvgOrLifetimeAvg();

            if (avg1h < 5000) {avg1h = 5000;} // minimum before complaining

            if ( (avg1h > 0) && (avg1m > avg1h * tunnelTestTimeGrowthFactor) ) {
                double probAccept = (avg1h*tunnelTestTimeGrowthFactor)/avg1m;
                probAccept = probAccept * probAccept; // square the decelerator for test times
                int v = _context.random().nextInt(100);
                if (v < probAccept*100 && _log.shouldInfo()) { // ok
                    _log.info("Probabalistically accepting Tunnel Request (p: " + probAccept
                              + " v: " + v + " test time avg 1m: " + avg1m + " 1h: " + avg1h + ")");
                }
            }
        }

        // ok, we're not hosed, but can we handle the bandwidth requirements of another tunnel?
        rs = _context.statManager().getRate("tunnel.participatingMessageCountAvgPerTunnel");
        double messagesPerTunnel = 0;
        if (rs != null) {
            r = rs.getRate(RateConstants.ONE_MINUTE);
            if (r != null) {messagesPerTunnel = r.computeAverages(ra, true).getAverage();}
        }
        if (messagesPerTunnel < DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE) {
            messagesPerTunnel = DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE;
        }

        double bytesAllocated = messagesPerTunnel * numTunnels * PREPROCESSED_SIZE;

        // Transient overload: a 1-minute rate above our cap indicates a recent spike,
        // not sustained saturation. Reject probabilistically in proportion to the
        // overage but signal TRANSIENT_OVERLOAD so peers retry soon instead of
        // deprioritizing us as bandwidth-starved; the graduated saturation curve in
        // allowTunnel() below handles genuine, sustained shortage.
        int maxKBpsIn = _context.bandwidthLimiter().getInboundKBytesPerSecond();
        int maxKBpsOut = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        long overage = Math.max(
                _context.router().get1mRateIn() - (maxKBpsIn*1024L),
                _context.router().get1mRate(true) - (maxKBpsOut*1024L));
        if ((overage > 0) && ((overage/(Math.min(maxKBpsIn, maxKBpsOut)*1024f)) > _context.random().nextFloat())) {
            if (_log.shouldWarn()) {
                _log.warn("Rejecting participating Tunnel Request \n* 1 minute rate (" + overage + " over) indicates overload.");
            }
            setTunnelStatus("[rejecting/overload]" + LIMIT_STR);
            return TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
        }

        if (!allowTunnel(bytesAllocated, numTunnels)) {
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }
        return TUNNEL_ACCEPT;
    }

    /**
     * This is the estimated number of 1 KB tunnel messages that we'll see in
     * 10 minute lifetime of an exploratory tunnel. We use it as a baseline
     * minimum for estimating tunnel bandwidth, if accepted.
     * <p>
     * 600 KB in 10 minutes equals ~4 KBps - increased from 200 for better
     * bandwidth allocation per transit tunnel.
     */
    public static final int DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE = 600; // ~4KBps

    /**
     * Calculate a floor for per-tunnel bandwidth allocation based on
     * participating tunnel count and total outbound bandwidth share.
     * This ensures a minimum allocation even when many tunnels are hosted.
     *
     * @param ctx the router context supplying bandwidth, share percentage and tunnel count
     * @return minimum bytes per second to allocate per tunnel
     */
    public static int getMinBandwidthFloorPerTunnel(RouterContext ctx) {
        int maxKBps = ctx.bandwidthLimiter().getOutboundKBytesPerSecond();
        int share = (int) (maxKBps * 1024L * ctx.router().getSharePercentage());
        int numTunnels = ctx.tunnelManager().getParticipatingCount();
        if (numTunnels <= 0 || share <= 0) {
            return (int) ((long) DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE * 4096 / (10*60));
        }
        // Floor: either 10% of share / tunnel count, or 4KB/s minimum
        int floor = Math.max(share / Math.max(numTunnels, 10), 4 * 1024);
        return Math.min(floor, share / 2); // Cap at 50% of share
    }

    /** Also limited to 90% - see below. */
    private static final int MIN_AVAILABLE_BPS = 4*1024; // always leave at least 4KBps free when allowing
    /** Log text for a bandwidth-limit rejection. */
    private static final String LIMIT_STR = _x("Declining requests" + ": " + "Bandwidth limit");

    /**
     * With bytesAllocated already accounted for across the numTunnels existing
     * tunnels we have agreed to, can we handle another tunnel with our existing
     * bandwidth?
     *
     * @param bytesAllocated bytes per second already allocated across the
     *                       existing tunnels
     * @param numTunnels the number of participating tunnels we are in
     * @return true if another tunnel request should be allowed
     */
    private boolean allowTunnel(double bytesAllocated, int numTunnels) {
        int maxKBpsIn = _context.bandwidthLimiter().getInboundKBytesPerSecond();
        int maxKBpsOut = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        int maxKBps = Math.min(maxKBpsIn, maxKBpsOut);
        // Deliberately permissive: taking the lower of the two windows biases
        // toward accepting tunnels during traffic ramps, letting WRED shed the
        // overflow gracefully on transit tunnels instead of rejecting them.
        int usedIn = Math.min(_context.router().get1sRateIn(), _context.router().get15sRateIn());
        int usedOut = Math.min(_context.router().get1sRate(true), _context.router().get15sRate(true));
        int used = Math.max(usedIn, usedOut);

        // Check the inbound and outbound total bw available (separately)
        // We block all tunnels when share bw is over (max * 0.9) - 4KB
        // This gives reasonable growth room for existing tunnels on both low and high
        // bandwidth routers. We want to be rejecting tunnels more aggressively than
        // dropping packets with WRED
        int availBps = Math.min((int)(maxKBpsIn*1024L*9/10) - usedIn, (int)(maxKBpsOut*1024L*9/10) - usedOut);
        if (availBps < MIN_AVAILABLE_BPS) {
            if (_log.shouldWarn()) {
            _log.warn("Rejecting participating tunnel requests \n* Available bandwidth (" + availBps +
                      "B/s) is less than minimum required (" + MIN_AVAILABLE_BPS + "B/s)");
            }
            setTunnelStatus("[rejecting/max]" + LIMIT_STR);
            return false;
        }

        // Now compute the share bw available, using
        // the bytes-allocated estimate for the participating tunnels
        // (if lower than the total bw, which it should be),
        // since some of the total used bandwidth may be for local clients
        double share = _context.router().getSharePercentage();
        used = Math.min(used, (int) (bytesAllocated / (10*60L)));
        availBps = Math.min(availBps, (int)(((maxKBps*1024L)*share) - used));

        double probReject;
        boolean reject;
        if (availBps <= 0) {
            probReject = 1;
            reject = true;
            _log.warn("Rejecting participating tunnel -> Insufficient bandwidth available" +
                      "\n* Available: " + availBps + " * Maximum allocated: " + maxKBps +
                      " * In use: " + used +
                      "\n* Active tunnels: " + numTunnels +
                      " * Estimated bandwidth required: " + bytesAllocated);
        } else {
            // limit at 90% - 4KBps (see above)
            float maxBps = (maxKBps * 1024f * 0.9f) - MIN_AVAILABLE_BPS;
            float pctFull = (maxBps - availBps) / (maxBps);
            if (pctFull < 0.90f) {
                probReject = 0; // probReject < ~5%
                reject = false;
                if (_log.shouldDebug()) {
                    _log.debug("Accepting a message -> Available: " + availBps + "B/s * Maximum: " + maxKBps + "KB/s" +
                               " * In use: " + used + "B/s\n* Reject probability: 0" +
                               " * Tunnels: " + numTunnels + " * Estimated: " + bytesAllocated + "B/s");
                }
            } else {
                // Configurable steepness of rejection curve (default: 10)
                // Lower value = smoother transition to full capacity
                // Clamped [3,20] — below 3 the curve is nearly linear (too aggressive
                // at moderate load), above 20 it creates a cliff (near-zero rejection
                // until saturation, then instant 100%).
                int exponent = getThrottleRejectExponent(_context);
                probReject = Math.pow(pctFull, exponent);
                double rand = _context.random().nextFloat();
                reject = rand <= probReject;
                if (reject) {
                    if (_log.shouldWarn()) {
                        _log.warn("Rejecting a message -> Available: " + availBps + "B/s * Maximum: " + maxKBps + "KB/s" +
                                  " * In use: " + used + "B/s" +
                                  "\n* Reject probability: " + probReject + " * Capacity fraction: " + pctFull +
                                  "\n* Tunnels: " + numTunnels + " * Random: " + rand +
                                  " * Estimated: " + bytesAllocated + "B/s");
                    }
                } else if (_log.shouldDebug()) {
                    _log.debug("Accepting a message -> Available: " + availBps + "B/s * Maximum: " + maxKBps + "KB/s" +
                           " * In use: " + used + "B/s" +
                           "\n* Reject probability: " + probReject + " * Capacity fraction: " + pctFull +
                           "\n* Tunnels: " + numTunnels + " * Random: " + rand +
                           " * Estimated: " + bytesAllocated + "B/s");
                }
            }
        }

        if (probReject >= 0.9) {setTunnelStatus("[rejecting]" + LIMIT_STR);}
        else if (probReject >= 0.5) {setTunnelStatus("[rejecting/bandwidth]" + _x("Declining requests" + ": " + "Bandwidth limit"));}
        else if(probReject >= 0.1) {setTunnelStatus("[accepting]" + _x("Accepting most tunnel requests"));} // hard to do {0} from here
        else {setTunnelStatus("[accepting]" + _x("Accepting tunnel requests"));}
        return !reject;
    }

    /**
     * Don't ever probabilistically throttle tunnels if we have less than this many.
     *
     * @return the configured minimum, or two thirds of the maximum tunnel
     *         count when the property is unset or invalid
     */
    private int getMinThrottleTunnels() {
        String p = _context.getProperty(PROP_MIN_THROTTLE_TUNNELS);
        if (!(p != null && p.equals(_cachedMinThrottleTunnelsProp))) {
            _cachedMinThrottleTunnelsProp = p;
            if (p == null) {
                _cachedMinThrottleTunnels = -1;
            } else {
                try {
                    _cachedMinThrottleTunnels = Integer.parseInt(p);
                } catch (NumberFormatException nfe) {
                    _cachedMinThrottleTunnels = -1;
                }
            }
        }
        if (_cachedMinThrottleTunnels > 0) {
            return _cachedMinThrottleTunnels;
        }
        return (getMaxTunnels() / 3) * 2;
    }

    /** Config key for the tunnel growth factor. */
    private static final String PROP_TUNNEL_GROWTH_FACTOR = "router.tunnelGrowthFactor";
    /** Config key for the test time growth factor. */
    private static final String PROP_TUNNEL_TEST_TIME_GROWTH_FACTOR = "router.tunnelTestTimeGrowthFactor";
    /** Default tunnel growth factor. */
    private static final double DEFAULT_TUNNEL_GROWTH_FACTOR = 2.0d;
    /** Default test time growth factor. */
    private static final double DEFAULT_TUNNEL_TEST_TIME_GROWTH_FACTOR = 1.5d;

    /** Tuner override for the growth factor, -1 if unset. */
    private static volatile double _tunedGrowthFactor = -1.0d;

    /**
     * Set the tunnel growth factor (called by Tuner).
     * @param factor the tunnel growth factor
     * @since 0.9.71+
     */
    public static void setTunnelGrowthFactor(double factor) { _tunedGrowthFactor = factor; }

    /**
     * The tunnel growth factor in effect, tuned value if set else config.
     * @param ctx the router context
     * @return the tunnel growth factor
     * @since 0.9.71+
     */
    public static double getTunnelGrowthFactorTuned(RouterContext ctx) {
        double tuned = _tunedGrowthFactor;
        if (tuned >= 0.0d) return tuned;
        String p = ctx.getProperty(PROP_TUNNEL_GROWTH_FACTOR);
        if (p != null) {
            try { return Double.parseDouble(p); } catch (NumberFormatException nfe) { /* unparseable property; use the default tunnel growth factor */ }
        }
        return DEFAULT_TUNNEL_GROWTH_FACTOR;
    }

    /** Config value the cached growth factor was read from. */
    private String _cachedGrowthFactorProp;
    /** Cached tunnel growth factor. */
    private double _cachedGrowthFactor = DEFAULT_TUNNEL_GROWTH_FACTOR;
    /** Config value the cached test time factor was read from. */
    private String _cachedTestTimeGrowthFactorProp;
    /** Cached test time growth factor. */
    private double _cachedTestTimeGrowthFactor = DEFAULT_TUNNEL_TEST_TIME_GROWTH_FACTOR;
    /** Config value the cached max tunnels was read from. */
    private String _cachedMaxTunnelsProp;
    /** Cached maximum tunnel count. */
    private int _cachedMaxTunnels = defaultMaxTunnels;
    /** Config value the cached processing time was read from. */
    private String _cachedMaxProcessingTimeProp;
    /** Cached processing time limit, in ms. */
    private int _cachedMaxProcessingTime = DEFAULT_MAX_PROCESSINGTIME;
    /** Config value the cached minimum throttle tunnels was read from. */
    private String _cachedMinThrottleTunnelsProp;
    /** Cached minimum tunnels before throttling, -1 if unset. */
    private int _cachedMinThrottleTunnels = -1;

    /**
     * Returns the cached maximum tunnel count.
     *
     * @return the maximum tunnels
     */
    private int getMaxTunnels() {
        String p = _context.getProperty(PROP_MAX_TUNNELS);
        if (p != null && p.equals(_cachedMaxTunnelsProp)) {
            return _cachedMaxTunnels;
        }
        _cachedMaxTunnelsProp = p;
        if (p == null) {
            _cachedMaxTunnels = defaultMaxTunnels;
        } else {
            try {
                _cachedMaxTunnels = Integer.parseInt(p);
            } catch (NumberFormatException nfe) {
                _cachedMaxTunnels = defaultMaxTunnels;
            }
        }
        return _cachedMaxTunnels;
    }

    /** Tuner override for the processing time, -1 if unset. */
    private static volatile int _tunedMaxProcessingTime = -1;

    /**
     * Set the max processing time (called by Tuner).
     * @param ms the max message processing time in milliseconds
     * @since 0.9.71+
     */
    public static void setMaxProcessingTime(int ms) { _tunedMaxProcessingTime = ms; }

    /**
     * The max processing time in effect, tuned value if set else config.
     * @param ctx the router context
     * @return the max message processing time in milliseconds
     * @since 0.9.71+
     */
    public static int getMaxProcessingTimeTuned(RouterContext ctx) {
        int tuned = _tunedMaxProcessingTime;
        if (tuned >= 0) return tuned;
        return ctx.getProperty(PROP_MAX_PROCESSINGTIME, DEFAULT_MAX_PROCESSINGTIME);
    }

    /** Tuner override for the reject exponent, -1 if unset. */
    private static volatile int _tunedThrottleRejectExponent = -1;

    /**
     * Set the throttle rejection-curve exponent (called by Tuner).
     * @param exp the rejection exponent in effect
     * @since 0.9.71+
     */
    public static void setThrottleRejectExponent(int exp) { _tunedThrottleRejectExponent = exp; }

    /**
     * The throttle rejection-curve exponent, tuned value if set else the
     * {@code router.throttleRejectExponent} config, clamped to the caller's
     * [3,20] validity window.
     * @param ctx the router context
     * @return the rejection exponent
     * @since 0.9.71+
     */
    public static int getThrottleRejectExponent(RouterContext ctx) {
        int tuned = _tunedThrottleRejectExponent;
        int exp = tuned >= 0 ? tuned : ctx.getProperty("router.throttleRejectExponent", 10);
        return Math.max(throttleRejectExponentMin(), Math.min(throttleRejectExponentMax(), exp));
    }

    /**
     * The lowest rejection exponent the throttle applies.
     *
     * <p>Public because the auto-tuner declares a range for this exponent and must
     * not propose a value the read path clamps away: the target lands back on the
     * current value and the param reads as one that will not take effect.
     *
     * @return the lower end of the validity window
     * @since 0.9.72+
     */
    public static int throttleRejectExponentMin() { return 3; }

    /**
     * The highest rejection exponent the throttle applies.
     *
     * @return the upper end of the validity window
     * @since 0.9.72+
     */
    public static int throttleRejectExponentMax() { return 20; }

    /**
     * Returns the cached processing time limit.
     *
     * @return the limit, in ms
     */
    private int getMaxProcessingTime() {
        int tuned = _tunedMaxProcessingTime;
        if (tuned >= 0) return tuned;
        String p = _context.getProperty(PROP_MAX_PROCESSINGTIME);
        if (p != null && p.equals(_cachedMaxProcessingTimeProp)) {
            return _cachedMaxProcessingTime;
        }
        _cachedMaxProcessingTimeProp = p;
        if (p == null) {
            _cachedMaxProcessingTime = DEFAULT_MAX_PROCESSINGTIME;
        } else {
            try {
                _cachedMaxProcessingTime = Integer.parseInt(p);
            } catch (NumberFormatException nfe) {
                _cachedMaxProcessingTime = DEFAULT_MAX_PROCESSINGTIME;
            }
        }
        return _cachedMaxProcessingTime;
    }

    /**
     * Returns the tunnel growth factor in effect.
     *
     * @return the growth factor
     */
    private double getTunnelGrowthFactor() {
        double tuned = _tunedGrowthFactor;
        if (tuned >= 0.0d) return tuned;
        String p = _context.getProperty(PROP_TUNNEL_GROWTH_FACTOR);
        if (p != null && p.equals(_cachedGrowthFactorProp)) {
            return _cachedGrowthFactor;
        }
        _cachedGrowthFactorProp = p;
        if (p == null) {
            _cachedGrowthFactor = DEFAULT_TUNNEL_GROWTH_FACTOR;
            return _cachedGrowthFactor;
        }
        try {
            _cachedGrowthFactor = Double.parseDouble(p);
        } catch (NumberFormatException nfe) {
            _cachedGrowthFactor = DEFAULT_TUNNEL_GROWTH_FACTOR;
        }
        return _cachedGrowthFactor;
    }

    /**
     * Returns the test time growth factor in effect.
     *
     * @return the growth factor
     */
    private double getTunnelTestTimeGrowthFactor() {
        String p = _context.getProperty(PROP_TUNNEL_TEST_TIME_GROWTH_FACTOR);
        if (p != null && p.equals(_cachedTestTimeGrowthFactorProp)) {
            return _cachedTestTimeGrowthFactor;
        }
        _cachedTestTimeGrowthFactorProp = p;
        if (p == null) {
            _cachedTestTimeGrowthFactor = DEFAULT_TUNNEL_TEST_TIME_GROWTH_FACTOR;
            return _cachedTestTimeGrowthFactor;
        }
        try {
            _cachedTestTimeGrowthFactor = Double.parseDouble(p);
        } catch (NumberFormatException nfe) {
            _cachedTestTimeGrowthFactor = DEFAULT_TUNNEL_TEST_TIME_GROWTH_FACTOR;
        }
        return _cachedTestTimeGrowthFactor;
    }

    /**
     * The average message processing delay.
     *
     * @return the message delay
     */
    public long getMessageDelay() {
        RateStat rs = _context.statManager().getRate("transport.sendProcessingTime");
        if (rs == null) {return 0;}
        Rate delayRate = rs.getRate(RateConstants.ONE_MINUTE);
        return (long)delayRate.getAverageValue();
    }

    /**
     * The current tunnel lag.
     *
     * @return the tunnel lag
     */
    public long getTunnelLag() {
        Rate lagRate = _context.statManager().getRate("tunnel.testSuccessTime").getRate(RateConstants.ONE_HOUR);
        return (long)lagRate.getAverageValue();
    }

    /**
     * The current tunnel status message.
     *
     * @return the tunnel status
     */
    public String getTunnelStatus() {return _tunnelStatus;}

    /**
     * The current tunnel status, translated if available.
     *
     * @return the localized tunnel status
     * @since 0.9.45
     */
    public String getLocalizedTunnelStatus() {
        return Translate.getString(_tunnelStatus, _context, CommSystemFacade.ROUTER_BUNDLE_NAME);
    }

    /** Rebuilds the tunnel status text for the reject log. */
    private void setTunnelStatus() {
        setTunnelStatus("[starting]" + _x("Starting up") + "&hellip;");
    }

    /**
     * Whether the router is shutting down.
     *
     * @param context the router context
     * @return whether shutting down
     */
    public static boolean isShuttingDown(RouterContext context) {
        int code = context.router().scheduledGracefulExitCode();
        return Router.EXIT_GRACEFUL == code || Router.EXIT_HARD == code;
    }

    /**
     * Throttle hard while the router attempts to shut down.
     *
     * @since 0.8.12
     */
    public void setShutdownStatus() {
        if (isShuttingDown(_context)) {
            setTunnelStatus("[shutdown]" + _x("Declining requests") + ": " + _x("Shutting down") + "&hellip;");
        } else {
            setTunnelStatus("[shutdown]" + _x("Declining requests") + ": " + _x("Restarting") + "&hellip;");
        }
    }

    /**
     * Restore normal throttling after a shutdown attempt.
     *
     * @since 0.8.12
     */
    public void cancelShutdownStatus() {
        // try hard to guess the state, before we actually get a request
        int maxTunnels = getMaxTunnels();
        RouterInfo ri = _context.router().getRouterInfo();
        if (maxTunnels > 0 && !_context.router().isHidden() && ri != null && !ri.getBandwidthTier().equals("K")) {
            setTunnelStatus("[accepting]" + _x("Accepting tunnel requests"));
        } else {setTunnelStatus("[rejecting/disabled]" + _x("Declining Tunnel Requests"));}
    }

    /**
     * The tunnel acceptance status message.
     *
     * @param msg the status message
     */
    public void setTunnelStatus(String msg) {_tunnelStatus = msg;}

    /**
     * Mark a string for extraction by xgettext and translation.
     * Use this only in static initializers.
     * It does not translate!
     *
     * @param s the literal string to mark for extraction
     * @return s unchanged, so xgettext still sees the literal in the source
     */
    private static final String _x(String s) {return s;}

}
