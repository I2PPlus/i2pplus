package net.i2p.router.tunnel.pool;

import static net.i2p.router.peermanager.ProfileOrganizer.Slice.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.util.ArraySet;

/**
 * Pick peers randomly out of the fast pool, and put them into tunnels
 * ordered by XOR distance from a random key.
 *
 */
class ClientPeerSelector extends TunnelPeerSelector {

    private static final double SEVERE_ATTACK_THRESHOLD = 0.30;
    /** Startup grace period: strict first-hop gates and soft fallbacks are relaxed for the first 15 minutes. */
    private static final long STARTUP_GRACE_MS = 15 * 60 * 1000L;
    /** First-hop quality attempts before preferring connecting peers. */
    private static final int ESTABLISHED_PREF_ATTEMPTS = 3;
    /** First-hop quality attempts before accepting any tier-passing peer. */
    private static final int CONNECTING_PREF_ATTEMPTS = 5;


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
     *  Constructor.
     *
     *  @param context the router context
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
     *         they should appear in a tunnel (ENDPOINT FIRST).  This includes
     *         the local router in the list.  Never null; an empty list means
     *         no peers could be selected.
     */
    public List<Hash> selectPeers(TunnelPoolSettings settings) {
        int length = getLength(settings);
        if (length < 0 || ((length == 0) && (settings.getLength() + settings.getLengthVariance() > 0))) {
            if (log.shouldWarn()) {
                log.warn("CPS selectPeers abort: getLength returned " + length +
                         " for " + settings.getDestinationNickname() +
                         " (" + (settings.isInbound() ? "in" : "out") + ")");
            }
            return Collections.emptyList();
        }
        List<Hash> rv;
        boolean isInbound = settings.isInbound();

        if (length > 0) {
            SelectionParams params = computeSelectionParams(settings, length, isInbound);
            if (shouldSelectExplicit(settings)) {return selectExplicit(settings, length);}
            SelectionExclusions ex = buildExclusions(settings, isInbound, params.buildSuccess);
            ArraySet<Hash> matches = new ArraySet<>(length);
            if (length == 1) {
                rv = selectSingleHop(settings, length, params, ex, matches);
            } else {
                rv = selectMultiHop(settings, length, params, ex, matches);
                if (rv.isEmpty()) {return Collections.emptyList();}
            }
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
        } else {
            rv = new ArrayList<>(1);
        }
        return finalizeSelection(settings, rv, isInbound);
    }

    /** Compute the shared selection parameters (tier priority, closest-hop checks, hidden flags, IP restriction). */
    private SelectionParams computeSelectionParams(TunnelPoolSettings settings, int length, boolean isInbound) {
        // Cache buildSuccess to avoid repeated expensive calls
        double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
        // Under stress (< 40% build success), prefer HighCapacity peers over
        // FastPeers — speed-ranked peers are often overloaded/rejecting while
        // HighCapacity peers have proven reliability in completed tunnels.
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
    private SelectionExclusions buildExclusions(TunnelPoolSettings settings, boolean isInbound, double buildSuccess) {
        // Excluder is lazy — contains() auto-classifies and tracks reasons.
        // Don't copy to HashSet or reason tracking is lost.
        Excluder excluder = new Excluder(isInbound, false, buildSuccess);
        Set<Hash> exclude = excluder;

        // Check shared peer cooldowns (from checkTunnel failures across ALL pools).
        // Filter expired entries at read time instead of mutating the shared map
        // on every selection: the exclusion window is identical and concurrent
        // selections no longer sweep the map (which is iterated by other
        // selectors too). Bulk hygiene is handled by prunePeerMaps() when a
        // map exceeds its size cap.
        long nowCooldown = ctx.clock().now();
        long sharedCooldownCutoff = nowCooldown - PEER_SELECTION_COOLDOWN_MS;
        int peerCooldownExcluded = addFreshCooldownExclusions(_peerCooldowns, sharedCooldownCutoff, exclude);
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
        // Relaxed when the pool is struggling (incomplete LeaseSet or active
        // tunnels below target) so replacement builds can reuse peers rather
        // than starving the pool of build candidates.
        Hash dest = settings.getDestination();
        if (dest != null) {
            TunnelManagerFacade tmf = ctx.tunnelManager();
            TunnelPool pool = isInbound ? tmf.getInboundPool(dest)
                                        : tmf.getOutboundPool(dest);
            if (pool != null && !pool.isStruggling()) {
                Set<Hash> poolPeers = getPeersInPool(ctx, pool);
                exclude.addAll(poolPeers);
            }
        }
        return new SelectionExclusions(excluder, exclude,
                                       peerCooldownExcluded, firstHopFailCount, firstPeerExclusions);
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

    /** Select all hops of a multi-hop tunnel: last hop, middle hops, then first hop. */
    private List<Hash> selectMultiHop(TunnelPoolSettings settings, int length, SelectionParams params,
                                      SelectionExclusions ex, ArraySet<Hash> matches) {
        // build a tunnel using 4 subtiers.
        // For a 2-hop tunnel, the first hop comes from subtiers 0-1 and the last from subtiers 2-3.
        // For a longer tunnels, the first hop comes from subtier 0, the middle from subtiers 2-3, and the last from subtier 1.
        List<Hash> rv = new ArrayList<>(length + 1);
        SessionKey randomKey = settings.getRandomKey();
        // OBEP or IB last hop
        // group 0 or 1 if two hops, otherwise group 0
        Set<Hash> lastHopExclude = buildLastHopExclude(params, ex.exclude);
        if (!selectLastHop(settings, length, params, randomKey, lastHopExclude, matches)) {
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
        rv.addAll(matches);
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

    /** Select the last hop (hidden-inbound closest hop, hidden-outbound OBEP, or normal OBEP). @return false to abort the selection */
    private boolean selectLastHop(TunnelPoolSettings settings, int length, SelectionParams params,
                                  SessionKey randomKey, Set<Hash> lastHopExclude, ArraySet<Hash> matches) {
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
            if (matches.isEmpty()) {
                if (log.shouldWarn()) {
                    log.warn("No peers found after all fallbacks -> Returning empty list...");
                }
                return false;
            }
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
                ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, params.ipRestriction, params.ipSet);
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
                ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey,
                    length == 2 ? SLICE_0_1 : SLICE_0, params.ipRestriction, params.ipSet);
            }
            if (matches.isEmpty()) {
                ctx.profileOrganizer().selectNotFailingPeers(1, lastHopExclude, matches, false, 0, null);
            }
            if (matches.isEmpty()) {
                ctx.profileOrganizer().selectAllNotFailingPeers(1, lastHopExclude, matches, false);
            }
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
                ctx.profileOrganizer().selectFastPeers(middleCount - matches.size(), ex.exclude, matches, randomKey, SLICE_2_3, params.ipRestriction, params.ipSet);
            }
            if (matches.size() < middleCount) {
                ctx.profileOrganizer().selectFastPeers(middleCount - matches.size(), ex.exclude, matches, 0, null);
            }
        } else {
            ctx.profileOrganizer().selectFastPeers(middleCount, ex.exclude, matches, randomKey, SLICE_2_3, params.ipRestriction, params.ipSet);
            // Single pass over the slice-2/3 subtier; a re-run with identical
            // parameters cannot add peers, so escalate straight to the
            // slice-unrestricted pass below.
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
        if (matches.isEmpty()) {
            if (params.useHighCapPrimary) {
                ctx.profileOrganizer().selectHighCapacityPeers(1, exclude, matches, params.ipRestriction, params.ipSet);
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectNotFailingPeers(1, exclude, matches, false, 0, null);
                }
            } else {
                // Single pass over the fast tier; a re-run with identical
                // parameters cannot add peers (candidates are consumed into
                // matches in one pass), so the no-slice escalation below is
                // the only retry that can differ.
                ctx.profileOrganizer().selectFastPeers(1, exclude, matches, randomKey, length == 2 ? SLICE_2_3 : SLICE_1, params.ipRestriction, params.ipSet);
            }
        }
        // Fallback to connected peers. KeepAlive job maintains active peer count
        // at all uptimes, so no startup leniency needed.
        if (matches.isEmpty()) {
            ctx.profileOrganizer().selectActiveNotFailingPeers(1, exclude, matches, 0, null);
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
        // Post-selection first-hop quality check.
        // Hard-fail gates: first-hop-failing peers, stale peers — always reject.
        // Established preference: prefer established/connecting peers but fall
        // back quickly (lower budget than before) to avoid starving the pool
        // during recovery.  During startup grace (first 15 min), skip the
        // established/connecting tier entirely — accept whatever passed the
        // tier filters above (transport address, acceptance ratio, etc.).
        if (!matches.isEmpty()) {
            // First-hop quality: aggressively prefer connected/established
            // peers.  33/min first-hop failures mean we're selecting peers
            // that look fast on paper but can't actually receive the build.
            // More attempts = higher chance of finding a connected peer.
            int qualityAttempts = 0;
            boolean inStartup = isStartupGracePeriod(ctx);
            // When very few candidates remain, skip established preference
            // to avoid exhausting the pool entirely.
            int tier = (inStartup || matches.size() < 3) ? 2 : 0;
            while (qualityAttempts < 8 && !matches.isEmpty()) {
                qualityAttempts++;
                tier = firstHopQualityTier(qualityAttempts, inStartup, tier);
                Hash firstHop = matches.iterator().next();
                // During startup grace (first 15 min), skip the first-hop
                // failing check. We have too few peers and too many transient
                // transport failures to permanently penalize peers.
                if (!inStartup && isFirstHopFailing(ctx, firstHop)) {
                    if (log.shouldInfo()) {
                        log.info("First hop " + firstHop.toBase64().substring(0,6) +
                                 " previously failed as first hop, retrying...");
                    }
                    matches.remove(firstHop);
                    continue;
                }
                if (isStalePeer(ctx, firstHop, params.buildSuccess)) {
                    if (log.shouldInfo()) {
                        log.info("First hop " + firstHop.toBase64().substring(0,6) +
                                 " is stale (no contact >4hrs), retrying selection...");
                    }
                    matches.remove(firstHop);
                    continue;
                }
                if (tier <= 1 && !ctx.commSystem().isEstablished(firstHop) &&
                    !ctx.commSystem().isConnecting(firstHop)) {
                    matches.remove(firstHop);
                    continue;
                }
                break;
            }
        }
        // preConnectTo: warm up the transport session so the TBR delivery
        // has a better chance of reaching the first hop.  The actual
        // fast-fail check is in configureNewTunnel(), which validates the
        // TBR target (cfg.getPeer(1)) after the full config is built.
        if (!matches.isEmpty()) {
            Hash candidate = matches.iterator().next();
            if (!ctx.commSystem().isEstablished(candidate) &&
                !ctx.commSystem().isConnecting(candidate)) {
                preConnectTo(ctx, candidate);
            }
        }
        // Shortfall fallback below reuses the (wrapped) exclude
        ex.exclude = exclude;
    }

    /**
     *  Whether the router is still in the startup grace period (first
     *  {@code STARTUP_GRACE_MS} ms of uptime), during which strict first-hop
     *  quality gates and soft fallbacks are relaxed because few peers are
     *  available and transient transport failures are common.
     *  <p>
     *  Pure decision — no side effects.
     *
     *  @param ctx the router context
     *  @return whether the router is within the startup grace period
     *  @since 0.9.71+
     */
    static boolean isStartupGracePeriod(RouterContext ctx) {
        return ctx.router() != null && ctx.router().getUptime() < STARTUP_GRACE_MS;
    }

    /**
     *  First-hop quality tier for the current attempt: 0 = prefer
     *  established peers, 1 = also accept connecting peers, 2 = accept any
     *  peer that passed the tier filters.  Escalates with attempts; during
     *  the startup grace period the tier never changes.  Note the quirk:
     *  attempts 4-5 downgrade a tier-2 selection to 1 (preserved verbatim).
     *  <p>
     *  Pure decision — no side effects.
     *
     *  @param attempts number of quality-check attempts already made
     *  @param inStartup whether the router is in the startup grace period
     *  @param currentTier the tier before this attempt
     *  @return the tier for this attempt
     *  @since 0.9.71+
     */
    static int firstHopQualityTier(int attempts, boolean inStartup, int currentTier) {
        if (inStartup) return currentTier;
        if (attempts > CONNECTING_PREF_ATTEMPTS) return 2;
        if (attempts > ESTABLISHED_PREF_ATTEMPTS) return 1;
        return currentTier;
    }

    /**
     *  Builds the soft-fallback exclusion set: a copy of the first-hop
     *  exclusion set without no-signal-excluded peers that have proven track
     *  records (tunnel acceptance > 50% and at least one successful test),
     *  giving them a chance when all standard tiers fail.  No side effects.
     *
     *  @param exclude the current first-hop exclusion set
     *  @param ex the selection exclusions with the per-peer reason map
     *  @return the reduced exclusion set
     *  @since 0.9.71+
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
     *  Whether the selection may use the progressive stress fallbacks and
     *  the shortened-tunnel allowance: network stress (build success below
     *  the attack threshold) or HighCap-primary mode, with at least one peer
     *  already selected.  Pure decision — no side effects.
     *
     *  @param buildSuccess current tunnel build success rate
     *  @param useHighCapPrimary whether the selection prefers high-capacity peers
     *  @param rvSize current number of selected peers
     *  @return whether stress fallbacks may be attempted
     *  @since 0.9.71+
     */
    static boolean canUseStressFallback(double buildSuccess, boolean useHighCapPrimary, int rvSize) {
        return (buildSuccess < ATTACK_THRESHOLD || useHighCapPrimary) && rvSize > 0;
    }

    /**
     *  Adopts the fallback peer set when it has candidates: clears the
     *  running selection and replaces it with the fallback.  Returns whether
     *  the fallback was adopted so callers can log success with the adopted
     *  size.  Mutates only {@code rv}.
     *
     *  @param rv the running selection, replaced when {@code fallback} is non-empty
     *  @param fallback the fallback candidates (self already removed)
     *  @return whether the fallback was adopted
     *  @since 0.9.71+
     */
    static boolean adoptIfFilled(List<Hash> rv, Set<Hash> fallback) {
        if (fallback.isEmpty())
            return false;
        rv.clear();
        rv.addAll(fallback);
        return true;
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
        int min = settings.getLength();
        int skew = settings.getLengthVariance();
        if (skew < 0) {min += skew;}

        // not enough peers to build the minimum size
        if (rv.size() < min) {
            Set<Hash> exclude = ex.exclude;
            // For firewalled routers with very few peers, allow shorter tunnels as fallback
            if (params.hidden && !rv.isEmpty()) {
                if (log.shouldInfo()) {
                    log.info("Firewalled router: allowing shorter tunnel (" + rv.size() + " hops) instead of requested " + length + " hops");
                }
                // Continue with whatever peers we have
            } else if (ctx.getBooleanProperty(PROP_LEGACY_SELECTION)) {
                ArraySet<Hash> fallback = new ArraySet<>(min);
                ctx.profileOrganizer().selectFastPeers(min, exclude, fallback, 0, null);
                fallback.remove(ctx.routerHash());
                adoptIfFilled(rv, fallback);
            } else {
                // Progressive fallback under network stress based on build success rate
                if (canUseStressFallback(params.buildSuccess, params.useHighCapPrimary, rv.size())) {
                    // Network stress or HighCap mode: try fallback with relaxed restrictions
                    if (log.shouldInfo()) {
                        log.info("Network stress or HighCap primary (" + (int) (params.buildSuccess * 100) + "% success) -> Trying relaxed fallback peer selection...");
                    }

                    // Priority: HighCap > Fast > Active > NotFailing (under network stress, prioritize bandwidth)
                    ArraySet<Hash> fallback = new ArraySet<>(min);
                    ctx.profileOrganizer().selectHighCapacityPeers(min, exclude, fallback, 0, null);
                    fallback.remove(ctx.routerHash());

                    if (adoptIfFilled(rv, fallback) && log.shouldDebug()) {
                        log.debug("HighCap fallback successful: found " + rv.size() + " peers for tunnel");
                    }

                    // If still not enough, try fast peers
                    if (rv.size() < min) {
                        fallback.clear();
                        ctx.profileOrganizer().selectFastPeers(min, exclude, fallback, 0, null);
                        fallback.remove(ctx.routerHash());
                        adoptIfFilled(rv, fallback);
                    }

                    // If still not enough, try active (connected) peers
                    if (rv.size() < min) {
                        fallback.clear();
                        ctx.profileOrganizer().selectActiveNotFailingPeers(min, exclude, fallback, 0, null);
                        fallback.remove(ctx.routerHash());

                        if (adoptIfFilled(rv, fallback) && log.shouldDebug()) {
                            log.debug("Active fallback successful: found " + rv.size() + " peers for tunnel");
                        }
                    }

                    // If still not enough, try all not-failing peers
                    if (rv.size() < min) {
                        ArraySet<Hash> nfFallback = new ArraySet<>(min);
                        ctx.profileOrganizer().selectNotFailingPeers(min, exclude, nfFallback, false, 0, null);
                        nfFallback.remove(ctx.routerHash());

                        if (adoptIfFilled(rv, nfFallback) && log.shouldDebug()) {
                            log.debug("Not-failing fallback successful: found " + rv.size() + " peers for tunnel");
                        }
                    }

                    // If still not enough, try all peers as last resort
                    if (rv.size() < min) {
                        ArraySet<Hash> allFallback = new ArraySet<>(min);
                        ctx.profileOrganizer().selectAllNotFailingPeers(min, exclude, allFallback, false);
                        allFallback.remove(ctx.routerHash());
                        adoptIfFilled(rv, allFallback);
                    }

                    // If still not enough, try with even more relaxed criteria but prefer better peers
                    // Instead of "any peer", allow some previously-failing peers with good recent performance
                    if (rv.size() < min && params.buildSuccess < SEVERE_ATTACK_THRESHOLD && rv.isEmpty()) {
                        if (log.shouldDebug()) {
                            log.debug("Severe network stress (" + (int) (params.buildSuccess * 100) + "% success) -> Trying quality-aware fallback with speed-adjusted peer selection...");
                        }
                        // Use a quality-ordered fallback that prefers faster peers even if they recently failed
                        ArraySet<Hash> qualityFallback = new ArraySet<>(min);
                        // Get peers with good speed even if they have some failures
                        ctx.profileOrganizer().selectActiveNotFailingPeers(min, exclude, qualityFallback);
                        qualityFallback.remove(ctx.routerHash());

                        if (adoptIfFilled(rv, qualityFallback)) {
                            if (log.shouldDebug()) {
                                log.debug("Quality-aware fallback successful: found " + rv.size() + " peers");
                            }
                        } else {
                            // Only use "any peer" as last resort
                            if (log.shouldDebug()) {
                                log.debug("All quality peers exhausted -> Using emergency fallback (any peer)");
                            }
                            ArraySet<Hash> relaxedFallback = new ArraySet<>(min);
                            ctx.profileOrganizer().selectAllNotFailingPeers(min, exclude, relaxedFallback, false);
                            relaxedFallback.remove(ctx.routerHash());

                            if (adoptIfFilled(rv, relaxedFallback) && log.shouldDebug()) {
                                log.debug("Emergency fallback: found " + rv.size() + " peers");
                            }
                        }
                    }
                }

                // Final check - if still not enough peers and we have some, allow shorter tunnel
                if (rv.size() < min) {
                    if (canUseStressFallback(params.buildSuccess, params.useHighCapPrimary, rv.size())) {
                        // Under stress but have some peers - allow shorter tunnel instead of null
                        if (log.shouldDebug()) {
                            log.debug("Network stress: allowing shorter tunnel (" + rv.size() + " hops) instead of " + min + " minimum");
                        }
                        // Continue with shorter tunnel
                    } else {
                        if (log.shouldWarn()) {
                            log.warn("CPS not enough peers for " + settings.getDestinationNickname() +
                                     " (" + (settings.isInbound() ? "in" : "out") + "): rv=" + rv.size() +
                                     " min=" + min + " length=" + length);
                        }
                        return Collections.emptyList();
                    }
                }
            }
        }
        return rv;
    }

    /** Insert self, sort by quality, ghost-filter, strategy post-processing, duplicate re-check, and cooldowns. */
    List<Hash> finalizeSelection(TunnelPoolSettings settings, List<Hash> rv, boolean isInbound) {
        if (isInbound) {rv.add(0, ctx.routerHash());}
        else {rv.add(ctx.routerHash());}

        // Sort non-self peers by reliability so better peers are preferred
        if (rv.size() > 2) {
            List<Hash> nonSelf = new ArrayList<>(rv);
            nonSelf.remove(ctx.routerHash());
            if (nonSelf.size() > 1) {
                sortByPeerQuality(nonSelf, null);
                // Rebuild with self in correct position
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

        // Filter out ghost peers before returning
        rv = filterGhostPeers(rv);

        // Strategy-specific post-processing
        if (rv.size() > 2) {
            String strategy = getStrategy();
            if (STRATEGY_RELIABILITY.equals(strategy)) {
                // Apply reliability filter on non-self peers
                List<Hash> nonSelf = new ArrayList<>(rv);
                nonSelf.remove(ctx.routerHash());
                Set<Hash> nonSelfSet = new HashSet<>(nonSelf);
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
                List<Hash> nonSelf = new ArrayList<>(rv);
                nonSelf.remove(ctx.routerHash());
                if (!isDuplicateSequence(settings, nonSelf)) {break;}
                List<Hash> regenerated = regeneratePeers(settings, nonSelf, attempts + 1);
                if (regenerated == null || regenerated.equals(nonSelf)) {break;}
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
        final int peerCooldownExcluded;
        final int firstHopFailCount;
        final Set<Hash> firstPeerExclusions;
        SelectionExclusions(Excluder excluder, Set<Hash> exclude,
                            int peerCooldownExcluded, int firstHopFailCount, Set<Hash> firstPeerExclusions) {
            this.excluder = excluder;
            this.exclude = exclude;
            this.peerCooldownExcluded = peerCooldownExcluded;
            this.firstHopFailCount = firstHopFailCount;
            this.firstPeerExclusions = firstPeerExclusions;
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
     *  Reliability gate: the acceptance ratio must be at least 0.3, and the
     *  peer must show a recent tunnel-test success, recent activity, or an
     *  established connection. Each signal is consulted exactly once.
     *
     *  @param profile the peer profile (may be null)
     *  @param now current time from the router clock
     *  @param tenMinutes tunnel-test recency window in ms
     *  @param thirtyMinutes activity recency window in ms
     *  @return true if the peer passes the reliability gate
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
     *  Build a comparator that orders peers by tunnel-building quality: excluded
     *  peers last, then by acceptance ratio, activity recency, and tunnel-test latency.
     *  <p>
     *  Stage order is significant — each stage short-circuits the ones below it.
     *
     *  @param exclude peers to deprioritize, or null
     *  @param now current time from the router clock
     *  @param thirtyMinutes activity window in ms
     *  @since 0.9.70+
     */
    Comparator<Hash> peerQualityComparator(Set<Hash> exclude, long now, long thirtyMinutes) {
        return (p1, p2) -> compareQuality(p1, p2, exclude,
                                          ctx.profileOrganizer().getProfile(p1),
                                          ctx.profileOrganizer().getProfile(p2),
                                          now, thirtyMinutes);
    }

    /**
     *  Full quality comparison cascade.  Stage order is significant — each
     *  stage short-circuits the ones below it: excluded last, then acceptance
     *  ratio, slow tunnel-test latency, activity recency, and latency.
     *
     *  @param p1 first peer
     *  @param p2 second peer
     *  @param exclude peers to deprioritize, or null
     *  @param prof1 first peer's profile, or null
     *  @param prof2 second peer's profile, or null
     *  @param now current time from the router clock
     *  @param thirtyMinutes activity window in ms
     *  @return negative, zero, or positive
     *  @since 0.9.71+ (extracted from peerQualityComparator)
     */
    static int compareQuality(Hash p1, Hash p2, Set<Hash> exclude, PeerProfile prof1, PeerProfile prof2,
                              long now, long thirtyMinutes) {
        int c = compareExcluded(p1, p2, exclude);
        if (c != 0) {return c;}
        c = compareAcceptance(prof1, prof2);
        if (c != 0) {return c;}
        float lat1 = prof1 != null ? prof1.getTunnelTestTimeAverage() : 0;
        float lat2 = prof2 != null ? prof2.getTunnelTestTimeAverage() : 0;
        c = compareSlowLatency(lat1, lat2);
        if (c != 0) {return c;}
        c = compareActivity(prof1, prof2, now, thirtyMinutes);
        if (c != 0) {return c;}
        return compareLatency(lat1, lat2);
    }

    /**
     *  Excluded peers sort last; two excluded peers compare equal.
     *
     *  @param p1 first peer
     *  @param p2 second peer
     *  @param exclude peers to deprioritize, or null
     *  @return negative, zero, or positive
     *  @since 0.9.71+ (extracted from peerQualityComparator)
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
     *  Acceptance ratio tiers: good (&gt; 0.3) ranks above low (&lt; 0.3), which
     *  ranks above dead (&lt;= 0).  Missing profiles default to 1.0.
     *
     *  @param prof1 first peer's profile, or null
     *  @param prof2 second peer's profile, or null
     *  @return negative, zero, or positive
     *  @since 0.9.71+ (extracted from peerQualityComparator)
     */
    static int compareAcceptance(PeerProfile prof1, PeerProfile prof2) {
        double ar1 = prof1 != null ? prof1.getTunnelAcceptanceRatio() : 1.0;
        double ar2 = prof2 != null ? prof2.getTunnelAcceptanceRatio() : 1.0;
        if (ar1 <= 0 && ar2 > 0.3) {return 1;}
        if (ar2 <= 0 && ar1 > 0.3) {return -1;}
        if (ar1 < 0.3 && ar2 >= 0.3) {return 1;}
        if (ar2 < 0.3 && ar1 >= 0.3) {return -1;}
        return 0;
    }

    /**
     *  Peers with tunnel test latency over 15s sort last; when both are slow,
     *  the less slow one sorts first.  0 latency means no data (unknown).
     *
     *  @param lat1 first peer's tunnel test time average
     *  @param lat2 second peer's tunnel test time average
     *  @return negative, zero, or positive
     *  @since 0.9.71+ (extracted from peerQualityComparator)
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
     *  Peers active within the activity window sort first.
     *
     *  @param prof1 first peer's profile, or null
     *  @param prof2 second peer's profile, or null
     *  @param now current time from the router clock
     *  @param thirtyMinutes activity window in ms
     *  @return negative, zero, or positive
     *  @since 0.9.71+ (extracted from peerQualityComparator)
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
     *  Lower measured tunnel-test latency sorts first; measured beats unknown.
     *
     *  @param lat1 first peer's tunnel test time average
     *  @param lat2 second peer's tunnel test time average
     *  @return negative, zero, or positive
     *  @since 0.9.71+ (extracted from peerQualityComparator)
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
                    log.debug("Skipping ghost peer: " + peer.toBase32().substring(0, 6));
                }
            } else {
                filtered.add(peer);
            }
        }

        if (filtered.isEmpty() && !peers.isEmpty()) {
            if (log.shouldWarn()) {
                log.warn("All selected peers were ghosts -> returning empty to allow fallback selection...");
            }
            return Collections.emptyList();
        }

        return filtered;
    }

    /**
     *  A Set of Hashes that automatically adds to the
     *  Set in the contains() check.
     *
     *  So we don't need to generate the exclude set up front.
     *
     *  @since 0.9.58
     */
    private class IBGWExcluder extends ExcluderBase {

        /**
         *  Automatically check if peer is connected
         *  and add the Hash to the set if not.
         *
         *  @param set not copied, contents will be modified by all methods
         */
        public IBGWExcluder(Set<Hash> set) {super(set);}

        /**
         *  Automatically check if peer is connected
         *  and add the Hash to the set if not.
         *
         *  @param o a Hash
         *  @return true if peer should be excluded
         */
        public boolean contains(Object o) {
            if (s.contains(o)) {return true;}
            Hash h = (Hash) o;
            boolean rv = !allowAsIBGW(h);
            if (rv) {
                s.add(h);
                recordExclusion(h, "not-ibgw");
                if (log.shouldDebug()) {
                    log.debug("InboundGateway exclude [" + h.toBase64().substring(0,6) + "]");
                }
            }
            return rv;
        }
    }

    /**
     *  A Set of Hashes that automatically adds to the
     *  Set in the contains() check.
     *
     *  So we don't need to generate the exclude set up front.
     *
     *  @since 0.9.58
     */
    private class OBEPExcluder extends ExcluderBase {

        /**
         *  Automatically check if peer is connected
         *  and add the Hash to the set if not.
         *
         *  @param set not copied, contents will be modified by all methods
         */
        public OBEPExcluder(Set<Hash> set) {super(set);}

        /**
         *  Automatically check if peer is connected
         *  and add the Hash to the set if not.
         *
         *  @param o a Hash
         *  @return true if peer should be excluded
         */
        public boolean contains(Object o) {
            if (s.contains(o)) {return true;}
            Hash h = (Hash) o;
            boolean rv = !allowAsOBEP(h);
            if (rv) {
                s.add(h);
                recordExclusion(h, "not-obep");
                if (log.shouldDebug()) {
                    log.debug("OutboundEndpoint exclude [" + h.toBase64().substring(0,6) + "]");
                }
            }
            return rv;
        }
    }

}
