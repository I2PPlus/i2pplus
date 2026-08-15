package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.ArraySet;
import net.i2p.util.SystemVersion;

/**
 * Pick peers randomly out of the not-failing pool, and put them into a tunnel
 * ordered by XOR distance from a random key.
 *
 */
class ExploratoryPeerSelector extends TunnelPeerSelector {

    /**
     *  Cooldown entries for exploratory selections, recorded when checkTunnel
     *  fails.  Separate from the shared {@link #_peerCooldowns} so exploratory
     *  builds cannot pollute the set used by client pools.  Only failures are
     *  recorded — healthy selections never cooldown peers.
     *  @since 0.9.71+
     */
    private final Map<Hash, Long> _exploratoryCooldowns = new ConcurrentHashMap<>();

    /**
     *  ExploratoryPeerSelector.
     */
    public ExploratoryPeerSelector(RouterContext context) {
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
    @Override
    public List<Hash> selectPeers(TunnelPoolSettings settings) {
        int length = getLength(settings);
        if (length < 0) {
            if (log.shouldDebug())
                log.debug("Tunnel length requested is zero: " + settings);
            return null;
        }

        List<Hash> rv = selectPeersInternal(settings, length, true);
        if (rv != null && rv.size() == 1 && length > 0) {
            // All candidates were excluded by selection cooldowns.
            // Retry once without cooldowns rather than starving the pool.
            if (log.shouldDebug())
                log.debug("EPS all candidates on cooldown, retrying without cooldowns");
            rv = selectPeersInternal(settings, length, false);
        }
        return rv;
    }

    /**
     *  The actual peer selection, shared between the normal path and the
     *  cooldown-bypass retry in {@link #selectPeers(TunnelPoolSettings)}.
     *
     *  @param settings the tunnel pool settings
     *  @param length the desired tunnel length
     *  @param includeCooldowns whether to exclude peers on selection cooldown
     *  @return ordered list of Hash objects (ENDPOINT FIRST), or null if no
     *          peers are available or checkTunnel fails
     */
    private List<Hash> selectPeersInternal(TunnelPoolSettings settings, int length, boolean includeCooldowns) {
        boolean isInbound = settings.isInbound();
        long now = ctx.clock().now();
        Set<Hash> exclude = getExclude(isInbound, true);
        exclude.add(ctx.routerHash());
        // Exclude peers on selection cooldown to ensure diversity.
        // Own map: peers that failed checkTunnel here.  Shared map: peers
        // failed by client pools or rejected at tunnel reuse.
        if (includeCooldowns) {
            long cooldownCutoff = now - PEER_SELECTION_COOLDOWN_MS;
            int cooldownExcluded = 0;
            _exploratoryCooldowns.entrySet().removeIf(e -> e.getValue() <= cooldownCutoff);
            for (Map.Entry<Hash, Long> entry : _exploratoryCooldowns.entrySet()) {
                exclude.add(entry.getKey());
                cooldownExcluded++;
            }
            _peerCooldowns.entrySet().removeIf(e -> e.getValue() <= cooldownCutoff);
            for (Map.Entry<Hash, Long> entry : _peerCooldowns.entrySet()) {
                exclude.add(entry.getKey());
                cooldownExcluded++;
            }
            if (log.shouldDebug())
                log.debug("EPS cooldown: own=" + _exploratoryCooldowns.size() +
                          " shared=" + _peerCooldowns.size() +
                          " excluded=" + cooldownExcluded +
                          " from=" + Thread.currentThread().getName());
        }

        // Per-pool diversity: exclude peers already in an active tunnel of this pool.
        // No peer should appear in more than 1 tunnel of the same pool.
        TunnelPool pool = isInbound ? ctx.tunnelManager().getInboundExploratoryPool()
                                    : ctx.tunnelManager().getOutboundExploratoryPool();
        Set<Hash> poolPeers = getPeersInPool(ctx, pool);
        exclude.addAll(poolPeers);
        if (log.shouldInfo() && !poolPeers.isEmpty())
            log.info("EPS per-pool exclusion: " + poolPeers.size() + " peers in active tunnels from=" + Thread.currentThread().getName());

        // Special cases
        boolean nonzero = length > 0;
        boolean exploreHighCap = nonzero && shouldPickHighCap();
        boolean v6Only = nonzero && isIPv6Only();
        boolean ntcpDisabled = nonzero && isNTCPDisabled();
        boolean ssuDisabled = nonzero && isSSUDisabled();
        // for these cases, check the closest hop up front,
        // otherwise, will be done in checkTunnel() at the end
        boolean checkClosestHop = v6Only || ntcpDisabled || ssuDisabled;
        boolean hidden = nonzero && (ctx.router().isHidden() ||
                                     ctx.router().getRouterInfo().getAddressCount() <= 0 ||
                                     !ctx.commSystem().haveInboundCapacity(95));
        boolean hiddenInbound = hidden && isInbound;
        boolean hiddenOutbound = hidden && !isInbound;
        boolean lowOutbound = nonzero && !isInbound && !ctx.commSystem().haveHighOutboundCapacity();
        int ipRestriction =  (ctx.getBooleanProperty("i2np.allowLocal") || length <= 1) ? 0 : settings.getIPRestriction();
        MaskedIPSet ipSet = ipRestriction > 0 ? new MaskedIPSet(ipRestriction) : null;

        ArrayList<Hash> rv = new ArrayList<>(length + 3);

        // closest-hop restrictions
        // Since we're applying orderPeers() later, we don't know
        // which will be the closest hop, so select the closest one here if necessary.

        Hash closestHop = null;
        if (v6Only || hiddenInbound || lowOutbound) {
            Set<Hash> closestExclude;
            if (checkClosestHop) {
                closestExclude = getClosestHopExclude(isInbound, exclude);
            } else {
                closestExclude = exclude;
            }

            ArraySet<Hash> closest = new ArraySet<>(1);
            if (hiddenInbound || lowOutbound) {
                // If hidden and inbound, use connected peers to guarantee
                // that the adjacent hop can connect to us.
                if (log.shouldInfo()) {
                    log.info("EPS SANFP closest " + (isInbound ? "IB " : "OB ") + closestExclude);
                }
                ctx.profileOrganizer().selectActiveNotFailingPeers(1, closestExclude, closest, ipRestriction, ipSet);
                if (closest.isEmpty()) {
                    // select from all active peers without restriction
                    ctx.profileOrganizer().selectActiveNotFailingPeers(1, closestExclude, closest, 0, null);
                }

                if (closest.isEmpty() && ctx.commSystem().getEstablished().isEmpty()) {
                    if (log.shouldWarn()) {
                        log.warn("Firewalled router with no established connections -> Allowing 0-hop exploratory tunnel...");
                    }
                    return new ArrayList<>(1); // Empty list = 0-hop tunnel
                }
            } else if (exploreHighCap) {
                if (log.shouldInfo())
                    log.info("EPS SHCP closest " + (isInbound ? "IB " : "OB ") + closestExclude);
                ctx.profileOrganizer().selectHighCapacityPeers(1, closestExclude, closest, ipRestriction, ipSet);
            } else {
                if (log.shouldInfo())
                    log.info("EPS SNFP closest " + (isInbound ? "IB " : "OB ") + closestExclude);
                ctx.profileOrganizer().selectNotFailingPeers(1, closestExclude, closest, false, ipRestriction, ipSet);
            }
            // D1: Post-selection first-hop quality check for closest hop
            if (!closest.isEmpty() && !isInbound) {
                Hash peer = closest.iterator().next();
                if (isFirstHopFailing(ctx, peer)) {
                    if (log.shouldInfo())
                        log.info("EPS closest hop " + peer.toBase64().substring(0,6) +
                                 " previously failed as first hop, retrying...");
                    closestExclude.add(peer);
                    closest.clear();
                    ctx.profileOrganizer().selectNotFailingPeers(1, closestExclude, closest, false, ipRestriction, ipSet);
                    if (closest.isEmpty()) {
                        ctx.profileOrganizer().selectFastPeers(1, closestExclude, closest, ipRestriction, ipSet);
                    }
                } else if (!ctx.commSystem().isEstablished(peer)) {
                    if (ctx.commSystem().wasUnreachable(peer)) {
                        if (log.shouldInfo())
                            log.info("EPS closest hop " + peer.toBase64().substring(0,6) +
                                     " is unreachable, retrying selection...");
                        closestExclude.add(peer);
                        closest.clear();
                        ctx.profileOrganizer().selectNotFailingPeers(1, closestExclude, closest, false, ipRestriction, ipSet);
                        if (closest.isEmpty()) {
                            ctx.profileOrganizer().selectFastPeers(1, closestExclude, closest, ipRestriction, ipSet);
                        }
                    } else if (!ctx.commSystem().isConnecting(peer)) {
                        if (log.shouldInfo())
                            log.info("EPS pre-connecting to closest hop " +
                                     peer.toBase64().substring(0,6) + " for tunnel build");
                        preConnectTo(ctx, peer);
                    }
                }
            }
            if (!closest.isEmpty()) {
                closestHop = closest.get(0);
                exclude.add(closestHop);
                length--;
            }
        }

        // furthest-hop restrictions
        // Since we're applying orderPeers() later, we don't know
        // which will be the furthest hop, so select the furthest one here if necessary.

        Hash furthestHop = null;
        if (hiddenOutbound && length > 0) {
            // OBEP
            // check for hidden and outbound, and the paired (inbound) tunnel is zero-hop
            // if so, we need the OBEP to be connected to us, so we get the build reply back
            // This should be rare except at startup
            TunnelManagerFacade tmf = ctx.tunnelManager();
            TunnelPool tp = tmf.getInboundExploratoryPool();
            TunnelPoolSettings tps = tp.getSettings();
            int len = tps.getLength();
            boolean pickFurthest = true;
            if (len <= 0 ||
                tps.getLengthOverride() == 0 ||
                len + tps.getLengthVariance() <= 0) {
                // leave it true
            } else {
                for (TunnelInfo ti : tp.listTunnels()) {
                    if (ti.getLength() > 1) {
                        pickFurthest = false;
                        break;
                    }
                }
            }
            if (pickFurthest) {
                ArraySet<Hash> furthest = new ArraySet<>(1);
                if (log.shouldInfo())
                    log.info("EPS SANFP OBEP exclude " + formatExcludedPeers(exclude));
                ctx.profileOrganizer().selectActiveNotFailingPeers(1, exclude, furthest, ipRestriction, ipSet);
                if (furthest.isEmpty()) {
                    // ANFP does not fall back to non-connected
                    if (log.shouldInfo())
                        log.info("EPS SFP OBEP exclude " + formatExcludedPeers(exclude));
                    ctx.profileOrganizer().selectFastPeers(1, exclude, furthest, ipRestriction, ipSet);
                }
                // D1: Post-selection first-hop quality check for furthest hop
                if (!furthest.isEmpty()) {
                    Hash peer = furthest.iterator().next();
                    if (isFirstHopFailing(ctx, peer)) {
                        if (log.shouldInfo())
                            log.info("EPS furthest hop " + peer.toBase64().substring(0,6) +
                                     " previously failed as first hop, retrying...");
                        exclude.add(peer);
                        furthest.clear();
                        ctx.profileOrganizer().selectFastPeers(1, exclude, furthest, ipRestriction, ipSet);
                    } else if (!ctx.commSystem().isEstablished(peer)) {
                        if (ctx.commSystem().wasUnreachable(peer)) {
                            if (log.shouldInfo())
                                log.info("EPS furthest hop " + peer.toBase64().substring(0,6) +
                                         " is unreachable, retrying selection...");
                            exclude.add(peer);
                            furthest.clear();
                            ctx.profileOrganizer().selectFastPeers(1, exclude, furthest, ipRestriction, ipSet);
                        } else if (!ctx.commSystem().isConnecting(peer)) {
                            if (log.shouldInfo())
                                log.info("EPS pre-connecting to furthest hop " +
                                         peer.toBase64().substring(0,6) + " for tunnel build");
                            preConnectTo(ctx, peer);
                        }
                    }
                }
                if (!furthest.isEmpty()) {
                    furthestHop = furthest.get(0);
                    exclude.add(furthestHop);
                    ctx.commSystem().exemptIncoming(furthestHop);
                    length--;
                }
            }
        }

        if (length > 0) {
            Set<Hash> matches = new ArraySet<>(length);
            if (exploreHighCap) {
                if (log.shouldInfo())
                    log.info("EPS SHCP " + length + (isInbound ? " IB " : " OB ") + formatExcludedPeers(exclude));
                ctx.profileOrganizer().selectHighCapacityPeers(length, exclude, matches, ipRestriction, ipSet);
            } else {
                // As of 0.9.23, we include a max of 2 not failing peers,
                // to improve build success on 3-hop tunnels.
                // Peer org credits existing items in matches
                if (length > 2)
                    ctx.profileOrganizer().selectHighCapacityPeers(length - 2, exclude, matches);
                // select will check both matches and exclude, no need to add matches to exclude here
                if (log.shouldInfo())
                    log.info("EPS SNFP " + length + (isInbound ? " IB " : " OB ") + formatExcludedPeers(exclude));
                ctx.profileOrganizer().selectNotFailingPeers(length, exclude, matches, false, ipRestriction, ipSet);
                if (matches.isEmpty()) {
                    // Fallback: try fast peers if not-failing is empty
                    ctx.profileOrganizer().selectFastPeers(length, exclude, matches, ipRestriction, ipSet);
                }
            }
            matches.remove(ctx.routerHash());
            rv.addAll(matches);
        }
        if (log.shouldInfo())
            log.info("EPS " + length + (isInbound ? " IB " : " OB ") + "final: " + formatExcludedPeers(exclude));

        if (closestHop != null) {
            if (isInbound)
                rv.add(0, closestHop);
            else
                rv.add(closestHop);
            length++;
        }
        if (furthestHop != null) {
            // always OBEP for now, nothing special for IBGW
            if (isInbound)
                rv.add(furthestHop);
            else
                rv.add(0, furthestHop);
        }
        if (rv.size() > 1)
            orderPeers(rv, settings.getRandomKey());
        // Ghost-peer filtering: peers with consistent tunnel build timeouts
        // waste build attempts and test cycles.  Client pools already filter
        // these (ClientPeerSelector.filterGhostPeers); apply the same here so
        // exploratory builds do not keep hammering the same failing peers.
        // Fall back to the original selection if every peer is a ghost, so we
        // never fail the build entirely.
        TunnelManagerFacade tmf = ctx.tunnelManager();
        GhostPeerManager ghostManager = tmf.getGhostPeerManager();
        if (ghostManager != null && rv.size() > 1) {
            List<Hash> before = new ArrayList<>(rv);
            rv.removeIf(peer -> ghostManager.isGhost(peer));
            if (rv.isEmpty()) {
                rv.addAll(before);
                if (log.shouldWarn()) {
                    log.warn("EPS all selected peers were ghosts -> keeping original selection");
                }
            } else if (rv.size() != before.size() && log.shouldDebug()) {
                log.debug("EPS ghost-filtered " + (before.size() - rv.size()) + " peer(s)");
            }
        }
        if (isInbound)
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());

        if (rv.size() > 1) {
            if (!checkTunnel(isInbound, true, rv)) {
                // Record the failed peers in the exploratory cooldown so the
                // next selection avoids them.  Only failures are recorded —
                // successful selections never cooldown peers.
                long failNow = ctx.clock().now();
                int recorded = 0;
                for (Hash peer : rv) {
                    if (!peer.equals(ctx.routerHash())) {
                        _exploratoryCooldowns.put(peer, failNow);
                        recorded++;
                    }
                }
                if (log.shouldDebug())
                    log.debug("EPS cooldown record: recorded=" + recorded +
                              " rvSize=" + rv.size() +
                              " from=" + Thread.currentThread().getName());
                rv = null;
            }
        }
        if (isInbound && rv != null && rv.size() > 1)
            ctx.commSystem().exemptIncoming(rv.get(1));
        return rv;
    }

    private static int getMinNonfailingPct(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.exploratoryPeer.minNonfailingPct", 15);
    }
    private static int getMinActivePeersStartup(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.exploratoryPeer.minActivePeersStartup", 6);
    }
    private static int getMinActivePeers(RouterContext ctx) {
        return ctx.getProperty("i2p.tunnel.exploratoryPeer.minActivePeers", 12);
    }

    /**
     *  Should we pick from the high cap pool instead of the larger not failing pool?
     *  This should return false most of the time, but if the not-failing pool's
     *  build success rate is much worse, return true so that reliability
     *  is maintained.
     * @return whether pick high cap
     */
    private boolean shouldPickHighCap() {
        if (ctx.getBooleanProperty("router.exploreHighCapacity"))
            return true;

        // If we don't have enough connected peers, use exploratory
        // tunnel building to get us better-connected.
        // This is a tradeoff, we could easily lose our exploratory tunnels,
        // but with so few connected peers, anonymity suffers and reliability
        // will decline also, as we repeatedly try to build tunnels
        // through the same few peers.
        int active = ctx.commSystem().countActivePeers();
        if (active < getMinActivePeersStartup(ctx))
            return false;

        // no need to explore too wildly at first (if we have enough connected peers)
        long uptime = ctx.router().getUptime();
        if (uptime <= (SystemVersion.isAndroid() ? 15*60*1000L : 5*60*1000L))
            return true;
        // wait for first expiration of old RIs, if we had a long downtime
        if (uptime <= 61*60*1000L && ctx.router().getEstimatedDowntime() > 3*24*60*60*1000L)
            return true;
        // or at the end
        if (ctx.router().gracefulShutdownInProgress())
            return true;

        // see above
        if (active < getMinActivePeers(ctx))
            return false;

        // ok, if we aren't explicitly asking for it, we should try to pick peers
        // randomly from the 'not failing' pool.  However, if we are having a
        // hard time building exploratory tunnels, lets fall back again on the
        // high capacity peers, at least for a little bit.
        int failPct;
        // getEvents() will be 0 for first 10 minutes
        if (uptime <= 11*60*1000L) {
            failPct = 100 - getMinNonfailingPct(ctx);
        } else {
            // If well connected or ff, don't pick from high cap
            // even during congestion, because congestion starts from the top
            if (active > 500 || ctx.netDb().floodfillEnabled())
                return false;

            failPct = getExploratoryFailPercentage();
            // always try a little, this helps keep the failPct stat accurate too
            if (failPct > 100 - getMinNonfailingPct(ctx))
                failPct = 100 - getMinNonfailingPct(ctx);
        }
        return (failPct >= ctx.random().nextInt(100));
    }

    /**
     * We should really use the difference between the exploratory fail rate
     * and the high capacity fail rate - but we don't have a stat for high cap,
     * so use the fast (== client) fail rate, it should be close
     * if the expl. and client tunnel lengths aren't too different.
     * So calculate the difference between the exploratory fail rate
     * and the client fail rate, normalized to 100:
     * 100 * ((Efail - Cfail) / (100 - Cfail))
     * Even this isn't the "true" rate for the NonFailingPeers pool, since we
     * are often building exploratory tunnels using the HighCapacity pool.
     * @return the exploratory fail percentage
     */
    private int getExploratoryFailPercentage() {
        int c = getFailPercentage("Client");
        int e = getFailPercentage("Exploratory");
        if (e <= c || e <= 25) // doing very well (unlikely)
            return 0;
        // Doing very badly? This is important to prevent network congestion collapse
        if (c >= 70 || e >= 75)
            return 100 - getMinNonfailingPct(ctx);
        return (100 * (e-c)) / (100-c);
    }

    private int getFailPercentage(String t) {
        String pfx = "tunnel.build" + t;
        int timeout = getEvents(pfx + "Expire", 10*60*1000L);
        int reject = getEvents(pfx + "Reject", 10*60*1000L);
        int accept = getEvents(pfx + "Success", 10*60*1000L);
        if (accept + reject + timeout <= 0)
            return 0;
        double pct = (double)(reject + timeout) / (accept + reject + timeout);
        return (int)(100 * pct);
    }

    /** Use current + last to get more recent and smoother data */
    private int getEvents(String stat, long period) {
        RateStat rs = ctx.statManager().getRate(stat);
        if (rs == null)
            return 0;
        Rate r = rs.getRate(period);
        if (r == null)
            return 0;
        return (int) (r.computeAverages().getTotalEventCount());
    }
}
