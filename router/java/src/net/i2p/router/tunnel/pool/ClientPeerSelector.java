package net.i2p.router.tunnel.pool;

import static net.i2p.router.peermanager.ProfileOrganizer.Slice.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.peermanager.TunnelHistory;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.util.ArraySet;

/**
 * Pick peers randomly out of the fast pool, and put them into tunnels
 * ordered by XOR distance from a random key.
 *
 */
class ClientPeerSelector extends TunnelPeerSelector {

    private static final long WARNING_THROTTLE_MS = 60_000;
    private static final AtomicLong _lastFallbackWarn = new AtomicLong(0);

    private static final double SEVERE_ATTACK_THRESHOLD = 0.30;

    private static final double SEVERE_STRESS_THRESHOLD = 0.30;

    /** Cooldown duration for client peer selections — shorter than exploratory
     *  to avoid exhausting the smaller client Fast pool (~447 peers vs full netdb). */
    private static final long CLIENT_COOLDOWN_MS = 15_000;

    /** Per-pool cooldown map so one pool's selections don't starve another's. */
    private static final Map<Hash, Long> _clientCooldowns = new ConcurrentHashMap<Hash, Long>();

    private static String formatExcludedPeers(Set<Hash> peers) {
        if (peers == null || peers.isEmpty()) {return "[no exclusions]";}
        StringBuilder sb = new StringBuilder(peers.size() * 10);
        int count = 0;
        for (Hash h : peers) {
            if (count % 12 == 0) {
                sb.append("\n* ");
            }
            sb.append('[').append(h.toBase64(), 0, 6).append("] ");
            count++;
        }
        return sb.toString();
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
     *         the local router in the list.  If there are no tunnels or peers
     *         to build through, and the settings reject 0 hop tunnels, this will
     *         return null.
     */
    public List<Hash> selectPeers(TunnelPoolSettings settings) {
        int length = getLength(settings);
        if (length < 0 || ((length == 0) && (settings.getLength() + settings.getLengthVariance() > 0))) {
            if (log.shouldWarn()) {
                log.warn("CPS selectPeers abort: getLength returned " + length +
                         " for " + settings.getDestinationNickname() +
                         " (" + (settings.isInbound() ? "in" : "out") + ")");
            }
            return null;
        }

        List<Hash> rv;
        boolean isInbound = settings.isInbound();

        if (length > 0) {
            // Cache buildSuccess to avoid repeated expensive calls
            double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
            // Under stress (< 40% build success), prefer HighCapacity peers over
            // FastPeers — speed-ranked peers are often overloaded/rejecting while
            // HighCapacity peers have proven reliability in completed tunnels.
            boolean useHighCapPrimary = buildSuccess < ATTACK_THRESHOLD;

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

            if (shouldSelectExplicit(settings)) {return selectExplicit(settings, length);}

            // Create a copy of exclude set to avoid mutating caller's set
            Set<Hash> exclude = new HashSet<Hash>(getExclude(isInbound, false));

            // Exclude recently-selected peers to ensure diversity across client pools
            long nowCooldown = ctx.clock().now();
            long cooldownCutoff = nowCooldown - CLIENT_COOLDOWN_MS;
            int cooldownExcluded = 0;
            for (Map.Entry<Hash, Long> entry : _clientCooldowns.entrySet()) {
                if (entry.getValue() > cooldownCutoff) {
                    exclude.add(entry.getKey());
                    cooldownExcluded++;
                }
            }
            // Evict stale entries every ~50 selections to keep map bounded
            if (cooldownExcluded == 0 && !_clientCooldowns.isEmpty() && (nowCooldown % 50) < 10) {
                _clientCooldowns.values().removeIf(v -> v <= cooldownCutoff);
            }

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
            ArraySet<Hash> matches = new ArraySet<Hash>(length);
            if (length == 1) {
                // closest-hop restrictions
                if (checkClosestHop) {exclude = getClosestHopExclude(isInbound, exclude);}
                if (isInbound) {exclude = new IBGWExcluder(exclude);}
                else {exclude = new OBEPExcluder(exclude);}
                // 1-hop, IP restrictions not required here
                if (hiddenInbound) {
                    if (ctx.getBooleanProperty(PROP_LEGACY_SELECTION)) {
                        ctx.profileOrganizer().selectActiveNotFailingPeers(1, exclude, matches);
                    } else {
                        // Priority: HighCap > Fast > Active > NotFailing
                        ctx.profileOrganizer().selectHighCapacityPeers(1, exclude, matches);
                        if (matches.isEmpty()) {
                            ctx.profileOrganizer().selectHighCapacityPeers(1, exclude, matches);
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
                    matches.removeAll(exclude);
                }
                matches.remove(ctx.routerHash());
                rv = new ArrayList<Hash>(matches);
            } else {
                // build a tunnel using 4 subtiers.
                // For a 2-hop tunnel, the first hop comes from subtiers 0-1 and the last from subtiers 2-3.
                // For a longer tunnels, the first hop comes from subtier 0, the middle from subtiers 2-3, and the last from subtier 1.
                rv = new ArrayList<Hash>(length + 1);
                SessionKey randomKey = settings.getRandomKey();
                // OBEP or IB last hop
                // group 0 or 1 if two hops, otherwise group 0
                Set<Hash> lastHopExclude;
                if (isInbound) {
                    if (checkClosestHop && !hidden) {
                        // exclude existing OBEPs to get some diversity ?
                        // closest-hop restrictions
                        lastHopExclude = getClosestHopExclude(true, exclude);
                    } else {lastHopExclude = exclude;}
                    if (log.shouldInfo()) {
                        log.info("Selecting fast peer for closest Inbound, excluding: " + formatExcludedPeers(lastHopExclude));
                    }
                } else {
                    lastHopExclude = new OBEPExcluder(exclude);
                    if (log.shouldInfo()) {log.info("Selecting fast peer for OutboundEndpoint, excluding: " + formatExcludedPeers(lastHopExclude));}
                }
                if (hiddenInbound) {
                    // IB closest hop
                    if (log.shouldInfo()) {
                        log.info("Selecting fast/non-failing peer for (hidden) closest Inbound... \n* Excluding: " + formatExcludedPeers(lastHopExclude));
                    }
                    if (ctx.getBooleanProperty(PROP_LEGACY_SELECTION)) {
                        ctx.profileOrganizer().selectActiveNotFailingPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                        if (matches.isEmpty()) {
                            ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                        }
                    } else {
                        // Priority: HighCap > Fast > Active > NotFailing
                        ctx.profileOrganizer().selectHighCapacityPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                        if (matches.isEmpty()) {
                            ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                        }
                        if (matches.isEmpty()) {
                            ctx.profileOrganizer().selectActiveNotFailingPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                        }
                        if (matches.isEmpty()) {
                            // Fallback to any not-failing peer if active not available
                            if (log.shouldWarn()) {
                                log.warn("No active peers found, falling back to any non-failing peers");
                            }
                            ctx.profileOrganizer().selectNotFailingPeers(1, lastHopExclude, matches, false, ipRestriction, ipSet);
                        }
                        if (matches.isEmpty()) {
                            // Fallback to all peers as last resort
                            if (log.shouldWarn()) {
                                log.warn("No non-failing peers found, falling back to all peers");
                            }
                            ctx.profileOrganizer().selectAllNotFailingPeers(1, lastHopExclude, matches, false);
                        }
                    }
                    if (matches.isEmpty()) {
                        // Emergency: try all-not-failing before giving up
                        if (log.shouldWarn()) {
                            log.warn("No peers found after standard fallbacks -> Attempting emergency all-peers fallback");
                        }
                        ctx.profileOrganizer().selectAllNotFailingPeers(1, lastHopExclude, matches, false);
                    }
                    if (matches.isEmpty()) {
                        if (log.shouldWarn()) {
                            log.warn("No peers found after all fallbacks -> Returning empty list...");
                        }
                        return Collections.emptyList();
                    }
                } else if (hiddenOutbound) {
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
                        int len = tps.getLength();
                        if (len <= 0 || tps.getLengthOverride() == 0 ||
                            len + tps.getLengthVariance() <= 0) {} // leave it true
                        else {
                            List<TunnelInfo> tunnels = tp.listTunnels();
                            if (!tunnels.isEmpty()) {
                                for (TunnelInfo ti : tp.listTunnels()) {
                                    if (ti.getLength() > 1) {
                                        pickFurthest = false;
                                        break;
                                    }
                                }
                            } else {
                                // no tunnels in the paired tunnel pool
                                // BuildRequester will be using exploratory
                                tp = tmf.getInboundExploratoryPool();
                                tps = tp.getSettings();
                                len = tps.getLength();
                                if (len <= 0 ||
                                    tps.getLengthOverride() == 0 ||
                                    len + tps.getLengthVariance() <= 0) {
                                    // leave it true
                                } else {
                                    tunnels = tp.listTunnels();
                                    if (!tunnels.isEmpty()) {
                                        for (TunnelInfo ti : tp.listTunnels()) {
                                            if (ti.getLength() > 1) {
                                                pickFurthest = false;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {pickFurthest = false;} // shouldn't happen
                    if (pickFurthest) {
                        if (log.shouldInfo()) {
                            log.info("Selecting non-failing peer for OutboundEndpoint... " + lastHopExclude);
                        }
                        if (ctx.getBooleanProperty(PROP_LEGACY_SELECTION)) {
                            ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                        } else {
                            // Priority: HighCap > Fast > Active > NotFailing
                            ctx.profileOrganizer().selectHighCapacityPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                            if (matches.isEmpty()) {
                                ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                            }
                            if (matches.isEmpty()) {
                                ctx.profileOrganizer().selectActiveNotFailingPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                            }
                            if (matches.isEmpty()) {
                                // Fallback to non-failing peers
                                ctx.profileOrganizer().selectNotFailingPeers(1, lastHopExclude, matches, false, ipRestriction, ipSet);
                                if (matches.isEmpty() && log.shouldWarn()) {
                                    log.warn("No active peers found for OutboundEndpoint, falling back to all peers");
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
                        ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                    }
                } else {
                    if (useHighCapPrimary) {
                        ctx.profileOrganizer().selectHighCapacityPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                        if (matches.isEmpty()) {
                            ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey,
                                length == 2 ? SLICE_0_1 : SLICE_0, ipRestriction, ipSet);
                        }
                    } else {
                        ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey,
                            length == 2 ? SLICE_0_1 : SLICE_0, ipRestriction, ipSet);
                    }
                }

                matches.remove(ctx.routerHash());
                exclude.addAll(matches);
                rv.addAll(matches);
                matches.clear();
                if (length > 2) {
                    // middle hop(s)
                    // group 2 or 3
                    if (log.shouldInfo()) {
                        log.info("Selecting middle hop peers (Client style)... \n* Excluding: " + formatExcludedPeers(exclude));
                    }
                    int middleCount = length - 2;

                    if (useHighCapPrimary) {
                        ctx.profileOrganizer().selectHighCapacityPeers(middleCount, exclude, matches, ipRestriction, ipSet);
                        if (matches.size() < middleCount) {
                            ctx.profileOrganizer().selectFastPeers(middleCount - matches.size(), exclude, matches, randomKey, SLICE_2_3, ipRestriction, ipSet);
                        }
                        if (matches.size() < middleCount) {
                            ctx.profileOrganizer().selectFastPeers(middleCount - matches.size(), exclude, matches, 0, null);
                        }
                    } else {
                        ctx.profileOrganizer().selectFastPeers(middleCount, exclude, matches, randomKey, SLICE_2_3, ipRestriction, ipSet);
                        if (matches.size() < middleCount) {
                            ctx.profileOrganizer().selectFastPeers(middleCount - matches.size(), exclude, matches, randomKey, SLICE_2_3, ipRestriction, ipSet);
                        }
                        if (matches.size() < middleCount) {
                            ctx.profileOrganizer().selectFastPeers(middleCount - matches.size(), exclude, matches, 0, null);
                        }
                        if (matches.size() < middleCount) {
                            ctx.profileOrganizer().selectNotFailingPeers(middleCount - matches.size(), exclude, matches, false, 0, null);
                        }
                    }
                    if (matches.size() < middleCount) {
                        ctx.profileOrganizer().selectHighBandwidthPeers(middleCount - matches.size(), exclude, matches, false, 0, null);
                    }
                    if (matches.size() < middleCount && ctx.getBooleanProperty(PROP_LEGACY_SELECTION)) {
                        ctx.profileOrganizer().selectFastPeers(middleCount, exclude, matches, 0, null);
                    } else if (matches.size() < middleCount) {
                        // Priority: HighCap > Active > NotFailing > AllNotFailing
                        int needed = middleCount - matches.size();
                        ArraySet<Hash> fallback = new ArraySet<Hash>(needed);
                        ctx.profileOrganizer().selectHighCapacityPeers(needed, exclude, fallback, 0, null);
                        fallback.remove(ctx.routerHash());
                        if (!fallback.isEmpty()) {
                            matches.addAll(fallback);
                        }
                    }
                    if (matches.size() < middleCount) {
                        int needed = middleCount - matches.size();
                        ArraySet<Hash> fallback = new ArraySet<Hash>(needed);
                        ctx.profileOrganizer().selectActiveNotFailingPeers(needed, exclude, fallback, 0, null);
                        fallback.remove(ctx.routerHash());
                        if (!fallback.isEmpty()) {
                            matches.addAll(fallback);
                        }
                    }
                    if (matches.size() < middleCount) {
                        int needed = middleCount - matches.size();
                        ArraySet<Hash> fallback = new ArraySet<Hash>(needed);
                        ctx.profileOrganizer().selectNotFailingPeers(needed, exclude, fallback, false, 0, null);
                        fallback.remove(ctx.routerHash());
                        if (!fallback.isEmpty()) {
                            matches.addAll(fallback);
                        }
                    }
                    if (matches.size() < middleCount) {
                        int needed = middleCount - matches.size();
                        ArraySet<Hash> fallback = new ArraySet<Hash>(needed);
                        ctx.profileOrganizer().selectAllNotFailingPeers(needed, exclude, fallback, false);
                        fallback.remove(ctx.routerHash());
                        if (!fallback.isEmpty()) {
                            matches.addAll(fallback);
                        }
                    }
                    matches.remove(ctx.routerHash());
                    if (matches.size() > 1) {
                        List<Hash> ordered = new ArrayList<Hash>(matches);
                        orderPeers(ordered, randomKey);
                        rv.addAll(ordered);
                    } else {
                        rv.addAll(matches);
                    }
                    exclude.addAll(matches);
                    matches.clear();
                }

                // IBGW or OB first hop
                if (isInbound) {
                    exclude = new IBGWExcluder(exclude);
                    if (log.shouldInfo()) {
                        log.info("Selecting InboundGateway... \n* Excluding: " + formatExcludedPeers(exclude));
                    }
                } else {
                    if (checkClosestHop) {
                        exclude = getClosestHopExclude(false, exclude);
                    }
                    if (log.shouldInfo()) {
                        log.info("Selecting closest Outbound... \n* Excluding: " + formatExcludedPeers(exclude));
                    }
                }
                if (useHighCapPrimary) {
                    ctx.profileOrganizer().selectHighCapacityPeers(1, exclude, matches, ipRestriction, ipSet);
                    if (matches.isEmpty()) {
                        ctx.profileOrganizer().selectFastPeers(1, exclude, matches, randomKey, length == 2 ? SLICE_2_3 : SLICE_1, ipRestriction, ipSet);
                    }
                } else {
                    ctx.profileOrganizer().selectFastPeers(1, exclude, matches, randomKey, length == 2 ? SLICE_2_3 : SLICE_1, ipRestriction, ipSet);
                    if (matches.isEmpty()) {
                        ctx.profileOrganizer().selectFastPeers(1, exclude, matches, randomKey, length == 2 ? SLICE_2_3 : SLICE_1, ipRestriction, ipSet);
                    }
                }
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectNotFailingPeers(1, exclude, matches, false, 0, null);
                }
                if (matches.isEmpty()) {
                    // Last resort: any not-failing peer
                    ctx.profileOrganizer().selectAllNotFailingPeers(1, exclude, matches, false);
                }
                matches.remove(ctx.routerHash());
                rv.addAll(matches);
            }
            if (log.shouldInfo()) {
                log.info("ClientPeerSelector " + length + (isInbound ? " Inbound" : " Outbound") + ", excluding: " + formatExcludedPeers(exclude));
            }
            if (rv.size() < length) {
                // not enough peers to build the requested size
                // client tunnels do not use overrides
                // Suppress warnings during startup
                long uptime = ctx.router() != null ? ctx.router().getUptime() : 0;
                if (log.shouldWarn() && uptime > STARTUP_WARNING_SUPPRESS_MS) {
                    log.warn("Not enough peers to build requested " + length + " hop tunnel (" + rv.size() + " available)");
                }
                int min = settings.getLength();
                int skew = settings.getLengthVariance();
                if (skew < 0) {min += skew;}

                // not enough peers to build the minimum size
                if (rv.size() < min) {
                // For firewalled routers with very few peers, allow shorter tunnels as fallback
                    if (hidden && rv.size() > 0) {
                        if (log.shouldInfo()) {
                            log.info("Firewalled router: allowing shorter tunnel (" + rv.size() + " hops) instead of requested " + length + " hops");
                        }
                        // Continue with whatever peers we have
                    } else if (ctx.getBooleanProperty(PROP_LEGACY_SELECTION)) {
                        ArraySet<Hash> fallback = new ArraySet<Hash>(min);
                        ctx.profileOrganizer().selectFastPeers(min, exclude, fallback, 0, null);
                        fallback.remove(ctx.routerHash());
                        if (!fallback.isEmpty()) {
                            rv.clear();
                            rv.addAll(fallback);
                        }
                    } else {
                        // Progressive fallback under network stress based on build success rate
                        boolean isUnderStress = buildSuccess < ATTACK_THRESHOLD;

                        if (isUnderStress && rv.size() > 0) {
                            // Network stress detected: try fallback with relaxed restrictions
                            if (log.shouldInfo()) {
                                log.info("Network stress (" + (int) (buildSuccess * 100) + "% success) -> Trying relaxed fallback peer selection...");
                            }

                            // Priority: HighCap > Fast > Active > NotFailing (under network stress, prioritize bandwidth)
                            ArraySet<Hash> fallback = new ArraySet<Hash>(min);
                            ctx.profileOrganizer().selectHighCapacityPeers(min, exclude, fallback, 0, null);
                            fallback.remove(ctx.routerHash());

                            if (!fallback.isEmpty()) {
                                rv.clear();
                                rv.addAll(fallback);
                                if (log.shouldDebug()) {
                                    log.debug("HighCap fallback successful: found " + rv.size() + " peers for tunnel");
                                }
                            }

                            // If still not enough, try fast peers
                            if (rv.size() < min) {
                                fallback.clear();
                                ctx.profileOrganizer().selectFastPeers(min, exclude, fallback, 0, null);
                                fallback.remove(ctx.routerHash());
                                if (!fallback.isEmpty()) {
                                    rv.clear();
                                    rv.addAll(fallback);
                                }
                            }

                            // If still not enough, try active (connected) peers
                            if (rv.size() < min) {
                                fallback.clear();
                                ctx.profileOrganizer().selectActiveNotFailingPeers(min, exclude, fallback, 0, null);
                                fallback.remove(ctx.routerHash());

                                if (!fallback.isEmpty()) {
                                    rv.clear();
                                    rv.addAll(fallback);
                                    if (log.shouldDebug()) {
                                        log.debug("Active fallback successful: found " + rv.size() + " peers for tunnel");
                                    }
                                }
                            }

                            // If still not enough, try all not-failing peers (may not be connected)
                            if (rv.size() < min) {
                                ArraySet<Hash> nfFallback = new ArraySet<Hash>(min);
                                ctx.profileOrganizer().selectNotFailingPeers(min, exclude, nfFallback, false, 0, null);
                                nfFallback.remove(ctx.routerHash());

                                if (!nfFallback.isEmpty()) {
                                    rv.clear();
                                    rv.addAll(nfFallback);
                                    if (log.shouldDebug()) {
                                        log.debug("Not-failing fallback successful: found " + rv.size() + " peers for tunnel");
                                    }
                                }
                            }

                            // If still not enough, try all peers as last resort
                            if (rv.size() < min) {
                                ArraySet<Hash> allFallback = new ArraySet<Hash>(min);
                                ctx.profileOrganizer().selectAllNotFailingPeers(min, exclude, allFallback, false);
                                allFallback.remove(ctx.routerHash());
                                if (!allFallback.isEmpty()) {
                                    rv.clear();
                                    rv.addAll(allFallback);
                                }
                            }

                            // If still not enough, try with even more relaxed criteria but prefer better peers
                            // Instead of "any peer", allow some previously-failing peers with good recent performance
                            if (rv.size() < min && buildSuccess < SEVERE_ATTACK_THRESHOLD && rv.isEmpty()) {
                                if (log.shouldWarn()) {
                                    log.warn("Severe network stress (" + (int) (buildSuccess * 100) + "% success) -> Trying quality-aware fallback with speed-adjusted peer selection...");
                                }
                                // Use a quality-ordered fallback that prefers faster peers even if they recently failed
                                ArraySet<Hash> qualityFallback = new ArraySet<Hash>(min);
                                // Get peers with good speed even if they have some failures
                                ctx.profileOrganizer().selectActiveNotFailingPeers(min, exclude, qualityFallback);
                                qualityFallback.remove(ctx.routerHash());

                                if (!qualityFallback.isEmpty()) {
                                    rv.clear();
                                    rv.addAll(qualityFallback);
                                    if (log.shouldDebug()) {
                                        log.debug("Quality-aware fallback successful: found " + rv.size() + " peers");
                                    }
                                } else {
                                    // Only use "any peer" as last resort
                                    if (log.shouldWarn()) {
                                        log.warn("All quality peers exhausted -> Using emergency fallback (any peer)");
                                    }
                                    ArraySet<Hash> relaxedFallback = new ArraySet<Hash>(min);
                                    ctx.profileOrganizer().selectAllNotFailingPeers(min, exclude, relaxedFallback, false);
                                    relaxedFallback.remove(ctx.routerHash());

                                    if (!relaxedFallback.isEmpty()) {
                                        rv.clear();
                                        rv.addAll(relaxedFallback);
                                        if (log.shouldDebug()) {
                                            log.debug("Emergency fallback: found " + rv.size() + " peers");
                                        }
                                    }
                                }
                            }
                        }

                        // Final check - if still not enough peers and we have some, allow shorter tunnel
                        if (rv.size() < min) {
                            if (isUnderStress && rv.size() > 0) {
                                // Under stress but have some peers - allow shorter tunnel instead of null
                                if (log.shouldWarn()) {
                                    log.warn("Network stress: allowing shorter tunnel (" + rv.size() + " hops) instead of " + min + " minimum");
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
            }
            if (rv.isEmpty()) {
                if (log.shouldWarn()) {
                    log.warn("CPS empty rv for " + settings.getDestinationNickname() +
                             " (" + (settings.isInbound() ? "in" : "out") +
                             ") length=" + length + " return empty");
                }
                return Collections.emptyList();
            }
        } else {
            rv = new ArrayList<Hash>(1);
        }

        if (isInbound) {rv.add(0, ctx.routerHash());}
        else {rv.add(ctx.routerHash());}

        // Sort non-self peers by reliability so better peers are preferred
        if (rv.size() > 2) {
            List<Hash> nonSelf = new ArrayList<Hash>(rv);
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

        // Check for duplicate sequence and regenerate if needed
        if (rv != null && rv.size() > 2) {
            int attempts = 0;
            int maxAttempts = 3;
            while (attempts < maxAttempts && isDuplicateSequence(settings, rv.subList(0, rv.size() - 1))) {
                List<Hash> regenerated = regeneratePeers(settings, new ArrayList<Hash>(rv.subList(0, rv.size() - 1)));
                if (regenerated == null || regenerated.equals(rv.subList(0, rv.size() - 1))) {
                    break; // No change possible, accept the duplicate
                }
                // Preserve self at end and update peer list
                Hash self = rv.get(rv.size() - 1);
                rv.clear();
                rv.addAll(regenerated);
                rv.add(self);
                attempts++;
            }
        }

        if (rv.size() > 1) {
            if (!checkTunnel(isInbound, false, rv)) {
                if (log.shouldWarn()) {
                    log.warn("CPS checkTunnel failed for " + settings.getDestinationNickname() +
                             " (" + (settings.isInbound() ? "in" : "out") + ") rv=" + formatPeerList(rv));
                }
                rv = null;
            }
        }
        // Record selection cooldown for all selected peers (excluding self)
        if (rv != null && rv.size() > 1) {
            long now = ctx.clock().now();
            for (Hash peer : rv) {
                if (!peer.equals(ctx.routerHash())) {
                    _clientCooldowns.put(peer, now);
                }
            }
        }
        if (isInbound && rv != null && rv.size() > 1) {ctx.commSystem().exemptIncoming(rv.get(1));}
        return rv;
    }

    /**
     * Select the best reliable peer from candidates based on:
     * 1. Tunnel acceptance ratio (prefer &gt;50%, minimum 40%)
     * 2. Recent tunnel test success (tested within last 10 minutes)
     * 3. Recent activity (heard from or successful send in last 30 minutes)
     * 4. Prefer connected peers
     *
     * @param candidates peers to choose from
     * @param exclude peers to exclude
     * @return best peer or null if none suitable
     */
    private Hash selectBestReliablePeer(List<Hash> candidates, Set<Hash> exclude) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        Hash bestPeer = null;
        double bestScore = -1;
        long now = ctx.clock().now();
        long tenMinutes = 10 * 60 * 1000L;
        long thirtyMinutes = 30 * 60 * 1000L;

        for (Hash peer : candidates) {
            if (exclude != null && exclude.contains(peer)) {
                continue;
            }

            PeerProfile profile = ctx.profileOrganizer().getProfile(peer);
            if (profile == null) {
                continue;
            }

            double score = 0;
            boolean isConnected = ctx.commSystem().isEstablished(peer);

            double acceptanceRatio = profile.getTunnelAcceptanceRatio();

            if (acceptanceRatio < 0.3) {
                continue;
            }

            long lastTested = profile.getLastTestedSuccessfully();
            boolean recentTest = (lastTested > 0) && (now - lastTested < tenMinutes);

            long lastHeardFrom = profile.getLastHeardFrom();
            long lastSendSuccessful = profile.getLastSendSuccessful();
            boolean recentActivity = (lastHeardFrom > 0 && now - lastHeardFrom < thirtyMinutes) ||
                                     (lastSendSuccessful > 0 && now - lastSendSuccessful < thirtyMinutes);

            if (acceptanceRatio > 0.5) {
                score += 30;
            } else if (acceptanceRatio > 0.3) {
                score += 10;
            }

            if (recentTest) {
                score += 40;
            }

            if (recentActivity) {
                score += 20;
            }

            if (isConnected) {
                score += 20;
            }

            if (score > bestScore) {
                bestScore = score;
                bestPeer = peer;
            }
        }

        return bestPeer;
    }

    /**
     * Filter candidates through reliability scoring (acceptance ratio, recent test, activity, connection).
     * Applies consistent quality filtering to any tier selection.
     *
     * @param candidates peers to filter
     * @param exclude peers to exclude
     * @param max max peers to return
     * @return list of best peers filtered by reliability
     */
    private List<Hash> filterByReliability(Set<Hash> candidates, Set<Hash> exclude, int max) {
        if (candidates == null || candidates.isEmpty() || max <= 0) {
            return Collections.emptyList();
        }
        List<Hash> result = new ArrayList<Hash>();
        long now = ctx.clock().now();
        long tenMinutes = 10 * 60 * 1000L;
        long thirtyMinutes = 30 * 60 * 1000L;

        for (Hash peer : candidates) {
            if (exclude != null && exclude.contains(peer)) {
                continue;
            }
            PeerProfile profile = ctx.profileOrganizer().getProfile(peer);
            if (profile == null) {
                continue;
            }

            double score = scorePeer(profile, now, tenMinutes, thirtyMinutes);
            if (score > 0) {
                result.add(peer);
            }
        }

        result.sort((p1, p2) -> {
            PeerProfile prof1 = ctx.profileOrganizer().getProfile(p1);
            PeerProfile prof2 = ctx.profileOrganizer().getProfile(p2);
            double s1 = scorePeer(prof1, now, tenMinutes, thirtyMinutes);
            double s2 = scorePeer(prof2, now, tenMinutes, thirtyMinutes);
            return Double.compare(s2, s1);
        });

        return result.subList(0, Math.min(max, result.size()));
    }

    private double scorePeer(PeerProfile profile, long now, long tenMinutes, long thirtyMinutes) {
        if (profile == null) return 0;
        double score = 0;
        double acceptanceRatio = profile.getTunnelAcceptanceRatio();
        if (acceptanceRatio < 0.3) return 0;
        if (acceptanceRatio > 0.5) score += 30;
        else if (acceptanceRatio > 0.3) score += 10;

        long lastTested = profile.getLastTestedSuccessfully();
        if (lastTested > 0 && now - lastTested < tenMinutes) score += 40;

        long lastHeardFrom = profile.getLastHeardFrom();
        long lastSendSuccessful = profile.getLastSendSuccessful();
        if ((lastHeardFrom > 0 && now - lastHeardFrom < thirtyMinutes) ||
            (lastSendSuccessful > 0 && now - lastSendSuccessful < thirtyMinutes)) {
            score += 20;
        }

        if (ctx.commSystem().isEstablished(profile.getPeer())) score += 20;
        return score;
    }

    /**
     * Sort peers by quality for tunnel building preference.
     * Higher quality peers (recently tested, active, connected) sort first.
     */
    @SuppressWarnings("deprecation")
    private void sortByPeerQuality(List<Hash> peers, Set<Hash> exclude) {
        if (peers == null || peers.isEmpty()) {
            return;
        }
        long now = ctx.clock().now();
        long thirtyMinutes = 30 * 60 * 1000L;
        peers.sort((p1, p2) -> {
            if (exclude != null && exclude.contains(p1)) {
                if (exclude.contains(p2)) return 0;
                return 1;
            }
            if (exclude != null && exclude.contains(p2)) return -1;

            PeerProfile prof1 = ctx.profileOrganizer().getProfile(p1);
            PeerProfile prof2 = ctx.profileOrganizer().getProfile(p2);
            double ar1 = prof1 != null ? prof1.getTunnelAcceptanceRatio() : 1.0;
            double ar2 = prof2 != null ? prof2.getTunnelAcceptanceRatio() : 1.0;

            if (ar1 <= 0 && ar2 > 0.3) return -1;
            if (ar2 <= 0 && ar1 > 0.3) return 1;

            if (ar1 < 0.3 && ar2 >= 0.3) return 1;
            if (ar2 < 0.3 && ar1 >= 0.3) return -1;

            boolean active1 = prof1 != null && (prof1.getLastHeardFrom() > 0 && now - prof1.getLastHeardFrom() < thirtyMinutes ||
                                              prof1.getLastSendSuccessful() > 0 && now - prof1.getLastSendSuccessful() < thirtyMinutes);
            boolean active2 = prof2 != null && (prof2.getLastHeardFrom() > 0 && now - prof2.getLastHeardFrom() < thirtyMinutes ||
                                              prof2.getLastSendSuccessful() > 0 && now - prof2.getLastSendSuccessful() < thirtyMinutes);
            // Push high-latency peers (>15s tunnel test time) to the bottom
            // so that fast peers are preferred. 0 = no data yet, treat as unknown.
            float lat1 = prof1 != null ? prof1.getTunnelTestTimeAverage() : 0;
            float lat2 = prof2 != null ? prof2.getTunnelTestTimeAverage() : 0;
            boolean slow1 = lat1 > 15_000;
            boolean slow2 = lat2 > 15_000;
            if (slow1 && !slow2) return 1;
            if (!slow1 && slow2) return -1;
            if (slow1 && slow2) {
                // Both slow — prefer the less slow one
                if (lat1 < lat2) return -1;
                if (lat1 > lat2) return 1;
            }

            if (active1 && !active2) return -1;
            if (!active1 && active2) return 1;

            // Prefer lower latency — peers with recent fast tunnel tests
            // get priority over peers with high or no latency data.
            if (lat1 > 0 && lat2 > 0) {
                if (lat1 < lat2) return -1;
                if (lat1 > lat2) return 1;
            } else if (lat1 > 0) {
                return -1;  // only p1 has measured latency
            } else if (lat2 > 0) {
                return 1;   // only p2 has measured latency
            }

            return 0;
        });
    }

    /**
     * Filter out ghost peers from the selected peer list.
     * Ghost peers are those with consistent tunnel build timeouts.
     *
     * @param peers the list of selected peers (excluding self)
     * @return filtered list without ghost peers
     */
    private List<Hash> filterGhostPeers(List<Hash> peers) {
        if (peers == null || peers.isEmpty()) {return peers;}

        TunnelManagerFacade tmf = ctx.tunnelManager();
        GhostPeerManager ghostManager = tmf.getGhostPeerManager();
        if (ghostManager == null) {return peers;}

        List<Hash> filtered = new ArrayList<Hash>(peers.size());
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
                log.warn("All selected peers were ghosts -> Returning null to allow fallback selection...");
            }
            return null;
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
                if (log.shouldDebug()) {
                    log.debug("OutboundEndpoint exclude [" + h.toBase64().substring(0,6) + "]");
                }
            }
            return rv;
        }
    }

}
