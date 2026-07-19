package net.i2p.router;

import net.i2p.data.Hash;
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
 *
 */
public class RouterThrottleImpl implements RouterThrottle {
    protected final RouterContext _context;
    private final Log _log;
    private volatile String _tunnelStatus;
    private final long _rejectStartupTime;

    /** Arbitrary hard limit - if it's taking this long to get to a job, we're congested. */
    private static final long JOB_LAG_LIMIT_NETWORK = 3*1000L;
    private static final long JOB_LAG_LIMIT_NETDB = 3*1000L;

    public static final String PROP_MAX_TUNNELS = "router.maxParticipatingTunnels";
    public static volatile int _defaultMaxTunnels = SystemVersion.isSlow() ? 3*1000 :
                                                  SystemVersion.getMaxMemory() < 512*1024*1024L ? 5*1000 :
                                                  SystemVersion.getCores() >= 8 ? 12*1000 : 8*1000;
    /** @since 0.9.70+ */
    public static int getDefaultMaxTunnels() { return _defaultMaxTunnels; }
    /** @since 0.9.70+ */
    public static void setDefaultMaxTunnels(int val) { _defaultMaxTunnels = Math.max(500, Math.min(20000, val)); }
    private static final String PROP_MAX_PROCESSINGTIME = "router.defaultProcessingTimeThrottle";
    private static final long DEFAULT_REJECT_STARTUP_TIME = 3*60*1000L;
    private static final long MIN_REJECT_STARTUP_TIME = 90*1000L;
    private static final String PROP_REJECT_STARTUP_TIME = "router.rejectStartupTime";

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

    /* TO BE FIXED - SEE COMMENTS BELOW */
    private static final int DEFAULT_MAX_PROCESSINGTIME = SystemVersion.isSlow() ? 3000 : 2000;

    /** tunnel acceptance */
    public static final int TUNNEL_ACCEPT = 0;

    /** = TrivialPreprocessor.PREPROCESSED_SIZE */
    private static final int PREPROCESSED_SIZE = 1024;

    private static final long[] RATES = { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR };

    public RouterThrottleImpl(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(RouterThrottleImpl.class);
        setTunnelStatus();
        _rejectStartupTime = Math.max(MIN_REJECT_STARTUP_TIME, _context.getProperty(PROP_REJECT_STARTUP_TIME, DEFAULT_REJECT_STARTUP_TIME));
        _context.simpleTimer2().addEvent(new ResetStatus(), 5*1000L + _rejectStartupTime);

    }

    /**
     *  Reset status from starting up to not-starting up,
     *  in case we don't get a tunnel request soon after the 20 minutes is up.
     *
     *  @since 0.8.12
     */
    private class ResetStatus extends SimpleTimer2.TimedEvent {
        public void timeReached() {
            if (_tunnelStatus.contains(_x("Starting up"))) {cancelShutdownStatus();}
        }
    }

    /**
     * Should we accept any more data from the network for any sort of message,
     * taking into account our current load, or should we simply slow down?
     *
     * FIXME only called by SSU Receiver, not NTCP!
     * FIXME should put warning on the console
     * FIXME or should we do this at all? We have Codel queues all over now...
     */
    public boolean acceptNetworkMessage() {
        long lag = _context.jobQueue().getMaxLag();
        if ((lag > JOB_LAG_LIMIT_NETWORK) && (_context.router().getUptime() > 60*1000L)) {
            if (_log.shouldWarn()) {_log.warn("Throttling Network Reader -> Job lag is " + lag + "ms");}
            return false;
        } else {return true;}
    }

    /** @deprecated unused, function moved to netdb */
    @Deprecated
    public boolean acceptNetDbLookupRequest(Hash key) {
        long lag = _context.jobQueue().getMaxLag();
        if (lag > JOB_LAG_LIMIT_NETDB) {
            if (_log.shouldDebug()) {_log.debug("Refusing NetDb Lookup request -> Job lag is " + lag + "ms");}
            return false;
        } else {return true;}
    }

    /**
     *  If we should send a reject, return a nonzero reject code.
     *  Anything that causes us to drop a request instead of rejecting it
     *  must go in BuildHandler.handleInboundRequest(), not here.
     *
     *  @return 0 for accept or nonzero reject code
     */
    public int acceptTunnelRequest() {
        if (_context.router().gracefulShutdownInProgress()) {
            if (_log.shouldWarn()) {_log.warn("Refusing all Tunnel Requests -> Graceful shutdown in progress...");}
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
        int maxTunnels = _context.getProperty(PROP_MAX_TUNNELS, _defaultMaxTunnels);
        RateStat rs = _context.statManager().getRate("transport.sendProcessingTime");
        Rate r = null;
        if (rs != null) {r = rs.getRate(RateConstants.ONE_MINUTE);}

            //Reject tunnels if the time to process messages and send them is too large. Too much time implies congestion.
        if (r != null) {
            r.computeAverages(ra,false);

            int maxProcessingTime = _context.getProperty(PROP_MAX_PROCESSINGTIME, DEFAULT_MAX_PROCESSINGTIME);

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
        } else if (numTunnels > minToThrottle && _defaultMaxTunnels >= maxTunnels) {
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
                            _log.info("Probabalistically accepting Tunnel Request (p=" + probAccept
                                      + " v=" + v + " avg=" + avg + " current=" + numTunnels + ")");
                        }
                    } else {
                        if (_log.shouldWarn()) {
                            _log.warn("Probabalistically refusing Tunnel Request (avg=" + avg
                                      + " current=" + numTunnels + ")");
                        }
                        // hard to do {0} from here
                        //setTunnelStatus("Rejecting " + (100 - (int) probAccept*100) + "% of tunnels: High number of requests");
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
                    _log.info("Probabalistically accepting Tunnel Request (p=" + probAccept
                              + " v=" + v + " test time avg 1m=" + avg1m + " 1h=" + avg1h + ")");
                }
            }
        }

        // ok, we're not hosed, but can we handle the bandwidth requirements of another tunnel?
        rs = _context.statManager().getRate("tunnel.participatingMessageCountAvgPerTunnel");
        r = null;
        double messagesPerTunnel = 0;
        if (rs != null) {
            r = rs.getRate(RateConstants.ONE_MINUTE);
            if (r != null) {messagesPerTunnel = r.computeAverages(ra, true).getAverage();}
        }
        if (messagesPerTunnel < DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE) {
            messagesPerTunnel = DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE;
        }

        double bytesAllocated = messagesPerTunnel * numTunnels * PREPROCESSED_SIZE;

        if (!allowTunnel(bytesAllocated, numTunnels)) {
            //setTunnelStatus("[rejecting/max]" + _x("Declining tunnel requests" + ":<br>" + _x("Bandwidth Limit")));
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }
        return TUNNEL_ACCEPT;
    }

    /**
     * This is the estimated number of 1 KB tunnel messages that we'll see in
     * 10 minute lifetime of an exploratory tunnel. We use it as a baseline
     * minimum for estimating tunnel bandwidth, if accepted.
     *
     * 600 KB in 10 minutes equals ~4 KBps - increased from 200 for better
     * bandwidth allocation per transit tunnel.
     *
     * @since public since 0.9.66, was package private
     */
    public static final int DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE = 600; // ~4KBps

    /**
     * Calculate a floor for per-tunnel bandwidth allocation based on
     * participating tunnel count and total outbound bandwidth share.
     * This ensures a minimum allocation even when many tunnels are hosted.
     *
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

    /** also limited to 90% - see below */
    private static final int MIN_AVAILABLE_BPS = 4*1024; // always leave at least 4KBps free when allowing
    private static final String LIMIT_STR = _x("Declining requests" + ": " + "Bandwidth limit");

    /**
     * With bytesAllocated already accounted for across the numTunnels existing
     * tunnels we have agreed to, can we handle another tunnel with our existing
     * bandwidth?
     *
     */
    private boolean allowTunnel(double bytesAllocated, int numTunnels) {
        int maxKBpsIn = _context.bandwidthLimiter().getInboundKBytesPerSecond();
        int maxKBpsOut = _context.bandwidthLimiter().getOutboundKBytesPerSecond();
        int maxKBps = Math.min(maxKBpsIn, maxKBpsOut);
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

        // Now see if 1m rates are too high
        int used1mIn = _context.router().get1mRateIn();
        int used1mOut = _context.router().get1mRate(true);
        long overage = Math.max(used1mIn - (maxKBpsIn*1024L), used1mOut - (maxKBpsOut*1024L));
        if ((overage > 0) && ((overage/(maxKBps*1024f)) > _context.random().nextFloat())) {
            _log.warn("Rejecting participating Tunnel Request \n* 1 minute rate (" + overage + " over) indicates overload.");
            setTunnelStatus("[rejecting/overload]" + LIMIT_STR);
            return false;
        }

        double probReject;
        boolean reject;
        if (availBps <= 0) {
            probReject = 1;
            reject = true;
            _log.warn("Rejecting participating tunnel -> Insufficient bandwidth available" +
                      "\n* Available: " + availBps + "; Maximum allocated: " + maxKBps + "; In use: " + used +
                      "\n* Active tunnels: " + numTunnels + "; Estimated bandwidth required = " + bytesAllocated);
        } else {
            // limit at 90% - 4KBps (see above)
            float maxBps = (maxKBps * 1024f * 0.9f) - MIN_AVAILABLE_BPS;
            float pctFull = (maxBps - availBps) / (maxBps);
            if (pctFull < 0.90f) {
                probReject = 0; // probReject < ~5%
                reject = false;
                if (_log.shouldDebug()) {
                    _log.debug("Accept avail / maxK / used " + availBps + " / " + maxKBps + " / "
                               + used + "\n* pReject = 0 numTunnels = " + numTunnels
                               + " est = " + bytesAllocated);
                }
            } else {
                // Configurable steepness of rejection curve (default: 10)
                // Lower value = smoother transition to full capacity
                // Clamped [3,20] — below 3 the curve is nearly linear (too aggressive
                // at moderate load), above 20 it creates a cliff (near-zero rejection
                // until saturation, then instant 100%).
                int exponent = Math.max(3, Math.min(20, _context.getProperty("router.throttleRejectExponent", 10)));
                probReject = Math.pow(pctFull, exponent);
                double rand = _context.random().nextFloat();
                reject = rand <= probReject;
                if (reject) {
                    if (_log.shouldWarn()) {
                        _log.warn("Reject avail / maxK / used " + availBps + " / " + maxKBps + " / " +
                                  used + "\n* pReject = " + probReject + " pFull = " + pctFull + " numTunnels = " + numTunnels +
                                  " rand = " + rand + " est = " + bytesAllocated);
                    }
                } else if (_log.shouldDebug()) {
                    _log.debug("Accept avail / maxK / used " + availBps + " / " + maxKBps + " / "
                           + used + "\n* pReject = " + probReject + " pFull = " + pctFull + " numTunnels = " + numTunnels
                           + " rand = " + rand + " est = " + bytesAllocated);
                }
            }
        }

        if (probReject >= 0.9) {setTunnelStatus("[rejecting]" + LIMIT_STR);}
        else if (probReject >= 0.5) {setTunnelStatus("[rejecting/bandwidth]" + _x("Declining requests" + ": " + "Bandwidth limit"));}
        else if(probReject >= 0.1) {setTunnelStatus("[accepting]" + _x("Accepting most tunnel requests"));} // hard to do {0} from here
        else {setTunnelStatus("[accepting]" + _x("Accepting tunnel requests"));}
        return !reject;
    }

    /** Don't ever probabalistically throttle tunnels if we have less than this many */
    private int getMinThrottleTunnels() {
        int configured = _context.getProperty(PROP_MIN_THROTTLE_TUNNELS, -1);
        if (configured > 0) {
            return configured;
        }
        // Calculate default on first access
        int maxTunnels = _context.getProperty(PROP_MAX_TUNNELS, _defaultMaxTunnels);
        return (maxTunnels / 3) * 2;
    }

    private double getTunnelGrowthFactor() {
        try {
            String p = _context.getProperty("router.tunnelGrowthFactor");
            if (p == null) {return 2.0d;}
            return Double.parseDouble(p);
        } catch (NumberFormatException nfe) {return 2.0d;}
    }

    private double getTunnelTestTimeGrowthFactor() {
        try {
            String p = _context.getProperty("router.tunnelTestTimeGrowthFactor");
            if (p == null) {return 1.5d;}
            return Double.parseDouble(p);
        } catch (NumberFormatException nfe) {return 1.5d;}
    }

    public long getMessageDelay() {
        RateStat rs = _context.statManager().getRate("transport.sendProcessingTime");
        if (rs == null) {return 0;}
        Rate delayRate = rs.getRate(RateConstants.ONE_MINUTE);
        return (long)delayRate.getAverageValue();
    }

    public long getTunnelLag() {
        Rate lagRate = _context.statManager().getRate("tunnel.testSuccessTime").getRate(RateConstants.ONE_HOUR);
        return (long)lagRate.getAverageValue();
    }

    public String getTunnelStatus() {return _tunnelStatus;}

    /**
     * getTunnelStatus(), translated if available.
     *
     * @since 0.9.45
     */
    public String getLocalizedTunnelStatus() {
        return Translate.getString(_tunnelStatus, _context, CommSystemFacade.ROUTER_BUNDLE_NAME);
    }

    private void setTunnelStatus() {
        setTunnelStatus("[starting]" + _x("Starting up") + "&hellip;");
    }

    public static boolean isShuttingDown(RouterContext _context) {
        int code = _context.router().scheduledGracefulExitCode();
        return Router.EXIT_GRACEFUL == code || Router.EXIT_HARD == code;
    }

    /** @since 0.8.12 */
    public void setShutdownStatus() {
        if (isShuttingDown(_context)) {
            setTunnelStatus("[shutdown]" + _x("Declining requests") + ": " + _x("Shutting down") + "&hellip;");
        } else {
            setTunnelStatus("[shutdown]" + _x("Declining requests") + ": " + _x("Restarting") + "&hellip;");
        }
    }

    /** @since 0.8.12 */
    public void cancelShutdownStatus() {
        // try hard to guess the state, before we actually get a request
        int maxTunnels = _context.getProperty(PROP_MAX_TUNNELS, _defaultMaxTunnels);
        RouterInfo ri = _context.router().getRouterInfo();
        if (maxTunnels > 0 && !_context.router().isHidden() && ri != null && !ri.getBandwidthTier().equals("K")) {
            setTunnelStatus("[accepting]" + _x("Accepting tunnel requests"));
        } else {setTunnelStatus("[rejecting/disabled]" + _x("Declining Tunnel Requests"));}
    }

    public void setTunnelStatus(String msg) {_tunnelStatus = msg;}

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *
     *  @return s
     */
    private static final String _x(String s) {return s;}

}
