package net.i2p.router.tunnel.pool;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import net.i2p.CoreVersion;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.crypto.SipHashInline;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.ArraySet;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Coordinate the selection of peers to go into a tunnel for one particular pool.
 */
public abstract class TunnelPeerSelector extends ConnectChecker {

    private static final String DEFAULT_EXCLUDE_CAPS = String.valueOf(Router.CAPABILITY_BW12) +
                                                       String.valueOf(Router.CAPABILITY_BW32) +
                                                       String.valueOf(Router.CAPABILITY_BW64) +
                                                       String.valueOf(Router.CAPABILITY_UNREACHABLE) +
                                                       String.valueOf(Router.CAPABILITY_CONGESTION_MODERATE) +
                                                       String.valueOf(Router.CAPABILITY_CONGESTION_SEVERE) +
                                                       String.valueOf(Router.CAPABILITY_NO_TUNNELS);

    protected TunnelPeerSelector(RouterContext context) {
        super(context);
    }

    /**
     * Which peers should go into the next tunnel for the given settings?
     *
     * @return ordered list of Hash objects (one per peer) specifying what order
     *         they should appear in a tunnel (ENDPOINT FIRST).  This includes
     *         the local router in the list.  If there are no tunnels or peers
     *         to build through, and the settings reject 0 hop tunnels, this will
     *         return null.
     */
    public abstract List<Hash> selectPeers(TunnelPoolSettings settings);

    /**
      *  @return randomized number of hops 0-7, not including ourselves
      */
    protected int getLength(TunnelPoolSettings settings) {
        int length = settings.getLength();
        int override = settings.getLengthOverride();
        if (override >= 0) {
            length = override;
        } else if (settings.getLengthVariance() != 0) {
            int skew = settings.getLengthVariance();
            if (skew > 0)
                length += ctx.random().nextInt(skew+1);
            else {
                skew = 1 - skew;
                int off = ctx.random().nextInt(skew);
                if (ctx.random().nextBoolean())
                    length += off;
                else
                    length -= off;
            }
        }
        if (length < 0)
            length = 0;
        else if (length > 7) // as documented in tunnel.html
            length = 7;

        // Enforce max 3 hops under attack (< 40% build success)
        if (length > 3) {
            double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
            if (buildSuccess > 0 && buildSuccess < 0.40) {
                length = 3;
            }
        }

        return length;
    }

    /**
     *  For debugging, also possibly for restricted routes?
     *  Needs analysis and testing
     *  @return usually false
     */
    protected boolean shouldSelectExplicit(TunnelPoolSettings settings) {
        if (settings.isExploratory()) return false;
        // To test IB or OB only
        //if (settings.isInbound()) return false;
        //if (!settings.isInbound()) return false;
        Properties opts = settings.getUnknownOptions();
        String peers = opts.getProperty("explicitPeers");
        if (peers == null)
            peers = ctx.getProperty("explicitPeers");
        // only one out of 4 times so we don't break completely if peer doesn't build one
        if (peers != null && ctx.random().nextInt(4) == 0)
            return true;
        return false;
    }

    /**
     * For debugging, also possibly for restricted routes.
     * Needs analysis and testing
     *
     * @param settings the tunnel pool settings
     * @param length the desired length of the tunnel
     * @return the list of explicit peer hashes for the tunnel
     */
    protected List<Hash> selectExplicit(TunnelPoolSettings settings, int length) {
        String peers = null;
        Properties opts = settings.getUnknownOptions();
        peers = opts.getProperty("explicitPeers");

        if (peers == null)
            peers = ctx.getProperty("explicitPeers");

        List<Hash> rv = new ArrayList<Hash>();
        StringTokenizer tok = new StringTokenizer(peers, ",");
        while (tok.hasMoreTokens()) {
            String peerStr = tok.nextToken();
            Hash peer = new Hash();
            try {
                peer.fromBase64(peerStr);

                if (ctx.profileOrganizer().isSelectable(peer)) {
                    rv.add(peer);
                } else {
                    if (log.shouldWarn())
                        log.warn("Explicit peer [" + peerStr + "] is not selectable");
                }
            } catch (DataFormatException dfe) {
                if (log.shouldError())
                    log.error("Explicit peer [" + peerStr + "] is improperly formatted", dfe);
            }
        }

        int sz = rv.size();
        if (sz == 0) {
            log.logAlways(Log.WARN, "No valid explicit peers found, building zero hop tunnel...");
        } else if (sz > 1) {
            Collections.shuffle(rv, ctx.random());
        }

        while (rv.size() > length) {
            rv.remove(0);
        }
        if (rv.size() < length) {
            int more = length - rv.size();
            Set<Hash> exclude = getExclude(settings.isInbound(), settings.isExploratory());
            exclude.addAll(rv);
            Set<Hash> matches = new ArraySet<Hash>(more);
            // don't bother with IP restrictions here
            ctx.profileOrganizer().selectFastPeers(more, exclude, matches);
            rv.addAll(matches);
            Collections.shuffle(rv, ctx.random());
        }

        if (log.shouldInfo()) {
            StringBuilder buf = new StringBuilder();
            if (settings.getDestinationNickname() != null)
                buf.append("peers for ").append(settings.getDestinationNickname());
            else if (settings.getDestination() != null)
                buf.append("peers for ").append(settings.getDestination().toBase64());
            else
                buf.append("peers for Exploratory ");
            if (settings.isInbound())
                buf.append(" Inbound");
            else
                buf.append(" Outbound");
            buf.append(" peers: ").append(rv);
            buf.append(", out of ").append(sz).append(" (not including us)");
            log.info(buf.toString());
        }

        if (settings.isInbound())
            rv.add(0, ctx.routerHash());
        else
            rv.add(ctx.routerHash());

        return rv;
    }

    /**
     *  As of 0.9.58, this returns a set populated only by TunnelManager.selectPeersInTooManyTunnels(),
     *  for passing to ProfileOrganizer.
     *  The set will be populated via the contains() calls.
     */
    protected Set<Hash> getExclude(boolean isInbound, boolean isExploratory) {
        return new Excluder(isInbound, isExploratory);
    }

    /**
     * Check if a peer should be excluded from closest hop selection.
     * This performs connectivity checks and version capability validation.
     *
     * @param peerHash the peer hash to check
     * @param isInbound true if this is for an inbound tunnel
     * @param isExploratory true if this is for exploratory tunnels
     * @return true if the peer should be excluded
     * @since 0.9.58
     */
    private boolean shouldExclude(Hash peerHash, boolean isInbound, boolean isExploratory) {
        /*
         *  We may want to update this to skip 'hidden' or 'unreachable' peers, but that isn't safe,
         *  since they may publish one set of routerInfo to us and another to other peers.
         *  The defaults for filterUnreachable has always been to return false, but might as well
         *  make it explicit with a "false &&"
         *
         *  Unreachable peers at the inbound gateway is a major cause of problems.
         *  Due to a bug in SSU peer testing in 0.6.1.32 and earlier, peers don't know
         *  if they are unreachable, so the netdb indication won't help much.
         *
         *  As of 0.6.1.33 we should have lots of unreachables, so enable this for now.
         *  Also (and more effectively) exclude peers we detect are unreachable,
         *  this should be much more effective, especially on a router that has been
         *  up a few hours.
         *
         *  We could just try and exclude them as the inbound gateway but that's harder
         *  (and even worse for anonymity?).
         */
        final long BANDWIDTH_REJECTION_CUTOFF_MS = 60_000L; // 60 seconds cutoff for recent rejection

        PeerProfile profile = ctx.profileOrganizer().getProfileNonblocking(peerHash);
        if (profile != null && wasRecentlyRejected(profile, BANDWIDTH_REJECTION_CUTOFF_MS)) {
            return true;
        }

        if (ctx.commSystem().wasUnreachable(peerHash)) {
            return true;
        }

        RouterInfo routerInfo = (RouterInfo) ctx.netDb().lookupLocally(peerHash);
        if (routerInfo == null) {
            return true;
        }

        if (shouldExcludeFloodfillPeer(isExploratory, routerInfo)) {
            return true;
        }

        if (filterUnreachable(isInbound, isExploratory)) {
            // During attacks, allow U-cap peers if they have M, N, O, P, or X capability
            if (routerInfo.getCapabilities().contains(Character.toString(Router.CAPABILITY_UNREACHABLE))) {
                if (!allowFirewalledUnderAttack(routerInfo)) {
                    return true;
                }
            }
        }

        if (filterSlow(isInbound, isExploratory)) {
            String excludeCaps = getExcludeCaps(ctx);
            if (shouldExclude(ctx, routerInfo, excludeCaps)) {
                return true;
            }
        }

        if (!canConnect(ctx.routerHash(), peerHash)) {
            return true;
        }

        return false;
    }

    private boolean wasRecentlyRejected(PeerProfile profile, long cutoffMillis) {
        long cutoff = ctx.clock().now() - cutoffMillis;
        return profile.getTunnelHistory().getLastRejectedBandwidth() > cutoff;
    }

    private boolean shouldExcludeFloodfillPeer(boolean isExploratory, RouterInfo routerInfo) {
        if (!isExploratory) {
            return false;
        }
        String capabilities = routerInfo.getCapabilities();
        boolean isFloodfill = capabilities.contains(Character.toString(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL));
        // Randomly exclude most exploratory floodfill peers to reduce load (approximate 15/16 exclusion)
        return isFloodfill && ctx.random().nextInt(16) != 0;
    }

    /**
     *  Are we IPv6 only?
     *  @since 0.9.34
     */
    protected boolean isIPv6Only() {
        // The setting is the same for both SSU and NTCP, so just take the SSU one
        return TransportUtil.getIPv6Config(ctx, "SSU") == TransportUtil.IPv6Config.IPV6_ONLY;
    }

    /**
     *  Should we allow as OBEP?
     *  This just checks for IPv4 support.
     *  Will return false for IPv6-only.
     *  This is intended for tunnel candidates, where we already have
     *  the RI. Will not force RI lookups.
     *  Default true.
     *
     *  @since 0.9.34, protected since 0.9.58 for ClientPeerSelector
     */
    protected boolean allowAsOBEP(Hash h) {
        RouterInfo ri = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(h);
        if (ri == null)
            return true;
        return canConnect(ri, ANY_V4);
    }

    /**
     *  Should we allow as IBGW?
     *  This just checks for the "R" capability and IPv4 support.
     *  Will return false for hidden or IPv6-only.
     *  This is intended for tunnel candidates, where we already have
     *  the RI. Will not force RI lookups.
     *  Default true.
     *
     *  @since 0.9.34, protected since 0.9.58 for ClientPeerSelector
     */
    protected boolean allowAsIBGW(Hash h) {
        RouterInfo ri = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(h);
        if (ri == null)
            return true;
        if (ri.getCapabilities().indexOf(Router.CAPABILITY_REACHABLE) < 0)
            return false;
        return canConnect(ANY_V4, ri);
    }

    /**
     *  Get the connect mask for a peer's addresses.
     *  Wrapper around parent's private static method.
     *
     *  @since 0.9.69
     */
    protected int getPeerConnectMask(RouterInfo ri) {
        if (ri == null) {return 0;}
        return getConnectMask(ri.getAddresses());
    }

    /**
     *  Pick peers that we want to avoid for the first OB hop or last IB hop.
     *  There's several cases of importance:
     *  <ol><li>Inbound and we are hidden -
     *      Exclude all unless connected.
     *      This is taken care of in ClientPeerSelector and TunnelPeerSelector selectPeers(), not here.
     *
     *  <li>We are IPv6-only.
     *      Exclude all v4-only peers, unless connected
     *      This is taken care of here.
     *
     *  <li>We have NTCP or SSU disabled.
     *      Exclude all incompatible peers, unless connected
     *      This is taken care of here.
     *
     *  <li>Minimum version check, if we are some brand-new sig type,
     *      or are using some new tunnel build method.
     *      Not currently used, but this is where to implement the checks if needed.
     *      Make sure that ClientPeerSelector and TunnelPeerSelector selectPeers() call this when needed.
     *  </ol>
     *
     *  As of 0.9.58, this a set with only toAdd, for use in ProfileOrganizer.
     *  The set will be populated via the contains() calls.
     *
     *  @param isInbound
     *  @return non-null
     *  @since 0.9.17
     */
    protected Set<Hash> getClosestHopExclude(boolean isInbound, Set<Hash> toAdd) {
        return new ClosestHopExcluder(isInbound, toAdd);
    }

    /**
     * Should the peer be excluded based on its published caps, crypto, and version?
     *
     * @param ctx Router context for peer count checks
     * @param peer The peer to evaluate
     * @return true if the peer should be excluded
     * @since 0.9.17
     */
    public static boolean shouldExclude(RouterContext ctx, RouterInfo peer) {
        return shouldExclude(ctx, peer, getEffectiveExcludeCaps(ctx));
    }

    /**
     *  @return non-null, possibly empty
     */
    private static String getExcludeCaps(RouterContext ctx) {
        return ctx.getProperty("router.excludePeerCaps", DEFAULT_EXCLUDE_CAPS);
    }

    /**
     *  Get effective exclude caps, adapting to build success.
     *  During low build success (<40%), relax exclusions for M, N, O, D, and P caps.
     *  Also relax during first 10 minutes of uptime when build success is unknown.
     *  @return non-null, possibly empty
     */
    private static String getEffectiveExcludeCaps(RouterContext ctx) {
        String configured = getExcludeCaps(ctx);
        if (configured == null || configured.isEmpty()) {
            // No configured exclusions - nothing to relax
            return configured;
        }

        // Check if we should relax exclusions
        boolean shouldRelax = false;
        String reason = "";

        // Check build success - only adapt if we have access to profile organizer
        double buildSuccess = 0;
        try {
            buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
        } catch (Exception e) {
            // Can't determine build success - use defaults
            return configured;
        }

        // Relax if build success is low - with hysteresis to prevent oscillation
        // Relax below 40%, restore above 45%
        if (buildSuccess > 0 && buildSuccess < 0.40) {
            shouldRelax = true;
            reason = "low build success (" + (int)(buildSuccess * 100) + "%)";
        }
        // Don't relax if recovering (above 45%)
        if (buildSuccess >= 0.45) {
            shouldRelax = false;
        }

        // Relax during first 10 minutes of uptime (no build success data yet)
        long uptime = ctx.router().getUptime();
        if (uptime > 0 && uptime < 10 * 60 * 1000) {
            shouldRelax = true;
            if (reason.isEmpty()) {
                reason = "router startup (first 10 minutes)";
            } else {
                reason = reason + " and router startup";
            }
        }

        // If no need to relax, use configured exclusions
        if (!shouldRelax) {
            return configured;
        }

        // Remove M, N, O, D, P from exclusions
        StringBuilder adjusted = new StringBuilder();
        for (int i = 0; i < configured.length(); i++) {
            char c = configured.charAt(i);
            // Keep K, L, U, E, G - remove M, N, O, D, P
            if (c == 'M' || c == 'N' || c == 'O' || c == 'D' || c == 'P') {
                continue;
            }
            adjusted.append(c);
        }

        return adjusted.toString();
    }

    /** SSU2 fixes (2.1.0), Congestion fixes (2.2.0) */
    private static final String MIN_VERSION = "0.9.62";

    /**
     * Should the peer be excluded based on its published caps, crypto, and version?
     *
     * @param ctx Router context for peer count checks
     * @param peer The peer to evaluate
     * @param excl Characters representing capabilities we want to exclude
     * @return true if the peer should be excluded
     */
    private static boolean shouldExclude(RouterContext ctx, RouterInfo peer, String excl) {
        String cap = peer.getCapabilities();
        RouterIdentity ident = peer.getIdentity();

        // Exclude peers with weak signing keys
        if (ident.getSigningPublicKey().getType() == SigType.DSA_SHA1) {
            return true;
        }

        // Require modern encryption (ECIES-X25519)
        if (ident.getPublicKey().getType() != EncType.ECIES_X25519) {
            return true;
        }

        // Check for explicitly excluded capabilities
        for (int j = 0; j < excl.length(); j++) {
            if (cap.indexOf(excl.charAt(j)) >= 0) {
                return true;
            }
        }

        // Avoid degraded peers
        // Allow E cap with 1/6 probability during attacks (build success < 40%)
        if (cap.contains("E") || cap.contains("G")) {
            double buildSuccess = 0;
            try {
                buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
            } catch (Exception e) {
                // Can't determine build success - exclude E/G caps
                return true;
            }
            // During attacks, allow E cap with 1/6 chance
            if (cap.contains("E") && buildSuccess > 0 && buildSuccess < 0.40) {
                if (ctx.random().nextInt(6) != 0) {
                    return true;  // Exclude (5/6 chance)
                }
                return false;  // Allow (1/6 chance)
            }
            return true;
        }

        // Count meaningful capabilities
        int knownCaps = 0;
        if (cap.contains("F")) knownCaps++;
        if (cap.contains("R")) knownCaps++;
        if (cap.contains("L") || cap.contains("M") || cap.contains("N") || cap.contains("O") ||
            cap.contains("P") || cap.contains("Q") || cap.contains("X")) knownCaps++;

        // Relax single-capability restriction when peer count is low
        int fastPeerCount = ctx != null ? ctx.profileOrganizer().countFastPeers() : 100;
        if (knownCaps < 2 && cap.length() <= knownCaps && fastPeerCount >= 20) {
            return true;
        }

        // Exclude outdated versions
        String v = peer.getVersion();
        if (v.equals(CoreVersion.PUBLISHED_VERSION)) {
            return false;
        }

        if (VersionComparator.comp(v, MIN_VERSION) < 0) {
            return true;
        }

        // Peer is acceptable
        return false;
    }

    private static final String PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = "router.outboundExploratoryExcludeUnreachable";
    private static final String PROP_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE = "router.outboundClientExcludeUnreachable";
    private static final String PROP_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = "router.inboundExploratoryExcludeUnreachable";
    private static final String PROP_INBOUND_CLIENT_EXCLUDE_UNREACHABLE = "router.inboundClientExcludeUnreachable";
    private static final boolean DEFAULT_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = true;
    private static final boolean DEFAULT_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE = true;
    // see comments at getExclude() above
    private static final boolean DEFAULT_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = true;
    private static final boolean DEFAULT_INBOUND_CLIENT_EXCLUDE_UNREACHABLE = true;

    /**
     * do we want to skip unreachable peers?
     * @return true if yes
     */
    private boolean filterUnreachable(boolean isInbound, boolean isExploratory) {
        if (SystemVersion.isSlow() || ctx.router().getUptime() < 65*60*1000)
            return true;
        if (isExploratory) {
            if (isInbound) {
                if (ctx.router().isHidden())
                    return true;
                return ctx.getProperty(PROP_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE, DEFAULT_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE);
            } else {
                return ctx.getProperty(PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE, DEFAULT_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE);
            }
        } else {
            if (isInbound) {
                if (ctx.router().isHidden())
                    return true;
                return ctx.getProperty(PROP_INBOUND_CLIENT_EXCLUDE_UNREACHABLE, DEFAULT_INBOUND_CLIENT_EXCLUDE_UNREACHABLE);
            } else {
                return ctx.getProperty(PROP_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE, DEFAULT_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE);
            }
        }
    }

    /**
     * Should we allow firewalled (U-cap) peers?
     * During attacks (build success < 40%), allow U-cap peers if they have M, N, O, P, or X capability.
     * @param routerInfo the peer to check
     * @return true if U-cap peer should be allowed (not filtered)
     */
    private boolean allowFirewalledUnderAttack(RouterInfo routerInfo) {
        if (routerInfo == null) return false;
        String cap = routerInfo.getCapabilities();
        if (!cap.contains(Character.toString(Router.CAPABILITY_UNREACHABLE))) {
            return true;  // Not U-cap, no issue
        }
        // Check if peer has M, N, O, P, or X capability
        if (cap.contains("M") || cap.contains("N") || cap.contains("O") ||
            cap.contains("P") || cap.contains("X")) {
            double buildSuccess = 0;
            try {
                buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
            } catch (Exception e) {
                return false;  // Can't determine - exclude U-cap
            }
            // Allow U-cap peers with M/N/O/P/X during attacks
            return buildSuccess > 0 && buildSuccess < 0.40;
        }
        return false;
    }

    private static final String PROP_OUTBOUND_EXPLORATORY_EXCLUDE_SLOW = "router.outboundExploratoryExcludeSlow";
    private static final String PROP_OUTBOUND_CLIENT_EXCLUDE_SLOW = "router.outboundClientExcludeSlow";
    private static final String PROP_INBOUND_EXPLORATORY_EXCLUDE_SLOW = "router.inboundExploratoryExcludeSlow";
    private static final String PROP_INBOUND_CLIENT_EXCLUDE_SLOW = "router.inboundClientExcludeSlow";

    /**
     * do we want to skip peers that are slow?
     * @return true unless configured otherwise
     */
    protected boolean filterSlow(boolean isInbound, boolean isExploratory) {
        if (isExploratory) {
            if (isInbound) {return ctx.getProperty(PROP_INBOUND_EXPLORATORY_EXCLUDE_SLOW, true);}
            else {return ctx.getProperty(PROP_OUTBOUND_EXPLORATORY_EXCLUDE_SLOW, true);}
        } else {
            if (isInbound) {return ctx.getProperty(PROP_INBOUND_CLIENT_EXCLUDE_SLOW, true);}
            else {return ctx.getProperty(PROP_OUTBOUND_CLIENT_EXCLUDE_SLOW, true);}
        }
    }

    /** see HashComparator */
    protected void orderPeers(List<Hash> rv, SessionKey key) {
        if (rv.size() > 1) {Collections.sort(rv, new HashComparator(key));}
    }

    /**
     * Check if the selected peer sequence matches an existing tunnel in the pool.
     * Prevents duplicate peer sequences which could weaken anonymity.
     *
     * @param settings the tunnel pool settings
     * @param newPeers the newly selected peers (excluding self)
     * @return true if duplicate detected
     * @since 0.9.68+
     */
    protected boolean isDuplicateSequence(TunnelPoolSettings settings, List<Hash> newPeers) {
        if (newPeers == null || newPeers.isEmpty()) {return false;}

        Hash dest = settings.getDestination();
        if (dest == null) {return false;}

        TunnelManagerFacade tmf = ctx.tunnelManager();
        TunnelPool pool = settings.isInbound() ? tmf.getInboundPool(dest)
                                                : tmf.getOutboundPool(dest);
        if (pool == null) {return false;}

        List<TunnelInfo> existingTunnels = pool.listTunnels();
        if (existingTunnels == null || existingTunnels.isEmpty()) {return false;}

        for (TunnelInfo existing : existingTunnels) {
            if (existing.getLength() != newPeers.size() + 1) {continue;}

            boolean match = true;
            for (int i = 0; i < newPeers.size(); i++) {
                Hash existingPeer = existing.getPeer(i);
                if (existingPeer == null || !existingPeer.equals(newPeers.get(i))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                if (log.shouldDebug()) {
                    log.debug("Detected duplicate tunnel sequence for " + settings.getDestinationNickname() +
                              " - regenerating peers");
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Regenerate tunnel peers to avoid duplicate sequence.
     * Shuffles the peer selection and re-orders.
     *
     * @param settings the tunnel pool settings
     * @param peers the peers to regenerate
     * @return regenerated peer list (same or different)
     * @since 0.9.68+
     */
    protected List<Hash> regeneratePeers(TunnelPoolSettings settings, List<Hash> peers) {
        if (peers == null || peers.isEmpty()) {return peers;}

        SessionKey randomKey = settings.getRandomKey();
        if (randomKey != null && peers.size() > 1) {
            Collections.shuffle(peers, ctx.random());
            List<Hash> reordered = new ArrayList<Hash>(peers);
            orderPeers(reordered, randomKey);
            return reordered;
        }
        return peers;
    }

    /**
     *  Implement a deterministic comparison that cannot be predicted by
     *  others. A naive implementation (using the distance from a random key)
     *  allows an attacker who runs two routers with hashes far apart
     *  to maximize his chances of those two routers being at opposite
     *  ends of a tunnel.
     *
     *  Previous Previous:
     *     d(l, h) - d(r, h)
     *
     *  Previous:
     *     d((H(l+h), h) - d(H(r+h), h)
     *
     *  Now:
     *     SipHash using h to generate the SipHash keys
     *     then siphash(l) - siphash(r)
     */
    private static class HashComparator implements Comparator<Hash>, Serializable {
        private final long k0, k1;

        /**
         * not thread safe
         *
         * @param k container for sort keys, not used as a Hash
         */
        private HashComparator(SessionKey k) {
            byte[] b = k.getData();
            // we use the first half of the random key in ProfileOrganizer.getSubTier(),
            // so use the last half here
            k0 = DataHelper.fromLong8(b, 16);
            k1 = DataHelper.fromLong8(b, 24);
        }

        public int compare(Hash l, Hash r) {
            long lh = SipHashInline.hash24(k0, k1, l.getData());
            long rh = SipHashInline.hash24(k0, k1, r.getData());
            if (lh > rh) {return 1;}
            if (lh < rh) {return -1;}
            return 0;
        }
    }

    /**
     *  Connectivity check.
     *  Check that each hop can connect to the next, including us.
     *  Check that the OBEP is not IPv6-only, and the IBGW is
     *  reachable and not hidden or IPv6-only.
     *  Tells the profile manager to blame the hop, and returns false on failure.
     *
     *  @param tunnel ENDPOINT FIRST, GATEWAY LAST!!!!, length 2 or greater
     *  @return ok
     *  @since 0.9.34
     */
    protected boolean checkTunnel(boolean isInbound, boolean isExploratory, List<Hash> tunnel) {
        if (!checkTunnel(tunnel)) {return false;}
        // client OBEP/IBGW checks now in CPS
        if (!isExploratory) {return true;}
        if (isInbound) {
            Hash h = tunnel.get(tunnel.size() - 1);
            if (!allowAsIBGW(h)) {
                if (log.shouldWarn()) {
                    log.warn("Selected IPv6-only or unreachable peer for Inbound Gateway [" + h.toBase64().substring(0,6) + "]");
                }
                // treat as a timeout in the profile
                // tunnelRejected() would set the last heard from time
                ctx.profileManager().tunnelTimedOut(h);
                return false;
            }
        } else {
            Hash h = tunnel.get(0);
            if (!allowAsOBEP(h)) {
                if (log.shouldWarn()) {
                    log.warn("Selected IPv6-only peer for Outbound Endpoint [" + h.toBase64().substring(0,6) + "]");
                }
                // treat as a timeout in the profile
                // tunnelRejected() would set the last heard from time
                ctx.profileManager().tunnelTimedOut(h);
                return false;
            }
        }
        return true;
    }

    /**
     *  Connectivity check.
     *  Check that each hop can connect to the next, including us.
     *
     *  @param tunnel ENDPOINT FIRST, GATEWAY LAST!!!!
     *  @return ok
     *  @since 0.9.34
     */
    private boolean checkTunnel(List<Hash> tunnel) {
        boolean rv = true;
        for (int i = 0; i < tunnel.size() - 1; i++) {
            // order is backwards!
            Hash hf = tunnel.get(i+1);
            Hash ht = tunnel.get(i);
            StringBuilder buf = new StringBuilder();
            for (Hash h : tunnel) {
                buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
            }
            if (!canConnect(hf, ht)) {
                if (log.shouldWarn())
                    log.warn("Connection check failed at hop [" + (i+1) + " -> " + i +
                             "] in tunnel (Gateway -> Endpoint)\n* Tunnel: " + buf.toString());
                // Blame them both
                // treat as a timeout in the profile
                // tunnelRejected() would set the last heard from time
                Hash us = ctx.routerHash();
                if (!hf.equals(us))
                    ctx.profileManager().tunnelTimedOut(hf);
                if (!ht.equals(us))
                    ctx.profileManager().tunnelTimedOut(ht);
                rv = false;
                break;
            }
        }
        return rv;
    }

    /**
     * Excluder that automatically adds peers to the set when they should be excluded.
     *
     * @since 0.9.58
     */
    protected class Excluder extends ExcluderBase {
        private final boolean _isIn, _isExpl;

        /**
         *  Automatically adds selectPeersInTooManyTunnels(), unless i2np.allowLocal.
         */
        public Excluder(boolean isInbound, boolean isExploratory) {
            super(ctx.getBooleanProperty("i2np.allowLocal") ? new HashSet<Hash>()
                                                            : ctx.tunnelManager().selectPeersInTooManyTunnels());
            _isIn = isInbound;
            _isExpl = isExploratory;
        }

        /**
         *  Does not add selectPeersInTooManyTunnels().
         *  Makes a copy of toAdd
         *
         *  @param toAdd initial contents, copied
         */
        public Excluder(boolean isInbound, boolean isExploratory, Set<Hash> toAdd) {
            super(new HashSet<Hash>(toAdd));
            _isIn = isInbound;
            _isExpl = isExploratory;
        }

    /**
     * Check if a peer should be excluded.
     * Automatically adds to the set if excluded.
     *
     * @param o a Hash object to check
     * @return true if peer should be excluded (and added to set)
     */
    @Override
    public boolean contains(Object o) {
            if (s.contains(o)) {return true;}
            Hash h = (Hash) o;
            if (shouldExclude(h, _isIn, _isExpl)) {
                s.add(h);
                return true;
            }
            return false;
        }
    }

    /**
     * Excludes peers that cannot connect as closest hops.
     * Used for hidden mode and other tough situations.
     * Not for hidden inbound; use SANFP instead.
     *
     * @since 0.9.58
     */
    private class ClosestHopExcluder extends ExcluderBase {
        private final boolean isIn;
        private final int ourMask;

        /**
         *  Automatically check if peer can connect to us (for inbound)
         *  or we can connect to it (for outbound)
         *  and add the Hash to the set if not.
         *
         *  @param set not copied, contents will be modified by all methods
         */
        public ClosestHopExcluder(boolean isInbound, Set<Hash> set) {
            super(set);
            isIn = isInbound;
            RouterInfo ri = ctx.router().getRouterInfo();
            if (ri != null) {ourMask = isInbound ? getInboundMask(ri) : getOutboundMask(ri);}
            else {ourMask = 0xff;}
        }

        /**
         * Check if a peer should be excluded from closest hop selection.
         * Automatically adds to the set if not connectable.
         *
         * @param o a Hash object to check
         * @return true if peer should be excluded (and added to set)
         */
        public boolean contains(Object o) {
            if (s.contains(o)) {return true;}
            Hash h = (Hash) o;
            if (ctx.commSystem().isEstablished(h)) {return false;}
            boolean canConnect;
            RouterInfo peer = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(h);
            if (peer == null) {canConnect = false;}
            else if (isIn) {
                // For inbound (peer connects to us): check if peer's outbound mask overlaps with our inbound mask
                canConnect = canConnect(peer, ourMask);
            } else {
                // For outbound (we connect to peer): check if peer's inbound mask overlaps with our outbound mask
                int peerInbound = getInboundMask(peer);
                canConnect = (peerInbound & ourMask) != 0;
            }
            if (!canConnect) {s.add(h);}
            return !canConnect;
        }
    }

    /**
     * Validates peer-to-peer connectivity in a tunnel hop chain.
     * Excludes peers that cannot connect to the previous hop in the chain.
     * Reduces tunnel build failures due to incompatible peers (e.g., IPv4-only ↔ IPv6-only).
     *
     * @since 0.9.69
     */
    protected class HopChainValidator extends ExcluderBase {
        private final List<Hash> _selectedHops;

        public HopChainValidator(List<Hash> selectedHops) {
            super(new HashSet<Hash>());
            _selectedHops = selectedHops;
        }

        @Override
        public boolean contains(Object o) {
            if (s.contains(o)) {return true;}
            Hash candidate = (Hash) o;
            if (_selectedHops.isEmpty()) {return false;}
            Hash prevHop = _selectedHops.get(_selectedHops.size() - 1);
            if (candidate.equals(prevHop)) {return true;}
            if (prevHop.equals(ctx.routerHash()) || candidate.equals(ctx.routerHash())) {
                return false;
            }
            if (ctx.commSystem().isEstablished(prevHop) && ctx.commSystem().isEstablished(candidate)) {
                return false;
            }
            RouterInfo prevRI = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(prevHop);
            RouterInfo candRI = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(candidate);
            if (prevRI == null || candRI == null) {
                s.add(candidate);
                return true;
            }
            int prevOutbound = getPeerConnectMask(prevRI);
            int candInbound = getInboundMask(candRI);
            if ((prevOutbound & candInbound) == 0) {
                s.add(candidate);
                return true;
            }
            return false;
        }
    }

}
