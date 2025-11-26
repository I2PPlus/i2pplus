package net.i2p.router.tunnel.pool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import static net.i2p.router.peermanager.ProfileOrganizer.Slice.*;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.util.ArraySet;

/**
 * Selects peers for client tunnels by randomly picking from the fast peer pool,
 * and ordering them by XOR distance from a random key.
 *
 * This class ensures that client tunnels are built with high-performance peers,
 * and enforces restrictions such as IPv6-only, transport disablement, and hidden mode.
 *
 * This class supports both inbound and outbound tunnels and ensures correct
 * hop selection and ordering.
 *
 * During the first 10 minutes after startup, this class prefers high-capacity peers
 * to avoid unstable fast peers, improving tunnel build success during warmup.
 */
class ClientPeerSelector extends TunnelPeerSelector {

    private static final int MIN_ACTIVE_PEERS = 800;

    public ClientPeerSelector(RouterContext context) {
        super(context);
    }

    /**
     * Selects peers for a client tunnel, ordered by XOR distance from a random key.
     *
     * Returns a list of peers in tunnel order, with the local router included.
     * - ENDPOINT FIRST (for outbound)
     * - GATEWAY LAST (for inbound)
     *
     * @param settings tunnel settings
     * @return ordered list of peers (ENDPOINT FIRST), including local router, or null if not possible
     */
    public List<Hash> selectPeers(TunnelPoolSettings settings) {
        int length = getLength(settings);
        if (length < 0 || ((length == 0) && (settings.getLength() + settings.getLengthVariance() > 0))) {
            return null;
        }

        boolean isInbound = settings.isInbound();
        boolean shouldLog = log.shouldInfo();

        if (length == 0) {
            List<Hash> rv = new ArrayList<>(1);
            rv.add(ctx.routerHash());
            return rv;
        }

        if (shouldSelectExplicit(settings)) {
            return selectExplicit(settings, length);
        }

        boolean exploreHighCap = shouldPickHighCap();

        // Special case flags
        boolean v6Only = isIPv6Only();
        boolean ntcpDisabled = isNTCPDisabled();
        boolean ssuDisabled = isSSUDisabled();
        boolean checkClosestHop = v6Only || ntcpDisabled || ssuDisabled;
        boolean hidden = ctx.router().isHidden() ||
                         ctx.router().getRouterInfo().getAddressCount() <= 0 ||
                         !ctx.commSystem().haveInboundCapacity(95);
        boolean hiddenInbound = hidden && isInbound;
        boolean hiddenOutbound = hidden && !isInbound;
        int ipRestriction = ctx.getBooleanProperty("i2np.allowLocal") || length <= 1
                ? 0 : settings.getIPRestriction();
        MaskedIPSet ipSet = ipRestriction > 0 ? new MaskedIPSet(16) : null;

        Set<Hash> exclude = getExclude(isInbound, false);
        ArraySet<Hash> matches = new ArraySet<>(length);
        List<Hash> rv = new ArrayList<>(length + 1);
        SessionKey randomKey = settings.getRandomKey();

        if (length == 1) {
            if (checkClosestHop) {
                exclude = getClosestHopExclude(isInbound, exclude);
            }
            exclude = isInbound ? new IBGWExcluder(exclude) : new OBEPExcluder(exclude);

            if (hiddenInbound) {
                logSelect("SelectFastPeers", "closest Inbound", exclude, shouldLog);
                ctx.profileOrganizer().selectFastPeers(1, exclude, matches);
                if (matches.isEmpty()) {
                    ctx.profileOrganizer().selectActiveNotFailingPeers(1, exclude, matches, ipRestriction, ipSet);
                }
            }

            if (matches.isEmpty()) {
                if (exploreHighCap) {
                    logSelect("SelectHighCapPeers", "1-hop", exclude, shouldLog);
                    ctx.profileOrganizer().selectHighCapacityPeers(1, exclude, matches, ipRestriction, ipSet);
                } else {
                    logSelect("SelectFastPeers", "1-hop", exclude, shouldLog);
                    ctx.profileOrganizer().selectFastPeers(1, exclude, matches);
                }

                if (matches.isEmpty()) {
                    if (hiddenInbound && log.shouldWarn()) {
                        log.warn("Can't find any active peers for Inbound connection");
                    }
                    return null;
                }
            }

            matches.remove(ctx.routerHash());
            rv.addAll(matches);
        } else {
            // Multi-hop tunnel: select last hop, middle hops, and first hop

            Set<Hash> lastHopExclude = isInbound
                    ? getClosestHopExclude(true, exclude)
                    : new OBEPExcluder(exclude);

            if (hiddenOutbound) {
                TunnelManagerFacade tmf = ctx.tunnelManager();
                TunnelPool tp = tmf.getInboundPool(settings.getDestination());
                boolean pickFurthest = isFurthestHopRequired(tp);

                if (pickFurthest) {
                    logSelect("SelectFastPeers", "OutboundEndpoint", lastHopExclude, shouldLog);
                    ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                    if (matches.isEmpty()) {
                        ctx.profileOrganizer().selectActiveNotFailingPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                    }
                    if (matches.isEmpty()) {
                        if (log.shouldWarn()) {
                            log.warn("Failed to select fast/non-failing peer for (hidden) OutboundEndpoint");
                        }
                        return null;
                    }
                    ctx.commSystem().exemptIncoming(matches.get(0));
                } else {
                    if (exploreHighCap) {
                        ctx.profileOrganizer().selectHighCapacityPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
                    } else {
                        ctx.profileOrganizer().selectFastPeers(1, lastHopExclude, matches, randomKey,
                                length == 2 ? SLICE_0_1 : SLICE_0, ipRestriction, ipSet);
                    }
                }
            } else {
                if (exploreHighCap) {
                    ctx.profileOrganizer().selectHighCapacityPeers(1, lastHopExclude, matches, ipRestriction, ipSet);
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
                logSelect("SelectHighCapPeers", "Middle", exclude, shouldLog && exploreHighCap);
                logSelect("SelectFastPeers", "Middle", exclude, shouldLog && !exploreHighCap);

                if (exploreHighCap) {
                    ctx.profileOrganizer().selectHighCapacityPeers(length - 2, exclude, matches, ipRestriction, ipSet);
                } else {
                    ctx.profileOrganizer().selectFastPeers(length - 2, exclude, matches, randomKey, SLICE_2_3, ipRestriction, ipSet);
                }

                if (matches.size() > 1) {
                    List<Hash> ordered = new ArrayList<>(matches);
                    orderPeers(ordered, randomKey);
                    rv.addAll(ordered);
                } else {
                    rv.addAll(matches);
                }
                exclude.addAll(matches);
                matches.clear();
            }

            // First hop
            Set<Hash> firstHopExclude = isInbound
                    ? new IBGWExcluder(exclude)
                    : getClosestHopExclude(false, exclude);

            if (exploreHighCap) {
                ctx.profileOrganizer().selectHighCapacityPeers(1, firstHopExclude, matches, ipRestriction, ipSet);
            } else {
                ctx.profileOrganizer().selectFastPeers(1, firstHopExclude, matches, randomKey,
                        length == 2 ? SLICE_2_3 : SLICE_1, ipRestriction, ipSet);
            }

            matches.remove(ctx.routerHash());
            rv.addAll(matches);
        }

        if (rv.size() < length) {
            if (log.shouldWarn()) {
                log.warn("Not enough peers to build requested " + length + " hop tunnel (" + rv.size() + " available)");
            }
            int min = settings.getLength();
            int skew = settings.getLengthVariance();
            if (skew < 0) min += skew;
            if (rv.size() < min) {
                return null;
            }
        }

        if (isInbound) {
            rv.add(0, ctx.routerHash());
        } else {
            rv.add(ctx.routerHash());
        }

        if (rv.size() > 1 && !checkTunnel(isInbound, false, rv)) {
            return null;
        }

        if (isInbound && rv.size() > 1) {
            ctx.commSystem().exemptIncoming(rv.get(1));
        }

        return rv;
    }

    /**
     * Determine if the paired inbound tunnel pool requires selecting a furthest hop.
     */
    private boolean isFurthestHopRequired(TunnelPool tp) {
        if (tp == null) return false;

        TunnelPoolSettings tps = tp.getSettings();
        int len = tps.getLength();
        if (len <= 0 || tps.getLengthOverride() == 0 || len + tps.getLengthVariance() <= 0) {
            return true;
        }

        for (TunnelInfo ti : tp.listTunnels()) {
            if (ti.getLength() > 1) {
                return false;
            }
        }

        return true;
    }

    /**
     * Determine whether to use high-capacity peers instead of fast peers.
     *
     * This is used during startup to avoid unstable fast peer selection
     * and improve tunnel build success.
     */
    private boolean shouldPickHighCap() {
        if (!ctx.getBooleanProperty("router.exploreHighCapacity") ||
            "false".equalsIgnoreCase(ctx.getProperty("router.exploreHighCapacity")) ||
            ctx.router().getUptime() > 30*60*1000) {
            return false;
        }
        return true;
    }

    private void logSelect(String method, String label, Collection<Hash> exclude, boolean shouldLog) {
        if (shouldLog) {
            log.info(method + " " + label + "\n* Excluded: " + formatHashCollection(exclude, 5));
        }
    }

    private String formatHashCollection(Collection<Hash> hashes, int max) {
        if (hashes == null || hashes.isEmpty()) return "none";

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Hash hash : hashes) {
            sb.append("[").append(hash.toBase64().substring(0, 6)).append("]");
            if (++count >= max) break;
            sb.append(" ");
        }
        if (hashes.size() > max) sb.append(" ...");
        return sb.toString();
    }

    private String formatHashCollection(Collection<Hash> hashes) {
        return formatHashCollection(hashes, 5);
    }

    /**
     * Base class for custom Set implementations that automatically
     * add to the set during a contains() check.
     */
    private abstract class ExcluderBase implements Set<Hash> {
        protected final Set<Hash> s;

        protected ExcluderBase(Set<Hash> set) {
            this.s = set;
        }

        public boolean add(Hash hash) { return s.add(hash); }
        public boolean addAll(Collection<? extends Hash> c) { return s.addAll(c); }
        public void clear() { s.clear(); }
        public boolean contains(Object o) { return s.contains(o); }
        public boolean containsAll(Collection<?> c) { return s.containsAll(c); }
        public boolean isEmpty() { return s.isEmpty(); }
        public Iterator<Hash> iterator() { return s.iterator(); }
        public boolean remove(Object o) { return s.remove(o); }
        public boolean removeAll(Collection<?> c) { return s.removeAll(c); }
        public boolean retainAll(Collection<?> c) { return s.retainAll(c); }
        public int size() { return s.size(); }
        public Object[] toArray() { return s.toArray(); }
        public <T> T[] toArray(T[] a) { return s.toArray(a); }
    }

    /**
     * Automatically excludes peers that cannot be used as Inbound Gateway (IBGW).
     */
    private class IBGWExcluder extends ExcluderBase {
        public IBGWExcluder(Set<Hash> set) { super(set); }

        @Override
        public boolean contains(Object o) {
            if (s.contains(o)) return true;
            Hash h = (Hash) o;
            boolean exclude = !allowAsIBGW(h);
            if (exclude) {
                s.add(h);
                if (log.shouldDebug()) {
                    log.debug("InboundGateway exclude [" + h.toBase64().substring(0,6) + "]");
                }
            }
            return exclude;
        }
    }

    /**
     * Automatically excludes peers that cannot be used as Outbound Endpoint (OBEP).
     */
    private class OBEPExcluder extends ExcluderBase {
        public OBEPExcluder(Set<Hash> set) { super(set); }

        @Override
        public boolean contains(Object o) {
            if (s.contains(o)) return true;
            Hash h = (Hash) o;
            boolean exclude = !allowAsOBEP(h);
            if (exclude) {
                s.add(h);
                if (log.shouldDebug()) {
                    log.debug("OutboundEndpoint exclude [" + h.toBase64().substring(0,6) + "]");
                }
            }
            return exclude;
        }
    }
}