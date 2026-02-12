package net.i2p.router;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
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
    private static final long JOB_LAG_LIMIT_NETWORK = 3*1000;
    private static final long JOB_LAG_LIMIT_NETDB = 3*1000;
    private static final long JOB_LAG_LIMIT_TUNNEL = SystemVersion.isSlow() ? 3000 : 2000;
    public static final String PROP_MAX_TUNNELS = "router.maxParticipatingTunnels";
    public static final int DEFAULT_MAX_TUNNELS = SystemVersion.isSlow() || SystemVersion.getMaxMemory() < 512*1024*1024 ? 4000 :
                                                  SystemVersion.getCores() >= 8 ? 12000 : 8000;
    private static final String PROP_MAX_PROCESSINGTIME = "router.defaultProcessingTimeThrottle";
    private static final long DEFAULT_REJECT_STARTUP_TIME = 3*60*1000;
    private static final long MIN_REJECT_STARTUP_TIME = 90*1000;
    private static final String PROP_REJECT_STARTUP_TIME = "router.rejectStartupTime";
    private static final int DEFAULT_MIN_THROTTLE_TUNNELS = SystemVersion.isSlow() ? 2000 : 6000;
    private static final String PROP_MIN_THROTTLE_TUNNELS = "router.minThrottleTunnels";

    // Percentage-based min throttle tunnels (new in 0.9.68+)
    // Allows throttling threshold to scale with maxParticipatingTunnels
    private static final String PROP_MIN_THROTTLE_PERCENT = "router.minThrottleTunnelsPercent";
    private static final int DEFAULT_MIN_THROTTLE_PERCENT = 10; // Start throttling at 10% of max tunnels
    private static final int MIN_THROTTLE_PERCENT = 5;  // Minimum 5%
    private static final int MAX_THROTTLE_PERCENT = 90; // Maximum 90%

    /* TO BE FIXED - SEE COMMENTS BELOW */
    private static final int DEFAULT_MAX_PROCESSINGTIME = SystemVersion.isSlow() ? 3000 : 2000;

    /** tunnel acceptance */
    public static final int TUNNEL_ACCEPT = 0;

    /** = TrivialPreprocessor.PREPROCESSED_SIZE */
    private static final int PREPROCESSED_SIZE = 1024;

    private static final long[] RATES = { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR, RateConstants.ONE_DAY };

    public RouterThrottleImpl(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(RouterThrottleImpl.class);
        setTunnelStatus();
        _rejectStartupTime = Math.max(MIN_REJECT_STARTUP_TIME, _context.getProperty(PROP_REJECT_STARTUP_TIME, DEFAULT_REJECT_STARTUP_TIME));
        _context.simpleTimer2().addEvent(new ResetStatus(), 5*1000 + _rejectStartupTime);
        _context.statManager().createRateStat("router.throttleNetworkCause", "JobQueue lag when an I2NP event was throttled", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("router.throttleTunnelBandwidthExceeded", "Bandwidth allocated when we refuse to build tunnel (bandwidth exceeded)", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("router.throttleTunnelBytesAllowed", "Bytes permitted to be sent when we get a tunnel request", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("router.throttleTunnelBytesUsed", "Used B/s at request (period = max KB/s)", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("router.throttleTunnelCause", "JobQueue lag when a tunnel request was throttled", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("router.throttleTunnelMaxExceeded", "Transit tunnels when max limit reached", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("router.throttleTunnelProbTooFast", "Transit tunnels beyond previous 1h average when we throttle", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("tunnel.bytesAllocatedAtAccept", "Allocated bytes for transit tunnels when we accepted a request", "Tunnels [Participating]", RATES);
    }

    /**
     *  Reset status from starting up to not-starting up,
     *  in case we don't get a tunnel request soon after the 20 minutes is up.
     *
     *  @since 0.8.12
     */
    private class ResetStatus implements SimpleTimer.TimedEvent {
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
        if ((lag > JOB_LAG_LIMIT_NETWORK) && (_context.router().getUptime() > 60*1000)) {
            if (_log.shouldWarn()) {_log.warn("Throttling Network Reader -> Job lag is " + lag + "ms");}
            _context.statManager().addRateData("router.throttleNetworkCause", lag);
            return false;
        } else {return true;}
    }

    /** @deprecated unused, function moved to netdb */
    @Deprecated
    public boolean acceptNetDbLookupRequest(Hash key) {
        long lag = _context.jobQueue().getMaxLag();
        if (lag > JOB_LAG_LIMIT_NETDB) {
            if (_log.shouldDebug()) {_log.debug("Refusing NetDb Lookup request -> Job lag is " + lag + "ms");}
            _context.statManager().addRateData("router.throttleNetDbCause", lag);
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
        int maxTunnels = _context.getProperty(PROP_MAX_TUNNELS, DEFAULT_MAX_TUNNELS);
        RateStat rs = _context.statManager().getRate("transport.sendProcessingTime");
        Rate r = null;
        if (rs != null) {r = rs.getRate(RateConstants.ONE_MINUTE);}

        //Reject tunnels if the time to process messages and send them is too large. Too much time implies congestion.
        if (r != null) {
            r.computeAverages(ra,false);

            int maxProcessingTime = _context.getProperty(PROP_MAX_PROCESSINGTIME, DEFAULT_MAX_PROCESSINGTIME);

            //Set throttling if necessary
            if ((ra.getAverage() > maxProcessingTime * 0.9 || ra.getCurrent() > maxProcessingTime ||
                 ra.getLast() > maxProcessingTime) && maxTunnels > 0) {
                if (_log.shouldInfo()) {
                    _log.warn("Refusing Tunnel Request -> Message processing congestion" +
                              "\n* Current: " + ((int)ra.getCurrent()) + "ms" +
                              "\n* Last: " + ((int)ra.getLast()) + "ms" +
                              "\n* Average: " + ((int)ra.getAverage()) + "ms" +
                              "\n* Max time to process: " + maxProcessingTime + "ms");
                } else if (_log.shouldWarn()) {
                    _log.warn("Refusing Tunnel Request -> Message processing congestion");
                }
                setTunnelStatus("[rejecting/overload]" + _x("Declining Tunnel Requests" + ":<br>" + _x("High message delay")));
                return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
            }
        }

        int numTunnels = _context.tunnelManager().getParticipatingCount();
        if (numTunnels >= maxTunnels) {
            if (maxTunnels > 0) {
                if (_log.shouldWarn()) {
                    _log.warn("Refusing Tunnel Request -> Already participating in "
                              + numTunnels + " (Max: " + maxTunnels + ")");
                }
                _context.statManager().addRateData("router.throttleTunnelMaxExceeded", numTunnels);
                setTunnelStatus("[rejecting/max]" + _x("Declining requests" + ": " + _x("Limit reached")));
            } else {
                if (_log.shouldWarn()) {
                    _log.warn("Refusing Tunnel Request -> Disabled by configuration");
                }
                _context.statManager().addRateData("router.throttleTunnelMaxExceeded", numTunnels);
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
        } else if (numTunnels > minToThrottle && DEFAULT_MAX_TUNNELS >= maxTunnels) {
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
                        _context.statManager().addRateData("router.throttleTunnelProbTooFast", (long)(numTunnels-avg));
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
                if (v < probAccept*100) { // ok
                    if (_log.shouldInfo()) {
                        _log.info("Probabalistically accepting Tunnel Request (p=" + probAccept
                                  + " v=" + v + " test time avg 1m=" + avg1m + " 1h=" + avg1h + ")");
                    }
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
            _context.statManager().addRateData("router.throttleTunnelBandwidthExceeded", (long)bytesAllocated);
            //setTunnelStatus("[rejecting/max]" + _x("Declining tunnel requests" + ":<br>" + _x("Bandwidth Limit")));
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }
        _context.statManager().addRateData("tunnel.bytesAllocatedAtAccept", (long)bytesAllocated, 60*10*1000);
        return TUNNEL_ACCEPT;
    }

    /**
     * This is the estimated number of 1 KB tunnel messages that we'll see in
     * 10 minute lifetime of an exploratory tunnel. We use it as a baseline
     * minimum for estimating tunnel bandwidth, if accepted.
     *
     * 200 KB in 10 minutes equals 340 Bps - optimized for high bandwidth contexts.
     *
     * @since public since 0.9.66, was package private
     */
    public static final int DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE = 200; // .340KBps
    /** also limited to 90% - see below */
    private static final int MIN_AVAILABLE_BPS = 4*1024; // always leave at least 4KBps free when allowing
    //private static final String LIMIT_STR = _x("Declining Tunnel Requests" + ":<br>" + "Bandwidth limit");
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
        int availBps = Math.min((maxKBpsIn*1024*9/10) - usedIn, (maxKBpsOut*1024*9/10) - usedOut);
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
        used = Math.min(used, (int) (bytesAllocated / (10*60)));
        availBps = Math.min(availBps, (int)(((maxKBps*1024)*share) - used));

        // Write stats before making decisions
        _context.statManager().addRateData("router.throttleTunnelBytesUsed", used, maxKBps);
        _context.statManager().addRateData("router.throttleTunnelBytesAllowed", availBps, (long)bytesAllocated);

        // Now see if 1m rates are too high
        int used1mIn = _context.router().get1mRateIn();
        int used1mOut = _context.router().get1mRate(true);
        long overage = Math.max(used1mIn - (maxKBpsIn*1024), used1mOut - (maxKBpsOut*1024));
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
                // Configurable steepness of rejection curve (default: 10, was: 16)
                // Lower value = smoother transition to full capacity
                // 10: 90% full -> 35% rejection (vs. 44% with 16)
                int exponent = _context.getProperty("router.throttleRejectExponent", 10);
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

    /**
     * Don't ever probabalistically throttle tunnels if we have less than this many.
     *
     * Priority order for determining the minimum throttle threshold:
     * 1. router.minThrottleTunnels - absolute value (for backwards compatibility)
     * 2. router.minThrottleTunnelsPercent - percentage of max tunnels (default: 10%)
     * 3. Fallback to hardcoded 66.7% of max tunnels
     *
     * @since 0.9.68+ supports percentage-based configuration
     */
    private int getMinThrottleTunnels() {
        // Check for absolute value configuration (backwards compatibility)
        int configuredAbsolute = _context.getProperty(PROP_MIN_THROTTLE_TUNNELS, -1);
        if (configuredAbsolute > 0) {
            return configuredAbsolute;
        }

        int maxTunnels = _context.getProperty(PROP_MAX_TUNNELS, DEFAULT_MAX_TUNNELS);

        // Check for percentage-based configuration (new in 0.9.68)
        int percent = _context.getProperty(PROP_MIN_THROTTLE_PERCENT, DEFAULT_MIN_THROTTLE_PERCENT);

        // Clamp percentage between 5% and 90% to prevent misconfiguration
        percent = Math.max(MIN_THROTTLE_PERCENT, Math.min(MAX_THROTTLE_PERCENT, percent));

        // Calculate threshold as percentage of max tunnels
        int threshold = (maxTunnels * percent) / 100;

        // Ensure we don't return 0 or negative
        return Math.max(1, threshold);
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
        int maxTunnels = _context.getProperty(PROP_MAX_TUNNELS, DEFAULT_MAX_TUNNELS);
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
     *  @return s
     */
    private static final String _x(String s) {return s;}

}