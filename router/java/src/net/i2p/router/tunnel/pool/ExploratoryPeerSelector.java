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

        //if (false && shouldSelectExplicit(settings)) {
        //    List<Hash> rv = selectExplicit(settings, length);
        //    if (l.shouldDebug())
        //        l.debug("Explicit peers selected: " + rv);
        //    return rv;
        //}

        boolean isInbound = settings.isInbound();
        Set<Hash> exclude = getExclude(isInbound, true);
        exclude.add(ctx.routerHash());

        // special cases
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
        MaskedIPSet ipSet = ipRestriction > 0 ? new MaskedIPSet(16) : null;


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
                if (log.shouldInfo())
                    log.info("SelectAllNonFailingPeers closest " + (isInbound ? "Inbound " : "Outbound ") + closestExclude);
                ctx.profileOrganizer().selectActiveNotFailingPeers(1, closestExclude, closest, ipRestriction, ipSet);
                if (closest.isEmpty()) {
                    if (hiddenInbound) {
                        // No connected peers found, give up now
                        if (log.shouldWarn())
                            log.warn("SelectAllNonFailingPeers (hidden) closest Inbound -> No active peers found, returning null");
                        return null;
                    }
                    // ANFP does not fall back to non-connected
                    if (log.shouldInfo())
                        log.info("SelectFastPeersclosest " + (isInbound ? "Inbound " : "Outbound ") + closestExclude);
                    ctx.profileOrganizer().selectFastPeers(1, closestExclude, closest, ipRestriction, ipSet);
                }
            } else if (exploreHighCap) {
                if (log.shouldInfo())
                    log.info("SelectHighCapPeers closest " + (isInbound ? "Inbound " : "Outbound ") + closestExclude);
                ctx.profileOrganizer().selectHighCapacityPeers(1, closestExclude, closest, ipRestriction, ipSet);
            } else {
                if (log.shouldInfo())
                    log.info("SelectNonFailingPeers closest " + (isInbound ? "Inbound " : "Outbound ") + closestExclude);
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
                    log.info("SelectAllNonFailingPeers OBEP exclude " + exclude);
                ctx.profileOrganizer().selectActiveNotFailingPeers(1, exclude, furthest, ipRestriction, ipSet);
                if (furthest.isEmpty()) {
                    // ANFP does not fall back to non-connected
                    if (log.shouldInfo())
                        log.info("SelectFastPeersOBEP exclude " + exclude);
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

        ArrayList<Hash> rv = new ArrayList<Hash>(length + 3);
        if (length > 0) {
            Set<Hash> matches = new ArraySet<Hash>(length);
            if (exploreHighCap) {
                if (log.shouldInfo())
                    log.info("SelectHighCapPeers " + length + (isInbound ? " Inbound " : " Outbound ") + exclude);
                ctx.profileOrganizer().selectHighCapacityPeers(length, exclude, matches, ipRestriction, ipSet);
            } else {
                // As of 0.9.23, we include a max of 2 not failing peers,
                // to improve build success on 3-hop tunnels.
                // Peer org credits existing items in matches
                if (length > 2)
                    ctx.profileOrganizer().selectHighCapacityPeers(length - 2, exclude, matches);
                // select will check both matches and exclude, no need to add matches to exclude here
                if (log.shouldInfo())
                    log.info("SelectNonFailingPeers " + length + (isInbound ? " Inbound " : " Outbound ") + exclude);
                ctx.profileOrganizer().selectNotFailingPeers(length, exclude, matches, false, ipRestriction, ipSet);
            }
            matches.remove(ctx.routerHash());
            rv.addAll(matches);
        }
        if (log.shouldInfo())
            log.info("ExploratoryPeerSelector " + length + (isInbound ? " Inbound " : " Outbound ") + "final: " + exclude);

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
        if (rv.size() > 1) {
            if (!checkTunnel(isInbound, true, rv))
                rv = null;
        }
        if (isInbound && rv != null && rv.size() > 1)
            ctx.commSystem().exemptIncoming(rv.get(1));
        return rv;
    }

//    private static final int MIN_NONFAILING_PCT = 15;
    private static final int MIN_NONFAILING_PCT = 30;
//    private static final int MIN_ACTIVE_PEERS_STARTUP = 6;
    private static final int MIN_ACTIVE_PEERS_STARTUP = 10;
//    private static final int MIN_ACTIVE_PEERS = 12;
    private static final int MIN_ACTIVE_PEERS = 20;

    /**
     *  Should we pick from the high cap pool instead of the larger not failing pool?
     *  This should return false most of the time, but if the not-failing pool's
     *  build success rate is much worse, return true so that reliability
     *  is maintained.
     */
    private boolean shouldPickHighCap() {
        if (ctx.getBooleanProperty("router.exploreHighCapacity") ||
            (ctx.getProperty("router.exploreHighCapacity") != null && !ctx.getProperty("router.exploreHighCapacity").equals("false")))
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
            return true;
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
        // hard time building exploratory tunnels, let's fall back again on the
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
            //if (l.shouldDebug())
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
