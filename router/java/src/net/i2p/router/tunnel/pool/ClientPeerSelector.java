package net.i2p.router.tunnel.pool;

import static net.i2p.router.peermanager.ProfileOrganizer.Slice.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
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

    private static String formatExcludedPeers(Set<Hash> peers) {
        if (peers == null || peers.isEmpty()) {return "[]";}
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
            return null;
        }

        List<Hash> rv;
        boolean isInbound = settings.isInbound();

        if (length > 0) {
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
            // Reduce IP restriction under low tunnel build success to improve diversity @since 0.9.68+
            if (ipRestriction > 0 && length > 1) {
                double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
                if (buildSuccess < 0.40) {
                    ipRestriction = Math.min(ipRestriction + 1, 16);
                }
            }
            if (ctx.getBooleanProperty("i2np.allowLocal") || length <= 1) {ipRestriction = 0;}
            MaskedIPSet ipSet = ipRestriction > 0 ? new MaskedIPSet(ipRestriction) : null;

            if (shouldSelectExplicit(settings)) {return selectExplicit(settings, length);}

            Set<Hash> exclude = getExclude(isInbound, false);

            // Add first peer exclusions for diversity @since 0.9.68+
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
                    // TODO this doesn't pick from fast
                    ctx.profileOrganizer().selectActiveNotFailingPeers(1, exclude, matches);
                }
                if (matches.isEmpty()) {
                    // No connected peers found, fall back to all fast peers
                    // Throttle warnings to reduce log spam during attacks
                    long now = ctx.clock().now();
                    if (log.shouldWarn() && _lastFallbackWarn.getAndSet(now) < now - WARNING_THROTTLE_MS) {
                        log.warn("No eligible non-failing peers available for " + (isInbound ? "Inbound" : "Outbound") +
                                 " connection -> Falling back to fast pool (throttled)");
                    }
                    ctx.profileOrganizer().selectFastPeers(length, exclude, matches);
                    if (matches.isEmpty()) {
                        ctx.profileOrganizer().selectHighCapacityPeers(length, exclude, matches);
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
                        log.info("SelectFastPeers closest Inbound " + formatExcludedPeers(lastHopExclude));
                    }
                } else {
                    lastHopExclude = new OBEPExcluder(exclude);
                    if (log.shouldInfo()) {log.info("SelectFastPeers OBEP " + formatExcludedPeers(lastHopExclude));}
                }
                if (hiddenInbound) {
                    // IB closest hop
                    if (log.shouldInfo()) {
                        log.info("Selecting fast/non-failing peer for (hidden) closest Inbound " + lastHopExclude);
                    }
                    ctx.profileOrganizer().selectActiveNotFailingPeers(length, lastHopExclude, matches, ipRestriction, ipSet);
                    if (matches.isEmpty()) {
                        log.info("Selecting any active peers without restictions for (hidden) Inbound connection");
                         ctx.profileOrganizer().selectActiveNotFailingPeers(length, lastHopExclude, matches, 0, null);
                    }
                    if (matches.isEmpty()) {
                        // No connected peers found, give up now
                        if (log.shouldWarn()) {
                            log.warn("No active peers found -> Returning null...");
                        }
                        return null;
                    }
                } else if (hiddenOutbound) {
                    // OBEP
                    // check for hidden and outbound, and the paired (inbound) tunnel is zero-hop
                    // if so, we need the OBEP to be connected to us, so we get the build reply back
                    // This should be rare except at startup
                    TunnelManagerFacade tmf = ctx.tunnelManager();
                    Hash dest = settings.getDestination();
                    TunnelPool tp = dest != null ? tmf.getInboundPool(dest) : null;
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
                        ctx.profileOrganizer().selectActiveNotFailingPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                        if (matches.isEmpty()) {
                            ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                            if (log.shouldWarn()) {
                                log.warn("Failed to select non-failing peer for (hidden) OutboundEndpoint -> Falling back to all fast peers...");
                            }
                        }

                        if (matches.isEmpty()) {
                            // No connected peers found, fall back to all fast peers
                            if (log.shouldWarn()) {
                                log.warn("Failed to select non-failing peer for (hidden) OutboundEndpoint -> Falling back to all fast peers...");
                            }
                            ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey, length == 2 ? SLICE_0_1 : SLICE_0, ipRestriction, ipSet);
                        }
                        // Filter out incompatible peers that bypassed lastHopExclude in fallback pools
                        Set<Hash> toRemove = new HashSet<Hash>();
                        for (Hash h : matches) {
                            if (lastHopExclude.contains(h)) {
                                toRemove.add(h);
                            }
                        }
                        matches.removeAll(toRemove);
                        if (!matches.isEmpty()) {
                            ctx.commSystem().exemptIncoming(matches.get(0));
                        }
                    } else {
                        ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey, length == 2 ? SLICE_0_1 : SLICE_0, ipRestriction, ipSet);
                    }
                } else {
                    // TODO exclude IPv6-only at OBEP? Caught in checkTunnel() below
                    ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey, length == 2 ? SLICE_0_1 : SLICE_0,
                                                           ipRestriction, ipSet);
                }

                matches.remove(ctx.routerHash());
                exclude.addAll(matches);
                rv.addAll(matches);
                matches.clear();
                if (length > 2) {
                    // middle hop(s)
                    // group 2 or 3
                    if (log.shouldInfo()) {log.info("SelectFastPeers Middle " + formatExcludedPeers(exclude));}
                    ctx.profileOrganizer().selectFastPeers(length - 2, exclude, matches, randomKey, SLICE_2_3, ipRestriction, ipSet);
                    matches.remove(ctx.routerHash());
                    if (matches.size() > 1) {
                        // order the middle peers for tunnels >= 4 hops
                        List<Hash> ordered = new ArrayList<Hash>(matches);
                        orderPeers(ordered, randomKey);
                        rv.addAll(ordered);
                    } else {rv.addAll(matches);}
                    exclude.addAll(matches);
                    matches.clear();
                }

                // IBGW or OB first hop
                // group 2 or 3 if two hops, otherwise group 1
                if (isInbound) {
                    exclude = new IBGWExcluder(exclude);
                    if (log.shouldInfo()) {log.info("SelectFastPeers InboundGateway: " + formatExcludedPeers(exclude));}
                } else {
                    // exclude existing IBGWs to get some diversity ?
                    // OB closest-hop restrictions
                    if (checkClosestHop) {exclude = getClosestHopExclude(false, exclude);}
                    if (log.shouldInfo()) {log.info("SelectFastPeers closest Outbound: " + formatExcludedPeers(exclude));}
                }
                // TODO exclude IPv6-only at IBGW? Caught in checkTunnel() below
                ctx.profileOrganizer().selectFastPeers(1, exclude, matches, randomKey, length == 2 ? SLICE_2_3 : SLICE_1, ipRestriction, ipSet);
                matches.remove(ctx.routerHash());
                rv.addAll(matches);
            }
            if (log.shouldInfo()) {
                log.info("ClientPeerSelector " + length + (isInbound ? " Inbound " : " Outbound ") + "final: " + formatExcludedPeers(exclude));
            }
            if (rv.size() < length) {
                // not enough peers to build the requested size
                // client tunnels do not use overrides
                // Suppress warnings during startup (first 15 minutes)
                long uptime = ctx.router() != null ? ctx.router().getUptime() : 0;
                if (log.shouldWarn() && uptime > 15*60*1000) {
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
                    } else {
                        // Progressive fallback under attack conditions based on build success rate
                        double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
                        boolean isUnderAttack = buildSuccess < 0.40;

                        if (isUnderAttack && rv.size() > 0) {
                            // Attack detected (25-40% success): try fallback with relaxed restrictions
                            if (log.shouldInfo()) {
                                log.info("Attack detected (" + (int)(buildSuccess * 100) + "% success) -> Trying relaxed fallback peer selection...");
                            }

                            // Fall back to any peer in notFailing pool with minimal exclusions
                            ArraySet<Hash> fallback = new ArraySet<Hash>(min);
                            ctx.profileOrganizer().selectNotFailingPeers(min, exclude, fallback, false, 0, null);
                            fallback.remove(ctx.routerHash());

                            if (!fallback.isEmpty()) {
                                rv.clear();
                                rv.addAll(fallback);
                                if (log.shouldDebug()) {
                                    log.debug("Fallback successful: found " + rv.size() + " peers for tunnel");
                                }
                            }

                            // If still not enough, try with even more relaxed criteria (allow failing peers)
                            if (rv.size() < min && buildSuccess < 0.30) {
                                if (log.shouldWarn()) {
                                    log.warn("Severe attack (" + (int)(buildSuccess * 100) + "% success) -> Trying any peer fallback...");
                                }
                                ArraySet<Hash> relaxedFallback = new ArraySet<Hash>(min);
                                ctx.profileOrganizer().selectAllNotFailingPeers(min, exclude, relaxedFallback, false);
                                relaxedFallback.remove(ctx.routerHash());

                                if (!relaxedFallback.isEmpty()) {
                                    rv.clear();
                                    rv.addAll(relaxedFallback);
                                    if (log.shouldDebug()) {
                                        log.debug("Relaxed fallback successful: found " + rv.size() + " peers");
                                    }
                                }
                            }
                        }

                        // Final check - if still not enough peers and we have some, allow shorter tunnel
                        if (rv.size() < min) {
                            if (isUnderAttack && rv.size() > 0) {
                                // Under attack but have some peers - allow shorter tunnel instead of null
                                if (log.shouldWarn()) {
                                    log.warn("Under attack: allowing shorter tunnel (" + rv.size() + " hops) instead of " + min + " minimum");
                                }
                                // Continue with shorter tunnel
                            } else {
                                return null;
                            }
                        }
                    }
                }
            }
            if (rv.isEmpty()) {return null;}
        } else {rv = new ArrayList<Hash>(1);}

        if (isInbound) {rv.add(0, ctx.routerHash());}
        else {rv.add(ctx.routerHash());}

        // Filter out ghost peers before returning @since 0.9.68+
        rv = filterGhostPeers(rv, settings);

        // Check for duplicate sequence and regenerate if needed @since 0.9.68+
        if (rv != null && rv.size() > 1) {
            int attempts = 0;
            while (isDuplicateSequence(settings, rv.subList(0, rv.size() - 1)) && attempts < 3) {
                List<Hash> regenerated = regeneratePeers(settings, new ArrayList<Hash>(rv.subList(0, rv.size() - 1)));
                if (regenerated == null || regenerated.equals(rv.subList(0, rv.size() - 1))) {
                    break;
                }
                rv.set(0, rv.get(rv.size() - 1)); // preserve self at end
                for (int i = 0; i < regenerated.size(); i++) {
                    rv.set(i, regenerated.get(i));
                }
                attempts++;
            }
        }

        if (rv.size() > 1) {
            if (!checkTunnel(isInbound, false, rv)) {rv = null;}
        }
        if (isInbound && rv != null && rv.size() > 1) {ctx.commSystem().exemptIncoming(rv.get(1));}
        return rv;
    }

    /**
     * Filter out ghost peers from the selected peer list.
     * Ghost peers are those with consistent tunnel build timeouts.
     *
     * @param peers the list of selected peers (excluding self)
     * @param settings tunnel settings
     * @return filtered list without ghost peers
     * @since 0.9.68+
     */
    private List<Hash> filterGhostPeers(List<Hash> peers, TunnelPoolSettings settings) {
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
                log.warn("All selected peers were ghosts! Allowing fallback selection.");
            }
            return peers; // Return original list to allow fallback handling
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
