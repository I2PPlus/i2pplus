package net.i2p.router.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry of graph groupings: sets of stats that answer the same question and share a
 * unit, so they can be drawn as one multi-series plot instead of several small ones.
 *
 * <p>Every member of a group is either a count or a duration or a rate, and the members of
 * one group always agree on which. That constraint is the whole point. Combining stats
 * whose units differ produces a plot that looks meaningful and is not: a count against a
 * byte count, or a per-second rate against a total, flattens the smaller series into the
 * baseline and reads as "nothing happening". Members were therefore checked against their
 * declaring code rather than their names.
 *
 * <p>Grouping is opt-in via {@link #PROP_COMBINE} and only takes effect where at least two
 * members are already enabled, so enabling a single stat of a group is never silently
 * changed into a combined plot.
 *
 * @since 0.9.71+
 */
public final class GraphGroups {

    /** Opt-in property; when false every stat renders on its own as before. */
    public static final String PROP_COMBINE = "routerconsole.graphCombine";

    /**
     * Most series drawn on one plot. Past this the lines are too close to tell apart in a
     * small tile, and more than six distinct colours stop being reliably distinguishable.
     */
    public static final int MAX_SERIES = 6;

    /**
     * Display name per group. A combined plot shows several stats, so naming it after the
     * primary series alone ("Number of NTCP Pumper loops/s") is misleading; these say what
     * the plot as a whole covers.
     */
    private static final Map<String, String> TITLES;

    /** Group id to member stat names, in legend order. */
    private static final Map<String, List<String>> GROUPS;

    static {
        Map<String, List<String>> g = new LinkedHashMap<>();

        // Peer capability counts. All are gauges of a peer set sampled once a minute:
        // ProfileOrganizer's fast/high-capacity tiers plus the floodfill set from
        // peerManager. Deliberately excludes activePeers (a live connection count on a
        // different clock) and knownPeers (netdb size, several times larger).
        g.put("peerCaps", Arrays.asList(
                "router.fastPeers", "router.highCapacityPeers", "router.integratedPeers"));

        // Peer profile counts, all fed from ProfileOrganizer's in-memory profile store.
        // peer.storedProfileCount is the on-disk subset: a gauge of what survives a
        // restart, published by ProfilePersistenceHelper.
        g.put("peerProfiles", Arrays.asList(
                "peer.profileCount", "peer.storedProfileCount", "peer.activeProfileCount",
                "peer.qualityPeerCount", "peer.fastPeerCount", "peer.highCapPeerCount"));

        // NTCP pumper loop rates. Both are per second as of the idle-rate normalisation;
        // before that the idle series published a raw five-second count and could not share
        // an axis with the total.
        g.put("ntcpPumper", Arrays.asList(
                "ntcp.pumperLoopsPerSecond", "ntcp.pumperIdleLoops"));

        // Job queue timings, all milliseconds.
        g.put("jobTiming", Arrays.asList(
                "jobQueue.jobLag", "jobQueue.jobRun", "jobQueue.jobRunSlow",
                "jobQueue.jobWait", "jobQueue.loadRecoveryTime"));

        // Job queue depth, all counts of jobs waiting or running.
        g.put("jobDepth", Arrays.asList(
                "jobQueue.queuedJobs", "jobQueue.readyJobs",
                "jobQueue.runnerCount", "jobQueue.testJobCount"));

        // Why the queue throttled: event counters, each a running total. Kept apart from
        // jobDepth because those are levels and these are cumulative events, and reading a
        // level and an event count off one axis invites a comparison that does not hold.
        g.put("jobLoadEvents", Arrays.asList(
                "jobQueue.runnerScaleUp", "jobQueue.runnerScaleDown", "jobQueue.scaleRollback",
                "jobQueue.testJobHardLimit", "jobQueue.droppedJobs"));

        // Tunnel build rejections, all counts of builds refused for a given reason.
        g.put("buildReject", Arrays.asList(
                "tunnel.rejectHopThrottle", "tunnel.receiveRejectionProbabalistic",
                "tunnel.receiveRejectionTransient", "tunnel.buildBanHit",
                "tunnel.buildBanFiltered", "tunnel.buildDupId"));

        // NetDb lookup durations, both milliseconds.
        g.put("netDbLookupTime", Arrays.asList("netDb.successTime", "netDb.failedTime"));

        // UDP retransmission timeouts, all milliseconds.
        g.put("udpRto", Arrays.asList(
                "udp.avgRTO", "udp.avgEffectiveRTO", "udp.congestedRTO"));

        // Tuner CPU time per transport worker pool, every one reported as a percentage of
        // a single core. Ten members, so this is the group the series cap actually bites
        // on: the first six plot together and the remainder stay as their own graphs
        // rather than being dropped.
        g.put("tunerCpu", Arrays.asList(
                "tuner.stageCpu.NTCPPumper", "tuner.stageCpu.NTCPReader",
                "tuner.stageCpu.NTCPTXFinis", "tuner.stageCpu.NTCPWriter",
                "tuner.stageCpu.UDMMsgRX", "tuner.stageCpu.UDPEstab",
                "tuner.stageCpu.UDPPktHandler", "tuner.stageCpu.UDPPktPusher",
                "tuner.stageCpu.UDPReceiver", "tuner.stageCpu.UDPSender"));

        // Cache occupancy, all gauges of entries held. Read together they show which cache
        // is growing against its ceiling.
        g.put("tunnelCaches", Arrays.asList(
                "tunnel.cache.participants", "tunnel.cache.participatingConfig",
                "tunnel.cache.inboundGateways", "tunnel.cache.outboundGateways",
                "tunnel.cache.outboundEndpoints"));

        // CoDel drop delay per priority band: the same measure at six priorities, so one
        // plot shows which band is being dropped and when.
        g.put("codelDrop", Arrays.asList(
                "codel.OBGW.drop.0", "codel.OBGW.drop.100", "codel.OBGW.drop.200",
                "codel.OBGW.drop.300", "codel.OBGW.drop.400", "codel.OBGW.drop.500"));

        // Precalculated key pool consumption per algorithm, all event counters. Read apart
        // from the empty counts on purpose: these say the pool is being drained, those say
        // it ran dry, and a drained pool is healthy while a dry one is not.
        g.put("cryptoPoolUsed", Arrays.asList(
                "crypto.EDHUsed", "crypto.MLKEMUsed", "crypto.XDHUsed"));
        g.put("cryptoPoolEmpty", Arrays.asList(
                "crypto.EDHEmpty", "crypto.MLKEMEmpty", "crypto.XDHEmpty"));

        // Bandwidth limiter queueing delay, both directions, both milliseconds.
        g.put("bwLimiterDelay", Arrays.asList(
                "bwLimiter.inboundDelayedTime", "bwLimiter.outboundDelayedTime"));

        // Requests waiting for bandwidth, both directions.
        g.put("bwLimiterPending", Arrays.asList(
                "bwLimiter.pendingInboundRequests", "bwLimiter.pendingOutboundRequests"));

        // I2CP thread accounting, all thread counts. Mixes the configured maxima with the
        // live counts on purpose: the gap between the two is the growth headroom.
        g.put("i2ptunnelThreads", Arrays.asList(
                "i2ptunnel.clientRunner.activeThreads", "i2ptunnel.clientRunner.threads",
                "i2ptunnel.serverHandler.active", "i2ptunnel.serverHandler.threads"));

        // Remote LeaseSet lookup time, split by outcome.
        g.put("leaseSetLookupTime", Arrays.asList(
                "client.leaseSetFoundRemoteTime", "client.leaseSetFailedRemoteTime"));

        // Messages dropped for any reason, one series per cause. All event counters.
        g.put("i2cpDrops", Arrays.asList(
                "inNetPool.dropped", "inNetPool.duplicate",
                "inNetPool.droppedDbLookupResponseMessage", "client.dispatchNoACK",
                "client.requestLeaseSetDropped", "client.writerQueueFull"));

        // I2PTunnel server socket setup time, both milliseconds.
        g.put("i2ptunnelServerTime", Arrays.asList(
                "i2ptunnel.serverHandler.socketConnectTime",
                "i2ptunnel.serverHandler.blockingHandleTime"));

        GROUPS = Collections.unmodifiableMap(g);

        Map<String, String> t = new LinkedHashMap<>();
        t.put("peerCaps", "Peer capabilities");
        t.put("peerProfiles", "Peer profiles");
        t.put("ntcpPumper", "NTCP pumper loops");
        t.put("jobTiming", "Job queue timing");
        t.put("jobDepth", "Job queue depth");
        t.put("jobLoadEvents", "Job queue load events");
        t.put("buildReject", "Tunnel build rejections");
        t.put("netDbLookupTime", "NetDb lookup time");
        t.put("udpRto", "UDP retransmission timeouts");
        t.put("tunerCpu", "Transport CPU by stage");
        t.put("tunnelCaches", "Tunnel caches");
        t.put("codelDrop", "CoDel drop delay by priority");
        t.put("cryptoPoolUsed", "Precalculated keys used");
        t.put("cryptoPoolEmpty", "Key pool empty");
        t.put("bwLimiterDelay", "Bandwidth limiter delay");
        t.put("bwLimiterPending", "Bandwidth limiter pending");
        t.put("i2ptunnelThreads", "I2CP threads");
        t.put("leaseSetLookupTime", "Remote LeaseSet lookup time");
        t.put("i2cpDrops", "Messages dropped by cause");
        t.put("i2ptunnelServerTime", "I2PTunnel server setup time");
        TITLES = Collections.unmodifiableMap(t);
    }

    /**
     * The display name of a group, for use as a combined graph title.
     *
     * @param groupId a group id
     * @return the title, never null; an unknown id yields the id itself
     */
    public static String titleOf(String groupId) {
        String t = TITLES.get(groupId);
        return t != null ? t : groupId;
    }

    /**
     * Whether every group has a display name of its own.
     *
     * @return true when the title map covers the registry exactly
     */
    public static boolean allGroupsTitled() {
        return GROUPS.keySet().equals(TITLES.keySet());
    }

    private GraphGroups() {}

    /**
     * @param groupId a group id, or null
     * @return the member stat names in legend order, or an empty list if unknown
     */
    public static List<String> members(String groupId) {
        List<String> m = GROUPS.get(groupId);
        return m != null ? m : Collections.<String>emptyList();
    }

    /** @return every known group id, in display order */
    public static Set<String> groupIds() {
        return Collections.unmodifiableSet(GROUPS.keySet());
    }

    /**
     * Find the group a stat belongs to.
     *
     * @param statName a stat name such as "router.fastPeers"
     * @return the owning group id, or null when the stat is not in any group
     */
    public static String groupOf(String statName) {
        for (Map.Entry<String, List<String>> e : GROUPS.entrySet()) {
            if (e.getValue().contains(statName)) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * The enabled members of a group, capped at {@link #MAX_SERIES}.
     *
     * <p>Never null; an empty list means the group has too few enabled members to be worth
     * combining.
     *
     * @param groupId a group id
     * @param enabledStats stat names the user has switched on, without any period suffix
     * @return members that are enabled, in legend order
     */
    public static List<String> enabledMembers(String groupId, Set<String> enabledStats) {
        List<String> out = new ArrayList<>(MAX_SERIES);
        if (enabledStats == null) {
            return out;
        }
        for (String stat : members(groupId)) {
            if (enabledStats.contains(stat)) {
                out.add(stat);
                if (out.size() >= MAX_SERIES) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Whether a group should be drawn as one plot.
     *
     * <p>Requires the opt-in, at least two enabled members, and no event-graph mode:
     * events change what a datasource means, so they are not combined.
     *
     * @param enabledMembers result of {@link #enabledMembers}
     * @param combine value of {@link #PROP_COMBINE}
     * @param showEvents whether the graph page is in event mode
     * @return true when the members collapse into one graph
     */
    public static boolean shouldCombine(List<String> enabledMembers, boolean combine, boolean showEvents) {
        return combine && !showEvents && enabledMembers.size() >= 2;
    }

    /**
     * Whether an individual stat's own graph is replaced by its group.
     *
     * @param statName the stat being rendered on its own
     * @param combined the stat name that stands for its group, or null when not combining
     * @return true when this stat must be suppressed in favour of the group plot
     */
    public static boolean isSuppressed(String statName, String combined) {
        return combined != null && combined.equals(statName);
    }

    /**
     * Work out which stat, if any, stands for each group given the enabled set.
     *
     * @param enabledStats stat names enabled by the user
     * @param combine value of {@link #PROP_COMBINE}
     * @param showEvents whether the graph page is in event mode
     * @return group id to the member chosen to carry the group's graph; groups that do not
     *         qualify are absent
     */
    public static Map<String, String> combinedRepresentatives(Set<String> enabledStats,
                                                               boolean combine, boolean showEvents) {
        Map<String, String> rv = new LinkedHashMap<>();
        if (!combine || showEvents || enabledStats == null) {
            return rv;
        }
        for (String groupId : GROUPS.keySet()) {
            List<String> on = enabledMembers(groupId, enabledStats);
            if (on.size() >= 2) {
                rv.put(groupId, on.get(0));
            }
        }
        return rv;
    }

    /**
     * Every stat drawn on its own after grouping: the enabled stats, minus the members
     * that their group's plot already covers.
     *
     * @param enabledStats stat names enabled by the user
     * @param combine value of {@link #PROP_COMBINE}
     * @param showEvents whether the graph page is in event mode
     * @return stats to render individually, order unspecified
     */
    public static Set<String> suppressedStats(Set<String> enabledStats, boolean combine, boolean showEvents) {
        Set<String> rv = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : combinedRepresentatives(enabledStats, combine, showEvents).entrySet()) {
            for (String member : enabledMembers(e.getKey(), enabledStats)) {
                if (!member.equals(e.getValue())) {
                    rv.add(member);
                }
            }
        }
        return rv;
    }
}