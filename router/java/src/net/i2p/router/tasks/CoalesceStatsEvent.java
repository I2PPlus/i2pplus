package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;

/**
 * Periodic statistics collection and aggregation system.
 * 
 * This event runs every minute to collect, aggregate, and coalesce
 * router performance statistics. It gathers metrics from all major
 * router components and updates the statistics framework for monitoring
 * and analysis.
 * 
 * <strong>Statistics Collected:</strong>
 * <ul>
 *   <li>Network metrics - peer counts, bandwidth usage</li>
 *   <li>System resources - memory usage, CPU load, thread count</li>
 *   <li>Tunnel performance - build success rates, participation</li>
 *   <li>Router health - job queue lag, processing times</li>
 *   <li>Communication system - send/receive rates, error counts</li>
 * </ul>
 * 
 * <strong>Features:</strong>
 * <ul>
 *   <li>Automatic cache clearing when memory is low</li>
 *   <li>Rate calculation and rolling averages</li>
 *   <li>Performance trend monitoring</li>
 *   <li>Data available via /stats.jsp and /graphs.jsp</li>
 * </ul>
 * 
 * This data is essential for router performance monitoring,
 * troubleshooting, and capacity planning. The statistics are
 * used by the router console to display real-time performance
 * graphs and historical trends.
 *
 * @since 0.8.12 moved from Router.java
 */
public class CoalesceStatsEvent implements SimpleTimer.TimedEvent {
    private final RouterContext _ctx;
    private final long _maxMemory;
    private static final long LOW_MEMORY_THRESHOLD = 5 * 1024 * 1024;

    /**
     * Create a new stats coalescence event.
     * Initializes all required rate statistics for monitoring router performance.
     * 
     * @param ctx the router context for accessing statistics manager
     */
    public CoalesceStatsEvent(RouterContext ctx) {
        _ctx = ctx;
        StatManager sm = ctx.statManager();
        // NOTE TO TRANSLATORS - each of these phrases is a description for a statistic
        // to be displayed on /stats.jsp and in the graphs on /graphs.jsp.
        // Please keep relatively short so it will fit on the graphs.
        _maxMemory = Runtime.getRuntime().maxMemory();
        sm.createRateStat("clock.skew", _x("Clock step adjustment (ms)"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
        sm.createRateStat("router.tunnelBacklog", _x("Size of tunnel acceptor backlog"), "Tunnels", new long[] { RateConstants.ONE_MINUTE });
        sm.createRequiredRateStat("bw.receiveBps", _x("Message receive rate (B/s)"), "Router", RateConstants.SIDEBAR_RATES);
        sm.createRequiredRateStat("bw.recvRate", _x("Low-level receive rate (B/s)"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.FIVE_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("bw.sendBps", _x("Message send rate (B/s)"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.FIVE_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("bw.sendRate", _x("Low-level send rate (B/s)"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.FIVE_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("router.activePeers", _x("Peers active in the last minute"), "Router", new long[] { RateConstants.ONE_MINUTE });
        sm.createRequiredRateStat("router.activeSendPeers", _x("Peers sent to in the last minute"), "Router", new long[] { RateConstants.ONE_MINUTE });
        sm.createRequiredRateStat("router.bannedPeers", _x("Total peers in our banlist"), "Router", new long[] { RateConstants.ONE_MINUTE });
        sm.createRequiredRateStat("router.cpuLoad", _x("CPU load average of the JVM"), "Router", new long[] { RateConstants.ONE_MINUTE });
        sm.createRequiredRateStat("router.fastPeers", _x("Known fast peers"), "Router", new long[] { RateConstants.ONE_MINUTE });
        sm.createRequiredRateStat("router.highCapacityPeers", _x("Known high capacity peers"), "Router", new long[] { RateConstants.ONE_MINUTE });
        sm.createRequiredRateStat("router.integratedPeers", _x("Known integrated (floodfill) peers"), "Router", new long[] { RateConstants.ONE_MINUTE });
        sm.createRequiredRateStat("router.knownPeers", _x("Total peers in our NetDb"), "Router", new long[] { RateConstants.ONE_MINUTE });
        sm.createRequiredRateStat("router.activeThreads", _x("Total number of threads in use"), "Router", new long[] { RateConstants.ONE_MINUTE });
        sm.createRequiredRateStat("router.unreachablePeers", _x("Peers without a published IP address"), "Router", new long[] { RateConstants.ONE_MINUTE });
        sm.createRequiredRateStat("tunnel.tunnelBuildSuccessAvg", _x("Average tunnel build success %"), "Tunnels", RateConstants.TUNNEL_RATES);
        String legend = "";
        if (_maxMemory < Long.MAX_VALUE) {legend += "Maximum allocated to the JVM is " + DataHelper.formatSize(_maxMemory) + 'B';}
        sm.createRequiredRateStat("router.memoryUsed", legend, "Router", new long[] { RateConstants.ONE_MINUTE });
    }

    /**
     * Collect and coalesce router statistics.
     * 
     * This method is called periodically to gather various router metrics
     * including network statistics, peer counts, memory usage, and tunnel performance.
     * The collected data is used for monitoring and performance analysis.
     * 
     * Statistics collected include:
     * <ul>
     *   <li>Peer counts (known, active, fast, high capacity, etc.)</li>
     *   <li>Bandwidth usage (send/receive rates)</li>
     *   <li>Memory usage and CPU load</li>
     *   <li>Tunnel performance metrics</li>
     *   <li>Thread counts and system resources</li>
     * </ul>
     */
    public void timeReached() {
        StatManager sm = _ctx.statManager();
        int known = _ctx.netDb().getKnownRouters() - 1;
        sm.addRateData("router.knownPeers", known, 60*1000);

        int active = _ctx.commSystem().countActivePeers();
        sm.addRateData("router.activePeers", active, 60*1000);

        int activeSend = _ctx.commSystem().countActiveSendPeers();
        sm.addRateData("router.activeSendPeers", activeSend, 60*1000);

        int fast = _ctx.profileOrganizer().countFastPeers();
        sm.addRateData("router.fastPeers", fast, 60*1000);

        int highCap = _ctx.profileOrganizer().countHighCapacityPeers();
        sm.addRateData("router.highCapacityPeers", highCap, 60*1000);

        int integrated = _ctx.peerManager().getPeersByCapability('f').size();
        sm.addRateData("router.integratedPeers", integrated, 60*1000);

        int banned = _ctx.banlist().getRouterCount();
        sm.addRateData("router.bannedPeers", banned, 60*1000);

        int unreachable = _ctx.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_UNREACHABLE).size();
        sm.addRateData("router.unreachablePeers", unreachable, 60*1000);

        sm.addRateData("bw.sendRate", (long)_ctx.bandwidthLimiter().getSendBps());
        sm.addRateData("bw.recvRate", (long)_ctx.bandwidthLimiter().getReceiveBps());

        sm.addRateData("router.tunnelBacklog", _ctx.tunnelManager().getInboundBuildQueueSize(), 60*1000);
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        sm.addRateData("router.memoryUsed", used);
        if (_maxMemory - used < LOW_MEMORY_THRESHOLD) {Router.clearCaches();}

        sm.addRateData("router.cpuLoad", (long) SystemVersion.getCPULoad());
        sm.addRateData("tunnel.tunnelBuildSuccessAvg", (long) SystemVersion.getTunnelBuildSuccess());
        sm.addRateData("router.activeThreads", (int) SystemVersion.getActiveThreads());

        _ctx.tunnelDispatcher().updateParticipatingStats(Router.COALESCE_TIME);

        sm.coalesceStats();

        RateStat receiveRate = sm.getRate("transport.receiveMessageSize");
        if (receiveRate != null) {
            Rate rate = receiveRate.getRate(RateConstants.ONE_MINUTE);
            if (rate != null) {
                double bytes = rate.getLastTotalValue();
                double bps = (bytes*1000.0d)/rate.getPeriod();
                sm.addRateData("bw.receiveBps", (long)bps, RateConstants.ONE_MINUTE);
            }
        }

        RateStat sendRate = sm.getRate("transport.sendMessageSize");
        if (sendRate != null) {
            Rate rate = sendRate.getRate(RateConstants.ONE_MINUTE);
            if (rate != null) {
                double bytes = rate.getLastTotalValue();
                double bps = (bytes*1000.0d)/rate.getPeriod();
                sm.addRateData("bw.sendBps", (long)bps, RateConstants.ONE_MINUTE);
            }
        }
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     *  @since 0.8.7
     */
    private static final String _x(String s) {
        return s;
    }
}
