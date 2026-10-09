package net.i2p.router.tunnel.pool;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.Banlist;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.peermanager.FloodfillReliability;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.ProfileOrganizer;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.util.ArraySet;

/**
 * Pick peers randomly out of the fast pool, and put them into tunnels
 * ordered by XOR distance from a random key.
 */
class ClientPeerSelector extends TunnelPeerSelector {

    private static final double SEVERE_ATTACK_THRESHOLD = 0.30;
    /** Startup grace period: strict first-hop gates and soft fallbacks are relaxed for the first 15 minutes. */
    private static final long STARTUP_GRACE_MS = 15 * 60 * 1000L;
    /** First-hop quality attempts before preferring connecting peers. */
    private static final int ESTABLISHED_PREF_ATTEMPTS = 3;
    /** First-hop quality attempts before accepting any tier-passing peer. */
    private static final int CONNECTING_PREF_ATTEMPTS = 5;
    /**
     * How many first-hop candidates to fetch at once.
     *
     * <p>The quality loop can only iterate while it has a candidate to reject, so asking the
     * tiers for exactly one left it a single attempt per refill and the 16-attempt budget went
     * largely unspent. A small batch lets one tier scan serve several attempts. Only the
     * accepted peer is kept — see the trim after the loop — so the rest never reach the tunnel.
     *
     * @since 0.9.71+
     */
    private static final int FIRST_HOP_CANDIDATES = 4;
    /**
     * Build success below which the first hop draws on both the Fast and HighCapacity tiers.
     *
     * <p>The two tiers hold different peers: Fast is speed-ranked, HighCapacity is
     * proven-reliability. Below this the network is not delivering often enough for the
     * distinction to earn its cost, and taking from only one of them needlessly halves the
     * pool.
     *
     * @since 0.9.71+
     */
    static final double WIDEN_BUILD_SUCCESS = 0.50;
    /**
     * Fast-tier size below which the first hop draws on both tiers regardless of build success.
     *
     * <p>A small Fast tier cannot supply {@link #FIRST_HOP_CANDIDATES} candidates however
     * healthy the network looks, so the batch is worth completing from the other tier even when
     * success is high. The same 300 marks the point where cross-pool diversity engages, so both
     * decisions agree on what counts as a large enough Fast tier.
     *
     * @since 0.9.71+
     */
    static final int WIDEN_FAST_PEERS = 300;
    /**
     * Build success below which the first-hop quality loop starts one tier lower, accepting
     * peers with only a connecting transport session rather than requiring an established one.
     *
     * <p>Distinct from {@link #WIDEN_BUILD_SUCCESS}: this widens the <em>transport</em>
     * requirement, which costs build latency, so it waits until success is closer to healthy.
     *
     * @since 0.9.71+
     */
    static final double CONNECTING_PREF_BUILD_SUCCESS = 0.60;
    /** How often the accepted-first-hop tier line may be written, per tier. @since 0.9.71+ */
    private static final long TIER_LOG_INTERVAL_MS = 60L * 1000;
    /** Last write time per accepted tier key, for rate limiting. @since 0.9.71+ */
    private final Map<String, Long> _tierLogTime = new ConcurrentHashMap<>(8);
    /** Accepts per tier since the last log line, so volume is visible. @since 0.9.71+ */
private final Map<String, Long> _tierLogCount = new ConcurrentHashMap<>(8);
    /** Pre-connect cooldown: peers connected within this window don't need pre-connect again. */
    private static final long PRECONNECT_COOLDOWN_MS = 5 * 60 * 1000L;
    /** Property to enable/disable conditional pre-connect in selectFirstHop. */
    static final String PROP_PRECONNECT_OPTIMIZE = "i2p.tunnel.preConnect.optimize";
    /** Default: true. */
    private static final boolean PROP_PRECONNECT_OPTIMIZE_DEFAULT = true;
    /** Cross-pool diversity: when fast tier exceeds this count, exclude peers in ANY
     * active tunnel across ALL pools to force each pool to use different fast peers. */
    static final int CROSS_POOL_DIVERSITY_THRESHOLD = 300;
    /** Cross-pool diversity is only active when build success is above this threshold.
     * Higher than {@link TunnelPeerSelector#ATTACK_THRESHOLD} because cross-pool
     * exclusion is far more aggressive (excludes ALL active peers across ALL pools)
     * and can starve builds when most fast peers are in active tunnels. */
    static final double CROSS_POOL_BUILD_SUCCESS_MIN = 0.60;
    /** Cross-pool exclusion may not consume more than this fraction of the fast tier.
     * When most fast peers are in active tunnels, natural pool-local diversity
     * already provides enough variability — explicit exclusion risks starvation. */
    static final double CROSS_POOL_EXCLUSION_RATIO = 0.50;


    private String getStrategy() {
        return ctx.getProperty(PROP_STRATEGY, STRATEGY_DEFAULT);
    }


    private static String formatPeerList(List<Hash> peers) {
        if (peers == null || peers.isEmpty()) {return "[empty]";}
        StringBuilder sb = new StringBuilder(peers.size() * 10);
        for (int i = 0; i < peers.size(); i++) {
            sb.append('[').append(peers.get(i).toBase64(), 0, 6).append("]");
            if (i < peers.size() - 1) {sb.append(" -> ");}
        }
        return sb.toString();
    }

    private static final String PROP_LEGACY_SELECTION = "router.tunnel.useLegacyPeerSelection";

    private static final String PROP_STRATEGY = "i2p.tunnel.peerSelector.clientStrategy";

    private static final String STRATEGY_DEFAULT = "default";
    private static final String STRATEGY_RELIABILITY = "reliability";
    private static final String STRATEGY_DIVERSITY = "diversity";

    /**
     * Constructor.
     *
     * @param context the router context
     */
    public ClientPeerSelector(RouterContext context) {
        super(context);
    }

    /**
     * Returns ENDPOINT FIRST, GATEWAY LAST!!!!
     * In: us .. closest .. middle .. IBGW
     * Out: OBGW .. middle .. closest .. us
     *
     * @return ordered list of Hash objects (one per peer) specifying what order
     * they should appear in a tunnel (ENDPOINT FIRST).  This includes
     * the local router in the list.  Never null; an empty list means
     * no peers could be selected.
     */
    public List<Hash> selectPeers(TunnelPoolSettings settings) {
        return selectPeers(settings, null);
    }

    /**
     * Select peers for a new tunnel, excluding given peers from first-hop
     * selection.  Used by the build executor's dispatch loop to ensure
     * concurrent builds in the same batch target diverse first-hop peers —
     * stacking multiple build requests on one peer floods it and makes the
     * first build answer slower.
     *
     * The first hop is the last element of the returned list (endpoint-first
     * order).  If the selected first hop is excluded, selection retries up to
     * {@code MAX_DIVERSITY_RETRIES} times before returning the last result, so
     * a caller always gets a usable set even when the exclusion set covers
     * most of the tier.
     *
     * @param settings pool settings
     * @param excludeFirstHops first-hop peers already targeted by concurrent
     * builds in the same dispatch batch, may be null
     * @return ordered hops, endpoint first; empty when nothing usable exists
     * @since 0.9.71+
     */
    public List<Hash> selectPeers(TunnelPoolSettings settings, Set<Hash> excludeFirstHops) {
        if (excludeFirstHops == null || excludeFirstHops.isEmpty()) {
            return selectPeersBase(settings);
        }
        List<Hash> rv = selectPeersBase(settings);
        for (int retry = 0; retry < MAX_DIVERSITY_RETRIES && !rv.isEmpty(); retry++) {
            Hash firstHop = rv.get(rv.size() - 1);
            if (!excludeFirstHops.contains(firstHop)) {return rv;}
            rv = selectPeersBase(settings);
        }
        return rv;
    }

    /**
     * Maximum first-hop diversity retries when concurrent builds exclude
     * already-selected first hops.  Bounded so a near-total exclusion set
     * cannot turn selection into a long loop.
     * @since 0.9.71+
     */
    private static final int MAX_DIVERSITY_RETRIES = 3;

    /**
     * Select peers for a new tunnel.  Delegates to
     * {@link #selectPeers(TunnelPoolSettings, Set)} with a null exclusion set.
     *
     * @param settings pool settings
     * @return ordered hops, endpoint first; empty when nothing usable exists
     */
    private List<Hash> selectPeersBase(TunnelPoolSettings settings) {
        int length = getLength(settings);
        if (length < 0 || ((length == 0) && (settings.getLength() + settings.getLengthVariance() > 0))) {
            if (log.shouldWarn()) {
                log.warn("CPS selectPeers abort: getLength returned " + length +
                         " for " + settings.getDestinationNickname() +
                         " (" + (settings.isInbound() ? "in" : "out") + ")");
            }
            return Collections.emptyList();
        }
        // Cold-start seeding: at startup the proven-responder map is empty,
        // so first-build selection has no proven preference.  Seed once with
        // random fast-tier peers.  The O(1) size check makes this a no-op
        // after the first seeding.  Called here (not prunePeerMaps) because
        // this path already expects PO access via selectFastPeers().
        if (_provenResponders.size() < MIN_PROVEN_RESPONDER_COUNT) {
            seedProvenResponders(ctx, ctx.clock().now());
        }
        List<Hash> rv;
        boolean isInbound = settings.isInbound();

        if (length > 0) {
            SelectionParams params = computeSelectionParams(settings, length, isInbound);
            if (shouldSelectExplicit(settings)) {return selectExplicit(settings, length);}
            SelectionExclusions ex = buildExclusions(settings, isInbound, params.buildSuccess, length);
            // Filter ghosts and banlisted peers BEFORE the shortfall fallback so
            // replacements are drawn from usable candidates.  Previously the
            // filter ran in finalizeSelection() after the fallback, so an
            // all-ghost selection aborted the whole cycle with no replacement.
            rv = selectHopsWithRetry(settings, length, params, ex);
            if (rv.isEmpty()) {return Collections.emptyList();}
            if (log.shouldDebug()) {
                log.debug("ClientPeerSelector " + length + (isInbound ? " Inbound" : " Outbound") +
                          ", " + ex.excluder.getReasonsSummary() +
                         "\n* Cooldowns: " + ex.peerCooldownExcluded + " shared(" + _peerCooldowns.size() +
                         "), firstHopFails=" + ex.firstHopFailCount +
                         (ex.firstPeerExclusions != null && !ex.firstPeerExclusions.isEmpty() ?
                          ", " + ex.firstPeerExclusions.size() + " first-hop diversity" : ""));
            }
            if (rv.size() < length) {
                rv = applyShortfallFallbacks(settings, rv, length, params, ex);
                if (rv.isEmpty()) {return Collections.emptyList();}
            }
            // P7: finalizeSelection re-checks ghosts and banlist after the ladder has
            // approved a selection, so a peer that turned unusable mid-selection shrinks
            // it — and an all-ghost result empties it.  Emptied here there is no retry
            // left: the selection is discarded and the cycle builds nothing.  Redraw once
            // with the unusable peers excluded, which is what selectHopsWithRetry already
            // does for the pre-ladder case; without this the same ghost set costs a build
            // every cycle for as long as the mark lasts.
            List<Hash> preFinalize = rv;
            rv = finalizeSelection(settings, rv, isInbound);
            if (rv.isEmpty() && !preFinalize.isEmpty()) {
                List<Hash> unusable = new ArrayList<>(preFinalize);
                if (log.shouldDebug()) {
                    log.debug("CPS finalize emptied an approved selection (" + unusable.size() +
                              " unusable peers) -> redrawing once with them excluded");
                }
                SelectionExclusions retry = buildExclusions(settings, isInbound,
                                                            params.buildSuccess, length);
                retry.exclude.addAll(unusable);
                rv = selectHopsWithRetry(settings, length, params, retry);
                if (rv.size() < length) {
                    rv = applyShortfallFallbacks(settings, rv, length, params, retry);
                }
                if (!rv.isEmpty()) {
                    rv = finalizeSelection(settings, rv, isInbound);
                }
                ex = retry;
            }
        } else {
            rv = new ArrayList<>(1);
            rv = finalizeSelection(settings, rv, isInbound);
        }
        // finalizeSelection's own ghost/banned safety net runs after the
        // shortfall ladder, so it can shrink a size the ladder already
        // approved. Never emit a peer that turned unusable mid-selection:
        // finalizeSelection has already removed it, so the survivors are
        // clean and the selection is structurally sound.
        //
        // The ladder already treats a short tunnel as an acceptable outcome
        // (applyShortfallFallbacks exists for exactly that), so a minor
        // shortfall is not a reason to throw the whole selection away.
        // Discarding it is what used to starve pools: a starved pool requests
        // *minimum* length, which leaves zero slack, so a single peer turning
        // unreliable mid-selection discarded the endpoint and every valid hop
        // and forced a full redraw -- repeated on every attempt while churn
        // kept peers tripping the reliability gate. Only a structurally
        // unusable selection is discarded.
        int min = minRequestedLength(settings);
        // Pre-finalize size, for the debug line only; finalizeSelection may have redrawn.
        int approved = rv.size();
        if (shouldDiscardShortSelection(rv.size(), min)) {
            if (log.shouldWarn()) {
                log.warn("CPS selection unusable for " + settings.getDestinationNickname() +
                         " (" + (isInbound ? "in" : "out") + "): " + (rv.size() - 1) +
                         "/" + min + " hops -> discarding for redraw");
            }
            return Collections.emptyList();
        }
        if (log.shouldDebug() && rv.size() - 1 < min) {
            log.debug("CPS accepting short selection for " + settings.getDestinationNickname() +
                      " (" + (isInbound ? "in" : "out") + "): " + (rv.size() - 1) +
                      "/" + min + " hops, " + approved + " approved");
        }
        return rv;
    }

    /**
     * Minimum non-self hops a selection needs to be buildable at all: the peer
     * adjacent to us (IBGW for inbound, our first hop for outbound) and the far
     * endpoint. Matches the {@code keepAtLeast} floor
     * {@link #dropUnreliable} already uses.
     *
     * @since 0.9.71+
     */
    static final int MIN_STRUCTURAL_NON_SELF_HOPS = 2;

    /**
     * Whether a post-finalize selection is too short to build and must be
     * discarded for a redraw.
     *
     * <p>Pure decision helper for the shortfall guard in {@code selectPeers()},
     * extracted for unit testing. A selection is discarded only when it holds
     * fewer than {@link #MIN_STRUCTURAL_NON_SELF_HOPS} non-self hops — that is,
     * when it cannot form a tunnel regardless of what was requested. Anything
     * longer is returned as a short tunnel, which the shortfall ladder already
     * produces deliberately; discarding those to force a redraw converts a
     * one-hop shortfall into a whole lost build cycle, and at minimum length
     * (what a starved pool asks for) there is no slack to absorb it.
     *
     * @param rvSize selection size after {@code finalizeSelection}, including self
     * @param min minimum requested tunnel length, from {@link #minRequestedLength}
     * @return true if the selection must be discarded for a redraw
     * @since 0.9.71+
     */
    static boolean shouldDiscardShortSelection(int rvSize, int min) {
        int nonSelf = rvSize - 1;
        if (nonSelf >= MIN_STRUCTURAL_NON_SELF_HOPS)
            return false;   // structurally sound; a short tunnel is acceptable
        return nonSelf < min;
    }

    /**
     * Draws every hop, drops unsuitable peers (ghost and banlisted), and
     * redraws once from a fresh exclusion set when filtering emptied the
     * selection.  The retry is what turns "all chosen peers were unusable"
     * from a wasted build cycle into a usable tunnel.
     *
     * @param settings pool settings
     * @param length tunnel length being built
     * @param params shared selection parameters
     * @param ex live exclusions, mutated so a retry cannot re-pick rejects
     * @return ordered hops, endpoint first; empty when nothing usable exists
     * @since 0.9.71+
     */
    private List<Hash> selectHopsWithRetry(TunnelPoolSettings settings, int length, SelectionParams params,
                                           SelectionExclusions ex) {
        List<Hash> rv = selectHops(settings, length, params, ex);
        if (rv.isEmpty()) {return rv;}
        List<Hash> before = new ArrayList<>(rv);
        rv = filterBannedPeers(filterGhostPeers(rv));
        if (!rv.isEmpty()) {return rv;}
        // Every selected peer was unsuitable — exclude them so the redraw
        // cannot pick them again, then try once more.
        ex.exclude.addAll(before);
        rv = selectHops(settings, length, params, ex);
        if (rv.isEmpty()) {return rv;}
        before = new ArrayList<>(rv);
        rv = filterBannedPeers(filterGhostPeers(rv));
        if (rv.isEmpty()) {ex.exclude.addAll(before);}
        return rv;
    }

    /** Single-hop or multi-hop selection for one tunnel length. Never null. */
    private List<Hash> selectHops(TunnelPoolSettings settings, int length, SelectionParams params,
                                  SelectionExclusions ex) {
        // Room for the hops plus the first-hop candidate batch. ArraySet is fixed-capacity
        // and throws SetFullException on overflow, so the batch size has to be
        // accounted for here or a short tunnel overflows on the first hop fetch.
        ArraySet<Hash> matches = new ArraySet<>(length + FIRST_HOP_CANDIDATES);
        if (length == 1) {return selectSingleHop(settings, length, params, ex, matches);}
        return selectMultiHop(settings, length, params, ex, matches);
    }

    /** Compute the shared selection parameters (tier priority, closest-hop checks, hidden flags, IP restriction). */
    private SelectionParams computeSelectionParams(TunnelPoolSettings settings, int length, boolean isInbound) {
        // Cache buildSuccess to avoid repeated expensive calls
        double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
        // Three separate build-success decisions, deliberately at different thresholds:
        //   useHighCapPrimary (< 40%) — which tier LEADS the first-hop batch, and which
        //     one the stress fallbacks trust. Speed-ranked peers overload and reject
        //     while HighCapacity peers have completed tunnels, so below this the proven
        //     ranking leads.
        //   shouldWidenBothTiers (< 50%, or Fast < 300) — whether the batch is completed
        //     from BOTH tiers instead of one. See selectFirstHop.
        //   CONNECTING_PREF_BUILD_SUCCESS (< 60%) — whether the quality loop may accept a
        //     merely connecting peer on its first attempts.
        // Ordered most-degraded first, so recovery unwinds them in reverse: HighCapacity
        // leads, then the batch widens to both tiers, and only then is the transport bar
        // relaxed. A router must never narrow its peer pool while simultaneously asking
        // more of each peer.
        boolean useHighCapPrimary = buildSuccess < ATTACK_THRESHOLD;
        String strat = getStrategy();
        if (STRATEGY_RELIABILITY.equals(strat)) {
            useHighCapPrimary = true;
        } else if (STRATEGY_DIVERSITY.equals(strat)) {
            useHighCapPrimary = buildSuccess < SEVERE_ATTACK_THRESHOLD;
        }

        // special cases
        boolean v6Only = isIPv6Only();
        boolean ntcpDisabled = isNTCPDisabled();
        boolean ssuDisabled = isSSUDisabled();
        // for these cases, check the closest hop up front,
        // otherwise, will be done in checkTunnel() at the end
        boolean checkClosestHop = v6Only || ntcpDisabled || ssuDisabled;
        boolean hidden = ctx.router().isHidden() ||
                         ctx.router().getRouterInfo().getAddressCount() <= 0 ||
                         !ctx.commSystem().haveInboundCapacity(95);
        boolean hiddenInbound = hidden && isInbound;
        boolean hiddenOutbound = hidden && !isInbound;
        int ipRestriction = settings.getIPRestriction();
        // Reduce IP restriction under low tunnel build success to improve diversity
        if (ipRestriction > 0 && length > 1) {
            if (buildSuccess < ATTACK_THRESHOLD) {
                ipRestriction = Math.max(0, ipRestriction - 1);
            }
        }
        if (ctx.getBooleanProperty("i2np.allowLocal") || length <= 1) {ipRestriction = 0;}
        MaskedIPSet ipSet = ipRestriction > 0 ? new MaskedIPSet(ipRestriction) : null;
        return new SelectionParams(isInbound, buildSuccess, useHighCapPrimary, checkClosestHop,
                                   hidden, hiddenInbound, hiddenOutbound, ipRestriction, ipSet);
    }

    /** Build the lazy Excluder with client/shared cooldowns, first/last peer and pool diversity exclusions. */
    private SelectionExclusions buildExclusions(TunnelPoolSettings settings, boolean isInbound,
                                                double buildSuccess, int length) {
        // Excluder is lazy — contains() auto-classifies and tracks reasons.
        // Don't copy to HashSet or reason tracking is lost.
        Excluder excluder = new Excluder(isInbound, false, buildSuccess);
        Set<Hash> exclude = excluder;

        // This pool, fetched once: feeds both the cooldown-relax decision
        // and the per-pool diversity pass below.
        Hash dest = settings.getDestination();
        TunnelPool pool = null;
        if (dest != null) {
            TunnelManagerFacade tmf = ctx.tunnelManager();
            pool = isInbound ? tmf.getInboundPool(dest)
                             : tmf.getOutboundPool(dest);
        }

        // Check shared peer cooldowns (from checkTunnel failures across ALL pools).
        // Filter expired entries at read time instead of mutating the shared map
        // on every selection: the exclusion window is identical and concurrent
        // selections no longer sweep the map (which is iterated by other
        // selectors too). Bulk hygiene is handled by prunePeerMaps() when a
        // map exceeds its size cap.
        long nowCooldown = ctx.clock().now();
        long sharedCooldownCutoff = nowCooldown - PEER_SELECTION_COOLDOWN_MS;
        int peerCooldownExcluded;
        Set<Hash> firstHopCooldowns = null;
        if (shouldRelaxCooldownToFirstHop(pool != null ? pool.getUsableTunnelCount() : -1, length)) {
            // Near-collapsed pool: keep shared cooldowns out of the base
            // exclude set — with usable <= 1, cooled peers can fill every
            // middle/last slot, and freezing all hops on one checkTunnel
            // cooldown starves selection outright. The cooled peers are
            // filtered at first-hop selection instead, where the build
            // actually talks to them.
            firstHopCooldowns = new HashSet<Hash>(8);
            peerCooldownExcluded = addFreshCooldownExclusions(_peerCooldowns, sharedCooldownCutoff, firstHopCooldowns);
        } else {
            peerCooldownExcluded = addFreshCooldownExclusions(_peerCooldowns, sharedCooldownCutoff, exclude);
        }
        // firstHopFails entries expire lazily in isFirstHopFailing() /
        // hasRecoveredFromFailure() and are bulk-pruned by prunePeerMaps();
        // no per-selection sweep needed. They filter first-hop selection only.
        int firstHopFailCount = _firstHopFails.size();

        // Add first peer exclusions for diversity
        Set<Hash> firstPeerExclusions = settings.getFirstPeerExclusions();
        if (firstPeerExclusions != null && !firstPeerExclusions.isEmpty()) {
            exclude.addAll(firstPeerExclusions);
        }
        // Add last peer exclusions for diversity - prevent same peer as first and last hop
        Set<Hash> lastPeerExclusions = settings.getLastPeerExclusions();
        if (lastPeerExclusions != null && !lastPeerExclusions.isEmpty()) {
            exclude.addAll(lastPeerExclusions);
        }
        // Per-pool diversity: exclude peers already in an active tunnel of this pool.
        // No peer should appear in more than 1 tunnel of the same pool.
        // Always enforce peer diversity (prevents pool-local circular dependency);
        // relax only IP restriction via getIPRestriction() under attack, not
        // peer identity — relaxing identity causes pool-local correlated failures.
        if (pool != null) {
            Set<Hash> poolPeers = getPeersInPool(ctx, pool);
            exclude.addAll(poolPeers);
        }
        // Cross-pool diversity: when the fast tier is large enough (> 300),
        // exclude peers in ANY active tunnel across ALL pools. This forces
        // each pool to use different fast peers, driving peer variability
        // and ensuring fast peers accumulate tunnel history for proper
        // retention/demotion evaluation. Below the threshold, only per-pool
        // diversity is enforced to avoid starving pools.
        // Under stress (< 60% build success), skip cross-pool exclusion —
        // availability matters more than diversity when builds are degrading.
        // Also skip when cross-pool exclusion would consume most of the fast
        // tier: natural pool-local diversity already provides variability when
        // most fast peers are in active tunnels.
        int fastCount = ctx.profileOrganizer().getFastPeerCount();
        if (fastCount > CROSS_POOL_DIVERSITY_THRESHOLD
            && buildSuccess >= CROSS_POOL_BUILD_SUCCESS_MIN) {
            Set<Hash> allActive = getPeersInAllPools(ctx);
            if (allActive.size() < fastCount * CROSS_POOL_EXCLUSION_RATIO) {
                exclude.addAll(allActive);
            } else if (log.shouldDebug()) {
                log.debug("Cross-pool exclusion skipped: " + allActive.size() + " of " + fastCount +
                          " fast peers in active tunnels (ratio exceeds " + CROSS_POOL_EXCLUSION_RATIO + ")");
            }
        }
        return new SelectionExclusions(excluder, exclude,
                                       peerCooldownExcluded, firstHopFailCount, firstPeerExclusions,
                                       firstHopCooldowns);
    }

    /**
     * Whether shared peer cooldowns should be relaxed to a first-hop-only
     * filter: with at most one usable tunnel the pool is near-collapsed,
     * and excluding checkTunnel-cooled peers from every hop can leave no
     * selectable path at all.  The cooled peers stay filtered at first-hop
     * selection (where the build actually contacts them); middle and last
     * hops may use them.  Single-hop tunnels (no multi-hop quality loop)
     * and unknown pools (negative count) never relax.
     *
     * @param usableTunnels usable tunnels in this pool, or -1 when the pool
     * is unknown
     * @param length tunnel length being selected for
     * @return true when cooldowns should apply to the first hop only
     * @since 0.9.71+
     */
    static boolean shouldRelaxCooldownToFirstHop(int usableTunnels, int length) {
        return length > 1 && usableTunnels >= 0 && usableTunnels <= 1;
    }

    /** Select the single hop of a 1-hop tunnel (with hidden-inbound special case). */
    private List<Hash> selectSingleHop(TunnelPoolSettings settings, int length, SelectionParams params,
                                       SelectionExclusions ex, ArraySet<Hash> matches) {
        Set<Hash> exclude = ex.exclude;
        // closest-hop restrictions
        if (params.checkClosestHop) {exclude = getClosestHopExclude(params.isInbound, exclude);}
        if (params.isInbound) {exclude = new IBGWExcluder(exclude);}
        else {exclude = new OBEPExcluder(exclude);}
        // 1-hop, IP restrictions not required here
        if (params.hiddenInbound) {
            if (ctx.getBooleanProperty(PROP_LEGACY_SELECTION)) {
                ctx.profileOrganizer().selectActiveNotFailingPeers(1, exclude, matches);
            } else {
                // Priority: HighCap > Fast > Active > NotFailing
                ctx.profileOrganizer().selectHighCapacityPeers(1, exclude, matches);
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectFastPeers(1, exclude, matches);
                }
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectActiveNotFailingPeers(1, exclude, matches);
                }
            }
        }
        if (matches.isEmpty()) {
            if (ctx.getBooleanProperty(PROP_LEGACY_SELECTION)) {
                ctx.profileOrganizer().selectFastPeers(length, exclude, matches);
            } else {
                // Fallback tiers: HighCap > Fast > Active > NotFailing > All
                ctx.profileOrganizer().selectHighCapacityPeers(length, exclude, matches);
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectFastPeers(length, exclude, matches);
                }
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectActiveNotFailingPeers(length, exclude, matches);
                }
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectNotFailingPeers(length, exclude, matches, false, 0, null);
                }
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectAllNotFailingPeers(length, exclude, matches, false);
                }
            }
            // Filter: remove peers that are in the exclude set
            // For outbound single-hop, prefer peers with transport connections
            matches.removeAll(exclude);
        }
        matches.remove(ctx.routerHash());
        // Shortfall fallback below reuses the (wrapped) exclude
        ex.exclude = exclude;
        return new ArrayList<>(matches);
    }

    /**
     * Select all hops of a multi-hop tunnel: last hop, middle hops, then first
     * hop.  rv ends up [endpoint .. first hop]; the vetted first hop is recorded
     * on {@code ex} so a shortfall fill inserts middles before it instead of
     * shifting roles.  A missing first hop is logged and left for that fill —
     * discarding the whole selection would waste a usable endpoint and middles.
     */
    private List<Hash> selectMultiHop(TunnelPoolSettings settings, int length, SelectionParams params,
                                      SelectionExclusions ex, ArraySet<Hash> matches) {
        // build a tunnel using 4 subtiers.
        // For a 2-hop tunnel, the first hop comes from subtiers 0-1 and the last from subtiers 2-3.
        // For a longer tunnels, the first hop comes from subtier 0, the middle from subtiers 2-3, and the last from subtier 1.
        List<Hash> rv = new ArrayList<>(length + 1);
        SessionKey randomKey = settings.getRandomKey();
        ex.firstHop = null;
        // OBEP or IB last hop
        // group 0 or 1 if two hops, otherwise group 0
        Set<Hash> lastHopExclude = buildLastHopExclude(params, ex.exclude);
        if (!selectLastHop(settings, length, params, randomKey, lastHopExclude, ex.exclude, matches)) {
            // selectLastHop already logged the reason
            return Collections.emptyList();
        }
        matches.remove(ctx.routerHash());
        ex.exclude.addAll(matches);
        rv.addAll(matches);
        matches.clear();
        if (length > 2) {
            selectMiddleHops(length, params, randomKey, ex, matches, rv);
        }
        selectFirstHop(length, params, randomKey, ex, matches);
        matches.remove(ctx.routerHash());
        if (!matches.isEmpty()) {
            ex.firstHop = matches.get(0);
            rv.addAll(matches);
        } else if (log.shouldWarn()) {
            log.warn("CPS no first hop for " + settings.getDestinationNickname() +
                     " (" + (params.isInbound ? "in" : "out") + ") " + length +
                     "-hop; shortfall fallback will fill it");
        }
        return rv;
    }

    /** Build the last-hop exclusion set (closest-hop wrapped for inbound, OBEP for outbound). */
    private Set<Hash> buildLastHopExclude(SelectionParams params, Set<Hash> exclude) {
        Set<Hash> lastHopExclude;
        if (params.isInbound) {
            if (params.checkClosestHop && !params.hidden) {
                // exclude existing OBEPs to get some diversity ?
                // closest-hop restrictions
                lastHopExclude = getClosestHopExclude(true, exclude);
            } else {lastHopExclude = exclude;}
            if (log.shouldInfo()) {
                log.info("Selecting fast peer for closest Inbound..." +
                         (lastHopExclude.size() > 0 ? "\n* Excluding: " + formatExcludedPeers(lastHopExclude) : ""));
            }
        } else {
            lastHopExclude = new OBEPExcluder(exclude);
            if (log.shouldInfo()) {
                log.info("Selecting fast peer for OutboundEndpoint..." +
                         (lastHopExclude.size() > 0 ? "\n* Excluding: " + formatExcludedPeers(lastHopExclude) : ""));
            }
        }
        return lastHopExclude;
    }

    /**
     * Select the last hop (hidden-inbound closest hop, hidden-outbound OBEP, or
     * normal OBEP).
     *
     * @param lastHopExclude wrapped exclusions (closest-hop / OBEP role checks)
     * @param rawExclude the unwrapped exclusion set, used only for the final
     * recovery pass when the wrapper excluded every candidate
     * @return false to abort the selection
     */
    private boolean selectLastHop(TunnelPoolSettings settings, int length, SelectionParams params,
                                  SessionKey randomKey, Set<Hash> lastHopExclude, Set<Hash> rawExclude,
                                  ArraySet<Hash> matches) {
        if (params.hiddenInbound) {
            // IB closest hop
            if (log.shouldInfo()) {
                log.info("Selecting fast/non-failing peer for (hidden) closest Inbound..." +
                         (lastHopExclude.size() > 0 ? "\n* Excluding: " + formatExcludedPeers(lastHopExclude) : ""));
            }
            if (ctx.getBooleanProperty(PROP_LEGACY_SELECTION)) {
                ctx.profileOrganizer().selectActiveNotFailingPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
                }
            } else {
                // Priority: HighCap > Fast > Active > NotFailing
                ctx.profileOrganizer().selectHighCapacityPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
                }
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectActiveNotFailingPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
                }
                if (matches.isEmpty()) {
                    // Fallback to any not-failing peer if active not available
                    if (log.shouldDebug()) {
                        log.debug("No active peers found, falling back to any non-failing peers");
                    }
                    ctx.profileOrganizer().selectNotFailingPeers(1, lastHopExclude, matches, false, params.ipRestriction, params.ipSet);
                }
                if (matches.isEmpty()) {
                    // Fallback to all peers as last resort
                    if (log.shouldDebug()) {
                        log.debug("No non-failing peers found, falling back to all peers");
                    }
                    ctx.profileOrganizer().selectAllNotFailingPeers(1, lastHopExclude, matches, false);
                }
            }
            if (matches.isEmpty()) {
                // Emergency: try all-not-failing before giving up
                if (log.shouldDebug()) {
                    log.debug("No peers found after standard fallbacks -> Attempting emergency all-peers fallback");
                }
                ctx.profileOrganizer().selectAllNotFailingPeers(1, lastHopExclude, matches, false);
            }
            // Falling through to the shared raw-exclusion recovery below.
        } else if (params.hiddenOutbound) {
            // OBEP
            // check for hidden and outbound, and the paired (inbound) tunnel is zero-hop
            // if so, we need the OBEP to be connected to us, so we get the build reply back
            // This should be rare except at startup
            TunnelManagerFacade tmf = ctx.tunnelManager();
            TunnelPool tp = tmf.getInboundPool(settings.getDestination());
            boolean pickFurthest;
            if (tp != null) {
                pickFurthest = true;
                TunnelPoolSettings tps = tp.getSettings();
                if (!isZeroHopSettings(tps)) {
                    List<TunnelInfo> tunnels = tp.listTunnels();
                    if (!tunnels.isEmpty()) {
                        pickFurthest = !hasTunnelLongerThanOne(tp);
                    } else {
                        // no tunnels in the paired tunnel pool
                        // BuildRequester will be using exploratory
                        tp = tmf.getInboundExploratoryPool();
                        tps = tp.getSettings();
                        pickFurthest = isZeroHopSettings(tps) || !hasTunnelLongerThanOne(tp);
                    }
                }
            } else {pickFurthest = false;} // shouldn't happen
            if (pickFurthest) {
                if (log.shouldInfo()) {
                    log.info("Selecting non-failing peer for OutboundEndpoint... " + formatExcludedPeers(lastHopExclude));
                }
                if (ctx.getBooleanProperty(PROP_LEGACY_SELECTION)) {
                    ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
                } else {
                    // Priority: HighCap > Fast > Active > NotFailing
                    ctx.profileOrganizer().selectHighCapacityPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
                    if (matches.isEmpty()) {
                        ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
                    }
                    if (matches.isEmpty()) {
                        ctx.profileOrganizer().selectActiveNotFailingPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
                    }
                    if (matches.isEmpty()) {
                        // Fallback to non-failing peers
                        ctx.profileOrganizer().selectNotFailingPeers(1, lastHopExclude, matches, false, params.ipRestriction, params.ipSet);
                        if (matches.isEmpty() && log.shouldDebug()) {
                            log.debug("No active peers found for OutboundEndpoint, falling back to all peers");
                        }
                    }
                    if (matches.isEmpty()) {
                        // Final fallback to all peers
                        ctx.profileOrganizer().selectAllNotFailingPeers(1, lastHopExclude, matches, false);
                    }
                }
                if (!matches.isEmpty()) {
                    ctx.commSystem().exemptIncoming(matches.get(0));
                }
            } else {
                // Same tier ladder as the pickFurthest path; a single Fast pass
                // left hidden-outbound endpoint selection with no recovery at all.
                ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectHighCapacityPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
                }
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectActiveNotFailingPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
                }
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectAllNotFailingPeers(1, lastHopExclude, matches, false);
                }
            }
        } else {
            // OBEP: prefer HighCapacity (includes floodfill/capable peers) for
            // reliable last-hop message delivery.  Floodfill peers have proven
            // NetDB lookup ability and high bandwidth, giving the best chance
            // that the tunnel's last hop can deliver the SYN to the destination's
            // inbound gateway.  HighCapacity already excludes slow L/M tiers
            // which is where BAD floodfills cluster, so we get GOOD/OK floodfills
            // without duplicating FloodfillPeerSelector's full classification.
            // Fall back to connected peers for fast build latency, then standard tiers.
            ctx.profileOrganizer().selectHighCapacityPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
            if (matches.isEmpty()) {
                ctx.profileOrganizer().selectActiveNotFailingPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
            }
            if (matches.isEmpty()) {
                ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
            }
            if (matches.isEmpty()) {
                ctx.profileOrganizer().selectNotFailingPeers(1, lastHopExclude, matches, false, 0, null);
            }
            if (matches.isEmpty()) {
                ctx.profileOrganizer().selectAllNotFailingPeers(1, lastHopExclude, matches, false);
            }
        }
        if (matches.isEmpty()) {
            // The closest-hop / OBEP wrapper may have excluded every candidate
            // (a busy router excludes most of the fast tier).  Retry against the
            // raw set before giving up: an endpoint-less tunnel cannot build at
            // all, so role exclusions are worth relaxing here alone.
            //
            // Only the role wrapper is relaxed. The IP restriction and set are
            // still honoured, because they express a hard network policy
            // (no US peers, say) rather than a preference about this hop's role.
            if (log.shouldWarn()) {
                log.warn("CPS no endpoint peer under role exclusions -> retrying against raw exclusions");
            }
            ctx.profileOrganizer().selectAllNotFailingPeers(1, rawExclude, matches, false,
                    params.ipRestriction, params.ipSet);
        }
        if (matches.isEmpty()) {
            if (log.shouldWarn()) {
                log.warn("CPS no endpoint peer found after all fallbacks -> aborting selection");
            }
            return false;
        }
        return true;
    }

    /** Select the middle hop(s) of a tunnel longer than 2 hops (subtiers 2-3, quality-ordered). */
    private void selectMiddleHops(int length, SelectionParams params, SessionKey randomKey,
                                  SelectionExclusions ex, ArraySet<Hash> matches, List<Hash> rv) {
        // middle hop(s)
        // group 2 or 3
        if (log.shouldInfo()) {
            log.info("Selecting middle hop peers (Client style)..." +
                     (ex.exclude.size() > 0 ? "\n* Excluding: " + formatExcludedPeers(ex.exclude) : ""));
        }
        int middleCount = length - 2;

        if (params.useHighCapPrimary) {
            ctx.profileOrganizer().selectHighCapacityPeers(middleCount, ex.exclude, matches, params.ipRestriction, params.ipSet);
            if (matches.size() < middleCount) {
                ctx.profileOrganizer().selectFastPeers(middleCount - matches.size(), ex.exclude, matches, params.ipRestriction, params.ipSet);
            }
            if (matches.size() < middleCount) {
                ctx.profileOrganizer().selectFastPeers(middleCount - matches.size(), ex.exclude, matches, 0, null);
            }
        } else {
            ctx.profileOrganizer().selectFastPeers(middleCount, ex.exclude, matches, params.ipRestriction, params.ipSet);
            // IP-restricted pass may not find enough; escalate to
            // unrestricted pass below.
            if (matches.size() < middleCount) {
                ctx.profileOrganizer().selectFastPeers(middleCount - matches.size(), ex.exclude, matches, 0, null);
            }
            if (matches.size() < middleCount) {
                ctx.profileOrganizer().selectNotFailingPeers(middleCount - matches.size(), ex.exclude, matches, false, 0, null);
            }
        }
        if (matches.size() < middleCount) {
            ctx.profileOrganizer().selectHighBandwidthPeers(middleCount - matches.size(), ex.exclude, matches, false, 0, null);
        }
        if (matches.size() < middleCount && ctx.getBooleanProperty(PROP_LEGACY_SELECTION)) {
            ctx.profileOrganizer().selectFastPeers(middleCount, ex.exclude, matches, 0, null);
        } else if (matches.size() < middleCount) {
            // Priority: HighCap > Active > NotFailing > AllNotFailing
            int needed = middleCount - matches.size();
            ArraySet<Hash> fallback = new ArraySet<>(needed);
            ctx.profileOrganizer().selectHighCapacityPeers(needed, ex.exclude, fallback, 0, null);
            fallback.remove(ctx.routerHash());
            if (!fallback.isEmpty()) {
                matches.addAll(fallback);
            }
        }
        if (matches.size() < middleCount) {
            int needed = middleCount - matches.size();
            ArraySet<Hash> fallback = new ArraySet<>(needed);
            ctx.profileOrganizer().selectActiveNotFailingPeers(needed, ex.exclude, fallback, 0, null);
            fallback.remove(ctx.routerHash());
            if (!fallback.isEmpty()) {
                matches.addAll(fallback);
            }
        }
        if (matches.size() < middleCount) {
            int needed = middleCount - matches.size();
            ArraySet<Hash> fallback = new ArraySet<>(needed);
            ctx.profileOrganizer().selectNotFailingPeers(needed, ex.exclude, fallback, false, 0, null);
            fallback.remove(ctx.routerHash());
            if (!fallback.isEmpty()) {
                matches.addAll(fallback);
            }
        }
        if (matches.size() < middleCount) {
            int needed = middleCount - matches.size();
            ArraySet<Hash> fallback = new ArraySet<>(needed);
            ctx.profileOrganizer().selectAllNotFailingPeers(needed, ex.exclude, fallback, false);
            fallback.remove(ctx.routerHash());
            if (!fallback.isEmpty()) {
                matches.addAll(fallback);
            }
        }
        matches.remove(ctx.routerHash());
        if (matches.size() > 1) {
            List<Hash> ordered = new ArrayList<>(matches);
            orderPeers(ordered, randomKey);
            rv.addAll(ordered);
        } else {
            rv.addAll(matches);
        }
        ex.exclude.addAll(matches);
        matches.clear();
    }

    /** Select the first hop (IBGW for inbound, closest outbound otherwise) with quality loop and pre-connect. */
    private void selectFirstHop(int length, SelectionParams params, SessionKey randomKey,
                                SelectionExclusions ex, ArraySet<Hash> matches) {
        // IBGW or OB first hop
        Set<Hash> exclude = ex.exclude;
        if (params.isInbound) {
            exclude = new IBGWExcluder(exclude);
            if (log.shouldInfo()) {
                log.info("Selecting InboundGateway..." +
                         (exclude.size() > 0 ? "\n* Excluding: " + formatExcludedPeers(exclude) : ""));
            }
        } else {
            if (params.checkClosestHop) {
                exclude = getClosestHopExclude(false, exclude);
            }
            if (log.shouldInfo()) {
                log.info("Selecting closest Outbound..." +
                         (exclude.size() > 0 ? "\n* Excluding: " + formatExcludedPeers(exclude) : ""));
            }
        }
        if (log.shouldInfo()) {
            log.info("Selecting first hop for " + (params.isInbound ? "Inbound" : "Outbound") + "...");
        }
        // Prefer vetted HighCap/Fast peers first — they've been tested
        // and are more reliable for tunnel builds than random connected peers.
        //
        // A batch rather than one peer: the quality loop below can only iterate
        // while `matches` is non-empty, so fetching exactly one candidate means
        // one attempt per refill and the 16-attempt budget goes largely unspent.
        // One tier scan then serves several attempts. Only the accepted peer
        // survives — see trimToAcceptedFirstHop — so the extra candidates never
        // reach the tunnel.
        //
        // When shouldWidenBothTiers says so, the batch is completed from the
        // other tier rather than left short: Fast is speed-ranked and
        // HighCapacity is proven-reliability, and neither ranking is trustworthy
        // enough when success is low or the Fast tier is small to justify
        // choosing between them.
        if (matches.isEmpty()) {
            boolean widen = shouldWidenBothTiers(params.buildSuccess,
                                              ctx.profileOrganizer().getFastPeerCount());
            if (params.useHighCapPrimary) {
                ctx.profileOrganizer().selectHighCapacityPeers(FIRST_HOP_CANDIDATES, exclude, matches, params.ipRestriction, params.ipSet);
                if (widen) {
                    topUpFromFastTier(params, exclude, matches);
                }
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectNotFailingPeers(FIRST_HOP_CANDIDATES, exclude, matches, false, 0, null);
                }
            } else {
                ctx.profileOrganizer().selectFastPeers(FIRST_HOP_CANDIDATES, exclude, matches, params.ipRestriction, params.ipSet);
                if (widen) {
                    topUpFromHighCapacityTier(params, exclude, matches);
                }
            }
        }
        // Fallback to connected peers. KeepAlive job maintains active peer count
        // at all uptimes, so no startup leniency needed.
        if (matches.isEmpty()) {
            ctx.profileOrganizer().selectActiveNotFailingPeers(FIRST_HOP_CANDIDATES, exclude, matches, 0, null);
        }
        if (matches.isEmpty()) {
            ctx.profileOrganizer().selectNotFailingPeers(1, exclude, matches, false, 0, null);
        }
        if (matches.isEmpty()) {
            ctx.profileOrganizer().selectAllNotFailingPeers(1, exclude, matches, false);
        }
        // Soft fallback: when all standard tiers fail, try peers that were
        // excluded ONLY for "no-signal" but have proven track records
        // (acceptance ratio > 50%, have been tested before).  These peers
        // are capable but simply haven't been contacted recently — better
        // than failing the build entirely.
        boolean softInStartup = isStartupGracePeriod(ctx);
        if (matches.isEmpty() && !softInStartup) {
            Set<Hash> softExclude = buildSoftFallbackExclude(exclude, ex);
            if (softExclude.size() < exclude.size()) {
                ctx.profileOrganizer().selectNotFailingPeers(1, softExclude, matches, false, 0, null);
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectAllNotFailingPeers(1, softExclude, matches, false);
                }
                if (!matches.isEmpty() && log.shouldInfo()) {
                    log.info("Soft fallback: found peer bypassing no-signal exclusion: " +
                             matches.get(0).toBase64().substring(0, 6));
                }
            }
        }
        // First-hop quality-loop bookkeeping, hoisted so the trim below can see the
        // outcome whether or not the loop ran. qualityAttempts counts the loop's
        // attempts across both the batch and any refills.
        int qualityAttempts = 0;
        Hash acceptedFirstHop = null;
        int acceptedTier = -1;
        int acceptedAttempt = -1;
        // Post-selection first-hop quality check.
        // Hard-fail gates: first-hop-failing peers, stale peers —
        // always reject regardless of startup state. Peers that
        // recently failed as first-hops are unlikely to succeed
        // again, and stale peers (no contact for hours) are likely
        // offline. During startup, still prefer established/connecting
        // peers first to avoid wasting build attempts on peers with
        // no transport session; only fall back to any selectable peer
        // when connected candidates are exhausted.
        if (!matches.isEmpty()) {
            // First-hop quality: prefer connected/established peers. Without this the
            // selector picks peers that look fast on paper but cannot actually receive
            // the build, and each rejection burns an attempt. Sixteen attempts, since a
            // single bad candidate otherwise costs the whole slot.
            int refills = 0;
            boolean inStartup = isStartupGracePeriod(ctx);
            // When very few candidates remain, start at tier 1 (accept
            // connecting) rather than tier 0 (accept any) to still
            // prefer peers with an active transport session.
            // Below CONNECTING_PREF_BUILD_SUCCESS (60%) also start at tier 1,
            // widening the acceptable pool faster than the attempt ladder
            // would reach it on its own.
            int tier = (matches.size() < 3
                      || params.buildSuccess < CONNECTING_PREF_BUILD_SUCCESS) ? 1 : 0;
            while (qualityAttempts < 16 && !matches.isEmpty()) {
                qualityAttempts++;
                tier = firstHopQualityTier(qualityAttempts, inStartup, tier);
                Hash firstHop = matches.iterator().next();
                // Always exclude peers that recently failed as first hop.
                // The 5-min cooldown in TunnelPeerSelector already handles
                // transient failures; this avoids permanently re-selecting
                // peers with persistent problems.
                if (isFirstHopFailing(ctx, firstHop)) {
                    if (log.shouldInfo()) {
                        log.info("First hop " + firstHop.toBase64().substring(0,6) +
                                 " previously failed as first hop, retrying...");
                    }
                    matches.remove(firstHop);
                    // Refill: re-select a replacement so the slot isn't wasted
                    if (refills < 3 && matches.isEmpty()) {
                        ex.exclude.add(firstHop);
                        refillFirstHop(params, randomKey, ex, matches, inStartup);
                        refills++;
                    }
                    continue;
                }
                // Near-collapsed pools keep shared cooldowns out of the base
                // exclude set (see buildExclusions) — enforce them here,
                // first hop only, so middle/last hops can still be filled.
                if (ex.firstHopCooldowns != null && ex.firstHopCooldowns.contains(firstHop)) {
                    if (log.shouldInfo()) {
                        log.info("First hop " + firstHop.toBase64().substring(0,6) +
                                 " is on shared selection cooldown, retrying...");
                    }
                    matches.remove(firstHop);
                    if (refills < 3 && matches.isEmpty()) {
                        ex.exclude.add(firstHop);
                        refillFirstHop(params, randomKey, ex, matches, inStartup);
                        refills++;
                    }
                    continue;
                }
                if (isStalePeer(ctx, firstHop, params.buildSuccess)) {
                    if (log.shouldInfo()) {
                        log.info("First hop " + firstHop.toBase64().substring(0,6) +
                                 " is stale (no contact >4hrs), retrying selection...");
                    }
                    matches.remove(firstHop);
                    if (refills < 3 && matches.isEmpty()) {
                        ex.exclude.add(firstHop);
                        refillFirstHop(params, randomKey, ex, matches, inStartup);
                        refills++;
                    }
                    continue;
                }
                if (tier <= 1 && !ctx.commSystem().isEstablished(firstHop) &&
                    !ctx.commSystem().isConnecting(firstHop)) {
                    matches.remove(firstHop);
                    if (refills < 3 && matches.isEmpty()) {
                        ex.exclude.add(firstHop);
                        refillFirstHop(params, randomKey, ex, matches, inStartup);
                        refills++;
                    }
                    continue;
                }
                // Accepted: this candidate cleared every gate at the current
                // tier.  Recorded so the tier that actually produced a gateway is
                // observable — a tier-2 accept has no transport-session
                // requirement, so an unreachable gateway is only explicable
                // after the fact, and the ladder walked to tier 2 on its own.
                acceptedFirstHop = firstHop;
                acceptedTier = tier;
                acceptedAttempt = qualityAttempts;
                break;
            }
            logAcceptedFirstHopTier(ctx, acceptedFirstHop, acceptedTier, acceptedAttempt,
                                    qualityAttempts, matches.size(), params.buildSuccess);
        }
        // Keep only the accepted candidate. The batch is an input to the quality loop, not a
        // set of hops: every caller does rv.addAll(matches), so leaving the rejects in place
        // would splice 2-3 unvetted peers into the tunnel path. The accepted peer is first by
        // construction, since the loop takes matches.iterator().next() each attempt.
        trimToAcceptedFirstHop(matches, acceptedFirstHop);
        // Fallback: if the quality loop exhausted every candidate without finding a suitable
        // peer, accept whatever remains rather than returning empty — one attempt with a
        // mediocre peer beats zero build attempts per cycle.
        if (matches.isEmpty() && qualityAttempts >= 16) {
            if (log.shouldDebug()) {
                log.debug("First-hop quality loop exhausted " + qualityAttempts +
                          " candidates without a match; build will be attempted " +
                          "with a degraded candidate if available");
            }
        }
        // preConnectTo: warm up the transport session so the TBR delivery
        // has a better chance of reaching the first hop.  Only call
        // preConnectTo() when the build timeout is too short to survive
        // the ~8.5s SSU2 handshake, the peer hasn't been connected
        // recently, and the pre-connect feature is enabled.
        // This eliminates ~8.5s of avoidable latency when the
        // adaptive timeout is sufficient for delivery.
        if (!matches.isEmpty()) {
            Hash candidate = matches.iterator().next();
            if (shouldPreConnect(ctx, candidate)) {
                preConnectTo(ctx, candidate);
                _lastPreConnect.put(candidate, ctx.clock().now());
            }
        }
        // Shortfall fallback below reuses the (wrapped) exclude
        ex.exclude = exclude;
    }

    /**
     * Reduce a first-hop candidate batch to the single peer the quality loop accepted.
     *
     * <p>When the loop accepted a candidate it is the one to keep, and it is the head of the set
     * because every attempt takes {@code iterator().next()}. When nothing was accepted the loop
     * has already ejected everything it vetted and rejected, so whatever remains was never
     * cleared — kept deliberately rather than discarded, since returning empty costs the cycle
     * a build outright.
     *
     * <p>No side effects beyond the set, so unit tests exercise it directly.
     *
     * @param matches the candidate batch, modified to hold at most the accepted peer
     * @param accepted the peer the quality loop accepted, or null if none was
     * @since 0.9.71+
     */
    static void trimToAcceptedFirstHop(ArraySet<Hash> matches, Hash accepted) {
        if (matches.size() <= 1) {
            return;
        }
        Hash keep = accepted;
        if (keep == null && !matches.isEmpty()) {
            keep = matches.get(0);
        }
        for (Hash candidate : new ArrayList<>(matches)) {
            if (!candidate.equals(keep)) {
                matches.remove(candidate);
            }
        }
    }

    /**
     * Whether the first hop should draw candidates from both the Fast and HighCapacity tiers.
     *
     * <p>True when the network is struggling, or when the Fast tier is too small to fill the
     * candidate batch on its own. Either way, taking from one tier alone needlessly shrinks the
     * pool the quality loop chooses from.
     *
     * <p>Pure decision — no context access, safe for unit tests.
     *
     * @param buildSuccess the build success ratio in [0.0, 1.0]
     * @param fastPeers the current Fast-tier size
     * @return whether to take candidates from both tiers
     * @since 0.9.71+
     */
    static boolean shouldWidenBothTiers(double buildSuccess, int fastPeers) {
        // NaN means no data yet, which is the startup case: treat it as struggling, matching
        // how every other gate here reads an unknown ratio.
        if (Double.isNaN(buildSuccess) || buildSuccess < WIDEN_BUILD_SUCCESS) {
            return true;
        }
        return fastPeers < WIDEN_FAST_PEERS;
    }

    /**
     * Add Fast-tier candidates until the batch is full, used when the first hop is widening
     * past the HighCapacity tier it started from.
     *
     * <p>Requests only the shortfall so the batch stays at
     * {@link #FIRST_HOP_CANDIDATES} in total rather than doubling.
     */
    private void topUpFromFastTier(SelectionParams params, Set<Hash> exclude, ArraySet<Hash> matches) {
        int need = FIRST_HOP_CANDIDATES - matches.size();
        if (need > 0) {
            ctx.profileOrganizer().selectFastPeers(need, exclude, matches, params.ipRestriction, params.ipSet);
        }
    }

    /** Add HighCapacity-tier candidates until the batch is full. See {@link #topUpFromFastTier}. */
    private void topUpFromHighCapacityTier(SelectionParams params, Set<Hash> exclude, ArraySet<Hash> matches) {
        int need = FIRST_HOP_CANDIDATES - matches.size();
        if (need > 0) {
            ctx.profileOrganizer().selectHighCapacityPeers(need, exclude, matches, params.ipRestriction, params.ipSet);
        }
    }

    /**
     * Refill the first-hop candidate after a quality-loop ejection.
     * Tries the same tier cascade as selectFirstHop (HighCap → Fast →
     * Active → NotFailing) with the ejected peer excluded, so the slot
     * isn't wasted.  At most 3 refills per build cycle.
     *
     * @param params selection parameters
     * @param randomKey random key for Fast tier sub-tiering
     * @param ex exclusion set (ejected peer added by caller)
     * @param matches singleton set to populate with replacement
     * @param inStartup true if still in startup grace period
     * @since 0.9.71+
     */
    private void refillFirstHop(SelectionParams params, SessionKey randomKey,
                                SelectionExclusions ex, ArraySet<Hash> matches,
                                boolean inStartup) {
        if (matches.isEmpty()) {
            if (params.useHighCapPrimary) {
                ctx.profileOrganizer().selectHighCapacityPeers(1, ex.exclude, matches, params.ipRestriction, params.ipSet);
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectNotFailingPeers(1, ex.exclude, matches, false, 0, null);
                }
            } else {
                ctx.profileOrganizer().selectFastPeers(1, ex.exclude, matches, params.ipRestriction, params.ipSet);
            }
        }
        if (matches.isEmpty()) {
            ctx.profileOrganizer().selectActiveNotFailingPeers(1, ex.exclude, matches, 0, null);
        }
        if (matches.isEmpty()) {
            ctx.profileOrganizer().selectNotFailingPeers(1, ex.exclude, matches, false, 0, null);
        }
    }

    /**
     * Whether the router is still in the startup grace period (first
     * {@code STARTUP_GRACE_MS} ms of uptime). The startup grace
     * relaxes the number of quality attempts (fewer candidates
     * early on) but does not skip hard-fail gates: first-hop-failing
     * peers, stale peers, and the established/connecting preference
     * are still enforced to avoid selecting unreliable tunnel targets.
     * <p>
     * Pure decision — no side effects.
     *
     * @param ctx the router context
     * @return whether the router is within the startup grace period
     * @since 0.9.71+
     */
    static boolean isStartupGracePeriod(RouterContext ctx) {
        return ctx.router() != null && ctx.router().getUptime() < STARTUP_GRACE_MS;
    }

    /**
     * Determines whether preConnectTo() should be called for a first-hop
     * candidate.  Returns false (skip pre-connect) when the build timeout
     * is sufficient to survive the ~8.5s SSU2 handshake, the peer was
     * recently connected, or the feature is disabled.  Returns true when
     * pre-connect is needed to ensure reliable tunnel delivery.
     * <p>
     * Approach 1: If the request timeout exceeds {@code 15s}, the
     * message survives the handshake, so skip pre-connect.
     * Approach 3: Property toggle allows runtime disabling.
     * Approach 4: Recently connected peers don't need pre-connect again.
     * <p>
     * The timeout is read through {@link BuildRequestor#getRequestTimeout} so it
     * reflects the budget the builder actually runs with, including runtime
     * tuning.  Reading {@code ctx.getProperty} here instead saw neither the tuned
     * value nor a configured one: with the property unset its own default equalled
     * the threshold, so the comparison was always true and first-hop pre-connect
     * never ran on a default install.
     * <p>
     * Pure decision — no side effects.
     *
     * @param ctx the router context
     * @param peer the first-hop candidate
     * @return whether preConnectTo() should be called for this peer
     * @since 0.9.71+
     */
    static boolean shouldPreConnect(RouterContext ctx, Hash peer) {
        // Approach 3: property toggle
        if (!Boolean.parseBoolean(ctx.getProperty(PROP_PRECONNECT_OPTIMIZE, Boolean.toString(PROP_PRECONNECT_OPTIMIZE_DEFAULT)))) return false;
        // Approach 4: recently connected peers don't need pre-connect
        if (wasRecentlyConnected(ctx, peer)) return false;
        // The condition that actually matters: do we have a session with this
        // peer?  Without one the build request cannot leave until a handshake
        // completes, so the build's whole reply budget is spent waiting.
        //
        // This deliberately replaces a timeout test.  The old rule inferred
        // "the handshake will fit" from requestTimeout > 15s, on the assumption
        // that a ~8.5s SSU2 handshake always completes inside a larger budget.
        // That assumption does not hold: with adaptive timeouts tuned to 17s and
        // 22s, 71% of builds skipped pre-connect entirely, and the builds that
        // then expired had a first hop with no session 22-33% of the time.  A
        // timeout is a proxy for a fact we can read directly, and reading the
        // fact is what fixes it.
        CommSystemFacade commSystem = ctx.commSystem();
        if (commSystem.isEstablished(peer) || commSystem.isConnecting(peer)) return false;
        return true;
    }

    /**
     * Check whether the peer was connected within the cooldown window.
     *
     * @param ctx the router context
     * @param peer the peer to check
     * @return true if the peer was established or connecting recently
     * @since 0.9.71+
     */
    private static boolean wasRecentlyConnected(RouterContext ctx, Hash peer) {
        Long lastConnected = _lastPreConnect.get(peer);
        if (lastConnected == null) return false;
        return ctx.clock().now() - lastConnected < PRECONNECT_COOLDOWN_MS;
    }

    /** Records that preConnectTo was called for the peer at the given time. */
    static void recordPreConnect(Hash peer, long time) {
        _lastPreConnect.put(peer, time);
    }

    /** Clears the pre-connect history (for testing). */
    static void clearPreConnectHistory() {
        _lastPreConnect.clear();
    }

    /** Tracks the last time preConnectTo was successfully called per peer. */
    private static final ConcurrentHashMap<Hash, Long> _lastPreConnect = new ConcurrentHashMap<>();

    /**
     * First-hop quality tier for the current attempt: 0 = prefer
     * established peers, 1 = also accept connecting peers, 2 = accept any
     * peer that passed the tier filters.  Escalates with attempts; during
     * the startup grace period the tier never changes.  Note the quirk:
     * attempts 4-5 downgrade a tier-2 selection to 1 (preserved verbatim).
     * <p>
     * Pure decision — no side effects.
     *
     * @param attempts number of quality-check attempts already made
     * @param inStartup whether the router is in the startup grace period
     * @param currentTier the tier before this attempt
     * @return the tier for this attempt
     * @since 0.9.71+
     */
    /**
     * Log the quality tier that actually produced this build's first hop.
     *
     * <p>The first-hop quality ladder relaxes its reachability requirement as
     * attempts accumulate: tiers 0 and 1 require the peer to be established or
     * connecting, tier 2 requires neither.  A gateway taken at tier 2 is
     * therefore one we have no transport session with, and cannot receive a
     * build request — which is indistinguishable, from the outside, from a
     * peer that went silent.  This line is the only way to tell those apart
     * after the fact.
     *
     * <p>Rate limited to one line per {@link #TIER_LOG_INTERVAL_MS} per tier so
     * a busy build loop cannot turn the diagnosis into the noise it is meant
     * to explain; the counters accumulate regardless and are reported in the
     * same line.  The limiter uses {@code compute} rather than
     * {@code replace(k, old, new)}: {@code replace} is a no-op returning false
     * when the key is absent, so the first log for each tier would never
     * happen and the key would never be inserted.
     *
     * @param ctx the router context
     * @param firstHop the accepted gateway, or null if none was accepted
     * @param tier the tier it was accepted at, or -1
     * @param attempt the attempt number it was accepted on, or -1
     * @param attempts total quality attempts made
     * @param remaining candidates left in the set
     * @since 0.9.71+
     */
    private void logAcceptedFirstHopTier(RouterContext ctx, Hash firstHop, int tier,
                                         int attempt, int attempts, int remaining,
                                         double buildSuccess) {
        if (tier < 0) {return;}
        String key = "tier" + tier;
        _tierLogCount.merge(key, 1L, Long::sum);
        long now = ctx.clock().now();
        long[] logged = new long[1];
        _tierLogTime.compute(key, (k, last) -> {
            long prev = last == null ? 0L : last;
            if (now - prev < TIER_LOG_INTERVAL_MS) {return prev;}
            logged[0] = 1L;
            return now;
        });
        if (logged[0] == 0L) {return;}
        if (log.shouldInfo()) {
            ProfileOrganizer po = ctx.profileOrganizer();
            boolean established = ctx.commSystem().isEstablished(firstHop);
            boolean connecting = ctx.commSystem().isConnecting(firstHop);
            log.info("First hop accepted at tier " + tier + " after " + attempt + "/" + attempts +
                     " attempts (" + remaining + " left, " + _tierLogCount.get(key) + " since last log)" +
                     " established=" + established + " connecting=" + connecting +
                     " buildSuccess=" + String.format("%.2f", buildSuccess) +
                     " fastTier=" + po.getFastPeerCount() +
                     " highCap=" + po.getHighCapPeerCount() +
                     " sample=" + po.getEffectiveCandidateSample(ctx) +
                     (established || connecting ? "" : "  <-- NO transport session") +
                     " " + firstHop.toBase32().substring(0, 6));
        }
    }

    /**
     * Build first-hop quality tier.  Pure so the ladder is testable.
     *
     * <p>Tier 0 and 1 require an established or connecting peer; tier 2
     * requires neither.  The ladder reaches tier 2 after
     * {@link #CONNECTING_PREF_ATTEMPTS} rejections, which is the point where
     * an unreachable gateway stops being filtered out.
     *
     * @param attempts 1-based quality attempt count
     * @param inStartup whether the router is inside its startup grace period
     * @param currentTier the tier to start from
     * @return the tier to apply for this attempt
     * @since 0.9.71+
     */
    static int firstHopQualityTier(int attempts, boolean inStartup, int currentTier) {
        if (inStartup) return currentTier;
        if (attempts > CONNECTING_PREF_ATTEMPTS) return 2;
        if (attempts > ESTABLISHED_PREF_ATTEMPTS) return 1;
        return currentTier;
    }

    /**
     * Builds the soft-fallback exclusion set: a copy of the first-hop
     * exclusion set without no-signal-excluded peers that have proven track
     * records (tunnel acceptance > 50% and at least one successful test),
     * giving them a chance when all standard tiers fail.  No side effects.
     *
     * @param exclude the current first-hop exclusion set
     * @param ex the selection exclusions with the per-peer reason map
     * @return the reduced exclusion set
     * @since 0.9.71+
     */
    private Set<Hash> buildSoftFallbackExclude(Set<Hash> exclude, SelectionExclusions ex) {
        Set<Hash> softExclude = new HashSet<>(exclude);
        // Remove no-signal peers from the exclusion set to give them
        // a chance, but only if they have good historical metrics
        for (Hash h : new ArrayList<>(softExclude)) {
            String reason = ex.excluder._reasons.get(h);
            if ("no-signal".equals(reason)) {
                PeerProfile prof = ctx.profileOrganizer().getProfile(h);
                if (prof != null && prof.getTunnelAcceptanceRatio() > 0.5 &&
                    prof.getTunnelHistory().getLastTestedSuccessfully() > 0) {
                    softExclude.remove(h);
                }
            }
        }
        return softExclude;
    }

    /**
     * Whether the selection may use the progressive stress fallbacks and
     * the shortened-tunnel allowance: network stress (build success below
     * the attack threshold) or HighCap-primary mode, with at least one peer
     * already selected.  Pure decision — no side effects.
     *
     * @param buildSuccess current tunnel build success rate
     * @param useHighCapPrimary whether the selection prefers high-capacity peers
     * @param rvSize current number of selected peers
     * @return whether stress fallbacks may be attempted
     * @since 0.9.71+
     */
    static boolean canUseStressFallback(double buildSuccess, boolean useHighCapPrimary, int rvSize) {
        return (buildSuccess < ATTACK_THRESHOLD || useHighCapPrimary) && rvSize > 0;
    }

    /** Progressive fallbacks when the selected peers are short of the requested length. @return the final rv, or null to abort (returns empty list) */
    private List<Hash> applyShortfallFallbacks(TunnelPoolSettings settings, List<Hash> rv, int length,
                                               SelectionParams params, SelectionExclusions ex) {
        // not enough peers to build the requested size
        // client tunnels do not use overrides
        // Suppress warnings during startup
        long uptime = ctx.router() != null ? ctx.router().getUptime() : 0;
        if (log.shouldDebug() && uptime > STARTUP_WARNING_SUPPRESS_MS) {
            log.debug("Not enough peers to build requested " + length + " hop tunnel (" + rv.size() + " available)");
        }
        int min = minRequestedLength(settings);
        if (rv.size() >= min)
            return rv;
        if (rv.isEmpty()) {
            if (log.shouldWarn()) {
                log.warn("CPS no peers at all for " + settings.getDestinationNickname() +
                         " (" + (settings.isInbound() ? "in" : "out") + " " + length + "-hop)");
            }
            return Collections.emptyList();
        }

        // Fill the missing first-hop slot first.  It is the peer the build
        // request is sent to, so it must come from selectFirstHop() with its
        // role checks, not from a generic tier.  It sits at the end of rv, so
        // filling it before the middle hops preserves [endpoint .. first hop].
        if (ex.firstHop == null) {
            // selectFirstHop fills a candidate batch, so this cannot be capacity 1:
            // ArraySet is fixed-capacity and overflow throws. Trimmed to one by
            // selectFirstHop before it is used as a hop.
            ArraySet<Hash> matches = new ArraySet<Hash>(FIRST_HOP_CANDIDATES);
            selectFirstHop(length, params, settings.getRandomKey(), ex, matches);
            matches.remove(ctx.routerHash());
            if (!matches.isEmpty()) {
                ex.firstHop = matches.get(0);
                rv.add(ex.firstHop);
                ex.exclude.add(ex.firstHop);
            }
        }
        if (rv.size() < min)
            fillRemainingHops(rv, min, params, ex);

        // Still short: a shorter tunnel is acceptable when firewalled or under
        // stress; otherwise hold the configured minimum rather than silently
        // reduce anonymity on a healthy network.
        if (rv.size() < min) {
            if (params.hidden || canUseStressFallback(params.buildSuccess, params.useHighCapPrimary, rv.size())) {
                if (log.shouldDebug()) {
                    log.debug("Allowing shorter tunnel (" + rv.size() + " hops) instead of " + min + " minimum");
                }
            } else {
                if (log.shouldWarn()) {
                    log.warn("CPS not enough peers for " + settings.getDestinationNickname() +
                             " (" + (settings.isInbound() ? "in" : "out") + "): rv=" + rv.size() +
                             " min=" + min + " length=" + length);
                }
                return Collections.emptyList();
            }
        }
        return rv;
    }

    /**
     * Tops {@code rv} up to {@code min} hops from the tier ladder, inserting
     * new middle hops before the vetted first hop so rv keeps its
     * [endpoint .. first hop] order.
     *
     * <p>The ladder only ever widens on genuine scarcity: banlisted, ghost,
     * first-hop-failing and chronically failing peers are rejected by the tier
     * gates before a candidate is ever collected, so a wider tier is more
     * peers, not lower quality.  Unsuitable peers are therefore excluded from
     * selection rather than discovered afterwards.
     *
     * @param rv the partial selection, endpoint first and non-empty
     * @param min the minimum hop count to reach
     * @param params selection parameters (tier priority, stress level)
     * @param ex live exclusions and the vetted first hop
     * @return number of hops added
     * @since 0.9.71+
     */
    private int fillRemainingHops(List<Hash> rv, int min, SelectionParams params, SelectionExclusions ex) {
        int added = collectFromTier(rv, min, params, ex);
        if (rv.size() < min)
            added += collectActive(rv, min, ex);
        // Untested peers (no-signal) are not proven bad — they simply lack
        // connectivity evidence.  When proven peers run dry, relax and retry.
        if (rv.size() < min) {
            int unblocked = ex.excluder.relaxNoSignalExclusions();
            if (unblocked > 0 && log.shouldInfo()) {
                log.info("Relaxing no-signal exclusion: " + unblocked +
                         " untested peers now available -> retrying fallback selection");
            }
            added += collectFromTier(rv, min, params, ex);
            if (rv.size() < min)
                added += collectActive(rv, min, ex);
        }
        if (added > 0 && log.shouldDebug()) {
            log.debug("Shortfall ladder added " + added + " hops -> " + rv.size() + "/" + min);
        }
        return added;
    }

    /**
     * One cascading tier selection.  The ProfileOrganizer tiers fall through to
     * wider tiers internally (Fast -> HighCap -> NotFailing -> all peers), so a
     * single call with the right entry tier covers the whole ladder.
     *
     * @return number of hops added
     */
    private int collectFromTier(List<Hash> rv, int min, SelectionParams params, SelectionExclusions ex) {
        int need = min - rv.size();
        if (need <= 0)
            return 0;
        ArraySet<Hash> picked = new ArraySet<Hash>(need);
        boolean legacy = ctx.getBooleanProperty(PROP_LEGACY_SELECTION);
        // HighCap leads under stress or when the selection already prefers
        // high-capacity peers; Fast leads otherwise, as it does in selectPeers.
        if (!legacy && params.useHighCapPrimary)
            ctx.profileOrganizer().selectHighCapacityPeers(need, ex.exclude, picked, 0, null);
        else
            ctx.profileOrganizer().selectFastPeers(need, ex.exclude, picked, 0, null);
        picked.remove(ctx.routerHash());
        int at = firstHopIndex(rv, ex.firstHop);
        int added = insertNewPeers(rv, at, picked, need);
        if (added > 0)
            ex.exclude.addAll(rv.subList(at, at + added));
        return added;
    }

    /**
     * Pulls connected peers, which are not reachable through the profile tier
     * cascade at all: a peer with an established transport session but no
     * usable profile still builds fast.
     *
     * @return number of hops added
     */
    private int collectActive(List<Hash> rv, int min, SelectionExclusions ex) {
        int need = min - rv.size();
        if (need <= 0)
            return 0;
        ArraySet<Hash> picked = new ArraySet<Hash>(need);
        ctx.profileOrganizer().selectActiveNotFailingPeers(need, ex.exclude, picked, 0, null);
        picked.remove(ctx.routerHash());
        int at = firstHopIndex(rv, ex.firstHop);
        int added = insertNewPeers(rv, at, picked, need);
        if (added > 0)
            ex.exclude.addAll(rv.subList(at, at + added));
        return added;
    }

    /**
     * Index at which a missing middle hop must be inserted: just before the
     * vetted first hop, or at the end when there is none yet.
     */
    private static int firstHopIndex(List<Hash> rv, Hash firstHop) {
        if (firstHop == null)
            return rv.size();
        int i = rv.indexOf(firstHop);
        return i >= 0 ? i : rv.size();
    }

    /**
     * Adds up to {@code need} peers from {@code picked} that are not already in
     * {@code rv}, inserting them at {@code index} so the surrounding order is
     * untouched.  Mutates only {@code rv}.
     *
     * @param rv the selection to extend
     * @param index insert position, clamped to the list bounds
     * @param picked candidate peers, order preserved
     * @param need maximum number to insert
     * @return number of peers inserted
     * @since 0.9.71+
     */
    static int insertNewPeers(List<Hash> rv, int index, Collection<Hash> picked, int need) {
        if (picked == null || picked.isEmpty() || need <= 0)
            return 0;
        List<Hash> fresh = new ArrayList<Hash>(need);
        for (Hash h : picked) {
            if (fresh.size() >= need)
                break;
            if (h == null || rv.contains(h) || fresh.contains(h))
                continue;
            fresh.add(h);
        }
        if (fresh.isEmpty())
            return 0;
        int at = index < 0 ? 0 : index > rv.size() ? rv.size() : index;
        rv.addAll(at, fresh);
        return fresh.size();
    }

    /**
     * Smallest tunnel the pool will accept: the configured length minus any
     * negative variance, never below zero.  Pure decision — no side effects.
     *
     * @param settings pool settings
     * @return minimum hop count for this pool
     * @since 0.9.71+
     */
    static int minRequestedLength(TunnelPoolSettings settings) {
        int min = settings.getLength();
        int skew = settings.getLengthVariance();
        if (skew < 0) {min += skew;}
        return min > 0 ? min : 0;
    }

    /**
     * Insert self, ghost-filter, strategy post-processing, duplicate re-check,
     * and cooldowns.  Quality-sorts the non-self hops for inbound selections
     * only (see below).
     */
    List<Hash> finalizeSelection(TunnelPoolSettings settings, List<Hash> rv, boolean isInbound) {
        if (isInbound) {rv.add(0, ctx.routerHash());}
        else {rv.add(ctx.routerHash());}

        // Inbound keeps its quality preference ordering (rv[1] is the IBGW,
        // best-first is benign there).
        if (isInbound && rv.size() > 2) {
            List<Hash> nonSelf = new ArrayList<>(rv);
            nonSelf.remove(ctx.routerHash());
            if (nonSelf.size() > 1) {
                sortByPeerQuality(nonSelf, null);
                rv.clear();
                rv.add(ctx.routerHash());
                rv.addAll(nonSelf);
            }
        }

        // Same quality pre-filtering both directions: swap poorly performing
        // hops for proven responders, then drop anything still unreliable
        // when surplus hops remain. Outbound slot 0 (cfg.getPeer(1)) stays
        // the vetted first hop chosen by selectFirstHop(); re-sorting exiled
        // it to the OBEP and put the worst-ranked peer adjacent to us, which
        // is why the swap below replaces hops in place.
        List<Hash> nonSelf = new ArrayList<>(rv);
        nonSelf.remove(ctx.routerHash());
        if (!nonSelf.isEmpty()) {
            // Peers already active in this destination's pools: excluded from
            // swap candidates (a swap must not create a per-pool duplicate)
            Hash dest = settings.getDestination();
            Set<Hash> poolActive = new HashSet<>();
            if (dest != null) {
                TunnelManagerFacade tmf = ctx.tunnelManager();
                TunnelPool ibp = tmf.getInboundPool(dest);
                if (ibp != null) {poolActive.addAll(getPeersInPool(ctx, ibp));}
                TunnelPool obp = tmf.getOutboundPool(dest);
                if (obp != null) {poolActive.addAll(getPeersInPool(ctx, obp));}
            }
            // Outbound protects both endpoint slots: slot 0 is the vetted
            // transport send target, the OBEP slot the far-end delivery exit.
            int from = isInbound ? 0 : 1;
            int to = isInbound ? nonSelf.size() : nonSelf.size() - 1;
            int swaps = preferProven(nonSelf, from, to,
                                     _provenResponders, ctx.clock().now(),
                                     PROVEN_RESPONDER_WINDOW_MS,
                                     provenCandidates(poolActive).iterator());
            Set<Hash> unreliable = findUnreliable(nonSelf, ctx.clock().now());
            // Entry/exit role-correlation guard: a peer slated to hold both a
            // monitoring position here and the opposite one in another of our
            // tunnels is swapped above when proof allows, else dropped while
            // surplus hops remain
            unreliable.addAll(positionalConflicts(nonSelf, isInbound, dest, ctx.clock().now()));
            boolean dropped = dropUnreliable(nonSelf, unreliable, 2);
            if (swaps > 0 || dropped) {
                if (log.shouldInfo()) {
                    log.info("Quality pre-filter for " + settings.getDestinationNickname() +
                              " (" + (isInbound ? "in" : "out") + "): " + swaps +
                              " proven swap(s), " + (dropped ? 1 : 0) + " drop(s)");
                }
                rv.clear();
                if (isInbound) {
                    rv.add(ctx.routerHash());
                    rv.addAll(nonSelf);
                } else {
                    rv.addAll(nonSelf);
                    rv.add(ctx.routerHash());
                }
            }
        }

        // Filter out ghost peers and banned peers before returning
        rv = filterGhostPeers(rv);
        rv = filterBannedPeers(rv);

        // Strategy-specific post-processing
        if (rv.size() > 2) {
            String strategy = getStrategy();
            if (STRATEGY_RELIABILITY.equals(strategy)) {
                // Apply reliability filter on non-self peers
                List<Hash> filtered = new ArrayList<>(rv);
                filtered.remove(ctx.routerHash());
                Set<Hash> nonSelfSet = new HashSet<>(filtered);
                List<Hash> reliable = filterByReliability(nonSelfSet, null);
                if (!reliable.isEmpty()) {
                    rv.clear();
                    if (isInbound) {
                        rv.add(ctx.routerHash());
                        rv.addAll(reliable);
                    } else {
                        rv.addAll(reliable);
                        rv.add(ctx.routerHash());
                    }
                }
            } else if (STRATEGY_DIVERSITY.equals(strategy)) {
                // Diversity: skip ghost re-check (already filtered above)
            }
        }

        // Check for duplicate sequence and regenerate if needed
        if (rv.size() > 2) {
            int attempts = 0;
            int maxAttempts = 3;
            while (attempts < maxAttempts) {
                List<Hash> existing = new ArrayList<>(rv);
                existing.remove(ctx.routerHash());
                if (!isDuplicateSequence(settings, existing)) {break;}
                List<Hash> regenerated = regeneratePeers(settings, existing, attempts + 1);
                if (regenerated == null || regenerated.equals(existing)) {break;}
                // Rebuild with self in correct position
                rv.clear();
                if (isInbound) {
                    rv.add(ctx.routerHash());
                    rv.addAll(regenerated);
                } else {
                    rv.addAll(regenerated);
                    rv.add(ctx.routerHash());
                }
                attempts++;
            }
        }

        if (rv.size() > 1) {
            if (!checkTunnel(isInbound, false, rv)) {
                if (log.shouldWarn()) {
                    log.warn("CPS checkTunnel failed for " + settings.getDestinationNickname() +
                             " (" + (settings.isInbound() ? "in" : "out") + ") rv=" + formatPeerList(rv));
                }
                // No blanket client cooldown here: checkTunnel already blames
                // the specific peers of the failing edge via tunnelTimedOut(),
                // and penalizing every selected peer collapses the pool to
                // degraded tier choices on each retry. Only the peer adjacent
                // to us (IBGW for inbound, OBEP for outbound) gets shared
                // cooldown (60s) across ALL pools, since an address-family
                // mismatch with us is fundamental and won't change between
                // retries.
                long now = ctx.clock().now();
                int adjIdx = isInbound ? 1 : rv.size() - 2;
                if (adjIdx >= 0 && adjIdx < rv.size()) {
                    Hash adjPeer = rv.get(adjIdx);
                    if (!adjPeer.equals(ctx.routerHash())) {
                        TunnelPeerSelector._peerCooldowns.put(adjPeer, now);
                    }
                }
                rv = Collections.emptyList();
            }
        }
        if (isInbound && rv.size() > 1) {ctx.commSystem().exemptIncoming(rv.get(1));}
        return rv;
    }

    /** Immutable selection parameters computed once per selectPeers() call. */
    private static final class SelectionParams {
        final boolean isInbound;
        final double buildSuccess;
        final boolean useHighCapPrimary;
        final boolean checkClosestHop;
        final boolean hidden;
        final boolean hiddenInbound;
        final boolean hiddenOutbound;
        final int ipRestriction;
        final MaskedIPSet ipSet;
        SelectionParams(boolean isInbound, double buildSuccess, boolean useHighCapPrimary, boolean checkClosestHop,
                        boolean hidden, boolean hiddenInbound, boolean hiddenOutbound, int ipRestriction, MaskedIPSet ipSet) {
            this.isInbound = isInbound;
            this.buildSuccess = buildSuccess;
            this.useHighCapPrimary = useHighCapPrimary;
            this.checkClosestHop = checkClosestHop;
            this.hidden = hidden;
            this.hiddenInbound = hiddenInbound;
            this.hiddenOutbound = hiddenOutbound;
            this.ipRestriction = ipRestriction;
            this.ipSet = ipSet;
        }
    }

    /** Excluder + exclusion counts gathered once per selectPeers() call; exclude is wrapped by the hop helpers. */
    private static final class SelectionExclusions {
        final Excluder excluder;
        Set<Hash> exclude;
        /** The vetted first hop already in rv, or null when the slot is empty. */
        Hash firstHop;
        final int peerCooldownExcluded;
        final int firstHopFailCount;
        final Set<Hash> firstPeerExclusions;
        /** Fresh shared-cooldown peers, first-hop-only filter (null when cooldowns are in the base exclude set). */
        final Set<Hash> firstHopCooldowns;
        SelectionExclusions(Excluder excluder, Set<Hash> exclude,
                            int peerCooldownExcluded, int firstHopFailCount, Set<Hash> firstPeerExclusions,
                            Set<Hash> firstHopCooldowns) {
            this.excluder = excluder;
            this.exclude = exclude;
            this.peerCooldownExcluded = peerCooldownExcluded;
            this.firstHopFailCount = firstHopFailCount;
            this.firstPeerExclusions = firstPeerExclusions;
            this.firstHopCooldowns = firstHopCooldowns;
        }
    }
    /**
     * Filter candidates through the reliability gate: a peer must have an
     * adequate acceptance ratio and at least one recent activity or
     * connection signal to remain. The input order is preserved; ranking
     * is left to the caller's quality sort so each reliability signal
     * plays a single role.
     *
     * @param candidates peers to filter
     * @param exclude peers to exclude
     * @return list of peers passing the reliability gate, in input order
     */
    List<Hash> filterByReliability(Set<Hash> candidates, Set<Hash> exclude) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<Hash> result = new ArrayList<>();
        long now = ctx.clock().now();
        long tenMinutes = 10 * 60 * 1000L;
        long thirtyMinutes = 30 * 60 * 1000L;

        for (Hash peer : candidates) {
            if (exclude != null && exclude.contains(peer)) {
                continue;
            }
            PeerProfile profile = ctx.profileOrganizer().getProfile(peer);
            if (profile != null && isReliable(profile, now, tenMinutes, thirtyMinutes)) {
                result.add(peer);
            }
        }
        return result;
    }

    /**
     * Fresh proven responders, freshest first, filtered for basic
     * eligibility: not us, not banlisted, not on selection cooldown,
     * with a locally cached RouterInfo so a build can reach them, and
     * not already active in the destination's pools (a swap must never
     * introduce a per-pool duplicate).
     *
     * @param excludePeers peers to exclude because they are already active
     * in this destination's tunnels
     * @return eligible proven candidates for hop swaps, never null
     */
    private List<Hash> provenCandidates(Set<Hash> excludePeers) {
        long now = ctx.clock().now();
        List<Map.Entry<Hash, Long>> entries = new ArrayList<>(_provenResponders.entrySet());
        entries.removeIf(e -> !isProvenResponder(e.getValue(), now));
        entries.sort(Map.Entry.<Hash, Long>comparingByValue().reversed());
        List<Hash> rv = new ArrayList<>(entries.size());
        for (Map.Entry<Hash, Long> e : entries) {
            Hash h = e.getKey();
            if (h.equals(ctx.routerHash())) {continue;}
            if (excludePeers != null && excludePeers.contains(h)) {continue;}
            Banlist banlist = ctx.banlist();
            if (banlist != null && banlist.isBanlisted(h)) {continue;}
            Long cd = _peerCooldowns.get(h);
            if (cd != null && now - cd < PEER_SELECTION_COOLDOWN_MS) {continue;}
            if (ctx.netDb().lookupLocallyWithoutValidation(h) == null) {continue;}
            rv.add(h);
        }
        // Anti-concentration: randomize within the freshest few so repeated
        // swaps never funnel this destination's traffic through one popular
        // proven peer (a magnet hop is a monitoring target)
        int k = Math.min(4, rv.size());
        Collections.shuffle(rv.subList(0, k), ctx.random());
        return rv;
    }

    /**
     * Selected hops whose placement would give a single peer concurrent
     * entry and exit visibility across this destination's pools — the
     * pairing that enables traffic correlation. Two rules are checked:
     *
     * <ul>
     * <li>inbound selection: the chosen IBGW already serves as the OBEP
     * of one of our active outbound tunnels</li>
     * <li>outbound selection: the chosen OBEP already serves as the IBGW
     * of one of our active inbound tunnels</li>
     * </ul>
     *
     * @param nonSelf the selected non-self peers, gateway-first order
     * @param isInbound true when selecting for an inbound tunnel
     * @param dest the destination hash, or null
     * @param now current time from the router clock
     * @return conflicting peers, possibly empty
     */
    private Set<Hash> positionalConflicts(List<Hash> nonSelf, boolean isInbound,
                                          Hash dest, long now) {
        Set<Hash> rv = new HashSet<>(0);
        if (dest == null || nonSelf.isEmpty()) {return rv;}
        TunnelManagerFacade tmf = ctx.tunnelManager();
        Hash us = ctx.routerHash();
        Set<Hash> ibGateways = new HashSet<>(4);
        Set<Hash> obEndpoints = new HashSet<>(4);
        TunnelPool ibPool = tmf.getInboundPool(dest);
        if (ibPool != null) {
            for (TunnelInfo ti : ibPool.listTunnels()) {
                Hash gw = ti.getPeer(0);
                if (gw != null && !us.equals(gw)) {ibGateways.add(gw);}
            }
        }
        TunnelPool obPool = tmf.getOutboundPool(dest);
        if (obPool != null) {
            for (TunnelInfo ti : obPool.listTunnels()) {
                int last = ti.getLength() - 1;
                if (last >= 0) {
                    Hash ep = ti.getPeer(last);
                    if (ep != null && !us.equals(ep)) {obEndpoints.add(ep);}
                }
            }
        }
        if (isInbound) {
            Hash gw = nonSelf.get(0);
            if (obEndpoints.contains(gw)) {rv.add(gw);}
        } else {
            Hash ep = nonSelf.get(nonSelf.size() - 1);
            if (ibGateways.contains(ep)) {rv.add(ep);}
        }
        return rv;
    }

    /**
     * The selected hops that fail the reliability gate. Peers without a
     * profile are unknown rather than poor — they are never returned.
     *
     * @param hops the selected non-self peers
     * @param now current time from the router clock
     * @return the subset failing the reliability gate, never null
     */
    private Set<Hash> findUnreliable(List<Hash> hops, long now) {
        Set<Hash> rv = new HashSet<>(hops.size());
        long tenMinutes = 10 * 60 * 1000L;
        long thirtyMinutes = 30 * 60 * 1000L;
        for (Hash h : hops) {
            PeerProfile profile = ctx.profileOrganizer().getProfile(h);
            if (profile != null && !isReliable(profile, now, tenMinutes, thirtyMinutes)) {
                rv.add(h);
            }
        }
        return rv;
    }

    /**
     * Reliability gate: the acceptance ratio must be at least 0.3, and the
     * peer must show a recent tunnel-test success, recent activity, or an
     * established connection. Each signal is consulted exactly once.
     *
     * @param profile the peer profile (may be null)
     * @param now current time from the router clock
     * @param tenMinutes tunnel-test recency window in ms
     * @param thirtyMinutes activity recency window in ms
     * @return true if the peer passes the reliability gate
     */
    boolean isReliable(PeerProfile profile, long now, long tenMinutes, long thirtyMinutes) {
        if (profile == null) {return false;}
        if (profile.getTunnelAcceptanceRatio() < 0.3) {return false;}
        long lastTested = profile.getLastTestedSuccessfully();
        if (lastTested > 0 && now - lastTested < tenMinutes) {return true;}
        long lastHeardFrom = profile.getLastHeardFrom();
        long lastSendSuccessful = profile.getLastSendSuccessful();
        if ((lastHeardFrom > 0 && now - lastHeardFrom < thirtyMinutes) ||
            (lastSendSuccessful > 0 && now - lastSendSuccessful < thirtyMinutes)) {
            return true;
        }
        return ctx.commSystem().isEstablished(profile.getPeer());
    }

    /**
     * Sort peers by quality for tunnel building preference.
     * Higher quality peers (recently tested, active, connected) sort first.
     */
    private void sortByPeerQuality(List<Hash> peers, Set<Hash> exclude) {
        if (peers == null || peers.isEmpty()) {
            return;
        }
        long now = ctx.clock().now();
        long thirtyMinutes = 30 * 60 * 1000L;
        peers.sort(peerQualityComparator(exclude, now, thirtyMinutes));
    }

    /**
     * Build a comparator that orders peers by tunnel-building quality: excluded
     * peers last, then by acceptance ratio, activity recency, and tunnel-test latency.
     * <p>
     * Stage order is significant — each stage short-circuits the ones below it.
     *
     * @param exclude peers to deprioritize, or null
     * @param now current time from the router clock
     * @param thirtyMinutes activity window in ms
     * @since 0.9.70+
     */
    Comparator<Hash> peerQualityComparator(Set<Hash> exclude, long now, long thirtyMinutes) {
        return (p1, p2) -> compareQuality(p1, p2, exclude,
                                          ctx.profileOrganizer().getProfile(p1),
                                          ctx.profileOrganizer().getProfile(p2),
                                          now, thirtyMinutes);
    }

    /**
     * Full quality comparison cascade.  Stage order is significant — each
     * stage short-circuits the ones below it: excluded last, then proven
     * responders (recent successful participation), then acceptance ratio,
     * slow tunnel-test latency, activity recency, and latency.
     *
     * @param p1 first peer
     * @param p2 second peer
     * @param exclude peers to deprioritize, or null
     * @param prof1 first peer's profile, or null
     * @param prof2 second peer's profile, or null
     * @param now current time from the router clock
     * @param thirtyMinutes activity window in ms
     * @return negative, zero, or positive
     * @since 0.9.71+ (extracted from peerQualityComparator)
     */
    static int compareQuality(Hash p1, Hash p2, Set<Hash> exclude, PeerProfile prof1, PeerProfile prof2,
                               long now, long thirtyMinutes) {
        int c = compareExcluded(p1, p2, exclude);
        if (c != 0) {return c;}
        long t1 = _provenResponders.getOrDefault(p1, 0L);
        long t2 = _provenResponders.getOrDefault(p2, 0L);
        c = compareProven(t1, t2, now);
        if (c != 0) {return c;}
        c = compareReliability(prof1, prof2);
        if (c != 0) {return c;}
        float lat1 = prof1 != null ? prof1.getTunnelTestTimeAverage() : 0;
        float lat2 = prof2 != null ? prof2.getTunnelTestTimeAverage() : 0;
        c = compareAcceptance(prof1, prof2);
        if (c != 0) {return c;}
        c = compareSlowLatency(lat1, lat2);
        if (c != 0) {return c;}
        c = compareActivity(prof1, prof2, now, thirtyMinutes);
        if (c != 0) {return c;}
        c = compareLatency(lat1, lat2);
        if (c != 0) {return c;}
        // When both latencies are 0 (expired or never tested), prefer
        // peers with a test history over never-tested peers.
        return compareTestHistory(prof1, prof2);
    }

    /**
     * Compare floodfill reliability: GOOD > OK > UNKNOWN > BAD.
     * Unproven floodfills sort lower to reduce load on them until
     * their reliability is established.
     *
     * @param prof1 first peer's profile, or null
     * @param prof2 second peer's profile, or null
     * @return negative if p1 is more reliable, positive if p2 is more reliable, 0 if equal
     */
    public static int compareReliability(PeerProfile prof1, PeerProfile prof2) {
        FloodfillReliability r1 = prof1 != null ? prof1.getFloodfillReliability() : null;
        FloodfillReliability r2 = prof2 != null ? prof2.getFloodfillReliability() : null;
        if (r1 == null) r1 = FloodfillReliability.UNKNOWN;
        if (r2 == null) r2 = FloodfillReliability.UNKNOWN;
        // Higher ordinal = more reliable: BAD(0) < UNKNOWN(1) < OK(2) < GOOD(3)
        // Sort descending so GOOD sorts first
        return Integer.compare(r2.ordinal(), r1.ordinal());
    }

    /**
     * Excluded peers sort last; two excluded peers compare equal.
     *
     * @param p1 first peer
     * @param p2 second peer
     * @param exclude peers to deprioritize, or null
     * @return negative, zero, or positive
     * @since 0.9.71+ (extracted from peerQualityComparator)
     */
    static int compareExcluded(Hash p1, Hash p2, Set<Hash> exclude) {
        if (exclude == null) {return 0;}
        if (exclude.contains(p1)) {
            if (exclude.contains(p2)) {return 0;}
            return 1;
        }
        if (exclude.contains(p2)) {return -1;}
        return 0;
    }

    /**
     * Acceptance ratio tiers: good (&gt; 0.3) ranks above low (&lt; 0.3), which
     * ranks above dead (&lt;= 0).  Missing profiles default to 1.0.
     *
     * @param prof1 first peer's profile, or null
     * @param prof2 second peer's profile, or null
     * @return negative, zero, or positive
     * @since 0.9.71+ (extracted from peerQualityComparator)
     */
    static int compareAcceptance(PeerProfile prof1, PeerProfile prof2) {
        double ar1 = prof1 != null ? prof1.getTunnelAcceptanceRatio() : 1.0;
        double ar2 = prof2 != null ? prof2.getTunnelAcceptanceRatio() : 1.0;
        // Good (> 0.3) > Low (0 < r < 0.3) > Dead (<= 0): decide the dead
        // tier first so dead never ties with low.
        if (ar1 <= 0 && ar2 > 0) {return 1;}
        if (ar2 <= 0 && ar1 > 0) {return -1;}
        if (ar1 < 0.3 && ar2 >= 0.3) {return 1;}
        if (ar2 < 0.3 && ar1 >= 0.3) {return -1;}
        return 0;
    }

    /**
     * Peers with tunnel test latency over 15s sort last; when both are slow,
     * the less slow one sorts first.  0 latency means no data (unknown).
     *
     * @param lat1 first peer's tunnel test time average
     * @param lat2 second peer's tunnel test time average
     * @return negative, zero, or positive
     * @since 0.9.71+ (extracted from peerQualityComparator)
     */
    static int compareSlowLatency(float lat1, float lat2) {
        boolean slow1 = lat1 > 15_000;
        boolean slow2 = lat2 > 15_000;
        if (slow1 && !slow2) {return 1;}
        if (!slow1 && slow2) {return -1;}
        if (slow1 && slow2) {
            // Both slow — prefer the less slow one
            if (lat1 < lat2) {return -1;}
            if (lat1 > lat2) {return 1;}
        }
        return 0;
    }

    /**
     * Peers active within the activity window sort first.
     *
     * @param prof1 first peer's profile, or null
     * @param prof2 second peer's profile, or null
     * @param now current time from the router clock
     * @param thirtyMinutes activity window in ms
     * @return negative, zero, or positive
     * @since 0.9.71+ (extracted from peerQualityComparator)
     */
    static int compareActivity(PeerProfile prof1, PeerProfile prof2, long now, long thirtyMinutes) {
        boolean active1 = prof1 != null && (prof1.getLastHeardFrom() > 0 && now - prof1.getLastHeardFrom() < thirtyMinutes ||
                                          prof1.getLastSendSuccessful() > 0 && now - prof1.getLastSendSuccessful() < thirtyMinutes);
        boolean active2 = prof2 != null && (prof2.getLastHeardFrom() > 0 && now - prof2.getLastHeardFrom() < thirtyMinutes ||
                                          prof2.getLastSendSuccessful() > 0 && now - prof2.getLastSendSuccessful() < thirtyMinutes);
        if (active1 && !active2) {return -1;}
        if (!active1 && active2) {return 1;}
        return 0;
    }

    /**
     * Lower measured tunnel-test latency sorts first; measured beats unknown.
     * When both latencies are 0 (unknown), peers that have been tested before
     * (have a persisted test timestamp) sort before never-tested peers, so
     * proven peers with expired latency data are preferred over new peers.
     *
     * @param lat1 first peer's tunnel test time average
     * @param lat2 second peer's tunnel test time average
     * @return negative, zero, or positive
     * @since 0.9.71+ (extracted from peerQualityComparator)
     */
    static int compareLatency(float lat1, float lat2) {
        // Prefer lower latency — peers with recent fast tunnel tests
        // get priority over peers with high or no latency data.
        if (lat1 > 0 && lat2 > 0) {
            if (lat1 < lat2) {return -1;}
            if (lat1 > lat2) {return 1;}
        } else if (lat1 > 0) {
            return -1;  // only p1 has measured latency
        } else if (lat2 > 0) {
            return 1;   // only p2 has measured latency
        }
        return 0;
    }

    /**
     * When both peers have unknown latency (lat=0), peers with a proven
     * test history sort first.  Uses the persisted EWMA update timestamp
     * to distinguish tested peers from never-tested peers.
     *
     * @param prof1 first peer's profile, or null
     * @param prof2 second peer's profile, or null
     * @return negative, zero, or positive
     * @since 0.9.71+
     */
    static int compareTestHistory(PeerProfile prof1, PeerProfile prof2) {
        long t1 = prof1 != null ? prof1.getTunnelTestTimeAvgLastUpdate() : 0;
        long t2 = prof2 != null ? prof2.getTunnelTestTimeAvgLastUpdate() : 0;
        // Tested peers (t > 0) sort before never-tested peers (t == 0)
        // Among tested peers, more recently tested sorts first
        return Long.compare(t2, t1);
    }

    /**
     * Filter out ghost peers from the selected peer list.
     * Ghost peers are those with consistent tunnel build timeouts.
     *
     * @param peers the list of selected peers (excluding self)
     * @return filtered list without ghost peers; never null
     */
    List<Hash> filterGhostPeers(List<Hash> peers) {
        if (peers == null || peers.isEmpty()) {return peers;}

        TunnelManagerFacade tmf = ctx.tunnelManager();
        GhostPeerManager ghostManager = tmf.getGhostPeerManager();
        if (ghostManager == null) {return peers;}

        List<Hash> filtered = new ArrayList<>(peers.size());
        for (Hash peer : peers) {
            if (ghostManager.isGhost(peer)) {
                if (log.shouldDebug()) {
                    log.debug("Skipping ghost peer [" + peer.toBase32().substring(0, 6) +"]");
                }
            } else {
                filtered.add(peer);
            }
        }

        if (filtered.isEmpty() && !peers.isEmpty()) {
            if (log.shouldWarn()) {
                log.warn("All selected peers were ghosts -> Returning empty to allow fallback selection...");
            }
            return new ArrayList<Hash>(0);
        }

        return filtered;
    }

    /**
     * Drop banlisted peers from a selection so builds do not dispatch
     * requests that BuildHandler will reject with "Next peer is banned".
     * Mirrors {@link #filterGhostPeers}: an all-banned selection returns
     * empty so the caller can redraw from usable candidates instead of
     * dispatching a build that cannot succeed.
     *
     * @param peers the list of selected peers (excluding self)
     * @return filtered list without banlisted peers; empty when all were banned
     * @since 0.9.71+
     */
    List<Hash> filterBannedPeers(List<Hash> peers) {
        if (peers == null || peers.isEmpty()) {return peers;}

        Banlist banlist = ctx != null ? ctx.banlist() : null;
        if (banlist == null) {return peers;}

        List<Hash> filtered = new ArrayList<>(peers.size());
        for (Hash peer : peers) {
            if (peer != null && banlist.isBanlisted(peer)) {
                if (log.shouldDebug()) {
                    log.debug("Skipping banlisted peer [" + peer.toBase32().substring(0, 6) + "]");
                }
            } else {
                filtered.add(peer);
            }
        }

        if (filtered.isEmpty() && !peers.isEmpty() && log.shouldWarn()) {
            log.warn("All selected peers were banlisted -> discarding selection for redraw");
        }

        return filtered;
    }

    /**
     * A Set of Hashes that automatically adds to the
     * Set in the contains() check.
     *
     * So we don't need to generate the exclude set up front.
     *
     * @since 0.9.58
     */
    private class IBGWExcluder extends ExcluderBase {

        /** Local cache of peers that passed the IBGW check.  These are NOT
         * added to the exclusion set {@code s} (they are allowed), but
         * caching them here avoids re-calling allowAsIBGW on every
         * contains() check.  The check is delegated to TunnelPeerSelector's
         * endpoint cache (300s TTL, banlist always live) on miss, so
         * entries here are valid for the lifetime of this excluder
         * instance (one selectSingleHop call).
         * @since 0.9.71+ */
        private final Set<Hash> _allowed = new HashSet<>();

        /**
         * Automatically check if peer is connected
         * and add the Hash to the set if not.
         *
         * @param set not copied, contents will be modified by all methods
         */
        public IBGWExcluder(Set<Hash> set) {super(set);}

        /**
         * Automatically check if peer is connected
         * and add the Hash to the set if not.
         * Passing peers are cached in {@code _allowed} so subsequent
         * contains() calls for the same Hash skip the global cache lookup.
         *
         * @param o a Hash
         * @return true if peer should be excluded
         */
        public boolean contains(Object o) {
            if (s.contains(o)) {return true;}
            if (_allowed.contains(o)) {return false;}
            Hash h = (Hash) o;
            boolean rv = !allowAsIBGW(h);
            if (rv) {
                s.add(h);
                recordExclusion(h, "not-ibgw");
                if (log.shouldDebug()) {
                    log.debug("InboundGateway exclude [" + h.toBase64().substring(0,6) + "]");
                }
            } else {
                _allowed.add(h);
            }
            return rv;
        }
    }

    /**
     * A Set of Hashes that automatically adds to the
     * Set in the contains() check.
     *
     * So we don't need to generate the exclude set up front.
     *
     * @since 0.9.58
     */
    private class OBEPExcluder extends ExcluderBase {

        /** Local cache of peers that passed the OBEP check — same lifecycle
         * and rationale as {@link IBGWExcluder#_allowed}.
         * @since 0.9.71+ */
        private final Set<Hash> _allowed = new HashSet<>();

        /**
         * Automatically check if peer is connected
         * and add the Hash to the set if not.
         *
         * @param set not copied, contents will be modified by all methods
         */
        public OBEPExcluder(Set<Hash> set) {super(set);}

        /**
         * Automatically check if peer is connected
         * and add the Hash to the set if not.
         * Passing peers are cached in {@code _allowed} so subsequent
         * contains() calls for the same Hash skip the global cache lookup.
         *
         * @param o a Hash
         * @return true if peer should be excluded
         */
        public boolean contains(Object o) {
            if (s.contains(o)) {return true;}
            if (_allowed.contains(o)) {return false;}
            Hash h = (Hash) o;
            boolean rv = !allowAsOBEP(h);
            if (rv) {
                s.add(h);
                recordExclusion(h, "not-obep");
                if (log.shouldDebug()) {
                    log.debug("OutboundEndpoint exclude [" + h.toBase64().substring(0,6) + "]");
                }
            } else {
                _allowed.add(h);
            }
            return rv;
        }
    }

}
