package net.i2p.router.tasks;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.stat.StatManager;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.Log;
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
public class CoalesceStatsEvent extends SimpleTimer2.TimedEvent {
    private final RouterContext _ctx;
    private final long _maxMemory;
    private static final long LOW_MEMORY_THRESHOLD = 5 * 1024 * 1024L;
    private final Log _log;

    // Cumulative GC pause time (ms) observed at the previous coalesce cycle,
    // used to derive per-minute GC latency. -1 = uninitialized.
    private long _lastGcPauseTime = -1;

    /**
     * Create a new stats coalescence event.
     * Initializes all required rate statistics for monitoring router performance.
     *
     * @param ctx the router context for accessing statistics manager
     */
    public CoalesceStatsEvent(RouterContext ctx) {
        super(ctx.simpleTimer2());
        _ctx = ctx;
        _log = I2PAppContext.getGlobalContext().logManager().getLog(CoalesceStatsEvent.class);
        StatManager sm = ctx.statManager();
        // NOTE TO TRANSLATORS - each of these phrases is a description for a statistic
        // to be displayed on /stats.jsp and in the graphs on /graphs.jsp.
        // Please keep relatively short so it will fit on the graphs.
        _maxMemory = Runtime.getRuntime().maxMemory();
        sm.createRateStat("clock.skew", _x("Clock step adjustment (ms)"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("router.tunnelBacklog", _x("Size of tunnel acceptor backlog"), "Tunnels", new long[] { RateConstants.ONE_MINUTE });
        sm.createRequiredRateStat("bw.receiveBps", _x("Message receive rate (B/s)"), "Router", RateConstants.SIDEBAR_RATES);
        sm.createRequiredRateStat("bw.recvRate", _x("Low-level receive rate (B/s)"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.FIVE_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("bw.sendBps", _x("Message send rate (B/s)"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.FIVE_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("bw.sendRate", _x("Low-level send rate (B/s)"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.FIVE_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("router.activePeers", _x("Peers active in the last minute"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("router.activeSendPeers", _x("Peers sent to in the last minute"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("router.bannedPeers", _x("Total peers in our banlist"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("router.cpuLoad", _x("CPU load average of the JVM"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        // router.fastPeers and peer.fastPeerCount (ProfileOrganizer) read the same _fastPeers set,
        // as do router.highCapacityPeers and peer.highCapPeerCount. Both stats are kept so
        // neither /configstats entry disappears; keep the pair out of any one combined graph.
        sm.createRequiredRateStat("router.fastPeers", _x("Known fast peers"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("router.highCapacityPeers", _x("Known high capacity peers"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("router.integratedPeers", _x("Known integrated (floodfill) peers"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("router.knownPeers", _x("Total peers in our NetDb"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("router.activeThreads", _x("Total number of threads in use"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("router.gcPauseTime", _x("Time spent paused in GC (ms)"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("router.unreachablePeers", _x("Peers without a published IP address"), "Router", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("tunnel.tunnelBuildSuccessAvg", _x("Average tunnel build success %"), "Tunnels", RateConstants.TUNNEL_RATES);
        // Counters recorded at the point the condition fires. Without a definition
        // they accumulate in memory but never reach /stats, so each one had to be
        // recovered by grepping the log to tell an inactive gate from a silent one.
        sm.createRequiredRateStat("tunnel.promotionHeldNoAddress", _x("Tier promotions held back for a peer with no usable address"), "Tunnels", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("tunnel.promotionRefreshIssued", _x("RouterInfo refreshes issued for peers held out of the tiers"), "Tunnels", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("peermanager.tiersUnderfilled", _x("Tier scans that left the fast or high-capacity tier short of target"), "Peers", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
        // RouterInfos requested for stored profiles whose peer the netdb could not resolve
        // at load. Non-zero only during the startup recovery that follows loading, and
        // sustained non-zero means peers are being profiled faster than their RouterInfos
        // can be re-fetched.
        sm.createRequiredRateStat("peermanager.routerInfoRequested", _x("RouterInfos requested for stored profiles that had none"), "Peers", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("tunnel.ghostMarksAdded", _x("Build timeouts recorded against a peer as a ghost mark"), "Tunnels", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("tunnel.ghostMarksCleared", _x("Ghost marks cleared by a successful build reply"), "Tunnels", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("tunnel.ghostPeers", _x("Peers currently excluded as ghosts"), "Tunnels", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("tunnel.ghostFastOrHighCapPeers", _x("Ghost peers in the fast or high-capacity tiers"), "Tunnels", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
        sm.createRequiredRateStat("streaming.duplicateCloseSent", _x("Duplicate CLOSE safety-net messages sent"), "Streaming", new long[] { RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR });
        // Describe the stat itself; the JVM ceiling is secondary detail. Using the ceiling
        // as the whole description left the stat unexplained on /configstats, and left it
        // blank entirely when the ceiling was unknown.
        String memDesc = _x("Memory used by the JVM");
        if (_maxMemory < Long.MAX_VALUE) {
            memDesc += " (" + _x("maximum") + ' ' + DataHelper.formatSize(_maxMemory) + "B)";
        }
        sm.createRequiredRateStat("router.memoryUsed", memDesc, "Router", new long[] { RateConstants.ONE_MINUTE });
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
    @Override
    public void timeReached() {
        // The reschedule MUST happen even if collection fails. This event is the
        // heartbeat that feeds every Rate to its RateSummaryListener, and thus to
        // the RRD4J databases behind the graphs; if an exception escapes before
        // schedule() runs, SimpleTimer2 never re-arms the event and statistics
        // stop being recorded for the remaining life of the router. A single
        // transient failure in any of the calls below used to be unrecoverable.
        try {
            collectStats();
        } catch (Throwable t) {
            // Never let a stats-collection failure kill the heartbeat. An Error
            // here (OOM, NoClassDefFoundError) is just as recoverable to the
            // timer as a RuntimeException, and swallowing it keeps the timer
            // alive; the failure is logged and the next cycle retries.
            if (_log.shouldWarn()) {
                _log.warn("Stats collection failed (" + t + "), continuing", t);
            }
        } finally {
            schedule(Router.COALESCE_TIME);
        }
    }

    /**
     *  Gather this cycle's statistics and coalesce them into the Rates.
     *
     *  Called every Router.COALESCE_TIME by timeReached(), which guarantees the
     *  reschedule and contains any exception this throws, so nothing here needs
     *  its own try/catch.
     *
     *  @since 0.9.71+
     */
    private void collectStats() {
        StatManager sm = _ctx.statManager();
        int known = _ctx.netDb().getKnownRouters() - 1;
        sm.addRateData("router.knownPeers", known, 60L*1000);

        int active = _ctx.commSystem().countActivePeers();
        sm.addRateData("router.activePeers", active, 60L*1000);

        int fast = _ctx.profileOrganizer().countFastPeers();
        sm.addRateData("router.fastPeers", fast, 60L*1000);

        int highCap = _ctx.profileOrganizer().countHighCapacityPeers();
        sm.addRateData("router.highCapacityPeers", highCap, 60L*1000);

        // The peer.* mirrors above are fed here, not from reorganize(). reorganize()
        // runs every 30-250s (REORGANIZE_TIME_LONG once uptime passes 2h), but
        // RATES starts at ONE_MINUTE, so an instant sample landed in only ~24% of
        // completed 1-minute windows and the other ~76% read zero events. A
        // consumer requiring a non-NaN value then saw no data at all and degraded
        // its whole subsystem. Feed them on this short cycle with the same 60s
        // event duration as router.fastPeers so every window is covered.
        ProfileOrganizer po = _ctx.profileOrganizer();
        sm.addRateData("peer.fastPeerCount", fast, 60L*1000);
        sm.addRateData("peer.highCapPeerCount", highCap, 60L*1000);
        sm.addRateData("peer.fastOrHighCapProfileCount", po.getFastOrHighCapCount(), 60L*1000);
        // The remaining peer.* gauges were recorded inside reorganize(), which
        // made them sparse for the same reason. reorganize() now only publishes
        // these values; reading them here costs a field load apiece.
        sm.addRateData("peer.profileCount", po.getProfileCount(), 60L*1000);
        // Recently-active profiles, not the total. This used to be fed getProfileCount() - the
        // same value as peer.profileCount directly above - so the Peers page's "Active" ring,
        // which divides activeProfileCount by profileCount, computed 100% unconditionally and
        // labelled it "activity in the last 24h" regardless. It now carries the same
        // short-term-activity meaning as the sidebar's Peers row (active in the last hour).
        // Read from the field the reorganize walk fills, not by rescanning here: that scan takes
        // the reorganize read lock, which tunnel peer selection contends on, and this runs
        // every 50s.
        sm.addRateData("peer.activeProfileCount", po.getActiveProfileCount(), 60L*1000);
        sm.addRateData("peer.qualityPeerCount", po.getQualityCount(), 60L*1000);
        sm.addRateData("peer.storedProfileCount", po.getStoredProfileCount(), 60L*1000);
        // Excluded peers, as a gauge rather than an event: a mark is a state lasting
        // minutes, so a rate would report how often it was applied and not how many
        // peers are currently unable to be selected.
        net.i2p.router.tunnel.pool.GhostPeerManager gpm = _ctx.tunnelManager().getGhostPeerManager();
        if (gpm != null) {
            sm.addRateData("tunnel.ghostPeers", gpm.getGhostCount(), 60L*1000);
            sm.addRateData("tunnel.ghostFastOrHighCapPeers", gpm.getFastOrHighCapGhostCount(), 60L*1000);
        }

        int integrated = _ctx.peerManager().getPeersByCapability('f').size();
        sm.addRateData("router.integratedPeers", integrated, 60L*1000);

        int banned = _ctx.banlist().getRouterCount();
        sm.addRateData("router.bannedPeers", banned, 60L*1000);

        int unreachable = _ctx.peerManager().getPeersByCapability(FloodfillNetworkDatabaseFacade.CAPABILITY_UNREACHABLE).size();
        sm.addRateData("router.unreachablePeers", unreachable, 60L*1000);

        sm.addRateData("bw.sendRate", (long)_ctx.bandwidthLimiter().getSendBps());
        sm.addRateData("bw.recvRate", (long)_ctx.bandwidthLimiter().getReceiveBps());

        sm.addRateData("router.tunnelBacklog", _ctx.tunnelManager().getInboundBuildQueueSize(), 60L*1000);
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        sm.addRateData("router.memoryUsed", used);
        if (_maxMemory - used < LOW_MEMORY_THRESHOLD) {Router.clearCaches();}

        sm.addRateData("router.cpuLoad", SystemVersion.getCPULoad());
        sm.addRateData("tunnel.tunnelBuildSuccessAvg", SystemVersion.getTunnelBuildSuccess());
        sm.addRateData("router.activeThreads", SystemVersion.getActiveThreads());

        long gcPause = getGcPauseTime();
        if (gcPause >= 0) {
            sm.addRateData("router.gcPauseTime", gcPause, 60L*1000);
        }

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

        RateStat         sendRate = sm.getRate("transport.sendMessageSize");
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

    /**
     *  Cumulative GC pause time (ms) across all collectors since JVM start.
     *  Returns -1 if GC beans are unavailable.
     * @return the cumulative gc pause time
     */
    private static long getCumulativeGcPauseTime() {
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        if (beans == null || beans.isEmpty())
            return -1;
        long total = 0;
        boolean any = false;
        for (GarbageCollectorMXBean bean : beans) {
            long t = bean.getCollectionTime();
            if (t >= 0) {
                total += t;
                any = true;
            }
        }
        return any ? total : -1;
    }

    /**
     *  GC pause time (ms) elapsed since the previous coalesce cycle.
     *  Returns -1 on the first call (no baseline) or if GC data is unavailable.
     * @return the gc pause time
     */
    private long getGcPauseTime() {
        long cumulative = getCumulativeGcPauseTime();
        if (cumulative < 0)
            return -1;
        if (_lastGcPauseTime < 0) {
            _lastGcPauseTime = cumulative;
            return -1;
        }
        long delta = cumulative - _lastGcPauseTime;
        _lastGcPauseTime = cumulative;
        // Guard against clock anomalies / counter resets
        return delta >= 0 ? delta : 0;
    }
}
