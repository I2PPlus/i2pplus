package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    private static String formatExcludedPeers(Set<Hash> peers) {
        if (peers == null || peers.isEmpty()) {return "[]";}
        StringBuilder sb = new StringBuilder(peers.size() * 10);
        int count = 0;
        for (Hash h : peers) {
            if (count % 10 == 0) {
                sb.append("\n* ");
            }
            sb.append('[').append(h.toBase64(), 0, 6).append("] ");
            count++;
        }
        return sb.toString();
    }

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
    public List<Hash> selectPeers(TunnelPoolSettings settings) {
        int length = getLength(settings);
        if (length < 0) {
            if (log.shouldDebug())
                log.debug("Tunnel length requested is zero: " + settings);
            return null;
        }

        boolean isInbound = settings.isInbound();
        Set<Hash> exclude = getExclude(isInbound, true);
        exclude.add(ctx.routerHash());

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
        int ipRestriction = settings.getIPRestriction();
        // Increase IP restriction under low tunnel build success to improve diversity @since 0.9.68+
        if (ipRestriction > 0 && length > 1) {
            double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
            if (buildSuccess < 0.40) {
                // Increase the limit to allow more peers per IP during attacks
                ipRestriction = Math.min(ipRestriction + 1, 16);
            }
        }
        if (ctx.getBooleanProperty("i2np.allowLocal") || length <= 1) {ipRestriction = 0;}
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

            ArraySet<Hash> closest = new ArraySet<Hash>(1);
            if (hiddenInbound || lowOutbound) {
                // If hidden and inbound, use connected peers to guarantee
                // that the adjacent hop can connect to us.
                if (log.shouldInfo()) {
                    log.info("EPS SANFP closest " + (isInbound ? "IB " : "OB ") + formatExcludedPeers(closestExclude));
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
                    return new ArrayList<Hash>(1); // Empty list = 0-hop tunnel
                }
            } else if (exploreHighCap) {
                if (log.shouldInfo())
                    log.info("EPS SHCP closest " + (isInbound ? "IB " : "OB ") + formatExcludedPeers(closestExclude));
                ctx.profileOrganizer().selectHighCapacityPeers(1, closestExclude, closest, ipRestriction, ipSet);
            } else {
                if (log.shouldInfo())
                    log.info("EPS SNFP closest " + (isInbound ? "IB " : "OB ") + formatExcludedPeers(closestExclude));
                ctx.profileOrganizer().selectNotFailingPeers(1, closestExclude, closest, false, ipRestriction, ipSet);
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
                ArraySet<Hash> furthest = new ArraySet<Hash>(1);
                if (log.shouldInfo())
                    log.info("EPS SANFP OBEP exclude " + formatExcludedPeers(exclude));
                ctx.profileOrganizer().selectActiveNotFailingPeers(1, exclude, furthest, ipRestriction, ipSet);
                if (furthest.isEmpty()) {
                    // ANFP does not fall back to non-connected
                    if (log.shouldInfo())
                        log.info("EPS SFP OBEP exclude " + formatExcludedPeers(exclude));
                    ctx.profileOrganizer().selectFastPeers(1, exclude, furthest, ipRestriction, ipSet);
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
            Set<Hash> matches = new ArraySet<Hash>(length);
            if (exploreHighCap) {
                if (log.shouldInfo())
                    log.info("EPS SHCP " + length + (isInbound ? " IB " : " OB ") + formatExcludedPeers(exclude));
                ctx.profileOrganizer().selectHighCapacityPeers(length, exclude, matches, ipRestriction, ipSet);
            } else {
                // As of 0.9.23, we include a max of 2 not failing peers,
                // to improve build success on 3-hop tunnels.
                // Peer org credits existing items in matches

                // Reduce high cap allocation under attack to include more L/U caps @since 0.9.68+
                int highCapCount = length - 2;
                if (highCapCount > 0) {
                    double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
                    if (buildSuccess < 0.50) {
                        // Under low success: reduce high cap allocation to minimum 1
                        highCapCount = Math.max(highCapCount / 2, 1);
                    }
                    if (buildSuccess < 0.30) {
                        // Under severe failure: use almost all non-high-cap peers
                        highCapCount = Math.max(highCapCount / 2, 1);
                    }
                    ctx.profileOrganizer().selectHighCapacityPeers(highCapCount, exclude, matches);
                }
                // select will check both matches and exclude, no need to add matches to exclude here
                if (log.shouldInfo())
                    log.info("EPS SNFP " + length + (isInbound ? " IB " : " OB ") + formatExcludedPeers(exclude));
                ctx.profileOrganizer().selectNotFailingPeers(length, exclude, matches, false, ipRestriction, ipSet);
            }
            matches.remove(ctx.routerHash());
            rv.addAll(matches);
        }
        if (log.shouldInfo())
            log.info("EPS " + length + (isInbound ? " IB " : " OB ") + "final: " + formatExcludedPeers(exclude));

        if (rv.size() > 1)
            orderPeers(rv, settings.getRandomKey());
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
            length++;
        }
        //if (length != rv.size() && log.shouldWarn())
        //    log.warn("EPS requested " + length + " got " + rv.size() + ": " + DataHelper.toString(rv));
        //else if (log.shouldDebug())
        //    log.debug("EPS result: " + DataHelper.toString(rv));
        if (isInbound)
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());

                // Progressive fallback under attack conditions @since 0.9.68+
        boolean firstFallbackNeeded = false;
        if (rv.size() <= 1) {
                double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
                boolean isUnderAttack = buildSuccess < 0.40;

                if (isUnderAttack && rv.size() == 1) {
                    // Attack detected (25-40% success) but only have self - try to get more peers
                    if (log.shouldWarn()) {
                        log.warn("Attack detected (" + (int)(buildSuccess * 100) + "% success) -> Trying exploratory fallback peer selection...");
                    }

                    // Remove self temporarily to check for available peers
                    rv.clear();

                    // Try with relaxed IP restrictions
                    ArraySet<Hash> fallback = new ArraySet<Hash>(length + 1);
                    ctx.profileOrganizer().selectNotFailingPeers(length + 1, exclude, fallback, false, 0, null);
                    fallback.remove(ctx.routerHash());

                    if (!fallback.isEmpty()) {
                        rv.addAll(fallback);
                        if (log.shouldDebug()) {
                            log.debug("Exploratory fallback successful: found " + rv.size() + " peers");
                        }
                    }

                    // Re-add self at the end
                    if (isInbound)
                        rv.add(0, ctx.routerHash());
                    else
                        rv.add(ctx.routerHash());

                    // Mark that we need the catastrophic fallback if this didn't work
                    firstFallbackNeeded = true;
                }

                // Catastrophic tunnel collapse - be even less selective @since 0.9.68+
                if (rv.size() <= 1 && buildSuccess < 0.25) {
                    if (log.shouldWarn()) {
                        log.warn("Catastrophic tunnel collapse detected (" + (int)(buildSuccess * 100) + "% success) -> Using emergency peer selection...");
                    }

                    // Only clear if we haven't already populated from first fallback
                    if (!firstFallbackNeeded) {
                        rv.clear();
                    }

                    // Emergency: include peers from all tiers without IP restrictions
                    ArraySet<Hash> emergency = new ArraySet<Hash>(length + 2);
                    // Get all available peers and filter
                    java.util.Set<Hash> allPeers = ctx.profileOrganizer().selectAllPeers();
                    for (Hash peer : allPeers) {
                        if (emergency.size() >= length + 2) break;
                        if (peer.equals(ctx.routerHash())) continue;
                        if (exclude.contains(peer)) continue;
                        emergency.add(peer);
                    }

                    if (!emergency.isEmpty()) {
                        // Don't clear existing peers, add to them
                        rv.addAll(emergency);
                        if (log.shouldWarn()) {
                            log.warn("Emergency peer selection found " + emergency.size() + " peers, total: " + rv.size());
                        }
                    } else if (!firstFallbackNeeded) {
                        // No peers available - allow 0-hop exploratory tunnel (just self)
                        // Exploratory tunnels can use 0-hops; client tunnels should fail instead
                        if (log.shouldWarn()) {
                            log.warn("No peers available for emergency tunnel build -> Allowing 0-hop exploratory tunnel");
                        }
                        // Return just self (0-hop tunnel) for exploratory pools
                        rv.clear();
                        if (isInbound)
                            rv.add(0, ctx.routerHash());
                        else
                            rv.add(ctx.routerHash());
                        return rv;
                    }

                    // Re-add self at the end if not already present
                    if (!rv.contains(ctx.routerHash())) {
                        if (isInbound)
                            rv.add(0, ctx.routerHash());
                        else
                            rv.add(ctx.routerHash());
                    }
                }
            }

        if (rv.size() > 1) {
            if (!checkTunnel(isInbound, true, rv))
                rv = null;
        }
        if (isInbound && rv != null && rv.size() > 1)
            ctx.commSystem().exemptIncoming(rv.get(1));
        return rv;
    }

    private static final int MIN_NONFAILING_PCT = 15;
    private static final int MIN_ACTIVE_PEERS_STARTUP = 6;
    private static final int MIN_ACTIVE_PEERS = 12;

    /**
     *  Should we pick from the high cap pool instead of the larger not failing pool?
     *  This should return false most of the time, but if the not-failing pool's
     *  build success rate is much worse, return true so that reliability
     *  is maintained.
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
        if (active < MIN_ACTIVE_PEERS_STARTUP)
            return false;

        // no need to explore too wildly at first (if we have enough connected peers)
        long uptime = ctx.router().getUptime();
        if (uptime <= (SystemVersion.isAndroid() ? 15*60*1000 : 5*60*1000))
            return false;
        // wait for first expiration of old RIs, if we had a long downtime
        if (uptime <= 61*60*1000 && ctx.router().getEstimatedDowntime() > 3*24*60*60*1000L)
            return true;
        // or at the end
        if (ctx.router().gracefulShutdownInProgress())
            return true;

        // see above
        if (active < MIN_ACTIVE_PEERS)
            return false;

        // ok, if we aren't explicitly asking for it, we should try to pick peers
        // randomly from the 'not failing' pool.  However, if we are having a
        // hard time building exploratory tunnels, lets fall back again on the
        // high capacity peers, at least for a little bit.
        int failPct;
        // getEvents() will be 0 for first 10 minutes
        if (uptime <= 11*60*1000) {
            failPct = 100 - MIN_NONFAILING_PCT;
        } else {
            // If well connected or ff, don't pick from high cap
            // even during congestion, because congestion starts from the top
            if (active > 500 || ctx.netDb().floodfillEnabled())
                return false;

            failPct = getExploratoryFailPercentage();
            //Log l = ctx.logManager().getLog(getClass());
            //if (l.shouldLog(Log.DEBUG))
            //    l.debug("Normalized Fail pct: " + failPct);
            // always try a little, this helps keep the failPct stat accurate too
            if (failPct > 100 - MIN_NONFAILING_PCT)
                failPct = 100 - MIN_NONFAILING_PCT;
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
     *    100 * ((Efail - Cfail) / (100 - Cfail))
     * Even this isn't the "true" rate for the NonFailingPeers pool, since we
     * are often building exploratory tunnels using the HighCapacity pool.
     */
    private int getExploratoryFailPercentage() {
        int c = getFailPercentage("Client");
        int e = getFailPercentage("Exploratory");
        //Log l = ctx.logManager().getLog(getClass());
        //if (l.shouldDebug())
        //    l.debug("Client, Expl. Fail pct: " + c + ", " + e);
        if (e <= c || e <= 25) // doing very well (unlikely)
            return 0;
        // Doing very badly? This is important to prevent network congestion collapse
        if (c >= 70 || e >= 75)
            return 100 - MIN_NONFAILING_PCT;
        return (100 * (e-c)) / (100-c);
    }

    private int getFailPercentage(String t) {
        String pfx = "tunnel.build" + t;
        int timeout = getEvents(pfx + "Expire", 10*60*1000);
        int reject = getEvents(pfx + "Reject", 10*60*1000);
        int accept = getEvents(pfx + "Success", 10*60*1000);
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
