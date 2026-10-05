package net.i2p.router.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
     * Most series drawn on one plot.
     *
     * <p>Every group is now at or under {@link #MAX_SERIES}: no frame combines more than
     * two, because a tile is too small to read a third line and two hues are all the
     * palette needs. This stays as a hard cap so a group added later cannot silently
     * exceed it, and {@code GraphGroupsTest} asserts the current groups all comply.
     */
    public static final int MAX_SERIES = 2;

    /**
     * Marker at the head of a group id built from an In/Out bandwidth pair rather than
     * declared in the registry.
     *
     * <p>Pool names come from configuration, so the two rates a pool publishes cannot be
     * listed in advance: an HTTP proxy is only there if the user named it one. The marker
     * keeps such an id recognisable, and keeps {@link #members} from treating an arbitrary
     * string as a group.
     *
     * <p>Not a plausible stat prefix: rate names are dotted subsystem paths
     * ({@code netDb.successTime}) or, for pools, bracketed.
     */
    private static final String PAIR_PREFIX = "pair:";

    /** Suffix of a pool's inbound rate, as built by TunnelPool. */
    private static final String IN_SUFFIX = " InBps";

    /** Suffix of a pool's outbound rate, as built by TunnelPool. */
    private static final String OUT_SUFFIX = " OutBps";

    /**
     * Subsystem a generated pair sorts under. A pool's rates answer how much traffic that
     * service is carrying, so they sort with the other service graphs rather than under
     * the tunnel jobs.
     */
    private static final String PAIR_SUBSYSTEM = "Service";

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
        // ProfileOrganizer's fast and high-capacity tiers. Deliberately excludes
        // activePeers (a live connection count on a different clock) and knownPeers
        // (netdb size, several times larger). router.integratedPeers, the floodfill
        // set, stays on its own graph: it is a membership count rather than a capability
        // tier, and pairing it with the two tiers made the frame answer two questions.
        g.put("peerCaps", Arrays.asList(
                "router.fastPeers", "router.highCapacityPeers"));

        // Profiles held in RAM against those written to disk. Same unit, and the on-disk
        // count is published by ProfilePersistenceHelper as a gauge of what survives a
        // restart.
        g.put("peerStoredProfiles", Arrays.asList(
                "peer.profileCount", "peer.storedProfileCount"));

        // Tier membership. The fast line is the union of both tiers rather than fast
        // alone, so it covers high-capacity as well; peer.fastPeerCount is fast only and
        // stays an individual graph.
        g.put("peerProfilesByTier", Arrays.asList(
                "peer.fastOrHighCapProfileCount", "peer.highCapPeerCount"));

        // NTCP pumper loop rates. Both are per second as of the idle-rate normalisation;
        // before that the idle series published a raw five-second count and could not share
        // an axis with the total.
        g.put("ntcpPumper", Arrays.asList(
                "ntcp.pumperLoopsPerSecond", "ntcp.pumperIdleLoops"));

        // Job queue execution timings, all milliseconds. Lag and wait are queueing measures
        // rather than execution ones, and loadRecoveryTime is a recovery trigger, so
        // each of those stays on its own graph; this frame contrasts a normal job
        // against one that took over a second.
        g.put("jobTiming", Arrays.asList(
                "jobQueue.jobRun", "jobQueue.jobRunSlow"));

        // Job queue depth, all counts of jobs waiting or running.
        // TestJob is deliberately absent: it is a synthetic workload rather than real
        // queue depth, and on an idle router it dwarfs the scheduled and active counts,
        // flattening both against the baseline.
        g.put("jobQueueDepth", Arrays.asList(
                "jobQueue.queuedJobs", "jobQueue.readyJobs"));

        // Why the runner scaled: event counters, each a running total. Kept apart from
        // jobQueueDepth because those are levels and these are cumulative events, and reading a
        // level and an event count off one axis invites a comparison that does not hold.
        // scaleRollback is a distinct failure signal and stays on its own graph, as does
        // droppedJobs. testJobHardLimit is gone from the console entirely: it counts
        // TestJob, the synthetic workload already excluded from jobQueueDepth.
        g.put("jobLoadEvents", Arrays.asList(
                "jobQueue.runnerScaleUp", "jobQueue.runnerScaleDown"));

        // Tunnel build rejections we received from a peer: probabalistic (the peer shed
        // load by design) against transient (an overload it did not expect). Both are
        // counters of the same event seen from the far end of the build.
        g.put("buildRejectReceived", Arrays.asList(
                "tunnel.receiveRejectionProbabalistic", "tunnel.receiveRejectionTransient"));

        // Tunnel builds we refused locally because the next hop is banned. Distinct from
        // buildRejectReceived in direction and cause, so the two are separate frames.
        g.put("buildRejectBanned", Arrays.asList(
                "tunnel.buildBanHit", "tunnel.buildBanFiltered"));

        // NetDb lookup durations, both milliseconds.
        g.put("netDbLookupTime", Arrays.asList("netDb.successTime", "netDb.failedTime"));

        // Note: tunnel.rejectHopThrottle (a per-hop throttle applied by this router) and
        // tunnel.buildDupId (a duplicate build ID) are refused for reasons that are
        // neither a received rejection nor a ban, so each stays on its own graph.

        // UDP retransmission timeouts, all milliseconds. congestedRTO is the value used
        // after congestion and is a regime of its own; it stays on its own graph rather
        // than being averaged in with the steady-state figures.
        g.put("udpRto", Arrays.asList(
                "udp.avgRTO", "udp.avgEffectiveRTO"));

        // Tuner CPU time per transport worker pool is deliberately NOT grouped. Each
        // pool is a percentage of a single core and only one or two are ever hot at
        // once, so a ten-line frame would be nine flat lines and one spike; ten
        // individual graphs answer "which pool" directly.

        // Inbound against outbound gateway cache occupancy. Both are gauges of entries
        // held against the same ceiling, so the pair shows which direction is filling.
        // The participants/participatingConfig pair and the outbound endpoint cache each
        // stay on their own graphs.
        g.put("tunnelCaches", Arrays.asList(
                "tunnel.cache.inboundGateways", "tunnel.cache.outboundGateways"));

        // The two directions of participating tunnel bandwidth, both bytes per second read
        // off the same pools. Apart they say nothing about the load we are carrying for
        // others: one direction is half the story and the pair is the question.
        g.put("participatingBw", Arrays.asList(
                "tunnel.participating InBps", "tunnel.participating OutBps"));

        // CoDel drop delay for the two highest-priority bands. Lower CoDel priority
        // numbers are the more urgent queues, so a drop at 0 or 100 is the one worth
        // reading together; bands 200-500 rarely drop and each stays on its own graph.
        g.put("codelDrop", Arrays.asList(
                "codel.OBGW.drop.0", "codel.OBGW.drop.100"));

        // Precalculated key pool consumption per algorithm, all event counters. Read apart
        // from the empty counts on purpose: these say the pool is being drained, those say
        // it ran dry, and a drained pool is healthy while a dry one is not. ML-KEM and
        // XDH are the live algorithms and share the frame; EDH is legacy ElGamal and
        // each EDH counter stays on its own graph.
        g.put("cryptoPoolUsed", Arrays.asList(
                "crypto.MLKEMUsed", "crypto.XDHUsed"));
        g.put("cryptoPoolEmpty", Arrays.asList(
                "crypto.MLKEMEmpty", "crypto.XDHEmpty"));

        // Bandwidth limiter queueing delay, both directions, both milliseconds.
        g.put("bwLimiterDelay", Arrays.asList(
                "bwLimiter.inboundDelayedTime", "bwLimiter.outboundDelayedTime"));

        // Requests waiting for bandwidth, both directions.
        g.put("bwLimiterPending", Arrays.asList(
                "bwLimiter.pendingInboundRequests", "bwLimiter.pendingOutboundRequests"));

        // I2CP thread accounting, split by side. Each frame mixes the configured maximum
        // with the live count on purpose: the gap between the two is the growth headroom.
        // Client and server are separate pools, so they get separate frames rather than
        // one four-line comparison.
        g.put("i2ptunnelClientThreads", Arrays.asList(
                "i2ptunnel.clientRunner.activeThreads", "i2ptunnel.clientRunner.threads"));
        g.put("i2ptunnelServerThreads", Arrays.asList(
                "i2ptunnel.serverHandler.active", "i2ptunnel.serverHandler.threads"));

        // Remote LeaseSet lookup time, split by outcome.
        g.put("leaseSetLookupTime", Arrays.asList(
                "client.leaseSetFoundRemoteTime", "client.leaseSetFailedRemoteTime"));

        // Messages dropped on the inbound pool path: a message we had to drop against
        // one that arrived twice. Both are counters of the same inbound event.
        g.put("inNetPoolDrops", Arrays.asList(
                "inNetPool.dropped", "inNetPool.duplicate"));

        // Messages the client side had to drop: a LeaseSet request it refused, and a
        // resend that needed no ACK. writerQueueFull is a backpressure signal rather
        // than a rejection and stays on its own graph, as does the dbLookup response
        // drop on the inbound side.
        g.put("clientDrops", Arrays.asList(
                "client.dispatchNoACK", "client.requestLeaseSetDropped"));

        // I2PTunnel server socket setup time, both milliseconds.
        g.put("i2ptunnelServerTime", Arrays.asList(
                "i2ptunnel.serverHandler.socketConnectTime",
                "i2ptunnel.serverHandler.blockingHandleTime"));

        GROUPS = Collections.unmodifiableMap(g);

        Map<String, String> t = new LinkedHashMap<>();
        t.put("peerCaps", "Capabilities");
        t.put("peerStoredProfiles", "Stored Profiles");
        t.put("peerProfilesByTier", "Profile Tiers");
        t.put("ntcpPumper", "Pumper Loops");
        t.put("jobTiming", "Queue Timing");
        t.put("jobQueueDepth", "Scheduled Jobs");
        t.put("jobLoadEvents", "Load Events");
        t.put("buildRejectReceived", "Build Rejections Received");
        t.put("buildRejectBanned", "Builds Refused as Banned");
        t.put("netDbLookupTime", "Lookup Time");
        t.put("udpRto", "UDP Retransmission Timeouts");
        t.put("tunnelCaches", "Caches");
        t.put("participatingBw", "Participating Bandwidth");
        t.put("codelDrop", "CoDel Drop Delay by Priority");
        t.put("cryptoPoolUsed", "Precalculated Keys Used");
        t.put("cryptoPoolEmpty", "Key Pool Empty");
        t.put("bwLimiterDelay", "Limiter Delay");
        t.put("bwLimiterPending", "Limiter Pending");
        t.put("i2ptunnelClientThreads", "Client Threads");
        t.put("i2ptunnelServerThreads", "Server Threads");
        t.put("leaseSetLookupTime", "Remote LeaseSet Lookup Time");
        t.put("inNetPoolDrops", "Inbound Pool Messages");
        t.put("clientDrops", "Client Messages Dropped");
        t.put("i2ptunnelServerTime", "Server Setup Time");
        TITLES = Collections.unmodifiableMap(t);
    }

    /**
     * Subsystem each group belongs to, used to build the {@code [Subsystem] } prefix
     * that makes the graph list sort by area.
     *
     * <p>Deliberately untranslated and kept out of {@link #TITLES}: a title is a
     * translation <i>key</i> ({@link net.i2p.util.Translate} returns the key for English
     * and falls back to it when a bundle lookup misses), so folding a prefix into the
     * title would silently untranslate every group in every locale. A prefix is also
     * only useful for sorting if it is stable, which a translated string is not.
     */
    private static final Map<String, String> SUBSYSTEMS;

    static {
        Map<String, String> subsystems = new LinkedHashMap<>();
        subsystems.put("peerCaps", "Peers");
        subsystems.put("peerStoredProfiles", "Peers");
        subsystems.put("peerProfilesByTier", "Peers");
        subsystems.put("ntcpPumper", "NTCP");
        subsystems.put("jobTiming", "Jobs");
        subsystems.put("jobQueueDepth", "Jobs");
        subsystems.put("jobLoadEvents", "Jobs");
        subsystems.put("buildRejectReceived", "Tunnel");
        subsystems.put("buildRejectBanned", "Tunnel");
        subsystems.put("netDbLookupTime", "NetDb");
        subsystems.put("leaseSetLookupTime", "NetDb");
        subsystems.put("udpRto", "Transport");
        subsystems.put("codelDrop", "Transport");
        subsystems.put("tunnelCaches", "Tunnel");
        subsystems.put("participatingBw", "Tunnel");
        subsystems.put("cryptoPoolUsed", "Crypto");
        subsystems.put("cryptoPoolEmpty", "Crypto");
        subsystems.put("bwLimiterDelay", "Bandwidth");
        subsystems.put("bwLimiterPending", "Bandwidth");
        subsystems.put("i2ptunnelClientThreads", "I2CP");
        subsystems.put("i2ptunnelServerThreads", "I2CP");
        subsystems.put("inNetPoolDrops", "Router");
        subsystems.put("clientDrops", "Router");
        subsystems.put("i2ptunnelServerTime", "I2PTunnel");
        SUBSYSTEMS = Collections.unmodifiableMap(subsystems);
    }

    /**
     * The sort prefix for a group, ready to prepend to a title.
     *
     * <p>Untranslated by design, see {@link #SUBSYSTEMS}.
     *
     * @param groupId a group id, or null
     * @return {@code "[Peers] "} including the trailing space, or the empty string
     *         when the group has no subsystem or the id is unknown
     * @since 0.9.71+
     */
    public static String displayPrefixOf(String groupId) {
        String sub = subsystemOf(groupId);
        return sub != null ? '[' + sub + "] " : "";
    }

    /**
     * The subsystem a group belongs to.
     *
     * <p>A generated In/Out pair is not in {@link #SUBSYSTEMS}; it is a pool rate and
     * answers "how much is this service carrying", so it sorts with the other service
     * graphs under the pool subsystem.
     *
     * @param groupId a group id, or null
     * @return the subsystem name, or null when the group has none
     * @since 0.9.71+
     */
    public static String subsystemOf(String groupId) {
        if (groupId == null) {
            return null;
        }
        String sub = SUBSYSTEMS.get(groupId);
        if (sub != null) {
            return sub;
        }
        return isPairId(groupId) ? PAIR_SUBSYSTEM : null;
    }

    /**
     * The display name of a group, for use as a combined graph title.
     *
     * <p>A generated pair is named after the directionless half the two rates share, with
     * the brackets a pool name carries dropped: {@code [skank.i2p]} is already a name, and
     * nesting it inside the title's own brackets reads as markup.
     *
     * @param groupId a group id
     * @return the title, never null; an unknown id yields the id itself
     */
    public static String titleOf(String groupId) {
        String t = TITLES.get(groupId);
        if (t != null) {
            return t;
        }
        if (isPairId(groupId)) {
            String key = keyOfPairId(groupId);
            if (key.length() > 1 && key.charAt(0) == '[' && key.charAt(key.length() - 1) == ']') {
                return key.substring(1, key.length() - 1);
            }
            return key;
        }
        return groupId;
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
        if (m != null) {
            return m;
        }
        // Inbound first, so the pair's legend order matches every other group's and the
        // representative never swaps sides as the enabled set changes.
        if (isPairId(groupId)) {
            String key = keyOfPairId(groupId);
            if (!key.isEmpty()) {
                return Arrays.asList(key + IN_SUFFIX, key + OUT_SUFFIX);
            }
        }
        return Collections.emptyList();
    }

    /** @return every known group id, in display order */
    public static Set<String> groupIds() {
        return Collections.unmodifiableSet(GROUPS.keySet());
    }

    /**
     * Whether an id names a generated In/Out pair rather than a registry group.
     *
     * @param groupId a group id, or null
     * @return true for a generated pair id
     * @since 0.9.71+
     */
    public static boolean isPairId(String groupId) {
        return groupId != null && groupId.length() > PAIR_PREFIX.length()
                && groupId.startsWith(PAIR_PREFIX);
    }

    /**
     * The directionless name the two rates of a pair share.
     *
     * @param statName a stat name such as {@code [skank.i2p] InBps}
     * @return the shared name, brackets included, or null when the stat is not half of a
     *         bandwidth pair
     * @since 0.9.71+
     */
    public static String pairKeyOf(String statName) {
        String suffix = suffixOf(statName);
        if (suffix == null) {
            return null;
        }
        String key = statName.substring(0, statName.length() - suffix.length());
        return key.isEmpty() ? null : key;
    }

    /**
     * The other half of a bandwidth pair, spelled exactly as a stat name.
     *
     * @param statName a stat name such as {@code [skank.i2p] InBps}
     * @return the counterpart's full stat name, or null when the stat is not half of a
     *         bandwidth pair
     * @since 0.9.71+
     */
    public static String counterpartOf(String statName) {
        String suffix = suffixOf(statName);
        if (suffix == null) {
            return null;
        }
        return statName.substring(0, statName.length() - suffix.length())
                + (IN_SUFFIX.equals(suffix) ? OUT_SUFFIX : IN_SUFFIX);
    }

    /**
     * The In/Out pairs the enabled stats form.
     *
     * <p>Only pairs the user has switched on both halves of are returned: a group id for a
     * half-enabled pair would render as an empty tile, and pairing must never turn a single
     * enabled stat into a combined plot. Stats the registry already claims are skipped, so
     * a declared group and a generated one can never cover the same rate.
     *
     * @param enabledStats stat names enabled by the user, without period suffixes;
     *                     null yields no pairs
     * @return the pair ids, in name order; never null
     * @since 0.9.71+
     */
    public static Set<String> pairGroupIds(Set<String> enabledStats) {
        Set<String> rv = new TreeSet<>();
        if (enabledStats == null) {
            return rv;
        }
        for (String stat : enabledStats) {
            if (groupOf(stat) != null) {
                continue;
            }
            String key = pairKeyOf(stat);
            if (key == null) {
                continue;
            }
            String other = counterpartOf(stat);
            if (other != null && enabledStats.contains(other)) {
                rv.add(PAIR_PREFIX + key);
            }
        }
        return rv;
    }

    /**
     * Every group id that may render for an enabled set: the registry, plus the In/Out
     * pairs those stats form.
     *
     * @param enabledStats stat names enabled by the user; may be null
     * @return group ids in display order; never null
     * @since 0.9.71+
     */
    public static Set<String> allGroupIds(Set<String> enabledStats) {
        Set<String> rv = new LinkedHashSet<>(GROUPS.keySet());
        rv.addAll(pairGroupIds(enabledStats));
        return rv;
    }

    private static String keyOfPairId(String groupId) {
        return groupId.substring(PAIR_PREFIX.length());
    }

    private static String suffixOf(String statName) {
        if (statName == null) {
            return null;
        }
        if (statName.endsWith(IN_SUFFIX)) {
            return IN_SUFFIX;
        }
        if (statName.endsWith(OUT_SUFFIX)) {
            return OUT_SUFFIX;
        }
        return null;
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
        for (String groupId : allGroupIds(enabledStats)) {
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
            // Every member is covered, the representative included. Leaving the
            // representative out drew its series twice: once inside the group plot and
            // again as its own single-stat graph, so combining a group added a graph
            // instead of replacing the ones it merged.
            rv.addAll(enabledMembers(e.getKey(), enabledStats));
        }
        return rv;
    }
}
