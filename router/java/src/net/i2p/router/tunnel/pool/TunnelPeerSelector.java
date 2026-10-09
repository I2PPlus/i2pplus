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
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.CoreVersion;
import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.crypto.SipHashInline;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.OutNetMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.transport.Transport;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.peermanager.FloodfillReliability;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.transport.TransportUtil;
import net.i2p.stat.RateConstants;
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
     * Endpoint eligibility cache: avoids re-checking ~670 fast peers on every
     * selectSingleHop / buildReplacementTunnels cycle.  Caches the RouterInfo /
     * canConnect decision only — the banlist is always consulted live (cheap
     * map lookup) so a newly banlisted peer is never selected from cache.
     * Separate caches for IBGW and OBEP because the same Hash can have
     * different eligibility per role.  Entries expire after
     * {@link #ENDPOINT_CACHE_TTL_MS}; oversized maps are pruned
     * opportunistically at amortized intervals.  Pure performance —
     * correctness is preserved on cache miss (re-check) and stale entries
     * are bounded to the TTL.
     * @since 0.9.71+
     */
    private static final long ENDPOINT_CACHE_TTL_MS = 300 * 1000L;
    private static final int ENDPOINT_CACHE_MAX_SIZE = 4096;
    /** Prune at most every this many cache misses once over capacity. */
    private static final int ENDPOINT_PRUNE_STRIDE = 64;
    private static final ConcurrentHashMap<Hash, EndpointCacheEntry> _ibgwCache =
        new ConcurrentHashMap<>(512);
    private static final ConcurrentHashMap<Hash, EndpointCacheEntry> _obepCache =
        new ConcurrentHashMap<>(512);
    private static final AtomicInteger _endpointPruneCounter = new AtomicInteger();

    private static final class EndpointCacheEntry {
        final boolean allowed;
        final long expiresAt;
        EndpointCacheEntry(boolean allowed, long expiresAt) {
            this.allowed = allowed;
            this.expiresAt = expiresAt;
        }
        boolean isExpired(long now) { return now >= expiresAt; }
    }

    /**
     * Prune expired entries when the cache grows too large.  Called
     * opportunistically before inserts — no background thread needed.
     * Amortized: only one prune scan per {@link #ENDPOINT_PRUNE_STRIDE}
     * oversized misses so a hot selection loop cannot burn CPU on
     * full-map removeIf every insert.
     */
    private static void pruneEndpointCache(ConcurrentHashMap<Hash, EndpointCacheEntry> cache, long now) {
        if (cache.size() <= ENDPOINT_CACHE_MAX_SIZE) return;
        if ((_endpointPruneCounter.incrementAndGet() % ENDPOINT_PRUNE_STRIDE) != 0)
            return;
        cache.entrySet().removeIf(e -> e.getValue().isExpired(now));
        // If still oversized (many non-expired entries), clear to bound memory;
        // the cache will repopulate on next build cycle.
        if (cache.size() > ENDPOINT_CACHE_MAX_SIZE) {
            cache.clear();
        }
    }

    /**
     * Refresh the cached peer-selection configuration from properties at
     * most once per CONFIG_REFRESH_MS, or immediately when the context
     * changes.  Benign race: duplicate refreshes are idempotent writes.
     * Values without a configured property cache as null, preserving the
     * per-call default behavior of the callers.
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

    /** Multiplier applied to the base activity window by {@link #getActivityWindow}. */
    private static final int MIN_WINDOW_MULTIPLIER = 1;
    private static final int MAX_WINDOW_MULTIPLIER = 4;
    private static final int DEFAULT_WINDOW_MULTIPLIER = 1;

    /**
     * Tuner-controlled multiplier for the peer-selection activity window.
     * Widening it re-admits recently-good peers whose last successful test has
     * aged out during a build slump. Adjusted by the Tuner's ActivityWindowParam
     * from {@code tunnel.buildSuccessRate}; clamped to [1, 4].
     */
    private static volatile int _windowMultiplier = DEFAULT_WINDOW_MULTIPLIER;

    /** Peers selected within this window are excluded from further selection to ensure diversity */
    protected static final long PEER_SELECTION_COOLDOWN_MS = 60_000;

    /**
     * Minimum proven-responder entries before cold-start seeding kicks in.
     * At startup the map is empty, so first-build selection has no proven
     * preference.  Seeding with random high-tier peers provides a warm
     * start that converges to real data quickly.
     * @since 0.9.71+
     */
    static final int MIN_PROVEN_RESPONDER_COUNT = 50;

    /**
     * Peers whose most recent tunnel participation succeeded, mapped to the
     * time of that success. Selection prefers fresh entries via
     * {@code ClientPeerSelector.compareQuality}; entries older than
     * {@link #PROVEN_RESPONDER_WINDOW_MS} carry no weight and are pruned.
     *
     * @since 0.9.71+
     */
    protected static final Map<Hash, Long> _provenResponders = new ConcurrentHashMap<>();

    /** Recency window for proven-responder proof */
    protected static final long PROVEN_RESPONDER_WINDOW_MS = 60 * 60 * 1000L;

    /** Shared cooldown map across all peer selectors */
    protected static final Map<Hash, Long> _peerCooldowns = new ConcurrentHashMap<>();

    /**
     * Format a set of excluded peers for logging, with exclusion reasons when
     * the set is an {@link Excluder} or {@link ExcluderBase}.
     * @since 0.9.71+
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

    /** Peers requested from the fast tier in a keepalive cycle. */
    private static final int KEEPALIVE_TARGET_TIERS = 200;

    /**
     * Peers requested from the high-capacity tier in a keepalive cycle.
     *
     * <p>Set deeper than {@link #KEEPALIVE_TARGET_TIERS} because the fast and
     * high-capacity tiers are both ranked on throughput, so their top entries overlap
     * heavily and equal depth returns nearly the same peers twice. Only
     * {@link #KEEPALIVE_ACTION_BUDGET} of the union is ever acted on, so the deeper
     * request costs set insertions and a counter, not sends: the budget check runs
     * first in the loop, and a peer past the budget is tallied without ever reaching
     * the stale check or the RouterInfo lookup.
     *
     * <p>Measured over an 85-minute run at equal depth, the union collapsed from ~395
     * to ~203 peers while the action budget held at 200 — so servicing never fell,
     * only the surplus above budget did. Deeper high-cap selection lifted the union
     * to ~600-800, restoring a surplus of several hundred.
     *
     * @since 0.9.71+
     */
    private static final int KEEPALIVE_HIGH_CAP_DEPTH = 2 * KEEPALIVE_TARGET_TIERS;

    /** Most peers acted on in one cycle, bounding a cycle's cost. */
    private static final int KEEPALIVE_ACTION_BUDGET = 200;

    /** Tracks last keepalive send time per peer */
    private static final ConcurrentHashMap<Hash, Long> _lastKeepAlive = new ConcurrentHashMap<>(512);

    /**
     * Size bound for the cooldown maps ({@link #_peerCooldowns},
     * {@link #_firstHopFails}, {@link ExploratoryPeerSelector#_exploratoryCooldowns}).
     * Entries are recorded on selection failures, tunnel rejects, and tunnel
     * reuse.  They expire lazily (read-time filtering during selection) and
     * are bulk-pruned only when a map exceeds this bound, so these maps stay
     * small — 128 entries is already generous.  This bound only limits
     * growth between selections; it does not evict live entries.
     * @since 0.9.71+
     */
    protected static final int FAILURE_MAP_MAX_SIZE = 128;

    /**
     * Size bound for {@link #_lastKeepAlive}.  Larger than the failure maps
     * because it tracks one entry per Fast/HighCap peer being keepalived
     * (keepalive budget is 400 peers per cycle), so hundreds of live entries
     * are normal.  Stale entries are expired time-based on every selection.
     * @since 0.9.71+
     */
    private static final int KEEPALIVE_MAP_MAX_SIZE = 512;

    /**
     * Check if a peer recently failed as first hop and should be excluded.
     * During ghost cascades (high ghost count), the cooldown is shortened
     * from 5 min to 60s to rehabilitate peers faster when the network
     * is stressed — many peers are ghosted through no fault of their own.
     *
     * @param ctx the router context
     * @param peer the candidate to test; its first-hop reachability is probed
     * @return true if the peer should be excluded
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
     * Record that a peer failed as first hop (first hop unreachable).
     * Prevents re-selection as first hop for the cooldown, and bulk-prunes
     * the shared static maps when they exceed their size caps.
     *
     * @param ctx the router context
     * @param peer the candidate to test; its first-hop reachability is probed
     * @since 0.9.70+
     */
    protected static void recordFirstHopFail(RouterContext ctx, Hash peer) {
        _firstHopFails.put(peer, ctx.clock().now());
        // Bulk hygiene for all shared maps when any exceeds its size cap;
        // read-time filtering in the selectors handles the common case.
        if (_peerCooldowns.size() > FAILURE_MAP_MAX_SIZE ||
            _firstHopFails.size() > FAILURE_MAP_MAX_SIZE ||
            _lastKeepAlive.size() > KEEPALIVE_MAP_MAX_SIZE) {
            prunePeerMaps(ctx);
        }
    }

    /**
     * Prune expired entries from static peer maps.
     * Called periodically from peer selection to prevent unbounded growth.
     *
     * @param ctx the router context
     * @since 0.9.70
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
        if (_provenResponders.size() > FAILURE_MAP_MAX_SIZE) {
            long cutoff = now - PROVEN_RESPONDER_WINDOW_MS;
            _provenResponders.entrySet().removeIf(e -> e.getValue() < cutoff);
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
     * Seed the proven-responder map with random peers from the router's peer
     * profiles when the map is below {@link #MIN_PROVEN_RESPONDER_COUNT}.
     * At cold start (after router restart or long idle period) the map is
     * empty, so first-build selection has no proven preference and may pick
     * unreliable peers.  Seeding with random high-tier peers provides a warm
     * start that converges to real proven data after the first few builds.
     * Seeded entries use a near-expired timestamp (now - window + 5 min) so
     * they expire quickly and only provide anti-concentration, not a quality
     * boost over truly proven peers.  Best-effort; concurrent access is safe.
     *
     * @param ctx the router context
     * @param now current time in ms
     */
    static void seedProvenResponders(RouterContext ctx, long now) {
        if (_provenResponders.size() >= MIN_PROVEN_RESPONDER_COUNT) return;
        Set<Hash> peers = new HashSet<>();
        ctx.profileOrganizer().selectFastPeers(
            MIN_PROVEN_RESPONDER_COUNT * 2, null, peers, 0, null);
        if (peers.isEmpty()) return;
        // Expire in 5 min — anti-concentration only, not a quality bias
        long seedTime = now - PROVEN_RESPONDER_WINDOW_MS + 5 * 60 * 1000;
        for (Hash peer : peers) {
            if (_provenResponders.size() >= MIN_PROVEN_RESPONDER_COUNT) break;
            _provenResponders.putIfAbsent(peer, seedTime);
        }
    }

    /**
     * Proven-responder comparison for the quality cascade: a peer whose last
     * tunnel participation succeeded within the recency window sorts before
     * one without; two peers on the same side compare equal so later cascade
     * stages decide.
     *
     * @param t1 last proven-join time of the first peer, 0 if none
     * @param t2 last proven-join time of the second peer, 0 if none
     * @param now current time from the router clock
     * @return negative if only p1 is proven, positive if only p2 is, else 0
     * @since 0.9.71+
     */
    static int compareProven(long t1, long t2, long now) {
        boolean p1 = isProvenResponder(t1, now);
        boolean p2 = isProvenResponder(t2, now);
        if (p1 == p2) {return 0;}
        return p1 ? -1 : 1;
    }

    /**
     * Whether a proven-join timestamp falls inside the recency window.
     *
     * @param lastJoin the last successful-participation time, 0 if none
     * @param now current time from the router clock
     * @return true if the proof is fresh
     * @since 0.9.71+
     */
    static boolean isProvenResponder(long lastJoin, long now) {
        return lastJoin > 0 && now - lastJoin <= PROVEN_RESPONDER_WINDOW_MS;
    }

    /**
     * Replace hops lacking fresh proof with proven replacements, in order.
     * Slots outside {@code [from, to)} are protected (never replaced);
     * hops with fresh proof are kept and their peers reserved, so
     * replacements stay unique and proven peers are never duplicated into
     * another slot. Pure decision helper; package visible for tests.
     *
     * @param hops selected peers, modified in place
     * @param from first replaceable slot index
     * @param to index after the last replaceable slot
     * @param proof peer -> last successful-participation time
     * @param now current time from the router clock
     * @param window recency window for proof
     * @param replacements eligible proven candidates, most preferred first;
     * consumed as used
     * @return number of swaps performed
     * @since 0.9.71+
     */
    static int preferProven(List<Hash> hops, int from, int to,
                            Map<Hash, Long> proof, long now, long window,
                            Iterator<Hash> replacements) {
        // Reserve every current occupant up front, so replacements can never
        // duplicate a peer already sitting in another slot of this tunnel
        Set<Hash> taken = new HashSet<>(hops);
        int swaps = 0;
        for (int i = from; i < to; i++) {
            Hash h = hops.get(i);
            Long t = proof.get(h);
            if (t != null && now - t <= window) {
                continue; // fresh proof: keep
            }
            boolean swapped = false;
            while (replacements.hasNext()) {
                Hash cand = replacements.next();
                if (!taken.contains(cand)) {
                    hops.set(i, cand);
                    taken.remove(h);
                    taken.add(cand);
                    swaps++;
                    swapped = true;
                    break;
                }
            }
        }
        return swaps;
    }

    /**
     * Remove unreliable hops while at least {@code minKeep} remain.
     * Pure decision helper; package visible for tests.
     *
     * @param hops selected peers, modified in place
     * @param unreliable the peers to remove
     * @param minKeep minimum list size to leave
     * @return true if any hop was removed
     * @since 0.9.71+
     */
    static boolean dropUnreliable(List<Hash> hops, Set<Hash> unreliable, int minKeep) {
        if (hops.size() <= minKeep || unreliable.isEmpty()) {return false;}
        boolean dropped = false;
        for (Iterator<Hash> iter = hops.iterator(); iter.hasNext() && hops.size() > minKeep;) {
            if (unreliable.contains(iter.next())) {
                iter.remove();
                dropped = true;
            }
        }
        return dropped;
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
     * All non-self peers in active tunnels across ALL pools (client IB/OB + exploratory IB/OB).
     * Used for cross-pool diversity: when the fast tier is large enough, each fast peer
     * serves at most one pool, forcing the selector to use unused peers and accumulate
     * tunnel history for proper retention/demotion decisions.
     *
     * @param ctx the router context
     * @return set of peer hashes in any active tunnel
     * @since 0.9.70
     */
    protected static Set<Hash> getPeersInAllPools(RouterContext ctx) {
        Set<Hash> rv = new HashSet<>();
        TunnelManagerFacade tmf = ctx.tunnelManager();
        List<TunnelPool> pools = new ArrayList<>(4);
        tmf.listPools(pools);
        for (TunnelPool pool : pools) {
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
        }
        return rv;
    }

    /**
     * Add cooldown entries still inside their window (value &gt; cutoff) to the
     * exclusion set without mutating the map; returns the count added.
     * Shared by both selectors; the maps themselves stay separate
     * ({@link #_peerCooldowns} for client pools, the exploratory map for
     * exploratory selections).
     *
     * @param cooldowns the cooldown map to scan (never mutated)
     * @param cutoff entries with value &lt;= cutoff are expired and skipped
     * @param exclude the exclusion set to add fresh entries to
     * @return the number of entries added
     * @since 0.9.71+ (moved from ClientPeerSelector)
     */
    protected static int addFreshCooldownExclusions(Map<Hash, Long> cooldowns, long cutoff, Set<Hash> exclude) {
        int count = 0;
        for (Map.Entry<Hash, Long> entry : cooldowns.entrySet()) {
            if (entry.getValue() > cutoff) {
                exclude.add(entry.getKey());
                count++;
            }
        }
        return count;
    }

    /**
     * True if the settings describe a zero-hop pair: length &lt;= 0, length
     * override == 0, or length + variance &lt;= 0.  Used to decide whether the
     * paired (inbound) pool needs a connected furthest hop (OBEP) so the
     * build reply can be delivered.
     *
     * @param settings the paired pool's settings
     * @return true if the pair is effectively zero-hop
     * @since 0.9.71+ (extracted from ClientPeerSelector/ExploratoryPeerSelector)
     */
    protected static boolean isZeroHopSettings(TunnelPoolSettings settings) {
        int len = settings.getLength();
        return len <= 0 || settings.getLengthOverride() == 0 || len + settings.getLengthVariance() <= 0;
    }

    /**
     * True if the pool has at least one active tunnel longer than one hop.
     *
     * @param pool the tunnel pool to scan (non-null)
     * @return true if any tunnel has length &gt; 1
     * @since 0.9.71+ (extracted from ClientPeerSelector/ExploratoryPeerSelector)
     */
    protected static boolean hasTunnelLongerThanOne(TunnelPool pool) {
        for (TunnelInfo ti : pool.listTunnels()) {
            if (ti.getLength() > 1) {return true;}
        }
        return false;
    }

    /**
     * Check if a peer has recovered from failure and can be reconsidered.
     * Uses the effective first-hop cooldown (shortened during ghost cascades).
     *
     * @param ctx the router context
     * @param peer the candidate to test; its first-hop reachability is probed
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
     * to allow tunnels to build. Public so the Tuner can share the same
     * definition when deciding whether to widen the activity window.
     *
     * @param ctx the router context
     * @return true if uptime is between 1ms and STARTUP_WARNING_SUPPRESS_MS
     * @since 0.9.70+
     */
    public static boolean isInStartupGracePeriod(RouterContext ctx) {
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
     * they should appear in a tunnel (ENDPOINT FIRST).  This includes
     * the local router in the list.  Never null; an empty list means
     * no peers could be selected.
     */
    public abstract List<Hash> selectPeers(TunnelPoolSettings settings);

    /**
     * Select peers for a new tunnel, with an optional set of first-hop peers
     * to exclude from selection.  The default implementation ignores the
     * exclusion set and delegates to {@link #selectPeers(TunnelPoolSettings)};
     * subclasses that support first-hop diversity (e.g. ClientPeerSelector)
     * override this to exclude those peers.
     *
     * @param settings the tunnel pool settings
     * @param excludeFirstHops first-hop peers already targeted by concurrent
     * builds in the same dispatch batch, may be null
     * @return ordered list of Hash objects (ENDPOINT FIRST), never null
     * @since 0.9.71+
     */
    public List<Hash> selectPeers(TunnelPoolSettings settings, Set<Hash> excludeFirstHops) {
        return selectPeers(settings);
    }

    /**
     * Determine the tunnel length (number of hops).
     *
     * @param settings the tunnel pool settings
     * @return randomized number of hops 0-7, not including ourselves
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
     * For debugging, also possibly for restricted routes?
     * Needs analysis and testing
     *
     * @param settings the tunnel pool settings
     * @return usually false
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
     * As of 0.9.58, this returns a set populated only by TunnelManager.selectPeersInTooManyTunnels(),
     * for passing to ProfileOrganizer.
     * The set will be populated via the contains() calls.
     *
     * @param isInbound true for inbound tunnels
     * @param isExploratory true for exploratory tunnels
     * @return set of excluded peers
     */
    protected Set<Hash> getExclude(boolean isInbound, boolean isExploratory) {
        return new Excluder(isInbound, isExploratory);
    }

    /**
     * Current tunnel build success ratio, 1.0 when no data is available.
     * <p>
     * Expensive: each call performs 6 RateStat lookups and 6 rate fetches
     * ({@link ProfileOrganizer#getTunnelBuildSuccess()}).  Fetch once per
     * selection or scan and pass the value down; never call per candidate
     * peer.  A value of 1.0 (no data) keeps the attack-threshold gates
     * closed at boot instead of treating missing stats as an attack.
     *
     * @param ctx the router context
     * @return the ratio in [0.0, 1.0]
     */
    static double getBuildSuccess(RouterContext ctx) {
        return ctx.profileOrganizer().getTunnelBuildSuccess();
    }

    /**
     * Check if a peer should be excluded from closest hop selection.
     * This performs connectivity checks and version capability validation.
     * Used by Excluder to classify exclusion reasons for diagnostics.
     *
     * @param peerHash the peer hash to check
     * @param isInbound true if this is for an inbound tunnel
     * @param isExploratory true if this is for exploratory tunnels
     * @param buildSuccess the build success ratio, fetched once per selection
     * @return the exclusion reason, or null if peer should not be excluded
     * @since 0.9.58
     */
    private String getExclusionReason(Hash peerHash, boolean isInbound, boolean isExploratory, double buildSuccess) {
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

        // Presence-only check: the validating lookup may fire network lookups
        // per candidate during hot-path selection, and the build requestor
        // accepts unvalidated cached entries, so selection matches that.
        RouterInfo routerInfo = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(peerHash);
        if (routerInfo == null) {
            return "no-routerinfo";
        }

        // Client tunnel builds: never exclude based on floodfill lookup
        // reliability — a peer bad at NetDB lookups can still be a fine
        // tunnel hop.  Only apply to exploratory pools (used for lookups).
        if (isExploratory && shouldExcludeFloodfillPeer(routerInfo)) {
            return "floodfill";
        }

        if (filterUnreachable(isInbound, isExploratory)) {
            if (routerInfo.getCapabilities().contains(Character.toString(Router.CAPABILITY_UNREACHABLE))) {
                if (!allowFirewalledUnderAttack(routerInfo.getCapabilities(), buildSuccess)) {
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
            String excludeCaps = getEffectiveExcludeCaps(ctx, buildSuccess);
            if (shouldExclude(ctx, routerInfo, excludeCaps, isExploratory, buildSuccess)) {
                return "slow/capped";
            }
        }

        // Pre-qualification: reject peers with zero connectivity signal.
        // Peers that have never been tested, heard from, or connected to
        // will waste tunnel builds and test cycles.  This is always active
        // for client pools (skipped for exploratory, during startup, and
        // while under stress — see shouldPreQual).  Use a 30-minute activity
        // window for heard-from/sent-to checks (wider than the old 10-minute
        // window) to avoid starving peer selection when the network is
        // sparse or under load.
        if (shouldPreQual(isExploratory, isInStartupGracePeriod(ctx), buildSuccess)) {
            boolean established = ctx.commSystem().isEstablished(peerHash);
            // Connected peers always pass; others need profile evidence of
            // connectivity within the activity window (see hasConnectivitySignal).
            if (!established &&
                (profile == null || !hasConnectivitySignal(profile, ctx.clock().now(),
                                                           getActivityWindow(ctx)))) {
                return "no-signal";
            }
        }

        return null;
    }

    /**
     * Hard exclusion reasons only — skips the no-signal (pre-qualification)
     * check.  Used when peer scarcity demands that untested peers be allowed
     * as tunnel candidates.  Only bans, unreachable, no-RouterInfo,
     * congestion, and slow/capped peers remain excluded.
     *
     * @since 0.9.71+
     */
    private String getHardExclusionReason(Hash peerHash, boolean isInbound,
                                           boolean isExploratory, double buildSuccess) {
        if (ctx.banlist().isBanlisted(peerHash)) {
            return "banned";
        }

        PeerProfile profile = ctx.profileOrganizer().getProfileNonblocking(peerHash);
        if (profile != null && wasRecentlyRejected(profile, 20_000L)) {
            return "recently-rejected";
        }

        if (ctx.commSystem().wasUnreachable(peerHash)) {
            return "unreachable";
        }

        RouterInfo routerInfo = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(peerHash);
        if (routerInfo == null) {
            return "no-routerinfo";
        }

        if (isExploratory && shouldExcludeFloodfillPeer(routerInfo)) {
            return "floodfill";
        }

        if (filterUnreachable(isInbound, isExploratory)) {
            if (routerInfo.getCapabilities().contains(Character.toString(Router.CAPABILITY_UNREACHABLE))) {
                if (!allowFirewalledUnderAttack(routerInfo.getCapabilities(), buildSuccess)) {
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
            String excludeCaps = getEffectiveExcludeCaps(ctx, buildSuccess);
            if (shouldExclude(ctx, routerInfo, excludeCaps, isExploratory, buildSuccess)) {
                return "slow/capped";
            }
        }

        return null;
    }

    /**
     * Whether strict client-pool pre-qualification should be applied to a
     * candidate peer.  Never for exploratory selections, never during the
     * startup grace period, and never while the network is under stress
     * (build success below {@link #ATTACK_THRESHOLD}) — a pool collapse is
     * made worse by rejecting candidates that merely lack recent activity,
     * and the exploratory pools already select without pre-qual.  A NaN
     * success ratio is treated as "missing data is not an attack", so
     * pre-qualification stays active.  Pure decision — no context access,
     * safe for unit tests.
     *
     * @param isExploratory true for exploratory selections
     * @param inStartupGrace true while the router is in its startup grace period
     * @param buildSuccess the tunnel build success ratio in [0.0, 1.0]
     * @return true if strict pre-qualification should run
     * @since 0.9.71+
     */
    static boolean shouldPreQual(boolean isExploratory, boolean inStartupGrace, double buildSuccess) {
        return !isExploratory && !inStartupGrace &&
               (Double.isNaN(buildSuccess) || buildSuccess >= ATTACK_THRESHOLD);
    }

    /**
     * Pre-qualification signal check: does the peer profile show recent
     * connectivity evidence?  Heard from or successfully sent to within the
     * last 30 minutes, a successful tunnel test within the activity window,
     * or any test history at all with a &gt;50% acceptance ratio.
     * Pure decision — no context access, safe for unit tests.
     *
     * @param profile the peer profile (non-null)
     * @param now current time in milliseconds
     * @param activityWindow the dynamic activity window in milliseconds
     * @return true if the profile shows recent connectivity signal
     * @since 0.9.71+ (extracted from getExclusionReason)
     */
    static boolean hasConnectivitySignal(PeerProfile profile, long now, long activityWindow) {
        long heardWindow = 60 * 60 * 1000L;
        if (profile.getLastHeardFrom() > 0 && now - profile.getLastHeardFrom() < heardWindow) {return true;}
        if (profile.getLastSendSuccessful() > 0 && now - profile.getLastSendSuccessful() < heardWindow) {return true;}
        long lastTested = profile.getTunnelHistory().getLastTestedSuccessfully();
        if (lastTested > 0 && now - lastTested < activityWindow) {return true;}
        return profile.getTunnelAcceptanceRatio() > 0.5 && lastTested > 0;
    }

    /**
     * Exclusion caps to apply, adapting to build success.  During low build
     * success (&lt;40%) the M, N, O, D and P exclusions are relaxed, as they
     * are during the first five minutes of uptime when the ratio is not yet
     * meaningful.
     * <p>Never null: {@link #getExcludeCaps} substitutes DEFAULT_EXCLUDE_CAPS
     * for a missing setting, and this folds away any null that survives so
     * the callers can iterate the result without a guard.
     *
     * @param ctx the router context
     * @param buildSuccess the build success ratio, fetched once per selection
     * @return non-null, possibly empty
     */
    private static String getEffectiveExcludeCaps(RouterContext ctx, double buildSuccess) {
        String configured = getExcludeCaps(ctx);
        long uptime = ctx.router() != null ? ctx.router().getUptime() : 0L;
        boolean wasRelaxed = _excludeCapsRelaxed;
        String relaxed = relaxedExcludeCaps(configured, buildSuccess, uptime, wasRelaxed);
        // Latch only what this decision actually changed. STARTUP relaxes without
        // latching, so the first post-startup reading starts from a clean threshold
        // rather than inheriting a relaxation no ratio ever justified.
        if (uptime <= 0 || uptime >= STARTUP_WARNING_SUPPRESS_MS) {
            _excludeCapsRelaxed = wasRelaxed
                    ? buildSuccess < ATTACK_RELAX_EXIT
                    : buildSuccess < ATTACK_THRESHOLD;
        }
        return relaxed != null ? relaxed : configured;
    }

    /**
     * Whether the exclude caps are currently relaxed, and by how much build success
     * has to recover before they are enforced again.
     *
     * <p>Wider than {@link #ATTACK_THRESHOLD} on purpose: relaxing the caps widens
     * the peer pool, which raises build success, which would otherwise release the
     * relaxation immediately and put the caps back — a loop that ratchets the peer
     * pool between wide and narrow several times a minute. The gap is what lets the
     * widening actually take hold.
     *
     * @since 0.9.71+
     */
    static final double ATTACK_RELAX_EXIT = 0.44;

    /**
     * Latch for the exclude-cap relaxation, shared by every selection.
     *
     * <p>Process-wide on purpose: the caps are a property of the router's current
     * state, not of one selection, so two concurrent selections must not disagree
     * about whether the pool is relaxed.
     */
    private static volatile boolean _excludeCapsRelaxed;

    /**
     * Strip the M, N, O, D, P capability exclusions from the configured caps
     * when the build success ratio is below {@link #ATTACK_THRESHOLD} or the
     * router is within its first {@link #STARTUP_WARNING_SUPPRESS_MS} of
     * uptime (when the ratio is not yet meaningful).  A ratio of 0.0 (no
     * data) relaxes as well, matching the conservative startup behavior.
     * Pure decision — no context access, safe for unit tests.
     *
     * @param configured the configured exclude caps, possibly null or empty
     * @param buildSuccess the build success ratio in [0.0, 1.0]
     * @param uptimeMs router uptime in milliseconds
     * @return the configured caps with M/N/O/D/P removed when relaxing, otherwise unchanged
     */
    static String relaxedExcludeCaps(String configured, double buildSuccess, long uptimeMs) {
        return relaxedExcludeCaps(configured, buildSuccess, uptimeMs, false);
    }

    /**
     * Strip the M, N, O, D, P capability exclusions from the configured caps
     * when the build success ratio is below {@link #ATTACK_THRESHOLD} or the
     * router is within its first {@link #STARTUP_WARNING_SUPPRESS_MS} of
     * uptime (when the ratio is not yet meaningful).  A ratio of 0.0 (no
     * data) relaxes as well, matching the conservative startup behavior.
     *
     * <p><b>P4:</b> {@code alreadyRelaxed} is the latch, and this is where the
     * hysteresis actually lives.  The previous form derived the decision from
     * {@code buildSuccess} alone, which is a plain threshold wearing
     * hysteresis's clothes — the {@code buildSuccess >= 0.44} arm could only
     * assign {@code false} to a variable already {@code false}, so it was dead
     * code and the {@code [0.40, 0.44)} band was not a dead zone but the
     * strict side of a 0.40 threshold.  Build success oscillating across 0.40
     * therefore flipped the caps on every crossing.  Passing the prior state
     * makes the band real: enter below {@link #ATTACK_THRESHOLD}, leave at or
     * above {@link #ATTACK_RELAX_EXIT}, hold in between.
     *
     * Pure decision — no context access and no shared state, safe for unit tests.
     *
     * @param configured the configured exclude caps, possibly null or empty
     * @param buildSuccess the build success ratio in [0.0, 1.0]
     * @param uptimeMs router uptime in milliseconds
     * @param alreadyRelaxed whether the caps were already relaxed, i.e. the latch state
     * from the previous decision
     * @return the configured caps with M/N/O/D/P removed when relaxing, otherwise unchanged
     * @since 0.9.71+ four-argument form carries the hysteresis latch
     */
    static String relaxedExcludeCaps(String configured, double buildSuccess, long uptimeMs,
                                     boolean alreadyRelaxed) {
        if (configured == null || configured.isEmpty()) {
            return configured;
        }

        // STARTUP always relaxes: build success is not yet meaningful.
        if (uptimeMs > 0 && uptimeMs < STARTUP_WARNING_SUPPRESS_MS) {
            return stripCaps(configured);
        }
        // Hysteresis: a latched relaxation is only released once the ratio has
        // recovered past the wider exit threshold.
        // The latch band is therefore (ATTACK_THRESHOLD, ATTACK_RELAX_EXIT): entry is
        // strict below the lower threshold, exit is at the upper one.
        boolean relax = alreadyRelaxed
                      ? buildSuccess < ATTACK_RELAX_EXIT
                      : buildSuccess < ATTACK_THRESHOLD;
        if (!relax) {
            return configured;
        }
        return stripCaps(configured);
    }

    /** Remove the M/N/O/D/P capability exclusions, leaving every other cap intact. */
    private static String stripCaps(String configured) {
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
     * Should we allow a firewalled (U-cap) peer?
     * During attacks (build success below {@link #ATTACK_THRESHOLD}), allow
     * U-cap peers that also publish M, N, O, P, or X capability.  Peers
     * without the U cap are always allowed.  Pure decision — no context
     * access, safe for unit tests.
     *
     * @param capabilities the peer's capability string, possibly null
     * @param buildSuccess the build success ratio, fetched once per selection
     * @return true if the peer may be used despite the U cap
     */
    static boolean allowFirewalledUnderAttack(String capabilities, double buildSuccess) {
        if (capabilities == null || !capabilities.contains(Character.toString(Router.CAPABILITY_UNREACHABLE))) {
            return true;
        }
        if (capabilities.contains("M") || capabilities.contains("N") || capabilities.contains("O") ||
            capabilities.contains("P") || capabilities.contains("X")) {
            return buildSuccess < ATTACK_THRESHOLD;
        }
        return false;
    }

    private boolean wasRecentlyRejected(PeerProfile profile, long cutoffMillis) {
        long cutoff = ctx.clock().now() - cutoffMillis;
        return profile.getTunnelHistory().getLastRejectedBandwidth() > cutoff;
    }

    private boolean shouldExcludeFloodfillPeer(RouterInfo routerInfo) {
        String capabilities = routerInfo.getCapabilities();
        boolean isFloodfill = capabilities.contains(Character.toString(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL));
        if (!isFloodfill) {return false;}
        Hash peerHash = routerInfo.getIdentity().getHash();
        PeerProfile profile = ctx.profileOrganizer().getProfileNonblocking(peerHash);
        FloodfillReliability reliability = profile != null ? profile.getFloodfillReliability() : FloodfillReliability.UNKNOWN;
        // Only exclude BAD floodfills — proven unreliable for lookups.
        // UNKNOWN peers are allowed through so they accumulate lookup
        // evidence and get classified; randomly blocking them delays
        // their profile indefinitely.
        return reliability == FloodfillReliability.BAD;
    }

    /**
     * Are we IPv6 only?
     *
     * @return true if configured for IPv6 only
     * @since 0.9.34
     */
    protected boolean isIPv6Only() {
        // The setting is the same for both SSU and NTCP, so just take the SSU one
        return TransportUtil.getIPv6Config(ctx, "SSU") == TransportUtil.IPv6Config.IPV6_ONLY;
    }

    /**
     * Should we allow as OBEP?
     * This just checks for IPv4 support.
     * Will return false for IPv6-only.
     * This is intended for tunnel candidates, where we already have
     * the RI. Will not force RI lookups.
     * Default true.
     *
     * @param h the peer hash
     * @return true if the peer can be used as OBEP
     * @since 0.9.34, protected since 0.9.58 for ClientPeerSelector
     */
    protected boolean allowAsOBEP(Hash h) {
        // Banlist is always live — never serve a stale "allowed" for a peer
        // banned after the cache entry was written.
        if (ctx.banlist().isBanlisted(h))
            return false;
        long now = ctx.clock().now();
        EndpointCacheEntry cached = _obepCache.get(h);
        if (cached != null && !cached.isExpired(now)) {
            return cached.allowed;
        }
        RouterInfo ri = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(h);
        boolean result = ri == null || canConnect(ri, ANY_V4);
        pruneEndpointCache(_obepCache, now);
        _obepCache.put(h, new EndpointCacheEntry(result, now + ENDPOINT_CACHE_TTL_MS));
        return result;
    }

    /**
     * Should we allow as IBGW?
     * This just checks for the "R" capability and IPv4 support.
     * Will return false for hidden or IPv6-only.
     * This is intended for tunnel candidates, where we already have
     * the RI. Will not force RI lookups.
     * Default true.
     *
     * @param h the peer hash
     * @return true if the peer can be used as IBGW
     * @since 0.9.34, protected since 0.9.58 for ClientPeerSelector
     */
    protected boolean allowAsIBGW(Hash h) {
        // Banlist is always live — never serve a stale "allowed" for a peer
        // banned after the cache entry was written.
        if (ctx.banlist().isBanlisted(h))
            return false;
        long now = ctx.clock().now();
        EndpointCacheEntry cached = _ibgwCache.get(h);
        if (cached != null && !cached.isExpired(now)) {
            return cached.allowed;
        }
        RouterInfo ri = (RouterInfo) ctx.netDb().lookupLocallyWithoutValidation(h);
        boolean result;
        if (ri == null) {
            result = true;
        } else if (ri.getCapabilities().indexOf(Router.CAPABILITY_REACHABLE) < 0) {
            result = false;
        } else {
            result = canConnect(ANY_V4, ri);
        }
        pruneEndpointCache(_ibgwCache, now);
        _ibgwCache.put(h, new EndpointCacheEntry(result, now + ENDPOINT_CACHE_TTL_MS));
        return result;
    }

    /**
     * Pick peers that we want to avoid for the first OB hop or last IB hop.
     * There's several cases of importance:
     * <ol><li>Inbound and we are hidden -
     * Exclude all unless connected.
     * This is taken care of in ClientPeerSelector and TunnelPeerSelector selectPeers(), not here.
     *
     * <li>We are IPv6-only.
     * Exclude all v4-only peers, unless connected
     * This is taken care of here.
     *
     * <li>We have NTCP or SSU disabled.
     * Exclude all incompatible peers, unless connected
     * This is taken care of here.
     *
     * <li>Minimum version check, if we are some brand-new sig type,
     * or are using some new tunnel build method.
     * Not currently used, but this is where to implement the checks if needed.
     * Make sure that ClientPeerSelector and TunnelPeerSelector selectPeers() call this when needed.
     * </ol>
     *
     * As of 0.9.58, this a set with only toAdd, for use in ProfileOrganizer.
     * The set will be populated via the contains() calls.
     *
     * @param isInbound true for inbound tunnels
     * @param toAdd set of peers to initially populate the exclusion set
     * @return non-null
     * @since 0.9.17
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
        return shouldExclude(ctx, peer, getExcludeCaps(ctx), false, getBuildSuccess(ctx));
    }

    /**
     * Should the peer be excluded based on its published caps, crypto, and version?
     * <p>
     * Variant for per-peer selection loops that already fetched the build
     * success ratio once; avoids re-reading router statistics per peer.
     *
     * @param ctx Router context for peer count checks
     * @param peer The peer to evaluate
     * @param buildSuccess the build success ratio in [0.0, 1.0]
     * @return true if the peer should be excluded
     * @since 0.9.17
     */
    public static boolean shouldExclude(RouterContext ctx, RouterInfo peer, double buildSuccess) {
        return shouldExclude(ctx, peer, getExcludeCaps(ctx), false, buildSuccess);
    }

    /**
     * Exclude caps to apply during peer selection.
     * @return non-null, possibly empty
     */
    private static String getExcludeCaps(RouterContext ctx) {
        String val = getCachedExcludeCaps(ctx);
        return val != null ? val : DEFAULT_EXCLUDE_CAPS;
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
     * @param buildSuccess the build success ratio, fetched once per selection
     * @return true if the peer should be excluded
     */
    private static boolean shouldExclude(RouterContext ctx, RouterInfo peer, String excl, boolean isExploratory,
                                         double buildSuccess) {
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
            // During attacks, allow E cap with 1/6 chance
            if (cap.contains("E") && buildSuccess < ATTACK_THRESHOLD) {
                return ctx.random().nextInt(6) != 0;  // 5/6 chance: Exclude, 1/6: Allow
            }
            return true;
        }

        // Count meaningful capabilities
        int knownCaps = countKnownCaps(cap);

        // Relax single-capability restriction when peer count is low
        int fastPeerCount = ctx.profileOrganizer().countFastPeers();
        if (knownCaps < 2 && cap.length() <= knownCaps && fastPeerCount >= 20) {
            return true;
        }

        // Exclude outdated versions
        if (isOutdatedVersion(peer.getVersion())) {
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

    /**
     * Count the meaningful capabilities a peer publishes, used to reject
     * peers with no useful caps when there are plenty of fast peers.
     * Pure — safe for unit tests.
     *
     * @param cap the peer's capability string
     * @return the count of known caps (0-2+)
     * @since 0.9.71+ (extracted from shouldExclude)
     */
    static int countKnownCaps(String cap) {
        int knownCaps = 0;
        if (cap.contains("F")) knownCaps++;
        if (cap.contains("R")) knownCaps++;
        if (cap.contains("L") || cap.contains("M") || cap.contains("N") || cap.contains("O") ||
            cap.contains("P") || cap.contains("Q") || cap.contains("X")) knownCaps++;
        return knownCaps;
    }

    /**
     * True if the peer runs a version too old to interoperate: not the
     * published version and older than {@link #MIN_VERSION}.
     * Pure — safe for unit tests.
     *
     * @param version the peer's version string
     * @return true if the version is outdated
     * @since 0.9.71+ (extracted from shouldExclude)
     */
    static boolean isOutdatedVersion(String version) {
        return !version.equals(CoreVersion.PUBLISHED_VERSION) &&
               VersionComparator.comp(version, MIN_VERSION) < 0;
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
            if (matchesExistingTunnel(existing, newPeers, settings.isInbound())) {
                if (log.shouldDebug()) {
                    log.debug("Detected duplicate tunnel sequence for " + settings.getDestinationNickname());
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Compare a newly selected peer sequence (excluding self) against one
     * existing tunnel.  Lengths must match (existing includes self, so one
     * longer than newPeers) and every peer must be equal in order, starting
     * from the gateway side for inbound (index 0) or after self for outbound
     * (index 1).
     * Pure decision — no context access, safe for unit tests.
     *
     * @param existing the existing tunnel to compare against
     * @param newPeers the newly selected peers (excluding self)
     * @param isInbound true for inbound tunnels
     * @return true if the sequences match
     * @since 0.9.71+ (extracted from isDuplicateSequence)
     */
    static boolean matchesExistingTunnel(TunnelInfo existing, List<Hash> newPeers, boolean isInbound) {
        if (existing.getLength() != newPeers.size() + 1) {return false;}
        int offset = isInbound ? 0 : 1;
        for (int i = 0; i < newPeers.size(); i++) {
            Hash existingPeer = existing.getPeer(i + offset);
            if (existingPeer == null || !existingPeer.equals(newPeers.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Regenerate tunnel peers to avoid duplicate sequence.
     * <p>
     * The canonical order is the key-distance sort from
     * {@link #orderPeers}.  Because that sort is deterministic, regenerating
     * always reproduced the identical sequence and could not avoid
     * duplicates.  Each regeneration attempt now rotates the canonical order
     * by a step derived from the pool key, so successive attempts yield
     * different sequences while staying key-derived (attacker-unpredictable).
     *
     * @param settings the tunnel pool settings
     * @param peers the peers to regenerate (not modified)
     * @param attempt 1-based regeneration attempt number
     * @return regenerated peer list (same or different)
     * @since 0.9.68+
     */
    protected List<Hash> regeneratePeers(TunnelPoolSettings settings, List<Hash> peers, int attempt) {
        if (peers == null || peers.isEmpty()) {return peers;}

        SessionKey randomKey = settings.getRandomKey();
        if (randomKey == null || peers.size() < 2) {return peers;}

        List<Hash> ordered = new ArrayList<>(peers);
        orderPeers(ordered, randomKey);
        if (attempt <= 0) {return ordered;}

        // Rotate by step * attempt mod size; nonzero for attempt 1 and
        // distinct across the bounded attempts, so each retry differs.
        // floorMod keeps the step positive for negative key bytes.
        byte[] rk = randomKey.getData();
        long step = Math.floorMod(DataHelper.fromLong8(rk, 0), ordered.size() - 1) + 1;
        Collections.rotate(ordered, (int) (Math.floorMod(step * attempt, ordered.size())));
        return ordered;
    }

    private static final Comparator<Hash> HASH_BASE64_COMPARATOR = Comparator.comparing(h -> h.toBase64());

    /**
     * Implement a deterministic comparison that cannot be predicted by
     * others. A naive implementation (using the distance from a random key)
     * allows an attacker who runs two routers with hashes far apart
     * to maximize his chances of those two routers being at opposite
     * ends of a tunnel.
     *
     * Previous Previous:
     * d(l, h) - d(r, h)
     *
     * Previous:
     * d((H(l+h), h) - d(H(r+h), h)
     *
     * Now:
     * SipHash using h to generate the SipHash keys
     * then siphash(l) - siphash(r)
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
     * Connectivity check.
     * Check that each hop can connect to the next, including us.
     * Check that the OBEP is not IPv6-only, and the IBGW is
     * reachable and not hidden or IPv6-only.
     * Tells the profile manager to blame the hop, and returns false on failure.
     *
     * @param isInbound true for inbound tunnels
     * @param isExploratory true for exploratory tunnels
     * @param tunnel ENDPOINT FIRST, GATEWAY LAST!!!!, length 2 or greater
     * @return ok
     * @since 0.9.34
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
     * Connectivity check.
     * Check that each hop can connect to the next, including us.
     *
     * @param tunnel ENDPOINT FIRST, GATEWAY LAST!!!!
     * @return ok
     * @since 0.9.34
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
                // Blame only the adjacent hop (hf = source) — matching
                // BuildExecutor.penalizeTimeout() which blames only the
                // contacted peer.  Blaming ht (destination) double-penalises
                // innocent middle hops and causes tier eviction.
                Hash us = ctx.routerHash();
                if (!hf.equals(us))
                    ctx.profileManager().tunnelTimedOut(hf);
                if (!ht.equals(us) && log.shouldDebug()) {
                    log.debug("checkTunnel failed between [" + hf.toBase64().substring(0,6) +
                              "] and [" + ht.toBase64().substring(0,6) + "] — ht not blamed");
                }
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
        REASON_LABELS.put("no-routerinfo", "No RouterInfo");
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
        /**
         * Cap on classified exclusions.  Bulk-loaded diversity entries carry no
         * reason and are therefore never evicted; only reasoned entries are
         * bounded, oldest first.
         */
        private static final int MAX_EXCLUDED_PEERS = 384;

        private final boolean _isIn;
        private final boolean _isExpl;
        /** Build success ratio, fetched once per Excluder construction. */
        private final double _buildSuccess;

        /**
         * Automatically adds selectPeersInTooManyTunnels(), unless i2np.allowLocal.
         * Fetches the build success ratio once, so the per-peer exclusion
         * checks in {@link #contains(Object)} never re-read router statistics.
         */
        public Excluder(boolean isInbound, boolean isExploratory) {
            this(isInbound, isExploratory, getBuildSuccess(ctx));
        }

        /**
         * Automatically adds selectPeersInTooManyTunnels(), unless i2np.allowLocal.
         * Uses a build success ratio already fetched by the caller.
         */
        public Excluder(boolean isInbound, boolean isExploratory, double buildSuccess) {
            super(ctx.getBooleanProperty("i2np.allowLocal") ? new LinkedHashSet<>()
                                                              : new LinkedHashSet<>(ctx.tunnelManager().selectPeersInTooManyTunnels()));
            _isIn = isInbound;
            _isExpl = isExploratory;
            _buildSuccess = buildSuccess;
            for (Hash h : s) {recordExclusion(h, "too-many-tunnels");}
        }

        /**
         * Does not add selectPeersInTooManyTunnels().
         * Makes a copy of toAdd.
         * Fetches the build success ratio once, so the per-peer exclusion
         * checks in {@link #contains(Object)} never re-read router statistics.
         *
         * @param toAdd initial contents, copied
         */
        public Excluder(boolean isInbound, boolean isExploratory, Set<Hash> toAdd) {
            this(isInbound, isExploratory, toAdd, getBuildSuccess(ctx));
        }

        /**
         * Does not add selectPeersInTooManyTunnels().
         * Makes a copy of toAdd.  Uses a build success ratio already fetched
         * by the caller.
         *
         * @param toAdd initial contents, copied
         */
        public Excluder(boolean isInbound, boolean isExploratory, Set<Hash> toAdd, double buildSuccess) {
            super(new LinkedHashSet<>(toAdd));
            _isIn = isInbound;
            _isExpl = isExploratory;
            _buildSuccess = buildSuccess;
        }

        /**
         * When true, the no-signal check in {@link #getExclusionReason} is
         * skipped, allowing untested peers to be considered for tunnel builds.
         * Set by {@link #relaxNoSignalExclusions()} when peer scarcity demands it.
         */
        private volatile boolean _allowNoSignal;

    @Override
    public boolean contains(Object o) {
            if (s.contains(o)) {return true;}
            if (_allowNoSignal) {
                // Under severe scarcity, only exclude for hard failures
                // (banned, unreachable, no-routerinfo, congestion).  Peers
                // merely lacking connectivity signal are untested, not bad.
                Hash h = (Hash) o;
                String reason = getHardExclusionReason(h, _isIn, _isExpl, _buildSuccess);
                if (reason != null) {
                    s.add(h);
                    recordExclusion(h, reason);
                    return true;
                }
                return false;
            }
            Hash h = (Hash) o;
            String reason = getExclusionReason(h, _isIn, _isExpl, _buildSuccess);
            if (reason != null) {
                s.add(h);
                recordExclusion(h, reason);
                // Bound the *classified* exclusions by evicting the oldest
                // reason, never the head of the underlying set.  Bulk-loaded
                // entries (too-many-tunnels, pool and cross-pool diversity) are
                // added without a reason, so head-of-set eviction silently
                // dropped exactly those diversity guarantees — and did so first,
                // when exclusion pressure was highest.
                if (_reasons.size() > MAX_EXCLUDED_PEERS) {
                    Iterator<Map.Entry<Hash, String>> it = _reasons.entrySet().iterator();
                    if (it.hasNext()) {
                        Hash evicted = it.next().getKey();
                        it.remove();
                        s.remove(evicted);
                    }
                }
                return true;
            }
            return false;
        }

        /**
         * Relax no-signal exclusions under peer scarcity: remove all peers
         * previously excluded for "no-signal" from the underlying set, and
         * set a flag so future contains() calls skip the no-signal check.
         * This allows untested peers to be considered as tunnel candidates
         * when we can't build tunnels due to insufficient proven peers.
         *
         * @return number of peers un-excluded
         */
        int relaxNoSignalExclusions() {
            _allowNoSignal = true;
            List<Hash> toRemove = new ArrayList<>();
            for (Map.Entry<Hash, String> e : _reasons.entrySet()) {
                if ("no-signal".equals(e.getValue())) {
                    toRemove.add(e.getKey());
                }
            }
            for (Hash h : toRemove) {
                _reasons.remove(h);
                s.remove(h);
            }
            return toRemove.size();
        }

        /**
         * Format excluded peers grouped by reason, sorted by hash within each group.
         * @return multi-line string like "Too many tunnels (128): peer1 peer2..."
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
         * Automatically check if peer can connect to us (for inbound)
         * or we can connect to it (for outbound)
         * and add the Hash to the set if not.
         *
         * @param set not copied, contents will be modified by all methods
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
     * Check if a peer supports NTCP2 transport.
     * NTCP2 is preferred for direct connections (first hop / IBGW)
     * because SSU2-only peers are typically firewalled, requiring
     * introduction-based connections that are slower and less reliable.
     *
     * @param ctx the router context
     * @param peer hash of the peer to check
     * @return true if the peer has an NTCP2 address
     */
    protected static boolean supportsNTCP2(RouterContext ctx, Hash peer) {
        // Presence-only check: the validating lookup may fire network lookups
        // per candidate during hot-path selection, and the build requestor
        // accepts unvalidated cached entries, so selection matches that.
        DatabaseEntry de = ctx.netDb().lookupLocallyWithoutValidation(peer);
        if (de == null || de.getType() != DatabaseEntry.KEY_TYPE_ROUTERINFO) return false;
        RouterInfo ri = (RouterInfo) de;
        for (RouterAddress ra : ri.getAddresses()) {
            if ("NTCP2".equals(ra.getTransportStyle()))
                return true;
        }
        return false;
    }

    /**
     * Check if a peer's RouterInfo has at least one reachable SSU or NTCP address.
     * Peers without valid transport addresses always fail as first hops and trigger
     * bans in EstablishmentManager.establish() — they should be excluded from selection.
     *
     * @param ctx the router context
     * @param peer hash of the peer to check
     * @return true if the peer has a valid SSU or NTCP address
     */
    protected static boolean hasValidTransportAddress(RouterContext ctx, Hash peer) {
        // Presence-only check: the validating lookup may fire network lookups
        // per candidate during hot-path selection, and the build requestor
        // accepts unvalidated cached entries, so selection matches that.
        DatabaseEntry de = ctx.netDb().lookupLocallyWithoutValidation(peer);
        if (de == null || de.getType() != DatabaseEntry.KEY_TYPE_ROUTERINFO) return false;
        return hasValidTransportAddress((RouterInfo) de);
    }

    /**
     * Address half of {@link #hasValidTransportAddress(RouterContext, Hash)},
     * split out so a caller that already holds the RouterInfo does not have to
     * probe the netdb a second time for the same entry. Pure decision, safe
     * for unit tests.
     *
     * @param ri the RouterInfo to check (non-null)
     * @return true if it has at least one usable SSU or NTCP address
     * @since 0.9.71+
     */
    static boolean hasValidTransportAddress(RouterInfo ri) {
        return TransportUtil.hasUsableTransportAddress(ri);
    }

    /**
     * Fetch a peer RouterInfo for a message we address to it, preferring the
     * unvalidated store lookup.
     *
     * <p>The peer reached this point through selection or through the already
     * established transport, so it passed store-time validation, and the
     * build requestor accepts unvalidated cached entries. Running the full
     * validate() here (address walk, banlist, country, version and
     * slow-router checks) per peer per cycle bought nothing, and for
     * {@link #keepAlive} it ran for up to 200 already-established peers a
     * cycle purely to build an OutNetMessage.
     *
     * <p>Unvalidated first, validating second — the same ordering
     * {@link BuildRequestor} uses for every hop of a build request, so a
     * pre-connect addresses the RouterInfo a build would. The fallback runs
     * only when the unvalidated probe missed: one extra store lookup for a
     * peer we hold no entry for, and it resolves the peer when an entry
     * landed in between the two probes.
     *
     * @param ctx the router context
     * @param peer hash of the peer
     * @return the RouterInfo, or null if neither lookup resolves it
     * @since 0.9.71+
     */
    private static RouterInfo lookupRouterInfoUnvalidated(RouterContext ctx, Hash peer) {
        DatabaseEntry de = ctx.netDb().lookupLocallyWithoutValidation(peer);
        if (de != null && de.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO) {return (RouterInfo) de;}
        return ctx.netDb().lookupRouterInfoLocally(peer);
    }

    /**
     * Is the RouterAddress usable for tunnel building?  SSU requires
     * protocol v2 plus a valid IP/port or an introduction; SSU2 requires a
     * valid IP/port or an introduction; NTCP/NTCP2 require a valid IP/port.
     * Pure decision — no context access, safe for unit tests.
     *
     * @param ra the router address to check (non-null)
     * @return true if the address is usable
     * @since 0.9.71+ (extracted from hasValidTransportAddress)
     */
    static boolean isUsableRouterAddress(RouterAddress ra) {
        return TransportUtil.isUsableRouterAddress(ra);
    }

    /**
     * Check if a peer has a history of rejecting tunnel build requests.
     * Returns true when the lifetime acceptance ratio drops below 30%.
     * Defaults to false (accept) when no data is available.
     *
     * @param ctx the router context
     * @param peer hash of the peer to check
     * @return true if the acceptance ratio is below threshold
     */
    protected static boolean isLowAcceptanceRatio(RouterContext ctx, Hash peer) {
        PeerProfile profile = ctx.profileOrganizer().getProfile(peer);
        if (profile == null) return false;
        return profile.getTunnelAcceptanceRatio() < 0.3;
    }

    /**
     * Trigger an outbound connection establishment to a peer.
     * Creates a low-priority dummy OutNetMessage that causes the transport
     * layer to initiate a connection. Used to pre-warm connections for
     * first-hop peers before the build message is sent.
     *
     * <p>A failed send is treated as evidence of unreachability rather than
     * a transient transport hiccup: a pre-connect probe has no reply to wait
     * for, so the transport giving up on it means the peer could not be
     * reached at all. That is the cheapest reachability signal available,
     * and without acting on it an unconnectable peer stays in the fast tier
     * because nothing else records that it never answers. The peer is
     * dropped from the fast/high-cap tiers on the first failed probe.
     *
     * @param ctx the router context
     * @param peer hash of the peer to connect to
     */
    /**
     * What a pre-connect probe actually achieved.
     *
     * <p>Returned so a caller can tell "the peer was asked and the transport took the message"
     * from "the peer was never really asked", which the previous {@code void} signature made
     * indistinguishable. Only the latter means anything about the peer.
     *
     * @since 0.9.71+
     */
    enum PreConnectOutcome {

        /** The transport accepted the message, which forces a connection attempt. */
        SENT,

        /** No RouterInfo in the netdb, so there was nothing to address. */
        NO_ROUTERINFO,

        /** The RouterInfo is present but every address in it is unusable or expired. */
        NO_USABLE_ADDRESS,

        /** No transport accepted the message. */
        NO_TRANSPORT_AVAILABLE
    }

    /**
     * Per-cycle tally of why keepalive did not act on the peers it selected.
     *
     * <p>Added because the candidate pool was observed shrinking from roughly 395 peers per
     * cycle to roughly 203, and the pre-existing log line only reported how many
     * peers were kept alive, never how many were dropped on the way. Without a reason for each
     * drop, an eroding pool and a busy pool look identical from the outside.
     *
     * <p>Note that the fall from 395 to 203 is <em>supply</em>, not delivery: the action
     * budget sits at 200, so the cycle was servicing its full budget in both periods and
     * only the surplus above it collapsed.
     *
     * <p>{@link #describe} is pure so the summary can be asserted in a test without a router.
     *
     * @since 0.9.71+
     */
    static final class KeepAliveTally {

        /**
         * Peers the selector asked for, before any filtering.
         *
         * <p>This is the per-tier request doubled for the two tiers, not a count of
         * anything on the wire.
         */
        int budget;

/**
 * Distinct peers the two tier selectors returned.
 *
 * <p>Named {@code candidates} rather than {@code delivered} because it is fixed
 * before the cycle sends anything: it measures supply, not delivery. A previous
 * field name of {@code delivered} sat on this same value and read as
 * "203 delivered of 400 requested", which was mistaken twice for a fall in
 * successful keepalives when it only ever showed the candidate pool.
 *
 * <p>May exceed {@link #budget}, which is the sum of the two nominal tier
 * requests rather than a cap. {@code selectFastPeers} backfills from the
 * high-capacity tier when the fast tier cannot fill its depth, and each
 * selector adds up to its own depth to whatever the set already holds.
 */
        int candidates;

        /** Acted on: sent a keepalive DLM. */
        int keepalived;

        /** Acted on: started an establishment. */
        int preConnected;

        /** Skipped: recorded as a recent first-hop failure and still in cooldown. */
        int skipCooldown;

        /** Skipped: no contact within the activity window. */
        int skipStale;

        /** Skipped: already serviced inside this keepalive interval. */
        int skipRecent;

        /** Skipped: no RouterInfo to address. */
        int skipNoRouterInfo;

        /** Skipped: RouterInfo present but no usable transport address. */
        int skipNoAddress;

        /** Skipped: no transport available to accept the probe. */
        int skipNoTransport;

        /** Never reached: the per-cycle action budget was already spent. */
        int unprocessed;

        /**
         * Peers the cycle actually acted on: a keepalive DLM sent, or an
         * establishment started. This is the figure that means delivery.
         *
         * @return keepalived plus preConnected
         */
        int actedOn() {
            return keepalived + preConnected;
        }

        /**
         * Summarise the cycle.
         *
         * <p>Reports supply ({@code candidates} of {@code budget}) separately from
         * delivery ({@code actedOn}), because conflating them hides which of the two
         * actually fell.
         *
         * @param aggressive whether this was an aggressive cycle
         * @return a single line naming candidates, acted-on peers and every skip reason
         */
        String describe(boolean aggressive) {
            StringBuilder buf = new StringBuilder(192);
            buf.append("KeepAlive: ").append(keepalived).append(" keepalives, ")
               .append(preConnected).append(" pre-connects (")
               .append(aggressive ? "aggressive" : "normal").append(", ")
               .append(actedOn()).append(" acted of ").append(candidates)
               .append(" candidates from a ").append(budget).append(" budget")
               .append(") skipped: cooldown=").append(skipCooldown)
               .append(" stale=").append(skipStale)
               .append(" recent=").append(skipRecent)
               .append(" noRouterInfo=").append(skipNoRouterInfo)
               .append(" noAddress=").append(skipNoAddress)
               .append(" noTransport=").append(skipNoTransport)
               .append(" unprocessed=").append(unprocessed);
            return buf.toString();
        }

        /**
         * Peers selected but never acted on for any reason.
         *
         * <p>Every selected peer must land in exactly one bucket, so this is the check that
         * the accounting has not lost a path.
         *
         * @return the number of candidate peers with no recorded disposition
         */
        int unaccounted() {
            int skipped = skipCooldown + skipStale + skipRecent + skipNoRouterInfo
                          + skipNoAddress + skipNoTransport;
            // unprocessed counts as a disposition: the budget was spent before this peer
            // was reached, which is different from falling through every branch but is
            // still accounted for.
            return candidates - actedOn() - skipped - unprocessed;
        }
    }

    protected static PreConnectOutcome preConnectTo(RouterContext ctx, Hash peer) {
        RouterInfo ri = lookupRouterInfoUnvalidated(ctx, peer);
        if (ri == null)
            return PreConnectOutcome.NO_ROUTERINFO;
        // Skip peers with no valid transport addresses to avoid triggering
        // bans in EstablishmentManager.establish() or NTCPTransport.send().
        // Tested against the RouterInfo already fetched above rather than
        // through hasValidTransportAddress(ctx, peer), which would probe the
        // netdb a second time for the entry we are holding.
        if (!hasValidTransportAddress(ri)) {
            // Record failure so selector avoids this peer
            recordFirstHopFail(ctx, peer);
            Log log = ctx.logManager().getLog(TunnelPeerSelector.class);
            if (log.shouldInfo())
                log.info("Skipping pre-connect to " + peer.toBase64().substring(0,6) +
                         " — no valid SSU or NTCP address");
            return PreConnectOutcome.NO_USABLE_ADDRESS;
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
        // A pre-connect that cannot be delivered is a reachability failure, so
        // attach the demotion callback before the send that may fail.
        onm.setOnFailedSendJob(new PreConnectFailJob(ctx, peer));
        // Send directly to the transport instead of going through GetBidsJob,
        // which may drop messages to non-connected peers. Direct send forces
        // connection establishment — same approach as TransportManager.establishTo().
        Transport udp = ctx.commSystem().getTransports().get("SSU");
        if (udp != null) {
            try { udp.send(onm); noteFirstHopRtt(ctx, peer, udp); return PreConnectOutcome.SENT; }
            catch (Exception e) { /* ignored */ }
        }
        Transport ntcp = ctx.commSystem().getTransports().get("NTCP");
        if (ntcp != null) {
            try { ntcp.send(onm); noteFirstHopRtt(ctx, peer, ntcp); return PreConnectOutcome.SENT; }
            catch (Exception e) { /* ignored */ }
        }
        return PreConnectOutcome.NO_TRANSPORT_AVAILABLE;
    }

    /**
     * Extract the transport's measured direct-link RTT and record it on the peer.
     *
     * <p>The probe goes out over one specific transport and that transport
     * already maintains a per-peer round trip time, so the first-hop latency
     * tier selection needs is available here for free. Reading it from the
     * transport also keeps it honest: it is the cost of the one hop, not the
     * cost of a multi-hop tunnel that merely contained this peer.
     *
     * <p>A freshly established session has not measured yet and reports 0,
     * which is discarded rather than stored as a fast peer; the next pass picks
     * it up.
     *
     * @param ctx the router context
     * @param peer the probed peer
     * @param transport the transport the probe was sent over
     * @since 0.9.71+
     */
    static void noteFirstHopRtt(RouterContext ctx, Hash peer, Transport transport) {
        if (transport == null) {return;}
        int rtt = transport.getEstimatedRTT(peer);
        if (rtt <= 0) {return;}
        ctx.profileOrganizer().noteFirstHopRtt(peer, rtt, ctx.clock().now());
    }

    /**
     * Demotes a peer that could not be reached by a pre-connect probe.
     *
     * <p>Fired from {@link OutNetMessage}'s failed-send path, so it runs only
     * after the transport has exhausted its own retries for the probe. That
     * distinguishes it from a single dropped packet: by then the peer has
     * failed to answer across every transport we hold.
     *
     * <p>Uses {@link ProfileOrganizer#demoteIfUnreachableNow} rather than the
     * three-strike path because send-failure strikes already exist for
     * in-use tunnels and count a congested path as much as a dead peer. A
     * peer that cannot be connected to at all is unambiguous, and leaving it
     * in the fast tier is what starves the pools.
     *
     * @since 0.9.71+
     */
    private static final class PreConnectFailJob extends JobImpl {
        private final Hash _peer;

        PreConnectFailJob(RouterContext ctx, Hash peer) {
            super(ctx);
            _peer = peer;
        }

        @Override
        public String getName() {return "Pre-Connect Failure";}

        @Override
        public void runJob() {
            applyProbeFailure(getContext(), _peer);
            Log log = getContext().logManager().getLog(TunnelPeerSelector.class);
            if (log.shouldDebug()) {
                log.debug("Pre-connect to [" + _peer.toBase64().substring(0,6) +
                          "] failed after transport retries; demoted from fast tiers");
            }
        }
    }

    /**
     * Act on a pre-connect probe that could not be delivered.
     *
     * <p>Separated from the job so the decision is testable without a
     * transport and a job queue: a probe that failed every transport retry
     * means the peer is not reachable, so it leaves the fast tiers at once
     * rather than after the three-strike send-failure path.
     *
     * <p>Demotion is immediate because unlike a send failure on a live tunnel,
     * this has no ambiguity to accumulate against — there was no reply at all.
     * Waiting for strikes leaves exactly the peers this sweep exists to find
     * sitting in the tier where selection will pick them again.
     *
     * @param ctx the router context
     * @param peer the peer that failed to answer the probe
     * @since 0.9.71+
     */
    static void applyProbeFailure(RouterContext ctx, Hash peer) {
        ctx.statManager().addRateData("tunnel.preConnectFail", 1);
        recordFirstHopFail(ctx, peer);
        ctx.profileOrganizer().demoteIfUnreachableNow(peer);
    }

    /**
     * Check whether a peer is stale — no contact (heard from or heard about)
     * within the dynamic activity window. The window adapts to network
     * visibility: 500+ active peers use 1 hour, 200+ use 2 hours, 100+ use
     * 4 hours, fewer than 100 use 8 hours (fresh router building up picture).
     *
     * Stale peers are skipped during first-hop selection and keepalive to
     * avoid wasting resources on peers that are likely offline. Skipped
     * during the first 15 minutes of uptime (startup grace).
     *
     * @param ctx the router context
     * @param peer hash of the peer to check
     * @return true if the peer has not been heard from or about within the activity window
     */
    static boolean isStalePeer(RouterContext ctx, Hash peer) {
        return isStalePeer(ctx, peer, getBuildSuccess(ctx));
    }

    /**
     * Check whether a peer is stale — no contact (heard from or heard about)
     * within the dynamic activity window.  The window adapts to network
     * visibility: 500+ active peers use 1 hour, 200+ use 2 hours, 100+ use
     * 4 hours, fewer than 100 use 8 hours (fresh router building up picture).
     *
     * Unprofiled peers (never-heard-of) are never stale — excluding them
     * would starve the pool of newcomers during recovery — the activity
     * window below only applies once we have profile data to judge.
     * Profiled peers are always checked against the activity window
     * regardless of uptime; the startup grace period previously allowed
     * stale profiled peers to be selected, which caused tunnel builds
     * to fail on peers with no recent contact.
     *
     * @param ctx the router context
     * @param peer hash of the peer to check
     * @param buildSuccess the build success ratio, fetched once by the caller
     * @return true if the peer has a profile and has not been heard from or
     * about within the activity window
     */
    static boolean isStalePeer(RouterContext ctx, Hash peer, double buildSuccess) {
        PeerProfile profile = ctx.profileOrganizer().getProfileNonblocking(peer);
        if (profile == null)
            return false;
        long now = ctx.clock().now();
        long cutoff = now - getActivityWindow(ctx);
        return profile.getLastHeardFrom() < cutoff && profile.getLastHeardAbout() < cutoff;
    }

    /**
     * Compute the activity window for peer selection based on current network
     * visibility.  When we hear from many peers, we can be selective (short window).
     * When the router is fresh or the network is sparse, use a wider window to
     * avoid starving peer pools.
     *
     * The base window (from active-peer count) is scaled by the Tuner-controlled
     * multiplier ({@link #setWindowMultiplier}) and floored to at least 6 hours
     * when build success is in the degraded/purgatory band, so good peers whose
     * last successful test has aged out are re-admitted instead of pruned in a
     * self-reinforcing loop.
     *
     * @param ctx the router context
     * @param buildSuccess the build success ratio, fetched once by the caller
     * @return activity window in milliseconds
     * @since 0.9.70+
     */
    public static long getActivityWindow(RouterContext ctx, double buildSuccess) {
        return getActivityWindow(ctx);
    }

    /**
     * Compute the activity window for peer selection from the base ladder only,
     * scaled by whatever multiplier the availability gate has granted.
     *
     * <p>The {@code buildSuccess} argument is ignored. It used to drive an acute
     * floor that pinned the window at 4h whenever build success was in the
     * degraded band, but build success measures <em>outcomes</em>, not peer
     * supply: it is depressed by unresponsive peers, build timeouts and
     * unreachable destinations just as readily as by an over-tight recency
     * window. Keying the window to it therefore closed a positive feedback loop
     * — worse builds widened the window, admitting older peers, which made
     * builds worse still — and did so while ample fast and high-capacity peers
     * were sitting unused. Peer supply is the only thing the window should
     * respond to, and that decision now lives solely in
     * {@link #windowMultiplierFor}, so the Tuner and the pool starvation bypass
     * cannot disagree with this ladder.
     *
     * @param ctx the router context
     * @return activity window in milliseconds
     * @since 0.9.71+
     */
    public static long getActivityWindow(RouterContext ctx) {
        int active = ctx.commSystem().countActivePeers();
        long base;
        if (active >= 500) {base = 1 * 60 * 60 * 1000L;}        // 1 hour
        else if (active >= 200) {base = 2 * 60 * 60 * 1000L;}   // 2 hours
        else if (active >= 100) {base = 4 * 60 * 60 * 1000L;}   // 4 hours
        else {base = 8 * 60 * 60 * 1000L;}                      // 8 hours

        return Math.min(base * _windowMultiplier, MAX_WINDOW_MS);
    }

    /**
     * Number of fast peers at or above which the peer set is considered well
     * supplied and the activity window is never widened on availability grounds.
     *
     * <p>Deliberately far below the ~1000 a healthy router typically holds: the
     * question is not "is the network healthy" but "are we short of the peers
     * selection would otherwise use". Only then is trading recency for pool
     * thickness the right trade.
     *
     * @since 0.9.71+
     */
    public static final int SCARCE_FAST_PEERS = 200;

    /** Upper bound on the activity window, applied after the multiplier. @since 0.9.71+ */
    public static final long MAX_WINDOW_MS = 12 * 60 * 60 * 1000L;

    /**
     * Multiplier the peer-supply signal earns, independent of any build-outcome
     * metric. This is the single gate on widening the activity window, shared by
     * the Tuner param, the pool starvation bypass and
     * {@link #getActivityWindow(RouterContext)} so the three cannot drift apart.
     *
     * <p>Rules, in order:
     * <ol>
     * <li>Above {@link #SCARCE_FAST_PEERS} the window is never widened, no
     * matter how bad build success looks. Plenty of good peers are
     * available, so admitting stale ones is a pure loss of quality.</li>
     * <li>During the startup grace period the window is widened to the
     * ceiling. This is the one legitimate exception: a cold router has an
     * almost-empty profile set, so peers look stale purely for lack of test
     * history rather than for any fault of their own.</li>
     * <li>Below the threshold the multiplier ramps linearly to the ceiling as
     * fast peers approach zero.</li>
     * </ol>
     *
     * <p>Pure, so the policy is testable without a router, and public because the
     * Tuner and the pool both consult it. A single shared definition of "we are
     * short of peers" is the whole point; two copies would drift apart and
     * reintroduce exactly the disagreement this change removes.
     *
     * @param fastPeers peers currently classified fast
     * @param startupGrace true while the router is within its startup grace period
     * @param min floor multiplier
     * @param max ceiling multiplier
     * @return multiplier in [min, max]
     * @since 0.9.71+
     */
    public static int windowMultiplierFor(int fastPeers, boolean startupGrace, int min, int max) {
        if (max <= min) {return min;}
        if (startupGrace) {return max;}
        if (fastPeers >= SCARCE_FAST_PEERS) {return min;}
        if (fastPeers <= 0) {return max;}
        double deficit = (double) (SCARCE_FAST_PEERS - fastPeers) / SCARCE_FAST_PEERS;
        return min + (int) Math.round(deficit * (max - min));
    }

    /**
     * {@link #windowMultiplierFor(int, boolean, int, int)} bound to the live
     * router: reads the current fast-peer count and startup state.
     *
     * @param ctx the router context
     * @return multiplier in [MIN_WINDOW_MULTIPLIER, MAX_WINDOW_MULTIPLIER}
     * @since 0.9.71+
     */
    public static int windowMultiplierFor(RouterContext ctx) {
        return windowMultiplierFor(ctx.profileOrganizer().countFastPeers(),
                                   isInStartupGracePeriod(ctx),
                                   MIN_WINDOW_MULTIPLIER, MAX_WINDOW_MULTIPLIER);
    }

    /**
     * Tuner-controlled activity-window multiplier, clamped to [1, 4]
     * ({@link #MIN_WINDOW_MULTIPLIER}..{@link #MAX_WINDOW_MULTIPLIER}).
     * Values outside the range are clamped, not rejected, so a Tuner param
     * change can never widen the window past the configured maximum.
     *
     * @param mult requested multiplier; clamped into [1, 4] before storing
     * @since 0.9.70+
     */
    public static void setWindowMultiplier(int mult) {
        _windowMultiplier = Math.max(MIN_WINDOW_MULTIPLIER, Math.min(MAX_WINDOW_MULTIPLIER, mult));
    }

    /**
     * Ceiling for {@link #getWindowMultiplier()}. Exposed so the pool's
     * starvation bypass can widen the window without duplicating the bound.
     *
     * @return the maximum multiplier
     * @since 0.9.71+
     */
    public static int getMaxWindowMultiplier() {
        return MAX_WINDOW_MULTIPLIER;
    }

    /** Stat name for the live window multiplier. @see #publishWindowMultiplier */
    public static final String WINDOW_MULTIPLIER_STAT = "tunnel.peerSelection.windowMultiplier";
    /**
     * Rate periods for {@link #WINDOW_MULTIPLIER_STAT}. Must not be null:
     * {@code RateStat} dereferences the array in its constructor, so a null
     * here throws before the stat is registered. The window is a level rather
     * than a rate, so a single short period is enough to show the current
     * value; the longer periods are kept so the entry renders consistently
     * with its neighbours on {@code /stats}.
     * @since 0.9.71+
     */
    private static final long[] WINDOW_MULTIPLIER_RATES = {
        RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR
    };
    /** Last value handed to the stat, so repeated publishes stay quiet. */
    private static volatile int _lastPublishedMultiplier = Integer.MIN_VALUE;

    /**
     * Publish the current activity-window multiplier.
     *
     * <p>The multiplier was a private field with no visible output, so a
     * multiplier stuck at its floor looked identical to a healthy one. During
     * a build slump that made the one control meant to relieve the slump
     * impossible to diagnose from the console: the window policy could be
     * inert and nothing on {@code /stats} or {@code /tuning} would say so.
     *
     * <p>StatManager has no gauge setter, so the level is published as a rate
     * sample and recorded only when the value actually changes. That keeps the
     * stat's average equal to the current multiplier instead of an average
     * over the history, and avoids a sample on every selection.
     *
     * <p>Neither the stat creation nor the data call may propagate: this runs
     * from {@link TunnelPool}'s emergency path, and a failing instrument must
     * not stop an emergency build. Failures are logged at WARN — which is how
     * the original null-periods NPE was found — and
     * {@code TunnelPeerSelectorWindowStatTest} asserts the stat is actually
     * registered, so a silently dead instrument cannot recur.
     *
     * @param ctx router context; null is ignored so callers on odd paths do not
     * need a null check of their own
     * @since 0.9.71+
     */
    public static void publishWindowMultiplier(RouterContext ctx) {
        if (ctx == null) {return;}
        // No "already created" guard: createRequiredRateStat returns
        // immediately when the name is registered, so calling it every time is
        // a cheap map lookup and is inherently idempotent. A static guard looked
        // equivalent but silently assumed one StatManager for the process
        // lifetime, so any replacement (an in-process restart, a test harness)
        // left the stat permanently unregistered.
        try {
            ctx.statManager().createRequiredRateStat(
                WINDOW_MULTIPLIER_STAT,
                "Peer activity window multiplier (higher = more peers eligible)",
                "Tunnels", WINDOW_MULTIPLIER_RATES);
            int current = _windowMultiplier;
            if (current == _lastPublishedMultiplier) {return;}
            _lastPublishedMultiplier = current;
            ctx.statManager().addRateData(WINDOW_MULTIPLIER_STAT, current);
        } catch (RuntimeException re) {
            // Reset so a later publish retries rather than being silenced
            // forever by a stale marker.
            _lastPublishedMultiplier = Integer.MIN_VALUE;
            ctx.logManager().getLog(TunnelPeerSelector.class)
               .log(Log.WARN, "Cannot record window multiplier", re);
        }
    }

    /**
     * Current Tuner-controlled activity-window multiplier.
     *
     * @return the current multiplier, in [1, 4]
     * @since 0.9.70+
     */
    public static int getWindowMultiplier() {
        return _windowMultiplier;
    }

    /**
     * Periodically called to keep transport sessions alive for top-tier peers and
     * proactively establish connections to Fast/HighCap peers before builds need them.
     *
     * This prevents the natural session aging that drops the active peer count from
     * ~600 to ~300 in the first 30 minutes, which starves first-hop selection and
     * causes tunnel pool collapse.
     *
     * @param ctx the router context
     * @param aggressive if true, also pre-connect to non-established eligible peers
     * (used when any pool has 0 tunnels)
     */
    public static void keepAlive(RouterContext ctx, boolean aggressive) {
        long now = ctx.clock().now();
        Log log = ctx.logManager().getLog(TunnelPeerSelector.class);
        RouterContext rctx = ctx;
        // Fetch once for the whole keepalive cycle; isStalePeer() per peer
        // would otherwise re-read router statistics up to 400 times.
        double buildSuccess = getBuildSuccess(ctx);

        // Collect top Fast + HighCap peers that aren't in first-hop fail cooldown.
        // Budget 200 (reduced from 400) — ~800 hash lookups + 200× isStalePeer
        // every 30s was the main CPU cost; 200 is sufficient to keep sessions
        // alive for the top-tier peers that builds actually need.
        Set<Hash> targets = new HashSet<>(256);
        // Must use mutable set — lockedSelectPeers may add to the exclude set
        rctx.profileOrganizer().selectFastPeers(KEEPALIVE_TARGET_TIERS, new HashSet<>(4), targets);
        // Also add top HighCap to cover more candidates.
        //
        // Overlap is inherent here: the fast and high-capacity tiers are both ordered
        // on throughput, so their heads are largely the same peers and asking each for
        // the same depth yields a union barely larger than either one. Measured over an
        // 85-minute run, the union collapsed from ~395 to ~203 while the action budget
        // held at 200 — so servicing never fell, only the surplus above budget did.
        // Asking the wider tier for more depth than the fast tier is what puts genuinely
        // distinct peers into the cycle; the action budget bounds cost regardless.
        rctx.profileOrganizer().selectHighCapacityPeers(KEEPALIVE_HIGH_CAP_DEPTH, targets, targets);
        // Remove self
        targets.remove(rctx.routerHash());

        KeepAliveTally tally = new KeepAliveTally();
        tally.budget = KEEPALIVE_TARGET_TIERS + KEEPALIVE_HIGH_CAP_DEPTH;
        tally.candidates = targets.size();

        if (targets.isEmpty()) {
            if (log.shouldInfo())
                log.info(tally.describe(aggressive));
            return;
        }

        for (Hash peer : targets) {
            if (tally.keepalived + tally.preConnected >= KEEPALIVE_ACTION_BUDGET) {
                // Per-cycle budget spent. The peers left are not rejected for any
                // fault of their own, so count them separately: a cycle that keeps
                // hitting the budget is a cycle that is not servicing its pool.
                tally.unprocessed++;
                continue;
            }

            // Skip peers in first-hop fail cooldown — they've proven unreachable recently
            if (isFirstHopFailing(rctx, peer)) {
                tally.skipCooldown++;
                continue;
            }

            // Skip stale peers — no activity in the last activity window
            if (isStalePeer(rctx, peer, buildSuccess)) {
                tally.skipStale++;
                continue;
            }

            Long lastKa = _lastKeepAlive.get(peer);
            if (lastKa != null && now - lastKa < KEEPALIVE_INTERVAL_MS) {
                tally.skipRecent++;
                continue;
            }

            boolean established = rctx.commSystem().isEstablished(peer);

            if (established) {
                // Peer already connected — send a lightweight DLM to keep the session alive.
                // For established peers, transport.send() just enqueues to fragments with
                // no establishment overhead.
                RouterInfo ri = lookupRouterInfoUnvalidated(rctx, peer);
                if (ri == null) { tally.skipNoRouterInfo++; continue; }
                long lifetime = now + 30*1000L;
                DatabaseLookupMessage dlm = new DatabaseLookupMessage(rctx, true);
                dlm.setFrom(rctx.routerHash());
                dlm.setSearchKey(peer);
                dlm.setSearchType(DatabaseLookupMessage.Type.RI);
                dlm.setMessageExpiration(lifetime);
                OutNetMessage onm = new OutNetMessage(rctx, dlm, lifetime,
                    OutNetMessage.PRIORITY_MY_BUILD_REQUEST, ri);
                Transport udp = rctx.commSystem().getTransports().get("SSU");
                Transport ntcp = rctx.commSystem().getTransports().get("NTCP");
                boolean sent = false;
                if (udp != null) {
                    try { udp.send(onm); sent = true; } catch (Exception e) { /* ignored */ }
                }
                if (!sent && ntcp != null) {
                    try { ntcp.send(onm); sent = true; } catch (Exception e) { /* ignored */ }
                }
                if (sent) { tally.keepalived++; _lastKeepAlive.put(peer, now); }
                else { tally.skipNoTransport++; }
            } else if (aggressive) {
                // Peer not connected and pools are depleted — proactively start
                // establishment so it's ready when the next build runs.
                PreConnectOutcome outcome = preConnectTo(rctx, peer);
                switch (outcome) {
                    case SENT: tally.preConnected++; _lastKeepAlive.put(peer, now); break;
                    case NO_ROUTERINFO: tally.skipNoRouterInfo++; break;
                    case NO_USABLE_ADDRESS: tally.skipNoAddress++; break;
                    default: tally.skipNoTransport++; break;
                }
            }
        }

        if (log.shouldInfo()) {
            log.info(tally.describe(aggressive));
        }
    }

}
