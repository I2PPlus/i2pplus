package net.i2p.router.tunnel.pool;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.CoreVersion;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.crypto.SipHashInline;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.transport.Transport;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.transport.TransportUtil;
import net.i2p.util.ArraySet;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

import net.i2p.router.peermanager.ProfileOrganizer;

/**
 * Coordinate the selection of peers to go into a tunnel for one particular pool.
 */
public abstract class TunnelPeerSelector extends ConnectChecker {

    private static final String DEFAULT_EXCLUDE_CAPS = String.valueOf(Router.CAPABILITY_BW12) +
                                                        String.valueOf(Router.CAPABILITY_BW32) +
                                                        String.valueOf(Router.CAPABILITY_CONGESTION_SEVERE) +
                                                        String.valueOf(Router.CAPABILITY_NO_TUNNELS);

    protected static final double ATTACK_THRESHOLD = ProfileOrganizer.ATTACK_THRESHOLD;
    protected static final long STARTUP_WARNING_SUPPRESS_MS = 5 * 60 * 1000;

    /** Peers selected within this window are excluded from further selection to ensure diversity */
    protected static final long PEER_SELECTION_COOLDOWN_MS = 60_000;

    /** Shared cooldown map across all peer selectors */
    protected static final Map<Hash, Long> _peerCooldowns = new ConcurrentHashMap<>();

    /** Lock for atomic cooldown check+record across peer selectors */
    protected static final Object _cooldownLock = new Object();

    /** Peers that failed as first hop (first hop unreachable) excluded for this long */
    protected static final long FIRST_HOP_FAIL_COOLDOWN_MS = 5 * 60 * 1000;

    /** Tracks when a peer last failed as first hop */
    protected static final Map<Hash, Long> _firstHopFails = new ConcurrentHashMap<>();

    /** How often to send keepalive pings to established Fast/HighCap peers */
    private static final long KEEPALIVE_INTERVAL_MS = 15_000; // More frequent keepalives

    /** Tracks last keepalive send time per peer */
    private static final ConcurrentHashMap<Hash, Long> _lastKeepAlive = new ConcurrentHashMap<>(512);

    /**
     *  Check if a peer recently failed as first hop and should be excluded.
     */
    public static boolean isFirstHopFailing(RouterContext ctx, Hash peer) {
        Long when = _firstHopFails.get(peer);
        if (when == null)
            return false;
        if (ctx.clock().now() - when > FIRST_HOP_FAIL_COOLDOWN_MS) {
            _firstHopFails.remove(peer);
            return false;
        }
        return true;
    }

    /**
     *  Record that a peer failed as first hop (first hop unreachable).
     */
    protected static void recordFirstHopFail(RouterContext ctx, Hash peer) {
        _firstHopFails.put(peer, ctx.clock().now());
        // Periodically prune expired entries to prevent unbounded growth
        if (_firstHopFails.size() > 64) {
            long cutoff = ctx.clock().now() - FIRST_HOP_FAIL_COOLDOWN_MS;
            _firstHopFails.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
    }

    /**
     * Record that a peer failed during peer selection (first-hop or adjacent).
     * Used by ClientPeerSelector and ExploratoryPeerSelector to mark peers
     * that failed selection criteria, preventing re-selection for the cooldown.
     */
    protected static void recordPeerFailure(RouterContext ctx, Hash peer) {
        _firstHopFails.put(peer, ctx.clock().now());
    }

    /**
     * Check if a peer has recovered from failure and can be reconsidered.
     * This is used to prevent permanent exclusion of peers that may recover.
     */
    protected static boolean hasRecoveredFromFailure(RouterContext ctx, Hash peer) {
        Long failTime = _firstHopFails.get(peer);
        if (failTime == null)
            return true;
        long recoveryTime = ctx.clock().now() - 60 * 1000; // 60 seconds recovery window
        if (failTime < recoveryTime) {
            _firstHopFails.remove(peer);
            return true;
        }
        return false;
    }

    protected TunnelPeerSelector(RouterContext context) {
        super(context);
    }

    /**
     * Is the router in the startup grace period?
     * During startup, peers haven't accumulated test history yet, so 
     * quality filters (pre-qualification, tier capping) should be relaxed
     * to allow tunnels to build.
     * @param ctx the router context
     * @return true if uptime is between 1ms and STARTUP_WARNING_SUPPRESS_MS
     */
    protected static boolean isInStartupGracePeriod(RouterContext ctx) {
        long uptime = ctx.router().getUptime();
        return uptime > 0 && uptime < STARTUP_WARNING_SUPPRESS_MS;
    }

    /**
     * Convenience instance method wrapping the static helper.
     */
    protected boolean isInStartupGracePeriod() {
        return isInStartupGracePeriod(ctx);
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
            if (buildSuccess < ATTACK_THRESHOLD) {
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

        List<Hash> rv = new ArrayList<>();
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
            Set<Hash> matches = new ArraySet<>(more);
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
                buf.append("peers for [").append(settings.getDestination().toBase64(), 0, 6).append("]");
            else
                buf.append("peers for Exploratory ");
            if (settings.isInbound())
                buf.append(" Inbound");
            else
                buf.append(" Outbound");
            buf.append(" peers: ");
            for (int i = 0; i < rv.size(); i++) {
                if (i > 0) {buf.append(", ");}
                buf.append("[").append(rv.get(i).toBase64(), 0, 6).append("]");
            }
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
    /**
     *  Check if a peer should be excluded, returning the reason or null.
     *  Used by Excluder to classify exclusion reasons for diagnostics.
     */
    private String getExclusionReason(Hash peerHash, boolean isInbound, boolean isExploratory) {
        final long BANDWIDTH_REJECTION_CUTOFF_MS = 60_000L;

        PeerProfile profile = ctx.profileOrganizer().getProfileNonblocking(peerHash);
        if (profile != null && wasRecentlyRejected(profile, BANDWIDTH_REJECTION_CUTOFF_MS)) {
            return "recently-rejected";
        }

        if (ctx.commSystem().wasUnreachable(peerHash)) {
            return "unreachable";
        }

        RouterInfo routerInfo = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(peerHash);
        if (routerInfo == null) {
            return null;
        }

        if (shouldExcludeFloodfillPeer(isExploratory, routerInfo)) {
            return "floodfill";
        }

        if (filterUnreachable(isInbound, isExploratory)) {
            if (routerInfo.getCapabilities().contains(Character.toString(Router.CAPABILITY_UNREACHABLE))) {
                if (!allowFirewalledUnderAttack(routerInfo)) {
                    return "U-cap";
                }
            }
        }

        if (filterSlow(isInbound, isExploratory)) {
            String excludeCaps = getEffectiveExcludeCaps(ctx);
            if (shouldExclude(ctx, routerInfo, excludeCaps, isExploratory)) {
                return "slow/capped";
            }
        }

        return null;
    }

    private boolean shouldExclude(Hash peerHash, boolean isInbound, boolean isExploratory) {
        return getExclusionReason(peerHash, isInbound, isExploratory) != null;
    }

    /**
     * Get effective exclude caps, adapting to build success.
     * During low build success (<40%), relax exclusions for M, N, O, D, and P caps.
     * Also relax during first 10 minutes of uptime when build success is unknown.
     * @return non-null, possibly empty
     */
    private static String getEffectiveExcludeCaps(RouterContext ctx) {
        String configured = getExcludeCaps(ctx);
        if (configured == null || configured.isEmpty()) {
            return configured;
        }

        boolean shouldRelax = false;
        double buildSuccess = 0;
        try {
            buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
        } catch (Exception e) {
            return configured;
        }

        if (buildSuccess < ATTACK_THRESHOLD) {
            shouldRelax = true;
        }
        if (buildSuccess >= 0.45) {
            shouldRelax = false;
        }

        long uptime = ctx.router().getUptime();
        if (uptime > 0 && uptime < STARTUP_WARNING_SUPPRESS_MS) {
            shouldRelax = true;
        }

        if (!shouldRelax) {
            return configured;
        }

        // Remove M, N, O, D, P from exclusions
        StringBuilder adjusted = new StringBuilder();
        for (int i = 0; i < configured.length(); i++) {
            char c = configured.charAt(i);
            if (c == 'M' || c == 'N' || c == 'O' || c == 'D' || c == 'P') {
                continue;
            }
            adjusted.append(c);
        }

        return adjusted.toString();
    }

    /**
     * Should we allow firewalled (U-cap) peers?
     * During attacks (build success < 40%), allow U-cap peers if they have M, N, O, P, or X capability.
     */
    private boolean allowFirewalledUnderAttack(RouterInfo routerInfo) {
        if (routerInfo == null) return false;
        String cap = routerInfo.getCapabilities();
        if (!cap.contains(Character.toString(Router.CAPABILITY_UNREACHABLE))) {
            return true;
        }
        if (cap.contains("M") || cap.contains("N") || cap.contains("O") ||
            cap.contains("P") || cap.contains("X")) {
            double buildSuccess = 0;
            try {
                buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
            } catch (Exception e) {
                return false;
            }
            return buildSuccess < ATTACK_THRESHOLD;
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
            return false;
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
            return false;
        if (ri.getCapabilities().indexOf(Router.CAPABILITY_REACHABLE) < 0)
            return false;
        return canConnect(ANY_V4, ri);
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
        return shouldExclude(ctx, peer, getExcludeCaps(ctx), false);
    }

    /**
     *  @return non-null, possibly empty
     */
    private static String getExcludeCaps(RouterContext ctx) {
        return ctx.getProperty("router.excludePeerCaps", DEFAULT_EXCLUDE_CAPS);
    }

    /** SSU2 fixes (2.1.0), Congestion fixes (2.2.0) */
    private static final String MIN_VERSION = "0.9.62";

    /**
     * Should the peer be excluded based on its published caps, crypto, and version?
     *
     * @param ctx Router context for peer count checks
     * @param peer The peer to evaluate
     * @param excl Characters representing capabilities we want to exclude
     * @param isExploratory true if this check is for an exploratory pool
     * @return true if the peer should be excluded
     */
    private static boolean shouldExclude(RouterContext ctx, RouterInfo peer, String excl, boolean isExploratory) {
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
                return true;
            }
            // During attacks, allow E cap with 1/6 chance
            if (cap.contains("E") && buildSuccess < ATTACK_THRESHOLD) {
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
        int fastPeerCount = ctx.profileOrganizer().countFastPeers();
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

        // Skip pre-qualification during startup — peers haven't accumulated
        // test history yet, so rejecting untested peers would block all
        // tunnel builds (including Ping tunnels for HostChecker).
        if (isInStartupGracePeriod(ctx)) {
            return false;
        }

        // Check build success rate for tiered quality filtering.
        // Under normal conditions (high build success), apply strict pre-qualification
        // to ensure first-hop peers have some evidence of connectivity.  Under stress
        // (low build success), relax the filter but don't disable it entirely — peers
        // with zero connectivity history still waste tunnel builds and test cycles.
        double buildSuccess;
        try {
            buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
        } catch (Exception e) {
            buildSuccess = 0;
        }
        // Exploratory pools always skip pre-qualification — they need to test
        // unknown peers to build connectivity profiles.
        // Client pools also skip during stress (< 40% build success) when few
        // peers have recent connectivity, to avoid starving the candidate pool.
        if (isExploratory || buildSuccess < ATTACK_THRESHOLD) {
            return false;
        }

        // Client pool pre-qualification: reject peers with ZERO evidence of
        // connectivity.  A peer that has never been tested, hasn't been heard
        // from in hours, and isn't currently connected is very likely to fail
        // as a first-hop, wasting the build attempt.
        // When there are plenty of fast peers, still allow ~10% of untested
        // peers through so struggling pools can discover new performant routes.
        Hash peerHash = ident.calculateHash();
        PeerProfile profile = ctx.profileOrganizer().getProfile(peerHash);
        if (profile != null && !ctx.commSystem().isBacklogged(peerHash)) {
            boolean hasRecentTest = profile.getTunnelTestTimeAverage() > 0;
            long lastHeardFrom = profile.getLastHeardFrom();
            long heardCutoff = ctx.clock().now() - 2 * 60 * 60 * 1000;
            boolean recentlyHeard = lastHeardFrom > heardCutoff;
            boolean isConnected = ctx.commSystem().isEstablished(peerHash);
            if (!hasRecentTest && !recentlyHeard && !isConnected && fastPeerCount >= 20) {
                if (ctx.random().nextInt(10) > 0) {
                    return true;
                }
            }
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
                Hash existingPeer = existing.getPeer(newPeers.size() - i);
                if (existingPeer == null || !existingPeer.equals(newPeers.get(i))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                if (log.shouldDebug()) {
                    log.debug("Detected duplicate tunnel sequence for " + settings.getDestinationNickname());
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
            List<Hash> reordered = new ArrayList<>(peers);
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
        private static final int MAX_EXCLUDED_PEERS = 384;
        private final boolean _isIn, _isExpl;
        /** Maps peer hash to the reason it was excluded, for diagnostic logging */
        final Map<Hash, String> _reasons = new LinkedHashMap<>();

        /**
         *  Automatically adds selectPeersInTooManyTunnels(), unless i2np.allowLocal.
         */
        public Excluder(boolean isInbound, boolean isExploratory) {
            super(ctx.getBooleanProperty("i2np.allowLocal") ? new LinkedHashSet<>()
                                                              : new LinkedHashSet<>(ctx.tunnelManager().selectPeersInTooManyTunnels()));
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
            super(new LinkedHashSet<>(toAdd));
            _isIn = isInbound;
            _isExpl = isExploratory;
        }

    /**
     * Check if a peer should be excluded.
     * Automatically adds to the set if excluded.
     * Capped at MAX_EXCLUDED_PEERS — evicts oldest entry when over limit.
     * Tracks exclusion reason for diagnostic logging.
     *
     * @param o a Hash object to check
     * @return true if peer should be excluded (and added to set)
     */
    @Override
    public boolean contains(Object o) {
            if (s.contains(o)) {return true;}
            Hash h = (Hash) o;
            String reason = getExclusionReason(h, _isIn, _isExpl);
            if (reason != null) {
                s.add(h);
                _reasons.put(h, reason);
                if (s.size() > MAX_EXCLUDED_PEERS) {
                    Iterator<Hash> it = s.iterator();
                    if (it.hasNext()) {
                        Hash evicted = it.next();
                        it.remove();
                        _reasons.remove(evicted);
                    }
                }
                return true;
            }
            return false;
        }

        /**
         *  Format exclusion summary grouped by reason.
         */
        String formatByReason() {
            if (_reasons.isEmpty()) return "";
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String r : _reasons.values()) {
                Integer c = counts.get(r);
                counts.put(r, c != null ? c + 1 : 1);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(s.size()).append(" excluded [");
            boolean first = true;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getValue()).append(" ").append(e.getKey());
                first = false;
            }
            sb.append("]");
            return sb.toString();
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
        @Override
        public boolean contains(Object o) {
            if (s.contains(o)) {return true;}
            Hash h = (Hash) o;
            if (ctx.commSystem().isEstablished(h)) {return false;}
            boolean canConnect;
            RouterInfo peer = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(h);
            if (peer == null) {canConnect = false;}
            else if (isIn) {canConnect = canConnect(peer, ourMask);}
            else {canConnect = canConnect(ourMask, peer);}
            if (!canConnect) {s.add(h);}
            return !canConnect;
        }
    }

    /**
     *  Check if a peer supports NTCP2 transport.
     *  NTCP2 is preferred for direct connections (first hop / IBGW)
     *  because SSU2-only peers are typically firewalled, requiring
     *  introduction-based connections that are slower and less reliable.
     *
     *  @param ctx the router context
     *  @param peer hash of the peer to check
     *  @return true if the peer has an NTCP2 address
     */
    protected static boolean supportsNTCP2(RouterContext ctx, Hash peer) {
        RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
        if (ri == null) return false;
        for (RouterAddress ra : ri.getAddresses()) {
            if ("NTCP2".equals(ra.getTransportStyle()))
                return true;
        }
        return false;
    }

    /**
     *  Check if a peer's RouterInfo has at least one reachable SSU or NTCP address.
     *  Peers without valid transport addresses always fail as first hops and trigger
     *  bans in EstablishmentManager.establish() — they should be excluded from selection.
     *
     *  @param ctx the router context
     *  @param peer hash of the peer to check
     *  @return true if the peer has a valid SSU or NTCP address
     */
    protected static boolean hasValidTransportAddress(RouterContext ctx, Hash peer) {
        RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
        if (ri == null) return false;
        for (RouterAddress ra : ri.getAddresses()) {
            String style = ra.getTransportStyle();
            byte[] ip = ra.getIP();
            int port = ra.getPort();
            if ("SSU".equals(style)) {
                if (!"2".equals(ra.getOption("v")))
                    continue;
                if (ip != null && TransportUtil.isValidPort(port))
                    return true;
                if (ra.getOption("itag0") != null)
                    return true;
            } else if ("SSU2".equals(style)) {
                if (ip != null && TransportUtil.isValidPort(port))
                    return true;
                if (ra.getOption("itag0") != null)
                    return true;
            } else if ("NTCP".equals(style) || "NTCP2".equals(style)) {
                if (ip != null && TransportUtil.isValidPort(port))
                    return true;
            }
        }
        return false;
    }

    /**
     *  Check if a peer has a history of rejecting tunnel build requests.
     *  Returns true when the lifetime acceptance ratio drops below 30%.
     *  Defaults to false (accept) when no data is available.
     *
     *  @param ctx the router context
     *  @param peer hash of the peer to check
     *  @return true if the acceptance ratio is below threshold
     */
    protected static boolean isLowAcceptanceRatio(RouterContext ctx, Hash peer) {
        PeerProfile profile = ctx.profileOrganizer().getProfile(peer);
        if (profile == null) return false;
        return profile.getTunnelAcceptanceRatio() < 0.3;
    }

    /**
     *  Trigger an outbound connection establishment to a peer.
     *  Creates a low-priority dummy OutNetMessage that causes the transport
     *  layer to initiate a connection. Used to pre-warm connections for
     *  first-hop peers before the build message is sent.
     *
     *  @param ctx the router context
     *  @param peer hash of the peer to connect to
     */
    protected static void preConnectTo(RouterContext ctx, Hash peer) {
        RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
        if (ri == null)
            return;
        // Skip peers with no valid transport addresses to avoid triggering
        // bans in EstablishmentManager.establish() or NTCPTransport.send().
        if (!hasValidTransportAddress(ctx, peer)) {
            // Record failure so selector avoids this peer
            recordFirstHopFail(ctx, peer);
            Log log = ctx.logManager().getLog(TunnelPeerSelector.class);
            if (log.shouldInfo())
                log.info("Skipping pre-connect to " + peer.toBase64().substring(0,6) +
                         " — no valid SSU or NTCP address");
            return;
        }
        long lifetime = ctx.clock().now() + 30*1000;
        // Use a DatabaseLookupMessage (peer looks up its own RouterInfo and replies)
        // This triggers a real transport connection + request/response cycle,
        // keeping the session alive for the upcoming tunnel build message.
        DatabaseLookupMessage dlm = new DatabaseLookupMessage(ctx, true);
        dlm.setFrom(ctx.routerHash());
        dlm.setSearchKey(peer);
        dlm.setSearchType(DatabaseLookupMessage.Type.RI);
        dlm.setMessageExpiration(lifetime);
        OutNetMessage onm = new OutNetMessage(ctx, dlm, lifetime,
            OutNetMessage.PRIORITY_MY_BUILD_REQUEST, ri);
        // Send directly to the transport instead of going through GetBidsJob,
        // which may drop messages to non-connected peers. Direct send forces
        // connection establishment — same approach as TransportManager.establishTo().
        Transport udp = ctx.commSystem().getTransports().get("SSU");
        if (udp != null) {
            try { udp.send(onm); return; } catch (Exception e) { /* ignored */ }
        }
        Transport ntcp = ctx.commSystem().getTransports().get("NTCP");
        if (ntcp != null) {
            try { ntcp.send(onm); } catch (Exception e) { /* ignored */ }
        }
    }

    /**
     *  Check if a peer is stale — no contact (heard from or heard about) in the last 4 hours.
     *  Peers in the netDb often go offline silently; this avoids wasting first-hop
     *  selection and keepalive resources on peers that are likely dead.
     *  Skipped during the first 15 minutes of uptime (startup grace) to allow
     *  the router to build initial tunnels before profile data accumulates.
     *
     *  @param ctx the router context
     *  @param peer hash of the peer to check
     *  @return true if the peer hasn't been heard from or about in the last 4 hours
     */
    static boolean isStalePeer(RouterContext ctx, Hash peer) {
        if (ctx.router() != null && ctx.router().getUptime() < 15*60*1000)
            return false;
        PeerProfile profile = ctx.profileOrganizer().getProfileNonblocking(peer);
        if (profile == null)
            return true;
        long now = ctx.clock().now();
        long cutoff = now - 4*60*60*1000;
        return profile.getLastHeardFrom() < cutoff && profile.getLastHeardAbout() < cutoff;
    }

    /**
     *  Periodically called to keep transport sessions alive for top-tier peers and
     *  proactively establish connections to Fast/HighCap peers before builds need them.
     *
     *  This prevents the natural session aging that drops the active peer count from
     *  ~600 to ~300 in the first 30 minutes, which starves first-hop selection and
     *  causes tunnel pool collapse.
     *
     *  @param ctx the router context
     *  @param aggressive if true, also pre-connect to non-established eligible peers
     *                    (used when any pool has 0 tunnels)
     */
    public static void keepAlive(RouterContext ctx, boolean aggressive) {
        long now = ctx.clock().now();
        Log log = ctx.logManager().getLog(TunnelPeerSelector.class);
        RouterContext rctx = ctx;

        // Collect top Fast + HighCap peers that aren't in first-hop fail cooldown
        Set<Hash> targets = new HashSet<>(512);
        // Must use mutable set — locked_selectPeers may add to the exclude set
        rctx.profileOrganizer().selectFastPeers(400, new HashSet<>(4), targets);
        // Also add top HighCap to cover more candidates
        rctx.profileOrganizer().selectHighCapacityPeers(400, targets, targets);
        // Remove self
        targets.remove(rctx.routerHash());

        if (targets.isEmpty())
            return;

        int keepalived = 0;
        int preConnected = 0;

        for (Hash peer : targets) {
            if (keepalived + preConnected >= 400)
                break; // per-cycle budget (doubled)

            // Skip peers in first-hop fail cooldown — they've proven unreachable recently
            if (isFirstHopFailing(rctx, peer))
                continue;

            // Skip stale peers — no activity in the last 4 hours
            if (isStalePeer(rctx, peer))
                continue;

            Long lastKa = _lastKeepAlive.get(peer);
            if (lastKa != null && now - lastKa < KEEPALIVE_INTERVAL_MS)
                continue;

            boolean established = rctx.commSystem().isEstablished(peer);

            if (established) {
                // Peer already connected — send a lightweight DLM to keep the session alive.
                // For established peers, transport.send() just enqueues to fragments with
                // no establishment overhead.
                RouterInfo ri = rctx.netDb().lookupRouterInfoLocally(peer);
                if (ri == null) continue;
                long lifetime = now + 30*1000;
                DatabaseLookupMessage dlm = new DatabaseLookupMessage(rctx, true);
                dlm.setFrom(rctx.routerHash());
                dlm.setSearchKey(peer);
                dlm.setSearchType(DatabaseLookupMessage.Type.RI);
                dlm.setMessageExpiration(lifetime);
                OutNetMessage onm = new OutNetMessage(rctx, dlm, lifetime,
                    OutNetMessage.PRIORITY_MY_BUILD_REQUEST, ri);
                Transport udp = rctx.commSystem().getTransports().get("SSU");
                if (udp != null) {
                    try { udp.send(onm); keepalived++; _lastKeepAlive.put(peer, now); } catch (Exception e) { /* ignored */ }
                }
            } else if (aggressive) {
                // Peer not connected and pools are depleted — proactively start
                // establishment so it's ready when the next build runs.
                preConnectTo(rctx, peer);
                preConnected++;
                _lastKeepAlive.put(peer, now);
            }
        }

        if (log.shouldInfo() && (keepalived + preConnected > 0)) {
            log.info("KeepAlive: " + keepalived + " keepalives, " + preConnected +
                     " pre-connects (" + (aggressive ? "aggressive" : "normal") +
                     ", " + targets.size() + " targets)");
        }
    }
}
