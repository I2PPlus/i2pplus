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
                                                        String.valueOf(Router.CAPABILITY_NO_TUNNELS);
    private static final String ALT_EXCLUDE_CAPS = String.valueOf(Router.CAPABILITY_BW12) +
                                                   String.valueOf(Router.CAPABILITY_NO_TUNNELS);

    private static volatile RouterContext _cfgCtx;
    private static volatile long _cfgRefreshed;
    private static volatile String _cachedExcludeCaps;
    private static volatile String _cachedExplicitPeers;
    private static volatile boolean _cachedIbExplUnreachable;
    private static volatile boolean _cachedObExplUnreachable;
    private static volatile boolean _cachedIbClientUnreachable;
    private static volatile boolean _cachedObClientUnreachable;
    private static volatile boolean _cachedIbExplSlow;
    private static volatile boolean _cachedObExplSlow;
    private static volatile boolean _cachedIbClientSlow;
    private static volatile boolean _cachedObClientSlow;
    private static final long CONFIG_REFRESH_MS = 30 * 1000L;

    /**
     *  Refresh the cached peer-selection configuration from properties at
     *  most once per CONFIG_REFRESH_MS, or immediately when the context
     *  changes.  Benign race: duplicate refreshes are idempotent writes.
     *  Values without a configured property cache as null, preserving the
     *  per-call default behavior of the callers.
     */
    private static void refreshPeerConfig(RouterContext ctx) {
        long now = ctx.clock().now();
        if (_cfgCtx == ctx && now - _cfgRefreshed < CONFIG_REFRESH_MS)
            return;
        _cachedExcludeCaps = ctx.getProperty("router.excludePeerCaps");
        _cachedExplicitPeers = ctx.getProperty("explicitPeers");
        _cachedIbExplUnreachable = ctx.getProperty(PROP_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE, DEFAULT_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE);
        _cachedObExplUnreachable = ctx.getProperty(PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE, DEFAULT_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE);
        _cachedIbClientUnreachable = ctx.getProperty(PROP_INBOUND_CLIENT_EXCLUDE_UNREACHABLE, DEFAULT_INBOUND_CLIENT_EXCLUDE_UNREACHABLE);
        _cachedObClientUnreachable = ctx.getProperty(PROP_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE, DEFAULT_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE);
        _cachedIbExplSlow = ctx.getProperty(PROP_INBOUND_EXPLORATORY_EXCLUDE_SLOW, true);
        _cachedObExplSlow = ctx.getProperty(PROP_OUTBOUND_EXPLORATORY_EXCLUDE_SLOW, true);
        _cachedIbClientSlow = ctx.getProperty(PROP_INBOUND_CLIENT_EXCLUDE_SLOW, true);
        _cachedObClientSlow = ctx.getProperty(PROP_OUTBOUND_CLIENT_EXCLUDE_SLOW, true);
        _cfgCtx = ctx;
        _cfgRefreshed = now;
    }

    private static String getCachedExcludeCaps(RouterContext ctx) {
        refreshPeerConfig(ctx);
        return _cachedExcludeCaps;
    }
    private static String getCachedExplicitPeers(RouterContext ctx) {
        refreshPeerConfig(ctx);
        return _cachedExplicitPeers;
    }
    private static boolean getCachedIbExplUnreachable(RouterContext ctx) {
        refreshPeerConfig(ctx);
        return _cachedIbExplUnreachable;
    }
    private static boolean getCachedObExplUnreachable(RouterContext ctx) {
        refreshPeerConfig(ctx);
        return _cachedObExplUnreachable;
    }
    private static boolean getCachedIbClientUnreachable(RouterContext ctx) {
        refreshPeerConfig(ctx);
        return _cachedIbClientUnreachable;
    }
    private static boolean getCachedObClientUnreachable(RouterContext ctx) {
        refreshPeerConfig(ctx);
        return _cachedObClientUnreachable;
    }
    private static boolean getCachedIbExplSlow(RouterContext ctx) {
        refreshPeerConfig(ctx);
        return _cachedIbExplSlow;
    }
    private static boolean getCachedObExplSlow(RouterContext ctx) {
        refreshPeerConfig(ctx);
        return _cachedObExplSlow;
    }
    private static boolean getCachedIbClientSlow(RouterContext ctx) {
        refreshPeerConfig(ctx);
        return _cachedIbClientSlow;
    }
    private static boolean getCachedObClientSlow(RouterContext ctx) {
        refreshPeerConfig(ctx);
        return _cachedObClientSlow;
    }

    /** Threshold for detecting tunnel build attacks */
    protected static final double ATTACK_THRESHOLD = ProfileOrganizer.ATTACK_THRESHOLD;
    /** Duration in ms to suppress startup warnings */
    protected static final long STARTUP_WARNING_SUPPRESS_MS = 5 * 60 * 1000L;

    /**
     *  Build success at or below this widens the activity window to counter the
     *  purgatory band (40-79% success) where the recency gate prunes good peers
     *  faster than they can be re-tested, tightening the eligible pool in a
     *  self-reinforcing loop. Higher than {@link #ATTACK_THRESHOLD} because
     *  relaxing recency is far safer than relaxing capability exclusions.
     */
    protected static final double DEGRADED_BUILD_THRESHOLD = 0.65;

    /** Multiplier applied to the base activity window by {@link #getActivityWindow}. */
    private static final int MIN_WINDOW_MULTIPLIER = 1;
    private static final int MAX_WINDOW_MULTIPLIER = 4;
    private static final int DEFAULT_WINDOW_MULTIPLIER = 1;

    /**
     *  Tuner-controlled multiplier for the peer-selection activity window.
     *  Widening it re-admits recently-good peers whose last successful test has
     *  aged out during a build slump. Adjusted by the Tuner's ActivityWindowParam
     *  from {@code tunnel.buildSuccessRate}; clamped to [1, 8].
     */
    private static volatile int _windowMultiplier = DEFAULT_WINDOW_MULTIPLIER;

    /** Peers selected within this window are excluded from further selection to ensure diversity */
    protected static final long PEER_SELECTION_COOLDOWN_MS = 60_000;

    /** Shared cooldown map across all peer selectors */
    protected static final Map<Hash, Long> _peerCooldowns = new ConcurrentHashMap<>();

    /** Lock for atomic cooldown check+record within client selection.  A single
     *  selector instance serves all client pools, so concurrent selections from
     *  different pool threads are serialized.  Exploratory selection does its
     *  own check+record on a per-selector map and does not take this lock. */
    protected static final Object _cooldownLock = new Object();

    /**
     *  Format a set of excluded peers for logging, with exclusion reasons when
     *  the set is an {@link Excluder} or {@link ExcluderBase}.
     *  @since 0.9.71+
     */
    protected static String formatExcludedPeers(Set<Hash> peers) {
        if (peers == null || peers.isEmpty()) {return "[no exclusions]";}
        if (peers instanceof Excluder) {
            return ((Excluder) peers).formatByReasonWithPeers();
        }
        if (peers instanceof ExcluderBase) {
            return ((ExcluderBase) peers).getReasonsSummary();
        }
        StringBuilder sb = new StringBuilder(peers.size() * 10);
        int count = 0;
        for (Hash h : peers) {
            if (count % 12 == 0) {sb.append("\n* ");}
            sb.append('[').append(h.toBase64(), 0, 6).append("] ");
            count++;
        }
        return sb.toString();
    }

    /** Peers that failed as first hop (first hop unreachable) excluded for this long */
    protected static final long FIRST_HOP_FAIL_COOLDOWN_MS = 5 * 60 * 1000L;

    /** Tracks when a peer last failed as first hop */
    protected static final Map<Hash, Long> _firstHopFails = new ConcurrentHashMap<>();

    /** How often to send keepalive pings to established Fast/HighCap peers */
    private static final long KEEPALIVE_INTERVAL_MS = 15_000; // More frequent keepalives

    /** Tracks last keepalive send time per peer */
    private static final ConcurrentHashMap<Hash, Long> _lastKeepAlive = new ConcurrentHashMap<>(512);

    /**
     *  Size bound for the cooldown maps ({@link #_peerCooldowns},
     *  {@link #_firstHopFails}).  Entries are recorded on selection failures,
     *  tunnel rejects, and tunnel reuse, and are also expired time-based on
     *  every selection, so these maps stay small — 128 entries is already
     *  generous.  This bound only limits growth between selections; it does
     *  not evict live entries.
     *  @since 0.9.71+
     */
    private static final int FAILURE_MAP_MAX_SIZE = 128;

    /**
     *  Size bound for {@link #_lastKeepAlive}.  Larger than the failure maps
     *  because it tracks one entry per Fast/HighCap peer being keepalived
     *  (keepalive budget is 400 peers per cycle), so hundreds of live entries
     *  are normal.  Stale entries are expired time-based on every selection.
     *  @since 0.9.71+
     */
    private static final int KEEPALIVE_MAP_MAX_SIZE = 512;

    /**
     *  Check if a peer recently failed as first hop and should be excluded.
     *  During ghost cascades (high ghost count), the cooldown is shortened
     *  from 5 min to 60s to rehabilitate peers faster when the network
     *  is stressed — many peers are ghosted through no fault of their own.
     *
     *  @param ctx the router context
     *  @param peer the peer
     *  @return true if the peer should be excluded
     */
    public static boolean isFirstHopFailing(RouterContext ctx, Hash peer) {
        Long when = _firstHopFails.get(peer);
        if (when == null)
            return false;
        long cooldown = getEffectiveFirstHopCooldown(ctx);
        if (ctx.clock().now() - when > cooldown) {
            _firstHopFails.remove(peer);
            return false;
        }
        return true;
    }

    /**
     * Effective first-hop fail cooldown.
     * During ghost cascades (>50 ghosts), shorten from 5 min to 60s
     * to rehabilitate peers faster when the network is stressed.
     * @return the effective first hop cooldown
     * @since 0.9.70
     */
    private static long getEffectiveFirstHopCooldown(RouterContext ctx) {
        try {
            TunnelManagerFacade tmf = ctx.tunnelManager();
            if (tmf != null) {
                GhostPeerManager gpm = tmf.getGhostPeerManager();
                if (gpm != null && gpm.getGhostCount() > 50) {
                    return 60 * 1000L;
                }
            }
        } catch (Exception e) {
            // ignore — fall through to default
        }
        return FIRST_HOP_FAIL_COOLDOWN_MS;
    }

    /**
     *  Record that a peer failed as first hop (first hop unreachable).
     *
     *  @param ctx the router context
     *  @param peer the peer
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
     *  Prune expired entries from static peer maps.
     *  Called periodically from peer selection to prevent unbounded growth.
     *
     *  @param ctx the router context
     *  @since 0.9.70
     */
    protected static void prunePeerMaps(RouterContext ctx) {
        long now = ctx.clock().now();
        // Eviction thresholds bound the cooldown maps tightly (128): entries
        // are recorded on failures, rejects, or reuse and also expire
        // time-based on every selection, so these maps hold only peers touched
        // within the current cooldown window.  The keepalive map is larger
        // because it tracks one entry per Fast/HighCap peer being kept alive.
        if (_peerCooldowns.size() > FAILURE_MAP_MAX_SIZE) {
            long cutoff = now - PEER_SELECTION_COOLDOWN_MS;
            _peerCooldowns.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
        if (_lastKeepAlive.size() > KEEPALIVE_MAP_MAX_SIZE) {
            long cutoff = now - KEEPALIVE_INTERVAL_MS * 4;
            _lastKeepAlive.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
        if (_firstHopFails.size() > FAILURE_MAP_MAX_SIZE) {
            long cutoff = now - FIRST_HOP_FAIL_COOLDOWN_MS;
            _firstHopFails.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
    }

    /**
     * All non-self peers in active tunnels of the given pool.
     * Used to enforce per-pool diversity: no peer in more than 1 tunnel per pool.
     *
     * @param ctx the router context
     * @param pool the tunnel pool to scan
     * @return set of peer hashes already in active tunnels of this pool
     * @since 0.9.70
     */
    protected static Set<Hash> getPeersInPool(RouterContext ctx, TunnelPool pool) {
        Set<Hash> rv = new HashSet<>();
        if (pool == null) return rv;
        for (TunnelInfo ti : pool.listTunnels()) {
            if (ti.getLength() > 1) {
                for (int j = 0; j < ti.getLength(); j++) {
                    Hash peer = ti.getPeer(j);
                    if (peer != null && !peer.equals(ctx.routerHash())) {
                        rv.add(peer);
                    }
                }
            }
        }
        return rv;
    }

    /**
     * Record that a peer failed during peer selection (first-hop or adjacent).
     * Used by ClientPeerSelector and ExploratoryPeerSelector to mark peers
     * that failed selection criteria, preventing re-selection for the cooldown.
     *
     * @param ctx the router context
     * @param peer the peer
     */
    protected static void recordPeerFailure(RouterContext ctx, Hash peer) {
        _firstHopFails.put(peer, ctx.clock().now());
        // Periodically prune all static peer maps
        if (_peerCooldowns.size() > FAILURE_MAP_MAX_SIZE || _lastKeepAlive.size() > KEEPALIVE_MAP_MAX_SIZE) {
            prunePeerMaps(ctx);
        }
    }

    /**
     * Check if a peer has recovered from failure and can be reconsidered.
     * Uses the effective first-hop cooldown (shortened during ghost cascades).
     *
     * @param ctx the router context
     * @param peer the peer
     * @return true if the peer has recovered
     */
    protected static boolean hasRecoveredFromFailure(RouterContext ctx, Hash peer) {
        Long failTime = _firstHopFails.get(peer);
        if (failTime == null)
            return true;
        long cooldown = getEffectiveFirstHopCooldown(ctx);
        long recoveryTime = ctx.clock().now() - cooldown;
        if (failTime < recoveryTime) {
            _firstHopFails.remove(peer);
            return true;
        }
        return false;
    }

    /**
     * TunnelPeerSelector.
     */
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
     *
     * @return true if the router is in the startup grace period
     */
    protected boolean isInStartupGracePeriod() {
        return isInStartupGracePeriod(ctx);
    }

    /**
     * Which peers should go into the next tunnel for the given settings?
     *
     * @param settings the tunnel pool settings
     * @return ordered list of Hash objects (one per peer) specifying what order
     *         they should appear in a tunnel (ENDPOINT FIRST).  This includes
     *         the local router in the list.  If there are no tunnels or peers
     *         to build through, and the settings reject 0 hop tunnels, this will
     *         return null.
     */
    public abstract List<Hash> selectPeers(TunnelPoolSettings settings);

    /**
     *  Determine the tunnel length (number of hops).
     *
     *  @param settings the tunnel pool settings
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
     *
     *  @param settings the tunnel pool settings
     *  @return usually false
     */
    protected boolean shouldSelectExplicit(TunnelPoolSettings settings) {
        if (settings.isExploratory()) return false;
        // To test IB or OB only
        Properties opts = settings.getUnknownOptions();
        String peers = opts.getProperty("explicitPeers");
        if (peers == null)
            peers = getCachedExplicitPeers(ctx);
        // only one out of 4 times so we don't break completely if peer doesn't build one
        return peers != null && ctx.random().nextInt(4) == 0;
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
            peers = getCachedExplicitPeers(ctx);

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
     *
     *  @param isInbound true for inbound tunnels
     *  @param isExploratory true for exploratory tunnels
     *  @return set of excluded peers
     */
    protected Set<Hash> getExclude(boolean isInbound, boolean isExploratory) {
        return new Excluder(isInbound, isExploratory);
    }

    /**
     * Check if a peer should be excluded from closest hop selection.
     * This performs connectivity checks and version capability validation.
     * Used by Excluder to classify exclusion reasons for diagnostics.
     *
     * @param peerHash the peer hash to check
     * @param isInbound true if this is for an inbound tunnel
     * @param isExploratory true if this is for exploratory tunnels
     * @return the exclusion reason, or null if peer should not be excluded
     * @since 0.9.58
     */
    private String getExclusionReason(Hash peerHash, boolean isInbound, boolean isExploratory) {
        final long BANDWIDTH_REJECTION_CUTOFF_MS = 20_000L;

        // A banlisted peer must never be pre-selected for tunnel builds.
        // The banlist is enforced at ingress (BuildHandler, throttlers), but
        // selection used to ignore it, so we could build through peers we had
        // flagged as abusive.  First check, cheapest and most decisive.
        if (ctx.banlist().isBanlisted(peerHash)) {
            return "banned";
        }

        PeerProfile profile = ctx.profileOrganizer().getProfileNonblocking(peerHash);
        if (profile != null && wasRecentlyRejected(profile, BANDWIDTH_REJECTION_CUTOFF_MS)) {
            return "recently-rejected";
        }

        if (ctx.commSystem().wasUnreachable(peerHash)) {
            return "unreachable";
        }

        RouterInfo routerInfo = (RouterInfo) ctx.netDb().lookupRouterInfoLocally(peerHash);
        if (routerInfo == null) {
            return "no-routerinfo";
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
            String caps = routerInfo.getCapabilities();
            if (caps.indexOf(Router.CAPABILITY_CONGESTION_SEVERE) >= 0) {
                return "severe-congestion";
            }
            if (caps.indexOf(Router.CAPABILITY_CONGESTION_MODERATE) >= 0) {
                return "moderate-congestion";
            }
            String excludeCaps = getEffectiveExcludeCaps(ctx);
            if (shouldExclude(ctx, routerInfo, excludeCaps, isExploratory)) {
                return "slow/capped";
            }
        }

        // Pre-qualification: reject peers with zero connectivity signal.
        // Peers that have never been tested, heard from, or connected to
        // will waste tunnel builds and test cycles.  This is always active
        // for client pools (skipped for exploratory and during startup).
        // Use a 30-minute activity window for heard-from/sent-to checks
        // (wider than the old 10-minute window) to avoid starving peer
        // selection when the network is sparse or under load.
        if (!isExploratory && !isInStartupGracePeriod(ctx)) {
            boolean hasSignal = false;
            long now = ctx.clock().now();
            // Connected peers always pass
            if (ctx.commSystem().isEstablished(peerHash)) {
                hasSignal = true;
            }
            // Recently heard from or successfully sent to (30-minute window)
            if (!hasSignal && profile != null) {
                long heardWindow = 30 * 60 * 1000L;
                if (profile.getLastHeardFrom() > 0 &&
                    now - profile.getLastHeardFrom() < heardWindow) {
                    hasSignal = true;
                }
                if (profile.getLastSendSuccessful() > 0 &&
                    now - profile.getLastSendSuccessful() < heardWindow) {
                    hasSignal = true;
                }
            }
            // Has a recent successful tunnel test (dynamic window)
            if (!hasSignal && profile != null) {
                long lastTested = profile.getTunnelHistory().getLastTestedSuccessfully();
                if (lastTested > 0 && now - lastTested < getActivityWindow(ctx)) {
                    hasSignal = true;
                }
            }
            // Soft fallback: accept peers with ANY tunnel test history
            // (even old) if they have a reasonable acceptance ratio (>50%).
            // These peers are proven-capable but simply haven't been contacted
            // recently — better than excluding them entirely.
            if (!hasSignal && profile != null) {
                double acceptanceRatio = profile.getTunnelAcceptanceRatio();
                long lastTested = profile.getTunnelHistory().getLastTestedSuccessfully();
                if (acceptanceRatio > 0.5 && lastTested > 0) {
                    hasSignal = true;
                }
            }
            if (!hasSignal) {
                return "no-signal";
            }
        }

        return null;
    }

    /**
     * Effective exclude caps, adapting to build success.
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
     *
     *  @return true if configured for IPv6 only
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
     *  @param h the peer hash
     *  @return true if the peer can be used as OBEP
     *  @since 0.9.34, protected since 0.9.58 for ClientPeerSelector
     */
    protected boolean allowAsOBEP(Hash h) {
        // Never use a banlisted peer as an endpoint — it may refuse or drop our builds.
        if (ctx.banlist().isBanlisted(h))
            return false;
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
     *  @param h the peer hash
     *  @return true if the peer can be used as IBGW
     *  @since 0.9.34, protected since 0.9.58 for ClientPeerSelector
     */
    protected boolean allowAsIBGW(Hash h) {
        // Never use a banlisted peer as an endpoint — it may refuse or drop our builds.
        if (ctx.banlist().isBanlisted(h))
            return false;
        RouterInfo ri = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(h);
        if (ri == null)
            return true;
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
     *  @param isInbound true for inbound tunnels
     *  @param toAdd set of peers to initially populate the exclusion set
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
     *  Exclude caps to apply during peer selection.
     *  @return non-null, possibly empty
     */
    private static String getExcludeCaps(RouterContext ctx) {
        String dflt = (ctx.random().nextInt(4) != 0) ? DEFAULT_EXCLUDE_CAPS : ALT_EXCLUDE_CAPS;
        String val = getCachedExcludeCaps(ctx);
        return val != null ? val : dflt;
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
                return ctx.random().nextInt(6) != 0;  // 5/6 chance: Exclude, 1/6: Allow
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

        // Peer is acceptable — pre-qualification by build-success rate is no
        // longer applied here; capability/version filtering above is sufficient.
        return false;
    }

    private static final String PROP_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = "router.outboundExploratoryExcludeUnreachable";
    private static final String PROP_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE = "router.outboundClientExcludeUnreachable";
    private static final String PROP_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = "router.inboundExploratoryExcludeUnreachable";
    private static final String PROP_INBOUND_CLIENT_EXCLUDE_UNREACHABLE = "router.inboundClientExcludeUnreachable";
    private static final boolean DEFAULT_OUTBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = false;
    private static final boolean DEFAULT_OUTBOUND_CLIENT_EXCLUDE_UNREACHABLE = false;
    // see comments at getExclude() above
    private static final boolean DEFAULT_INBOUND_EXPLORATORY_EXCLUDE_UNREACHABLE = false;
    private static final boolean DEFAULT_INBOUND_CLIENT_EXCLUDE_UNREACHABLE = false;

    /**
     * Whether to skip unreachable peers.
     * @return true if unreachable peers should be skipped
     */
    private boolean filterUnreachable(boolean isInbound, boolean isExploratory) {
        if (SystemVersion.isSlow() || ctx.router().getUptime() < 65*60*1000L)
            return true;
        if (isExploratory) {
            if (isInbound) {
                if (ctx.router().isHidden())
                    return true;
                return getCachedIbExplUnreachable(ctx);
            } else {
                return getCachedObExplUnreachable(ctx);
            }
        } else {
            if (isInbound) {
                if (ctx.router().isHidden())
                    return true;
                return getCachedIbClientUnreachable(ctx);
            } else {
                return getCachedObClientUnreachable(ctx);
            }
        }
    }

    private static final String PROP_OUTBOUND_EXPLORATORY_EXCLUDE_SLOW = "router.outboundExploratoryExcludeSlow";
    private static final String PROP_OUTBOUND_CLIENT_EXCLUDE_SLOW = "router.outboundClientExcludeSlow";
    private static final String PROP_INBOUND_EXPLORATORY_EXCLUDE_SLOW = "router.inboundExploratoryExcludeSlow";
    private static final String PROP_INBOUND_CLIENT_EXCLUDE_SLOW = "router.inboundClientExcludeSlow";

    /**
     * Whether to skip peers that are slow.
     *
     * @param isInbound true for inbound tunnels
     * @param isExploratory true for exploratory tunnels
     * @return true unless configured otherwise
     */
    protected boolean filterSlow(boolean isInbound, boolean isExploratory) {
        if (isExploratory) {
            if (isInbound) {return getCachedIbExplSlow(ctx);}
            else {return getCachedObExplSlow(ctx);}
        } else {
            if (isInbound) {return getCachedIbClientSlow(ctx);}
            else {return getCachedObClientSlow(ctx);}
        }
    }

    /**
     * Order peers using the given key.
     *
     * @param rv the list to order
     * @param key the session key for ordering
     */
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

            // newPeers excludes self. Compare from gateway side for inbound
            // (existing index 0) or after self for outbound (existing index 1).
            boolean match = true;
            int offset = settings.isInbound() ? 0 : 1;
            for (int i = 0; i < newPeers.size(); i++) {
                Hash existingPeer = existing.getPeer(i + offset);
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

    private static final Comparator<Hash> HASH_BASE64_COMPARATOR = Comparator.comparing(h -> h.toBase64());

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
        private final long k0;
        private final long k1;

        /**
         * Not thread safe.
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

        /**
         * Compare the two hashes by SipHash distance.
         */
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
     *  @param isInbound true for inbound tunnels
     *  @param isExploratory true for exploratory tunnels
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
            if (!canConnect(hf, ht)) {
                if (log.shouldWarn()) {
                    StringBuilder buf = new StringBuilder();
                    for (Hash h : tunnel) {
                        buf.append("[").append(h.toBase64().substring(0,6)).append("]"); buf.append(" ");
                    }
                    log.warn("Connection check failed at hop [" + (i+1) + " -> " + i +
                             "] in tunnel (Gateway -> Endpoint)\n* Tunnel: " + buf.toString());
                }
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

    private static final Map<String, String> REASON_LABELS = new LinkedHashMap<>(8);
    static {
        REASON_LABELS.put("too-many-tunnels", "Too many tunnels");
        REASON_LABELS.put("recently-rejected", "Recently rejected");
        REASON_LABELS.put("unreachable", "Unreachable");
        REASON_LABELS.put("no-routerinfo", "No router info");
        REASON_LABELS.put("floodfill", "Floodfill");
        REASON_LABELS.put("U-cap", "Unreachable cap");
        REASON_LABELS.put("moderate-congestion", "Moderate congestion");
        REASON_LABELS.put("severe-congestion", "Severe congestion");
        REASON_LABELS.put("slow/capped", "Slow or capped");
        REASON_LABELS.put("no-signal", "No signal");
    }

    /**
     * Excluder that automatically adds peers to the set when they should be excluded.
     *
     * @since 0.9.58
     */
    protected class Excluder extends ExcluderBase {
        private static final int MAX_EXCLUDED_PEERS = 384;

        private final boolean _isIn;
        private final boolean _isExpl;

        /**
         *  Automatically adds selectPeersInTooManyTunnels(), unless i2np.allowLocal.
         */
        public Excluder(boolean isInbound, boolean isExploratory) {
            super(ctx.getBooleanProperty("i2np.allowLocal") ? new LinkedHashSet<>()
                                                              : new LinkedHashSet<>(ctx.tunnelManager().selectPeersInTooManyTunnels()));
            _isIn = isInbound;
            _isExpl = isExploratory;
            for (Hash h : s) {recordExclusion(h, "too-many-tunnels");}
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

    @Override
    public boolean contains(Object o) {
            if (s.contains(o)) {return true;}
            Hash h = (Hash) o;
            String reason = getExclusionReason(h, _isIn, _isExpl);
            if (reason != null) {
                s.add(h);
                recordExclusion(h, reason);
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
         *  Format excluded peers grouped by reason, sorted by hash within each group.
         *  @return multi-line string like "Too many tunnels (128): peer1 peer2..."
         */
        String formatByReasonWithPeers() {
            if (_reasons.isEmpty()) return "";
            Map<String, List<Hash>> byReason = new LinkedHashMap<>();
            List<String> reasonOrder = new ArrayList<>();
            for (Map.Entry<Hash, String> e : _reasons.entrySet()) {
                String reason = e.getValue();
                List<Hash> list = byReason.get(reason);
                if (list == null) {
                    list = new ArrayList<>();
                    byReason.put(reason, list);
                    reasonOrder.add(reason);
                }
                list.add(e.getKey());
            }
            for (List<Hash> list : byReason.values()) {
                list.sort(HASH_BASE64_COMPARATOR);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(s.size()).append(" excluded\n");
            for (int i = 0; i < reasonOrder.size(); i++) {
                List<Hash> list = byReason.get(reasonOrder.get(i));
                String label = REASON_LABELS.get(reasonOrder.get(i));
                if (label == null) {label = reasonOrder.get(i);}
                sb.append("* ").append(label).append(" (").append(list.size()).append("):");
                for (Hash h : list) {
                    sb.append(' ').append(h.toBase64(), 0, 6);
                }
                if (i + 1 < reasonOrder.size()) {sb.append('\n');}
            }
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
            if (!canConnect) {
                s.add(h);
                recordExclusion(h, "unreachable");
            }
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
        long lifetime = ctx.clock().now() + 30*1000L;
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
     *  Check whether a peer is stale — no contact (heard from or heard about)
     *  within the dynamic activity window. The window adapts to network
     *  visibility: 500+ active peers use 1 hour, 200+ use 2 hours, 100+ use
     *  4 hours, fewer than 100 use 8 hours (fresh router building up picture).
     *
     *  Stale peers are skipped during first-hop selection and keepalive to
     *  avoid wasting resources on peers that are likely offline. Skipped
     *  during the first 15 minutes of uptime (startup grace).
     *
     *  @param ctx the router context
     *  @param peer hash of the peer to check
     *  @return true if the peer has not been heard from or about within the activity window
     */
    static boolean isStalePeer(RouterContext ctx, Hash peer) {
        if (ctx.router() != null && ctx.router().getUptime() < 15*60*1000L)
            return false;
        PeerProfile profile = ctx.profileOrganizer().getProfileNonblocking(peer);
        if (profile == null)
            return true;
        long now = ctx.clock().now();
        long cutoff = now - getActivityWindow(ctx);
        return profile.getLastHeardFrom() < cutoff && profile.getLastHeardAbout() < cutoff;
    }

    /**
     *  Compute the activity window for peer selection based on current network
     *  visibility.  When we hear from many peers, we can be selective (short window).
     *  When the router is fresh or the network is sparse, use a wider window to
     *  avoid starving peer pools.
     *
     *  The base window (from active-peer count) is scaled by the Tuner-controlled
     *  multiplier ({@link #setWindowMultiplier}) and floored to at least 4 hours
     *  when build success is in the degraded/purgatory band, so good peers whose
     *  last successful test has aged out are re-admitted instead of pruned in a
     *  self-reinforcing loop.
     *
     *  @param ctx the router context
     *  @return activity window in milliseconds
     *  @since 0.9.70+
     */
    public static long getActivityWindow(RouterContext ctx) {
        int active = ctx.commSystem().countActivePeers();
        long base;
        if (active >= 500) {base = 1 * 60 * 60 * 1000L;}        // 1 hour
        else if (active >= 200) {base = 2 * 60 * 60 * 1000L;}   // 2 hours
        else if (active >= 100) {base = 4 * 60 * 60 * 1000L;}   // 4 hours
        else {base = 8 * 60 * 60 * 1000L;}                      // 8 hours

        long window = base * _windowMultiplier;

        // Acute floor: when builds are degraded, never let the window fall below
        // 6 hours regardless of active-peer count, to break the pruning loop fast
        // (the Tuner multiplier reacts more slowly across cycles).
        // Raised from 4h to 6h to retain more peer candidates during sustained
        // degradation — the old 4h floor still excluded too many viable peers.
        double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
        if (buildSuccess > 0 && buildSuccess < DEGRADED_BUILD_THRESHOLD) {
            window = Math.max(window, 6 * 60 * 60 * 1000L);
        }

        return Math.min(window, 12 * 60 * 60 * 1000L);
    }

    /**
     *  Tuner-controlled activity-window multiplier, clamped to [1, 8].
     *
     *  @param mult the multiplier applied to the base activity window
     *  @since 0.9.70+
     */
    public static void setWindowMultiplier(int mult) {
        _windowMultiplier = Math.max(MIN_WINDOW_MULTIPLIER, Math.min(MAX_WINDOW_MULTIPLIER, mult));
    }

    /**
     *  Current Tuner-controlled activity-window multiplier.
     *
     *  @return the current multiplier, in [1, 8]
     *  @since 0.9.70+
     */
    public static int getWindowMultiplier() {
        return _windowMultiplier;
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
                long lifetime = now + 30*1000L;
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
