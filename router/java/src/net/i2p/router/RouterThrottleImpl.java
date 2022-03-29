package net.i2p.router;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
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

    /**
     * arbitrary hard limit - if it's taking this long to get
     * to a job, we're congested.
     *
     */
    private static final long JOB_LAG_LIMIT_NETWORK = 2*1000;
    private static final long JOB_LAG_LIMIT_NETDB = 2*1000;
    // TODO reduce
//    private static final long JOB_LAG_LIMIT_TUNNEL = 500;
    private static final long JOB_LAG_LIMIT_TUNNEL = 300;
    public static final String PROP_MAX_TUNNELS = "router.maxParticipatingTunnels";
    public static final int DEFAULT_MAX_TUNNELS = (SystemVersion.isSlow() || SystemVersion.getMaxMemory() < 512*1024*1024) ? 2*1000 : 8*1000;
    private static final String PROP_MAX_PROCESSINGTIME = "router.defaultProcessingTimeThrottle";
    private static final long DEFAULT_REJECT_STARTUP_TIME = 10*60*1000;
    private static final long MIN_REJECT_STARTUP_TIME = 90*1000;
    private static final String PROP_REJECT_STARTUP_TIME = "router.rejectStartupTime";
    private static final int DEFAULT_MIN_THROTTLE_TUNNELS = SystemVersion.isAndroid() ? 100 : SystemVersion.isARM() ? 800 : 4000;
    private static final String PROP_MIN_THROTTLE_TUNNELS = "router.minThrottleTunnels";

    /**
     *  TO BE FIXED - SEE COMMENTS BELOW
     */
    private static final int DEFAULT_MAX_PROCESSINGTIME = 2250;

    /** tunnel acceptance */
    public static final int TUNNEL_ACCEPT = 0;

    /** = TrivialPreprocessor.PREPROCESSED_SIZE */
    private static final int PREPROCESSED_SIZE = 1024;

    private static final long[] RATES = { 60*1000, 10*60*1000l, 60*60*1000l };


    public RouterThrottleImpl(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(RouterThrottleImpl.class);
        setTunnelStatus();
        _rejectStartupTime = Math.max(MIN_REJECT_STARTUP_TIME, _context.getProperty(PROP_REJECT_STARTUP_TIME, DEFAULT_REJECT_STARTUP_TIME));
        _context.simpleTimer2().addEvent(new ResetStatus(), 5*1000 + _rejectStartupTime);
        _context.statManager().createRateStat("router.throttleNetworkCause", "JobQueue lag when an I2NP event was throttled", "Router [Throttle]", RATES);
        //_context.statManager().createRateStat("router.throttleNetDbCause", "How lagged the jobQueue was when a networkDb request was throttled", "Throttle", RATES);
        _context.statManager().createRateStat("router.throttleTunnelCause", "JobQueue lag when a tunnel request was throttled", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("tunnel.bytesAllocatedAtAccept", "Allocated bytes for participating tunnels when we accepted a request", "Tunnels [Participating]", RATES);
        _context.statManager().createRateStat("router.throttleTunnelProcessingTime1m", "Message processing time when we throttle a tunnel", "Router [Throttle]", RATES);
 //       _context.statManager().createRateStat("router.throttleTunnelProcessingTime10m", "Time to process a message when we throttle a tunnel (10 minute average)", "Router [Throttle]", new long[] { 10*60*1000, 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("router.throttleTunnelMaxExceeded", "Participating tunnels when max limit reached", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("router.throttleTunnelProbTooFast", "Participating tunnels beyond previous 1h average when we throttle", "Router [Throttle]", RATES);
        //_context.statManager().createRateStat("router.throttleTunnelProbTestSlow", "How slow are our tunnel tests when our average exceeds the old average and we throttle?", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("router.throttleTunnelBandwidthExceeded", "Bandwidth allocated when we refuse to build tunnel (bandwidth exceeded)", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("router.throttleTunnelBytesAllowed", "Bytes permitted to be sent when we get a tunnel request", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("router.throttleTunnelBytesUsed", "Used Bps at request (period = max KBps)", "Router [Throttle]", RATES);
        _context.statManager().createRateStat("router.throttleTunnelFailCount1m", "Average message send fails in last 2 minutes (failure spike throttle)", "Router [Throttle]", RATES);
        //_context.statManager().createRateStat("router.throttleTunnelQueueOverload", "How many pending tunnel request messages have we received when we reject them due to overload (period = time to process each)?", "Router [Throttle]", RATES);
    }

    /**
     *  Reset status from starting up to not-starting up,
     *  in case we don't get a tunnel request soon after the 20 minutes is up.
     *
     *  @since 0.8.12
     */
    private class ResetStatus implements SimpleTimer.TimedEvent {
        public void timeReached() {
            if (_tunnelStatus.contains(_x("Starting up")))
                cancelShutdownStatus();
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
        //if (true) return true;
        long lag = _context.jobQueue().getMaxLag();
//        if ( (lag > JOB_LAG_LIMIT_NETWORK) && (_context.router().getUptime() > 60*1000) ) {
        if ( (lag > JOB_LAG_LIMIT_NETWORK) && (_context.router().getUptime() > 3*60*1000) ) {
            if (_log.shouldWarn())
                _log.warn("Throttling Network Reader -> job lag is " + lag + "ms");
            _context.statManager().addRateData("router.throttleNetworkCause", lag);
            return false;
        } else {
            return true;
        }
    }

    /** @deprecated unused, function moved to netdb */
    @Deprecated
    public boolean acceptNetDbLookupRequest(Hash key) {
        long lag = _context.jobQueue().getMaxLag();
        if (lag > JOB_LAG_LIMIT_NETDB) {
            if (_log.shouldDebug())
                _log.debug("Refusing NetDb Lookup request -> job lag is " + lag + "ms");
            _context.statManager().addRateData("router.throttleNetDbCause", lag);
            return false;
        } else {
            return true;
        }
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
            if (_log.shouldWarn())
                _log.warn("Refusing tunnel requests -> Graceful shutdown in progress...");
            setShutdownStatus();
            // Don't use CRIT because this tells everybody we are shutting down
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }

        // Don't use CRIT because we don't want peers to think we're failing
        if (_context.router().getUptime() < _rejectStartupTime && !_context.router().isHidden()) {
//            setTunnelStatus(_x("No participating tunnels" + ":<br>" + _x("Starting up")));
            setTunnelStatus(_x("Starting up") + "&hellip;");
        } else if (_context.router().isHidden()) {
            setTunnelStatus(_x("Declining all tunnel requests" + ":<br>" + _x("Hidden Mode")));
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }

    /**** Moved to BuildHandler
        long lag = _context.jobQueue().getMaxLag();
        if (lag > JOB_LAG_LIMIT_TUNNEL) {
            if (_log.shouldWarn())
                _log.warn("Refusing tunnel request, as the job lag is " + lag);
            _context.statManager().addRateData("router.throttleTunnelCause", lag);
            setTunnelStatus(_x("Declining tunnel requests" + ":<br>" + _x(High job lag"));
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }
     ****/

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
        if (rs != null)
            r = rs.getRate(60*1000);

        //Reject tunnels if the time to process messages and send them is too large. Too much time implies congestion.
        if (r != null) {
            r.computeAverages(ra,false);

            int maxProcessingTime = _context.getProperty(PROP_MAX_PROCESSINGTIME, DEFAULT_MAX_PROCESSINGTIME);

            //Set throttling if necessary
            if ((ra.getAverage() > maxProcessingTime * 0.9
                    || ra.getCurrent() > maxProcessingTime
                    || ra.getLast() > maxProcessingTime) && maxTunnels > 0) {
                if (_log.shouldWarn()) {
                    _log.warn("Refusing participating tunnel request: message processing congestion" +
                              "\n* Current: " + ((int)ra.getCurrent()) + "ms" +
                              "\n* Last: " + ((int)ra.getLast()) + "ms" +
                              "\n* Average: " + ((int)ra.getAverage()) + "ms" +
                              "\n* Max time to process: " + maxProcessingTime + "ms");
                }
                setTunnelStatus(_x("Declining tunnel requests" + ":<br>" + _x("High message delay")));
                return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
            }
        }


        int numTunnels = _context.tunnelManager().getParticipatingCount();
        if (numTunnels >= maxTunnels) {
            if (maxTunnels > 0) {
                if (_log.shouldWarn())
                    _log.warn("Refusing participating tunnel request: already participating in "
                              + numTunnels + " (max: " + maxTunnels + ")");
                _context.statManager().addRateData("router.throttleTunnelMaxExceeded", numTunnels);
                setTunnelStatus(_x("Declining requests" + ": " + _x("Limit reached")));
            } else {
                if (_log.shouldWarn())
                    _log.warn("Refusing participating tunnel request: disabled by configuration");
                _context.statManager().addRateData("router.throttleTunnelMaxExceeded", numTunnels);
                setTunnelStatus(_x("Declining tunnel requests" + ":<br>" + _x("Participation disabled")));
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
        if ((numTunnels > getMinThrottleTunnels()) && (DEFAULT_MAX_TUNNELS >= maxTunnels)) {
            Rate avgTunnels = _context.statManager().getRate("tunnel.participatingTunnels").getRate(10*60*1000);
            if (avgTunnels != null) {
                double avg = avgTunnels.getAvgOrLifetimeAvg();
                double tunnelGrowthFactor = getTunnelGrowthFactor();
                int min = getMinThrottleTunnels();
                if (avg < min)
                    avg = min;
                // if the current tunnel count is higher than 1.3 * the average...
                if ( (avg > 0) && (avg*tunnelGrowthFactor < numTunnels) ) {
                    // we're accelerating, lets try not to take on too much too fast
                    double probAccept = (avg*tunnelGrowthFactor) / numTunnels;
                    probAccept *= probAccept; // square the decelerator for tunnel counts
                    int v = _context.random().nextInt(100);
                    if (v < probAccept*100) {
                        // ok
                        if (_log.shouldInfo())
                            _log.info("Probabalistically accept tunnel request (p=" + probAccept
                                      + " v=" + v + " avg=" + avg + " current=" + numTunnels + ")");
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("Probabalistically refusing tunnel request (avg=" + avg
                                      + " current=" + numTunnels + ")");
                        _context.statManager().addRateData("router.throttleTunnelProbTooFast", (long)(numTunnels-avg));
                        // hard to do {0} from here
                        //setTunnelStatus("Rejecting " + (100 - (int) probAccept*100) + "% of tunnels: High number of requests");
                        if (probAccept <= 0.5)
                            setTunnelStatus(_x("Rejecting most tunnel requests" + ":<br>" + _x("High number of requests")));
                        else if (probAccept <= 0.9)
                            setTunnelStatus(_x("Accepting most tunnel requests"));
                        else
                            setTunnelStatus(_x("Accepting tunnel requests"));
                        return TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT;
                    }
                } else {
                    if (_log.shouldInfo())
                        _log.info("Accepting participating tunnel request: tunnel count average is " + avg
                                      + " and we only have " + numTunnels + ")");
                }
            }
        }

        double tunnelTestTimeGrowthFactor = getTunnelTestTimeGrowthFactor();
        Rate tunnelTestTime1m = _context.statManager().getRate("tunnel.testSuccessTime").getRate(1*60*1000);
        Rate tunnelTestTime10m = _context.statManager().getRate("tunnel.testSuccessTime").getRate(10*60*1000);
        if ( (tunnelTestTime1m != null) && (tunnelTestTime10m != null) && (tunnelTestTime1m.getLastEventCount() > 0) ) {
            double avg1m = tunnelTestTime1m.getAverageValue();
            double avg10m = tunnelTestTime10m.getAvgOrLifetimeAvg();

            if (avg10m < 5000)
                avg10m = 5000; // minimum before complaining

            if ( (avg10m > 0) && (avg1m > avg10m * tunnelTestTimeGrowthFactor) ) {
                double probAccept = (avg10m*tunnelTestTimeGrowthFactor)/avg1m;
                probAccept = probAccept * probAccept; // square the decelerator for test times
                int v = _context.random().nextInt(100);
                if (v < probAccept*100) {
                    // ok
                    if (_log.shouldInfo())
                        _log.info("Probabalistically accept tunnel request (p=" + probAccept
                                  + " v=" + v + " test time avg 1m=" + avg1m + " 10m=" + avg10m + ")");
                //} else if (false) {
                //    if (_log.shouldWarn())
                //        _log.warn("Probabalistically refusing tunnel request (test time avg 1m=" + avg1m
                //                  + " 10m=" + avg10m + ")");
                //    _context.statManager().addRateData("router.throttleTunnelProbTestSlow", (long)(avg1m-avg10m), 0);
                //    setTunnelStatus("Rejecting " + ((int) probAccept*100) + "% of tunnels: High test time");
                //    return TunnelHistory.TUNNEL_REJECT_PROBABALISTIC_REJECT;
                }
            } else {
                // not yet...
                //if (_log.shouldInfo())
                //    _log.info("Accepting tunnel request, since 60m test time average is " + avg10m
                //              + " and past 1m only has " + avg1m + ")");
            }
        }

        // ok, we're not hosed, but can we handle the bandwidth requirements
        // of another tunnel?
        rs = _context.statManager().getRate("tunnel.participatingMessageCountAvgPerTunnel");
        r = null;
        double messagesPerTunnel = DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE;
        if (rs != null) {
            r = rs.getRate(60*1000);
            if (r != null)
                messagesPerTunnel = r.computeAverages(ra, true).getAverage();
        }
        if (messagesPerTunnel < DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE)
            messagesPerTunnel = DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE;

        double bytesAllocated = messagesPerTunnel * numTunnels * PREPROCESSED_SIZE;

        if (!allowTunnel(bytesAllocated, numTunnels)) {
            _context.statManager().addRateData("router.throttleTunnelBandwidthExceeded", (long)bytesAllocated);
            return TunnelHistory.TUNNEL_REJECT_BANDWIDTH;
        }

/***
        int queuedRequests = _context.tunnelManager().getInboundBuildQueueSize();
        int timePerRequest = 1000;
        rs = _context.statManager().getRate("tunnel.decryptRequestTime");
        if (rs != null) {
            r = rs.getRate(60*1000);
            if (r.getLastEventCount() > 0)
                timePerRequest = (int)r.getAverageValue();
            else
                timePerRequest = (int)rs.getLifetimeAverageValue();
        }
        float pctFull = (queuedRequests * timePerRequest) / (4*1000f);
        double pReject = Math.pow(pctFull, 16); //1 - ((1-pctFull) * (1-pctFull));
***/
        // let it in because we drop overload- rejecting may be overkill,
        // especially since we've done the cpu-heavy lifting to figure out
        // whats up
        /*
        if ( (pctFull >= 1) || (pReject >= _context.random().nextFloat()) ) {
            if (_log.shouldWarn())
                _log.warn("Rejecting a new tunnel request because we have too many pending requests (" + queuedRequests
                          + " at " + timePerRequest + "ms each, %full = " + pctFull);
            _context.statManager().addRateData("router.throttleTunnelQueueOverload", queuedRequests, timePerRequest);
            return TunnelHistory.TUNNEL_REJECT_TRANSIENT_OVERLOAD;
        }
        */

        // ok, all is well, let 'er in
        _context.statManager().addRateData("tunnel.bytesAllocatedAtAccept", (long)bytesAllocated, 60*10*1000);

        //if (_log.shouldDebug())
        //    _log.debug("Accepting a new tunnel request (now allocating " + bytesAllocated + " bytes across " + numTunnels
        //               + " tunnels with lag of " + lag + ")");
        return TUNNEL_ACCEPT;
    }

    private static final int DEFAULT_MESSAGES_PER_TUNNEL_ESTIMATE = 40; // .067KBps
    /** also limited to 90% - see below */
    private static final int MIN_AVAILABLE_BPS = 4*1024; // always leave at least 4KBps free when allowing
    //private static final String LIMIT_STR = _x("Declining tunnel requests" + ":<br>" + "Bandwidth limit");
    private static final String LIMIT_STR = _x("Declining requests" + ": " + "Bandwidth limit");

    /**
     * with bytesAllocated already accounted for across the numTunnels existing
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
            if (_log.shouldWarn()) _log.warn("Rejecting participating tunnel requests \n* Available bandwidth (" + availBps +
                                                     "Bps) is less than minimum required (" + MIN_AVAILABLE_BPS + "Bps)");
            setTunnelStatus(LIMIT_STR);
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
        if ( (overage > 0) &&
             ((overage/(maxKBps*1024f)) > _context.random().nextFloat()) ) {
            if (_log.shouldWarn()) _log.warn("Rejecting participating tunnel request \n* 1 minute rate (" + overage + " over) indicates overload.");
            setTunnelStatus(LIMIT_STR);
            return false;
        }

        double probReject;
        boolean reject;
        if (availBps <= 0) {
            probReject = 1;
            reject = true;
            if (_log.shouldWarn())
                _log.warn("Reject avail / maxK/ used " + availBps + " / " + maxKBps + " / "
                          + used + "\n* pReject = 1 numTunnels = " + numTunnels
                          + " est = " + bytesAllocated);
        } else {
            // limit at 90% - 4KBps (see above)
            float maxBps = (maxKBps * 1024f * 0.9f) - MIN_AVAILABLE_BPS;
            float pctFull = (maxBps - availBps) / (maxBps);
            if (pctFull < 0.83f) {
                // probReject < ~5%
                probReject = 0;
                reject = false;
                if (_log.shouldDebug())
                    _log.debug("Accept avail / maxK / used " + availBps + " / " + maxKBps + " / "
                               + used + "\n* pReject = 0 numTunnels = " + numTunnels
                               + " est = " + bytesAllocated);
            } else {
                probReject = Math.pow(pctFull, 16); // steep curve
            double rand = _context.random().nextFloat();
                reject = rand <= probReject;
                if (reject && _log.shouldWarn())
                    _log.warn("Reject avail / maxK / used " + availBps + " / " + maxKBps + " / "
                          + used + "\n* pReject = " + probReject + " pFull = " + pctFull + " numTunnels = " + numTunnels
                          + " rand = " + rand + " est = " + bytesAllocated);
                else if (_log.shouldDebug())
                    _log.debug("Accept avail / maxK/ used " + availBps + " / " + maxKBps + " / "
                           + used + "\n* pReject = " + probReject + " pFull = " + pctFull + " numTunnels = " + numTunnels
                           + " rand = " + rand + " est = " + bytesAllocated);
            }
        }

            if (probReject >= 0.9)
                setTunnelStatus(LIMIT_STR);
            else if (probReject >= 0.5)
                // hard to do {0} from here
                //setTunnelStatus("Rejecting " + ((int)(100.0*probReject)) + "% of tunnels: Bandwidth limit");
                //setTunnelStatus(_x("Declining most tunnel requests" + ":<br>" + "Bandwidth limit"));
                setTunnelStatus(_x("Declining requests" + ": " + "Bandwidth limit"));
            else if(probReject >= 0.1)
                // hard to do {0} from here
                //setTunnelStatus("Accepting " + (100-(int)(100.0*probReject)) + "% of tunnels");
                setTunnelStatus(_x("Accepting most tunnel requests"));
            else
                setTunnelStatus(_x("Accepting tunnel requests"));
            return !reject;


        /*
        if (availBps <= 8*1024) {
            // lets be more conservative for people near their limit and assume 1KBps per tunnel
            boolean rv = ( (numTunnels + 1)*1024 < availBps);
            if (_log.shouldDebug())
                _log.debug("Nearly full router (" + availBps + ") with " + numTunnels + " tunnels, allow a new request? " + rv);
            return rv;
        }
        */

/***
        double growthFactor = ((double)(numTunnels+1))/(double)numTunnels;
        double toAllocate = (numTunnels > 0 ? bytesAllocated * growthFactor : 0);

        double allocatedBps = toAllocate / (10 * 60);
        double pctFull = allocatedBps / availBps;

        if ( (pctFull < 1.0) && (pctFull >= 0.0) ) { // (_context.random().nextInt(100) > 100 * pctFull) {
            if (_log.shouldDebug())
                _log.debug("Allowing the tunnel w/ " + pctFull + " of our " + availBps
                           + "Bps/" + allocatedBps + "KBps allocated through " + numTunnels + " tunnels");
            return true;
        } else {
            double probAllow = availBps / (allocatedBps + availBps);
            boolean allow = (availBps > MIN_AVAILABLE_BPS) && (_context.random().nextFloat() <= probAllow);
            if (allow) {
                if (_log.shouldInfo())
                    _log.info("Probabalistically allowing the tunnel w/ " + (pctFull*100d) + "% of our " + availBps
                               + "Bps allowed (" + toAllocate + "bytes / " + allocatedBps
                               + "Bps) through " + numTunnels + " tunnels");
                return true;
            } else {
                if (_log.shouldWarn())
                    _log.warn("Rejecting the tunnel w/ " + (pctFull*100d) + "% of our " + availBps
                               + "Bps allowed (" + toAllocate + "bytes / " + allocatedBps
                               + "Bps) through " + numTunnels + " tunnels");
                return false;
            }
        }
***/
    }

    /** don't ever probabalistically throttle tunnels if we have less than this many */
    private int getMinThrottleTunnels() {
        return _context.getProperty(PROP_MIN_THROTTLE_TUNNELS, DEFAULT_MIN_THROTTLE_TUNNELS);
    }

    private double getTunnelGrowthFactor() {
        try {
//            return Double.parseDouble(_context.getProperty("router.tunnelGrowthFactor", "1.3"));
            return Double.parseDouble(_context.getProperty("router.tunnelGrowthFactor", "2.5"));
        } catch (NumberFormatException nfe) {
//            return 1.3;
            return 2.5;
        }
    }

    private double getTunnelTestTimeGrowthFactor() {
        try {
//            return Double.parseDouble(_context.getProperty("router.tunnelTestTimeGrowthFactor", "1.3"));
            return Double.parseDouble(_context.getProperty("router.tunnelTestTimeGrowthFactor", "2.5"));
        } catch (NumberFormatException nfe) {
//            return 1.3;
            return 2.5;
        }
    }

    public long getMessageDelay() {
        RateStat rs = _context.statManager().getRate("transport.sendProcessingTime");
        if (rs == null)
            return 0;
        Rate delayRate = rs.getRate(60*1000);
        return (long)delayRate.getAverageValue();
    }

    public long getTunnelLag() {
        Rate lagRate = _context.statManager().getRate("tunnel.testSuccessTime").getRate(10*60*1000);
        return (long)lagRate.getAverageValue();
    }

    public double getInboundRateDelta() {
        RateStat receiveRate = _context.statManager().getRate("transport.sendMessageSize");
        if (receiveRate == null)
            return 0;
        double nowBps = getBps(receiveRate.getRate(60*1000));
        double fiveMinBps = getBps(receiveRate.getRate(5*60*1000));
        double hourBps = getBps(receiveRate.getRate(60*60*1000));
        double dailyBps = getBps(receiveRate.getRate(24*60*60*1000));

        if (nowBps < 0) return 0;
        if (dailyBps > 0) return nowBps - dailyBps;
        if (hourBps > 0) return nowBps - hourBps;
        if (fiveMinBps > 0) return nowBps - fiveMinBps;
        return 0;
    }

    private static double getBps(Rate rate) {
        if (rate == null) return -1;
        double bytes = rate.getLastTotalValue();
        return (bytes*1000.0d)/rate.getPeriod();
    }

    public String getTunnelStatus() {
        return _tunnelStatus;
    }

    /**
     * getTunnelStatus(), translated if available.
     * @since 0.9.45
     */
    public String getLocalizedTunnelStatus() {
        return Translate.getString(_tunnelStatus, _context, CommSystemFacade.ROUTER_BUNDLE_NAME);
    }

    private void setTunnelStatus() {
// NPE, too early
//        if (_context.router().getRouterInfo().getBandwidthTier().equals("K"))
//            setTunnelStatus("Not expecting tunnel requests: Advertised bandwidth too low");
//        else
//            setTunnelStatus(_x("No participating tunnels" + ":<br>" + _x("Starting up")));
            setTunnelStatus(_x("Starting up") + "&hellip;");
    }

    /** @since 0.8.12 */
    public void setShutdownStatus() {
        setTunnelStatus(_x("Declining requests") + ": " + _x("Shutting down") + "&hellip;");
    }

    /** @since 0.8.12 */
    public void cancelShutdownStatus() {
        // try hard to guess the state, before we actually get a request
        int maxTunnels = _context.getProperty(PROP_MAX_TUNNELS, DEFAULT_MAX_TUNNELS);
        RouterInfo ri = _context.router().getRouterInfo();
        if (maxTunnels > 0 &&
            !_context.router().isHidden() &&
            ri != null && !ri.getBandwidthTier().equals("K")) {
            setTunnelStatus(_x("Accepting tunnel requests"));
        } else {
            setTunnelStatus(_x("Declining tunnel requests"));
        }
    }

    public void setTunnelStatus(String msg) {
        _tunnelStatus = msg;
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    private static final String _x(String s) {
        return s;
    }
}
