package net.i2p.router.networkdb.kademlia;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;

import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.util.Log;

/**
 * Periodically republishes a local LeaseSet to the network database.
 *
 * Handles the lifecycle of lease set publication including:
 * <ul>
 *   <li>Initial publication after sufficient uptime (deferred until target tunnels built)</li>
 *   <li>Periodic republishing before lease expiration (default 5 min)</li>
 *   <li>On-success global fail count reset</li>
 *   <li>Retry with exponential backoff on failure</li>
 *   <li>Floodfill verification after repeated failures</li>
 *   <li>Cleanup when the client is no longer local</li>
 * </ul>
 *
 * Thread-safe via concurrent maps across instances.
 */
public class RepublishLeaseSetJob extends JobImpl {
    /** Class logger. */
    private final Log _log;
    /** Config property for LeaseSet publish timeout. */
    private static final String PROP_TIMEOUT = "router.leaseSetPublishTimeout";
    /** Config property for publish retry base delay. */
    private static final String PROP_RETRY_DELAY = "router.leaseSetPublishRetryDelay";
    /** Config property for max publish retry delay. */
    private static final String PROP_MAX_RETRY_DELAY = "router.leaseSetPublishMaxRetryDelay";
    /** Default timeout for LeaseSet publication to floodfills. */
    public static final long REPUBLISH_LEASESET_TIMEOUT_DEFAULT = 60L * 1000;
    /** Default base delay before retrying a failed publish. */
    public static final int RETRY_DELAY_DEFAULT = (int) (10L * 1000);
    /** Maximum backoff delay for publish retries. */
    public static final int RETRY_MAX_DELAY_DEFAULT = (int) (120L * 1000);
    /** Window before lease expiry to trigger a re-mint instead of flooding the
     *  dying copy.  Must stay below (lease cap - republish interval) — here
     *  240s < 600s - 300s — so a freshly re-minted copy (capped at ~600s) still
     *  presents above this window at the next interval check and gets flooded
     *  directly; with an equal boundary the check lands at the knife edge and
     *  re-mints forever, starving the floodfill. */
    private static final long EXPIRY_WINDOW = 4L * 60 * 1000;
    /** Minimum reschedule interval — prevents sub-minute flood treadmills. */
    private static final long MIN_RESCHEDULE = 60L * 1000;
    /** Retry interval after a missing local LeaseSet — long enough for the
     *  pool to build and test a replacement tunnel before we ask again. */
    private static final long MISSING_LEASESET_RETRY = 30L * 1000;
    /**
     *  A lease is viable for a re-mint while it has at least this much time
     *  remaining: the re-sign request, floodfill propagation, and connection
     *  establishment all need the lease alive, and a tunnel closer to expiry
     *  than this is already inside the pool's proactive replacement window, so
     *  counting it would overstate the pool's health.  A healthy pool therefore
     *  reads close to its target count, and re-mints proceed without
     *  requesting extra builds.
     *
     *  @param ctx the router context
     *  @return the lease viability window in ms
     */
    private static long getLeaseViabilityWindow(RouterContext ctx) {
        return TunnelPool.getLeaseViabilityWindow(ctx);
    }
    /** Consecutive fresh-build deferrals before falling back to a thin re-mint. */
    private static final int MAX_REMINT_DEFERS = 3;
    /** Staleness threshold for cleaning up tracking maps. */
    private static final long CACHE_CLEANUP_THRESHOLD = 15L * 60 * 1000;
    // Last time cleanupStaleEntries() ran — guards against redundant sweeps
    /** Last cleanup time. */
    private static volatile long _lastCleanupTime;
    /** Tracks destinations currently being retried to prevent duplicate retries. */
    private static final ConcurrentHashMap<Hash, Boolean> _retryInProgress = new ConcurrentHashMap<>();
    /** Last log timestamp per destination to throttle publish log messages. */
    private static final ConcurrentHashMap<Hash, Long> _lastPublishLogTime = new ConcurrentHashMap<>();
    /** Last log timestamp per destination to throttle verification log messages. */
    private static final ConcurrentHashMap<Hash, Long> _lastVerifyLogTime = new ConcurrentHashMap<>();
    /** Last log timestamp per destination to throttle no-requeue log messages. */
    private static final ConcurrentHashMap<Hash, Long> _lastNotRequeueLogTime = new ConcurrentHashMap<>();
    /** Last log timestamp per destination to throttle missing-LeaseSet log messages. */
    private static final ConcurrentHashMap<Hash, Long> _lastMissingLogTime = new ConcurrentHashMap<>();
    // Persistent per-destination failure count — never reset, survives job instances.
    // Used to decide when to perform expensive floodfill verification.
    /** Persistent per-destination store failure count. */
    private static final ConcurrentHashMap<Hash, AtomicInteger> _globalFailCount = new ConcurrentHashMap<>();
    /** Consecutive re-mint deferral count per destination, to bound fresh-build waiting. */
    private static final ConcurrentHashMap<Hash, AtomicInteger> _remintDefers = new ConcurrentHashMap<>();
    /** Max verifications per destination before falling back to direct retry. */
    private static final int MAX_FLOODFILL_VERIFICATIONS = 3;
    // Tracks defer start for startup-gate; cleared on success or FIRST_PUBLISH_TIMEOUT
    /** Timestamp when first publish was deferred, per destination. */
    private static final ConcurrentHashMap<Hash, Long> _firstDeferredAt = new ConcurrentHashMap<>();
    /** Max time to defer first publish while waiting for target tunnels. */
    private static final long FIRST_PUBLISH_TIMEOUT = 60L * 1000;
    /** Destinations that have published successfully at least once — never defer these again. */
    private static final Set<Hash> _publishedOnce = ConcurrentHashMap.newKeySet();
    /** Destination hash. */
    private final Hash _dest;
    /** Kademlia network database facade instance. */
    private final KademliaNetworkDatabaseFacade _facade;
    /** Last published time. */
    private volatile long _lastPublished;
    /** Per-instance retry counter for exponential backoff. */
    private final AtomicInteger failCount = new AtomicInteger(0);
    /** Whether this job instance is promoted to high priority. */
    private boolean highPriority;
    /** Whether a floodfill verification lookup is in progress. */
    private final AtomicBoolean _lookupInProgress = new AtomicBoolean(false);
    /** Registered flag. */
    private boolean _registered = false;

    /**
     * Creates a new job to republish a LeaseSet for the given destination.
     *
     * @param ctx the router context
     * @param facade the network database facade
     * @param destHash the destination hash to republish
     */
    public RepublishLeaseSetJob(RouterContext ctx, KademliaNetworkDatabaseFacade facade, Hash destHash) {
        super(ctx);
        _log = ctx.logManager().getLog(RepublishLeaseSetJob.class);
        _facade = facade;
        _dest = destHash;
    }

    /**
     * Attempt to register this job with the facade.
     * @return true if registered, false if a job was already active
     */
    public boolean registerSelf() {
        _registered = _facade.registerPublishingJob(this);
        return _registered;
    }

    @Override
    /** Run the job */
    public void runJob() {
        cleanupStaleEntries();
        if (!_registered) {
            if (_log.shouldWarn()) {
                _log.warn("Job not registered for [" + shortHash() + "] - skipping execution");
            }
            return;
        }

        long uptime = getContext().router().getUptime();
        try {
            // A local client that does not publish to the network
            // (i2cp.dontPublishLeaseSet, e.g. HTTP proxy) still needs its local
            // LeaseSet kept fresh.  Only treat "should not publish" as a stop
            // signal when the client is truly gone: its I2CP session may be
            // temporarily closed by close-on-idle while the tunnel pools still
            // exist, so keep the re-mint cycle alive while the pools remain.
            if (!getContext().clientManager().shouldPublishLeaseSet(_dest) &&
                !getContext().clientManager().isLocal(_dest)) {
                if (isTrackedLocal()) {
                    handleLocalLeaseSet();
                } else {
                    handleShouldNotPublish();
                }
                return;
            }
            if (uptime < 5L * 1000) {
                long delay = Math.max(1000, 5L * 1000 - uptime);
                scheduleRepublish(delay);
                return;
            }
            if (getContext().clientManager().isLocal(_dest)) {
                handleLocalLeaseSet();
            } else {
                handleNotLocal();
            }
        } catch (RuntimeException re) {
            if (_log.shouldError()) {_log.error("Uncaught error republishing the LeaseSet", re);}
            _facade.stopPublishing(_dest);
            throw re;
        } finally {
            _facade.removePublishingJob(_dest, this);
            clearRetryInProgress();
        }
    }

    // Client has indicated it should no longer publish this LeaseSet
    /** Handle should not publish */
    private void handleShouldNotPublish() {
        LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
        if (ls != null && _log.shouldDebug()) {
            _log.debug("Cleaning up local LeaseSet [" + shortHash() + "] on service stop");
        }
        // Drop the store entry unconditionally.  lookupLeaseSetLocally() hides
        // an expired copy (returns null), but the raw store still holds it —
        // without this the expired LeaseSet lingers in the netdb indefinitely.
        _facade.fail(_dest);
        _facade.stopPublishing(_dest);
    }

    // Handle a local LeaseSet that needs publication
    /** Handle local lease set */
    private void handleLocalLeaseSet() {
        LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
        if (ls != null) {
            String tunnelName = getTunnelName(ls.getDestination());
            String name = !tunnelName.isEmpty() ? " for '" + tunnelName + "'" : " for key";
            long now = getContext().clock().now();

            if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
                handleExpiredLeaseSet(ls, name);
            } else {
                long timeUntilExpiry = ls.getLatestLeaseDate() - now;
                handleValidLeaseSet(ls, name, now, timeUntilExpiry);
            }
        } else {
            handleMissingLeaseSet();
        }
    }

    // LeaseSet has expired — request a fresh one and check back soon
    /** Handle expired lease set */
    private void handleExpiredLeaseSet(LeaseSet ls, String name) {
        if (_log.shouldWarn()) {
            _log.warn("LeaseSet EXPIRED - triggering immediate rebuild for " + name +
                      " [" + shortHash() + "]");
        }
        // Send the pool's current leases so the client re-signs fresh tunnels,
        // not the expired stored copy (which would fail expiry validation).
        // When the pool has no usable leases (e.g. rebuilding after a build
        // failure), skip the request — re-signing the expired copy would just
        // produce another invalid LS — and retry after the 30s pool rebuild
        // window.
        LeaseSet fresh = getFreshPoolLeaseSet();
        if (fresh != null) {
            getContext().clientManager().requestLeaseSet(_dest, fresh);
        }
        TunnelPool pool = getContext().tunnelManager().getInboundPool(_dest);
        if (pool != null) {
            pool.requestFreshTunnelBuild();
        }
        scheduleRepublish(30L * 1000);
    }

    // No LeaseSet exists locally — ask the client to create one
    /** Handle missing lease set */
    private void handleMissingLeaseSet() {
        long now = getContext().clock().now();
        Long lastLog = _lastMissingLogTime.get(_dest);
        if (_log.shouldInfo() && (lastLog == null || now - lastLog > 10L * 1000)) {
            _log.info("Client [" + shortHash() +
                      "] is LOCAL, but no valid LeaseSet found -> Requesting rebuild");
            _lastMissingLogTime.put(_dest, now);
        }
        clearRetryInProgress();
        // A LOCAL client with no stored LeaseSet is usually a pool still
        // building its first tunnels or a store race.  The pool requests a
        // re-sign itself on every completed build, so while it is actively
        // building or testing our request would be redundant — skip it and
        // let the pool's own hook land the LeaseSet.  Only a quiet pool
        // (no builds in flight) needs the nudge to re-sign its tunnels.
        TunnelPool pool = getContext().tunnelManager().getInboundPool(_dest);
        if (pool == null || (pool.getInProgressCount() == 0 && pool.getTestingTunnelCount() == 0)) {
            getContext().clientManager().requestLeaseSet(_dest, null);
        } else if (_log.shouldDebug()) {
            _log.debug("Skipping LeaseSet request for busy pool [" + shortHash() + "] (" +
                       pool.getInProgressCount() + " building, " + pool.getTestingTunnelCount() +
                       " testing)");
        }
        scheduleRepublish(MISSING_LEASESET_RETRY);
    }

    // Client is no longer local — clean up and stop publishing
    /** Handle not local */
    private void handleNotLocal() {
        if (_log.shouldInfo()) {
            _log.info("Client [" + shortHash() +
                      "] is no longer LOCAL -> Not republishing LeaseSet");
        }
        LeaseSet ls = _facade.lookupLeaseSetLocally(_dest);
        if (ls == null) {
            // lookupLeaseSetLocally() hides an expired copy, but the raw store
            // may still hold it — drop it so it can't linger stale.  No-op if
            // no entry exists.
            _facade.fail(_dest);
            _facade.stopPublishing(_dest);
        } else if (!ls.isCurrent(Router.CLOCK_FUDGE_FACTOR)) {
            _facade.fail(_dest);
            _facade.stopPublishing(_dest);
        } else {
            // LeaseSet still current: keep the chain alive until expiry so the
            // stale copy is dropped instead of lingering forever.
            long untilExpiry = ls.getLatestLeaseDate() - getContext().clock().now() + 1000;
            scheduleRepublish(Math.max(MIN_RESCHEDULE, untilExpiry));
        }
    }

    // True if the destination is still a configured local tunnel even when
    // its I2CP session is closed (close-on-idle), i.e. a pool still exists.
    /** Determine if client is still a configured local tunnel */
    private boolean isTrackedLocal() {
        return getContext().clientManager().isLocal(_dest) ||
               getContext().tunnelManager().getInboundSettings(_dest) != null ||
               getContext().tunnelManager().getOutboundSettings(_dest) != null;
    }

    // Publish a valid LeaseSet.  Startup gate defers first publication until
    // target tunnel count met (or FIRST_PUBLISH_TIMEOUT elapses).  After first
    // successful publish, subsequent renewals proceed immediately.
    /** Handle valid lease set */
    private void handleValidLeaseSet(LeaseSet ls, String name, long now, long timeUntilExpiry) {
        Long lastPubLog = _lastPublishLogTime.get(_dest);
        boolean expiring = timeUntilExpiry <= EXPIRY_WINDOW;
        if (expiring) {
            if (_log.shouldInfo()) {
                _log.info("LeaseSet expiring soon for " + name + " [" + shortHash() +
                          "] (expires in " + (timeUntilExpiry / 1000) + "s) — requesting immediate renew");
            }
            lastPubLog = null;
        }
        if (_log.shouldInfo() && (lastPubLog == null || (now - lastPubLog > 10L * 1000))) {
            _log.info("Publishing LeaseSet" + name + " [" + shortHash() +
                       "] (expires in " + (timeUntilExpiry / 1000) + "s)...");
            _lastPublishLogTime.put(_dest, now);
        }

        int leaseCount = ls.getLeaseCount();
        TunnelPoolSettings settings = getContext().tunnelManager().getInboundSettings(_dest);
        int targetLeases = settings != null ? settings.getQuantity() : 1;

        // Active local client with publication disabled (i2cp.dontPublishLeaseSet,
        // e.g. HTTP proxy): keep the local copy fresh but never floodfill.
        // publish() stores the re-minted LeaseSet locally; this just keeps the
        // republish cycle alive so it re-mints before the lease expires.
        if (!getContext().clientManager().shouldPublishLeaseSet(_dest)) {
            if (_log.shouldInfo()) {
                _log.info("Local-only LeaseSet maintenance for " + name + " [" + shortHash() +
                           "] (not published to network)");
            }
            _lastPublished = now;
            refloatLeaseSet(name, now, timeUntilExpiry);
            return;
        }

        // Published LeaseSet too close to expiry to floodfill: don't flood a
        // copy that would expire before propagation completes.  Re-mint from
        // the tunnel pool's current tunnels and request a fresh re-signed
        // LeaseSet; the client's Reply re-floods the new LS with full expiry.
        // This check runs before the startup deferral gate so an expiring LS
        // always re-mints immediately — never waits behind the gate.
        if (expiring) {
            if (_log.shouldInfo()) {
                _log.info("Published LeaseSet for " + name + " [" + shortHash() +
                          "] expires in " + (timeUntilExpiry / 1000) +
                          "s — re-minting instead of flooding dying copy");
            }
            refloatLeaseSet(name, now, timeUntilExpiry);
            return;
        }

        // Startup gate: defer first publication until target tunnel count met
        // (or FIRST_PUBLISH_TIMEOUT elapses).  After a successful publish the
        // destination is marked _publishedOnce and never deferred again.
        if (maybeDeferFirstPublish(leaseCount, targetLeases))
            return;

        getContext().statManager().addRateData("netDb.republishLeaseSetCount", 1);
        _facade.sendStore(_dest, ls, new OnRepublishSuccess(),
                          new OnRepublishFailure(ls), getPublishTimeout(), null);
        _lastPublished = now;

        scheduleRepublish(computeNextRepublish(timeUntilExpiry));
    }

    // At startup, defer first publication until target tunnels met.
    // Falls through after FIRST_PUBLISH_TIMEOUT so pools at partial
    // capacity (e.g. 70% build success) don't block indefinitely.
    // One-time only: once a destination publishes, _publishedOnce prevents
    // re-deferral on subsequent cycles (no 15s treadmill).
    /**
     * Whether publication was deferred while waiting for target tunnels.
     *
     * @return true if publication was deferred
     */
    private boolean maybeDeferFirstPublish(int leaseCount, int targetLeases) {
        if (_publishedOnce.contains(_dest))
            return false;
        Long deferred = _firstDeferredAt.get(_dest);
        if (deferred == null && leaseCount < targetLeases) {
            deferred = getContext().clock().now();
            Long existing = _firstDeferredAt.putIfAbsent(_dest, deferred);
            if (existing != null)
                deferred = existing;
        }
        if (deferred != null) {
            long elapsed = getContext().clock().now() - deferred;
            if (elapsed < FIRST_PUBLISH_TIMEOUT && leaseCount < targetLeases) {
                if (_log.shouldInfo()) {
                    _log.info("Deferring LS publish for [" + shortHash() +
                              "] — " + leaseCount + "/" + targetLeases + " tunnels " +
                              "(elapsed " + (elapsed / 1000) + "s)");
                }
                scheduleRepublish(MIN_RESCHEDULE);
                return true;
            }
            _firstDeferredAt.remove(_dest);
        }
        return false;
    }

    /**
     * Human-readable name for the job queue.
     * @return "Republish Local LeaseSet" with optional high-priority marker
     */
    public String getName() {return "Republish Local LeaseSet" + (highPriority ? " [High priority]" : "");}

    private long getPublishTimeout() {
        return getContext().getProperty(PROP_TIMEOUT, REPUBLISH_LEASESET_TIMEOUT_DEFAULT);
    }

    private int getRetryDelay() {
        return getContext().getProperty(PROP_RETRY_DELAY, RETRY_DELAY_DEFAULT);
    }

    private int getMaxRetryDelay() {
        return getContext().getProperty(PROP_MAX_RETRY_DELAY, RETRY_MAX_DELAY_DEFAULT);
    }

    private long getRepublishInterval() {
        return getContext().getProperty("i2p.netdb.republishInterval", 5L * 60 * 1000);
    }

    /**
     * Next republish delay: the configured interval floored at MIN_RESCHEDULE,
     * but capped so the next check fires before the current LeaseSet hits the
     * EXPIRY_WINDOW re-mint threshold.  A fresh 10-minute lease with a 5-min
     * interval schedules at the interval; a shorter/thinner lease schedules
     * sooner, so a re-mint that only slightly extends expiry is re-flooded
     * again before the copy dies instead of rotting for a full interval.
     * With the lease cap at 600s the alternation is: re-mint lands ~594s,
     * the next check at the interval sees it above EXPIRY_WINDOW and floods
     * directly, then the 60s re-check crosses the window and re-mints again —
     * every cycle both refreshes and floodfills.
     *
     * @param timeUntilExpiry ms until the current LeaseSet expires
     * @return delay in ms
     */
    private long computeNextRepublish(long timeUntilExpiry) {
        long interval = getRepublishInterval();
        long expiryAnchored = timeUntilExpiry - EXPIRY_WINDOW;
        if (expiryAnchored > interval)
            expiryAnchored = interval;
        return Math.max(MIN_RESCHEDULE, expiryAnchored);
    }

    /**
     *  The inbound pool's current tunnels as a re-mintable LeaseSet, or null
     *  if the client has no inbound pool or no usable leases.
     *
     *  @return current-pool LeaseSet, or null
     */
    private LeaseSet getFreshPoolLeaseSet() {
        TunnelPool pool = getContext().tunnelManager().getInboundPool(_dest);
        if (pool != null) {
            return pool.getInboundTunnelsAsLeaseSet();
        }
        return null;
    }

    /**
     *  Re-mint from the tunnel pool's current tunnels rather than re-signing or
     *  flooding the stored copy, whose leases may be near expiry.  The client
     *  signs whatever leases we send, so sending the stored (dying) copy would
     *  keep the local LeaseSet perpetually close to expiry.  Schedules the
     *  successor based on the fresh copy's expiry so a thin extension is
     *  re-flooded before it can die.
     *
     *  Re-mints are gated on the pool actually holding enough viable tunnels:
     *  a pool at its target count with aging leases never rebuilds on its own
     *  (the pool only replaces within its proactive expiry window), so an
     *  ungated re-mint just re-signs the same near-expired leases — the
     *  "extends expiry from 629s to 630s" no-op.  When the pool falls short,
     *  request fresh tunnel builds and re-check after a minute; only after
     *  several consecutive deferred tries does it fall back to re-minting the
     *  current copy whenever at least one viable lease remains, so a congested
     *  network can't leave the LeaseSet empty.  The fallback resets the
     *  deferral counter so the next cycle resumes requesting fresh builds
     *  instead of re-minting thin copies forever.  The fallback no longer
     *  requires the re-mint to extend the stored copy: rotation gives every
     *  re-mint a different earliest lease date, so the floodfill accepts and
     *  re-floods it, and a thin copy the pool can still serve beats letting
     *  the stored copy die while the deferral waits for capacity.
     *
     *  @param name the destination name for logging
     *  @param now current time in ms
     *  @param timeUntilExpiry time until the stored copy expires, in ms
     */
    private void refloatLeaseSet(String name, long now, long timeUntilExpiry) {
        LeaseSet fresh = getFreshPoolLeaseSet();
        long freshTimeUntilExpiry = fresh != null ? fresh.getLatestLeaseDate() - now : 0;
        TunnelPool pool = getContext().tunnelManager().getInboundPool(_dest);
        int freshCount = countFreshLeases(fresh, now);
        int target = getTargetLeaseCount();
        boolean requested = false;

        // Gate on the minimum viable set: two for server pools with target
        // > 2, one otherwise.  Re-minting with fewer viable leases would
        // serve an unnecessarily thin copy — the leases would expire before
        // the successor propagates.
        int required = Math.min(2, Math.max(1, target - 1));
        boolean extendsExpiry = freshTimeUntilExpiry > timeUntilExpiry;
        if (fresh != null && extendsExpiry && freshCount >= required) {
            if (_log.shouldInfo()) {
                _log.info("Requesting re-mint of LeaseSet for " + name + " [" + shortHash() +
                          "] (extends expiry from " + (timeUntilExpiry / 1000) +
                          "s to " + (freshTimeUntilExpiry / 1000) + "s, " +
                          freshCount + "/" + target + " viable leases)");
            }
            getContext().clientManager().requestLeaseSet(_dest, fresh);
            _remintDefers.remove(_dest);
            requested = true;
        } else if (pool != null) {
            // Thin extension or none: bound the wait with a per-destination
            // deferral counter.  Each deferred cycle requests fresh builds so
            // the next re-mint can carry capacity; after MAX_REMINT_DEFERS
            // consecutive tries, fall back to re-minting whatever the pool
            // holds whenever at least one viable lease remains — a thin
            // copy beats letting the LeaseSet die while waiting for full
            // capacity.  Rotation guarantees each re-minted set differs from
            // the stored copy, so the floodfill accepts and re-floods it even
            // when the latest lease date barely moves.
            AtomicInteger defers = _remintDefers.computeIfAbsent(_dest, k -> new AtomicInteger(0));
            if (freshCount > 0 && defers.getAndIncrement() >= MAX_REMINT_DEFERS) {
                if (_log.shouldWarn()) {
                    _log.warn("Re-mint for " + name + " [" + shortHash() +
                              "] falling back after " + defers.get() +
                              " deferred tries (" + freshCount + "/" + target + " viable leases)");
                }
                getContext().clientManager().requestLeaseSet(_dest, fresh);
                // Reset so the next cycle requests fresh builds again instead
                // of re-minting thin copies forever
                _remintDefers.remove(_dest);
                requested = true;
            } else {
                if (_log.shouldInfo()) {
                    _log.info("Deferring re-mint for " + name + " [" + shortHash() +
                              "] — " + freshCount + "/" + target +
                              " viable leases, requesting build");
                }
                pool.requestFreshTunnelBuild();
            }
        } else {
            // No pool and no extension possible: keep the republish cycle
            // alive so the LeaseSet is never abandoned while the network
            // recovers; the expiry path triggers the rebuild once the copy
            // finally dies.
            if (_log.shouldInfo()) {
                _log.info("No inbound pool for " + name + " [" + shortHash() +
                          "], keeping cycle alive until expiry");
            }
        }
        long effectiveExpiry = requested ? freshTimeUntilExpiry : timeUntilExpiry;
        scheduleRepublish(computeNextRepublish(effectiveExpiry));
    }

    /**
     *  Count leases still viable for a re-mint, i.e. with at least the
     *  viability window remaining so the re-signed copy is not full of
     *  near-dead leases that expire before they propagate.
     *
     *  @param ls the LeaseSet to inspect, may be null
     *  @param now current time in ms
     *  @return the number of leases with at least a viability window remaining
     */
    private int countFreshLeases(LeaseSet ls, long now) {
        if (ls == null) {
            return 0;
        }
        long window = getLeaseViabilityWindow(getContext());
        int count = 0;
        for (int i = 0; i < ls.getLeaseCount(); i++) {
            if (ls.getLease(i).getEndTime() - now >= window) {
                count++;
            }
        }
        return count;
    }

    /**
     *  The number of inbound leases a re-mint should carry to be useful:
     *  the inbound tunnel quantity for a server pool, 1 otherwise.
     *
     *  @return the target lease count
     */
    private int getTargetLeaseCount() {
        TunnelPoolSettings settings = getContext().tunnelManager().getInboundSettings(_dest);
        return settings != null ? settings.getQuantity() : 1;
    }

    // Register a successor RepublishLeaseSetJob with timing set.
    private RepublishLeaseSetJob registerSuccessor(long delayMs) {
        RepublishLeaseSetJob job = new RepublishLeaseSetJob(getContext(), _facade, _dest);
        if (!job.registerSelf()) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping successor for [" + shortHash() + "] -> Registration failed");
            }
            return null;
        }
        job.getTiming().setStartAfter(getContext().clock().now() + delayMs);
        return job;
    }

    // Schedule the next republish cycle.  Unregisters current job first
    // so hasActiveRepublishJob() doesn't block the successor.
    /** Schedule republish */
    private void scheduleRepublish(long delayMs) {
        _facade.removePublishingJob(_dest, this);
        if (_facade.hasActiveRepublishJob(_dest)) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping republish for [" + shortHash() +
                           "] -> Job already active (scheduled externally)");
            }
            return;
        }
        RepublishLeaseSetJob next = registerSuccessor(delayMs);
        if (next != null)
            getContext().jobQueue().addJob(next);
    }

    // Schedule a retry after a failed publish.
    // Exponential backoff with aggressive first-retry delay;
    // every 4th attempt is promoted to high-priority.
    /** Requeue republish */
    void requeueRepublish() {
        if (_retryInProgress.putIfAbsent(_dest, Boolean.TRUE) != null) {
            if (_log.shouldDebug()) {
                _log.debug("Retry already in progress for " + shortHash() + "] -> Skipping...");
            }
            return;
        }
        cleanupStaleEntries();
        if (_facade.hasActiveRepublishJob(_dest)) {
            if (_log.shouldDebug()) {
                _log.debug("Skipping retry for [" + shortHash() + "] -> Job already active");
            }
            clearRetryInProgress();
            return;
        }
        int count = failCount.incrementAndGet();
        LeaseSet ls = getContext().clientManager().isLocal(_dest) ? _facade.lookupLeaseSetLocally(_dest) : null;
        String b32 = shortHash();
        String tunnelName = ls != null ? getTunnelName(ls.getDestination()) : "";
        String name = !tunnelName.isEmpty() ? "'" + tunnelName + "'" + " [" + b32 + "]" : "[" + b32 + "]";
        String countStr = count > 1 ? " (Attempt: " + count + ")" : "";
        if (_log.shouldInfo() && count > 3) {
            _log.info("Failed to publish LeaseSet for " + name + " -> Retrying..." + countStr);
        }
        getContext().statManager().addRateData("netDb.republishLeaseSetFail", 1);

        long baseDelay = count <= 1 ? 5_000L : (long) getRetryDelay();
        long retryDelay = Math.min(baseDelay * (1 << Math.min(Math.max(0, count - 1), 4)), getMaxRetryDelay());
        boolean isHighPriority = count % 4 == 0;
        RepublishLeaseSetJob retryJob = registerSuccessor(retryDelay);
        if (retryJob == null) {
            clearRetryInProgress();
            return;
        }
        if (isHighPriority) {
            retryJob.highPriority = true;
            getContext().jobQueue().addJobToTop(retryJob);
        } else {
            getContext().jobQueue().addJob(retryJob);
        }
    }

    /** Clear retry in progress */
    private void clearRetryInProgress() {
        _retryInProgress.remove(_dest);
    }

    private String shortHash() {
        return _dest.toBase32().substring(0, 8);
    }

    /** Cleanup stale entries */
    private static void cleanupStaleEntries() {
        long now = System.currentTimeMillis();
        if (now - _lastCleanupTime <= CACHE_CLEANUP_THRESHOLD)
            return;
        _lastCleanupTime = now;
        cleanupMap(_lastPublishLogTime, now);
        cleanupMap(_lastVerifyLogTime, now);
        cleanupMap(_lastNotRequeueLogTime, now);
        cleanupMap(_lastMissingLogTime, now);
        cleanupGlobalFailCount(now);
        cleanupRemintDefers();
        cleanupPublishedOnce();
    }

    /** Drop _publishedOnce entries for destinations no longer being tracked. */
    private static void cleanupPublishedOnce() {
        _publishedOnce.retainAll(_lastPublishLogTime.keySet());
    }

    /** Cleanup map */
    private static void cleanupMap(ConcurrentHashMap<Hash, Long> map, long now) {
        Iterator<Map.Entry<Hash, Long>> iter = map.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Hash, Long> entry = iter.next();
            if (now - entry.getValue() > CACHE_CLEANUP_THRESHOLD) {
                iter.remove();
            }
        }
    }

    // Reset global fail counters idle beyond the threshold
    /** Cleanup global fail count */
    private static void cleanupGlobalFailCount(long now) {
        // Global fail count has no timestamp per entry; just clear entries
        // for destinations no longer being tracked in the publish log.
        // If _lastPublishLogTime is empty for a given hash and the global
        // fail counter exists, assume publication succeeded and reset.
        Iterator<Map.Entry<Hash, AtomicInteger>> iter = _globalFailCount.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Hash, AtomicInteger> entry = iter.next();
            if (!_retryInProgress.containsKey(entry.getKey()) &&
                !_lastPublishLogTime.containsKey(entry.getKey())) {
                iter.remove();
            }
        }
    }

    /**
     *  Reset re-mint deferral counters for destinations no longer tracked.
     */
    private static void cleanupRemintDefers() {
        _remintDefers.keySet().removeIf(h -> !_lastPublishLogTime.containsKey(h));
    }

    /**
     * Timestamp of the last successful publication.
     * @return timestamp in ms, or 0 if never published
     */
    public long lastPublished() {return _lastPublished;}

    /**
     * The destination hash this job publishes for.
     * @return destination hash
     */
    Hash getDestHash() {return _dest;}

    // Handles store timeout: retries (with floodfill verification on recurring failures)
    // or cancels if a newer LeaseSet appeared locally.
    /** Handles publish timeout and schedules retries with floodfill verification. */
    private class OnRepublishFailure extends JobImpl {
        /** LeaseSet that failed to publish. */
        private final LeaseSet _ls;

        public OnRepublishFailure(LeaseSet ls) {
            super(RepublishLeaseSetJob.this.getContext());
            _ls = ls;
        }

        public String getName() {return "Timeout LeaseSet Publication";}

        public void runJob() {
            LeaseSet ls = _facade.lookupLeaseSetLocally(_ls.getHash());
            String tunnelName = ls != null ? getTunnelName(_ls.getDestination()) : "";
            String name = !tunnelName.isEmpty() ? " for '" + tunnelName + "'" : "";

            if (ls == null || KademliaNetworkDatabaseFacade.isNewer(ls, _ls)) {
                clearRetryInProgress();
                long now = getContext().clock().now();
                Long lastNotRequeueLog = _lastNotRequeueLogTime.get(_ls.getHash());
                if (_log.shouldInfo() && (lastNotRequeueLog == null || (now - lastNotRequeueLog > 10L * 1000))) {
                    _log.info("Not requeueing LeaseSet" + name + " [" +
                              _ls.getHash().toBase32().substring(0, 8) +
                              "] -> Newer LeaseSet exists locally");
                    _lastNotRequeueLogTime.put(_ls.getHash(), now);
                }
                cleanupStaleEntries();
                return;
            }

            // Use the persistent global fail counter, NOT the per-instance failCount
            // (which is reset to 0 every runJob()).  Verification only kicks in after
            // multiple real failures, not on the first store timeout.
            int globalCount = _globalFailCount
                .computeIfAbsent(_ls.getHash(), k -> new AtomicInteger(0))
                .incrementAndGet();

            if (globalCount > 2 && globalCount % 3 == 0 && _lookupInProgress.compareAndSet(false, true)) {
                long now = getContext().clock().now();
                Long lastVerifyLog = _lastVerifyLogTime.get(_ls.getHash());
                if (globalCount / 3 > MAX_FLOODFILL_VERIFICATIONS) {
                    if (_log.shouldWarn()) {
                        _log.warn("Floodfill verification maxed out for" + name + " [" +
                                  _ls.getHash().toBase32().substring(0, 8) +
                                  "] -> falling back to retry directly");
                    }
                    _lookupInProgress.set(false);
                    requeueRepublish();
                    return;
                }
                if (_log.shouldInfo() && (lastVerifyLog == null || (now - lastVerifyLog > 10L * 1000))) {
                    _log.info("Verifying LeaseSet publication" + name + " [" +
                              _ls.getHash().toBase32().substring(0, 8) + "] via floodfill...");
                    _lastVerifyLogTime.put(_ls.getHash(), now);
                }
                cleanupStaleEntries();
                verifyAndRetry(ls);
            } else {
                requeueRepublish();
            }
        }

        /** Verify and retry */
        private void verifyAndRetry(final LeaseSet ls) {
            String tunnelName = getTunnelName(ls.getDestination());
            String name = !tunnelName.isEmpty() ? " for '" + tunnelName + "'" : " for key";

            Job onFound = new JobImpl(getContext()) {
                public String getName() {return "Verify LS Published";}
                public void runJob() {
                    _lookupInProgress.set(false);
                    clearRetryInProgress();
                    LeaseSet local = _facade.lookupLeaseSetLocally(_ls.getHash());
                    if (local != null && KademliaNetworkDatabaseFacade.isNewer(local, ls)) {
                        if (_log.shouldInfo()) {
                            _log.info("Valid LeaseSet" + name + " confirmed via floodfill -> Skipping retry");
                        }
                    } else {
                        if (_log.shouldInfo()) {
                            _log.info("Valid LeaseSet" + name + " not confirmed via floodfill -> Retrying...");
                        }
                        requeueRepublish();
                    }
                }
            };

            Job onFailed = new JobImpl(getContext()) {
                public String getName() {return "Verify LS Failed";}
                public void runJob() {
                    _lookupInProgress.set(false);
                    clearRetryInProgress();
                    if (_log.shouldInfo()) {
                        _log.info("Valid LeaseSet" + name + " not found via floodfill -> Retrying...");
                    }
                    requeueRepublish();
                }
            };

            _facade.lookupLeaseSetRemotely(_ls.getHash(), onFound, onFailed, 10L * 1000, null);
        }
    }

    // Fired on successful store — resets global fail counter and clears
    // the first-publish gate so subsequent renewals proceed immediately.
    /** Fired on successful store to reset counters and log confirmation. */
    private class OnRepublishSuccess extends JobImpl {
        public OnRepublishSuccess() {
            super(RepublishLeaseSetJob.this.getContext());
        }

        public String getName() {return "LeaseSet Publish Succeeded";}

        public void runJob() {
            cleanupStaleEntries();
            _firstDeferredAt.remove(_dest);
            _publishedOnce.add(_dest);
            _globalFailCount.remove(_dest);
            _remintDefers.remove(_dest);
            if (_log.shouldInfo()) {
                long now = getContext().clock().now();
                Long lastLog = _lastPublishLogTime.get(_dest);
                if (lastLog == null || now - lastLog > 10L * 1000) {
                    _log.info("LeaseSet publication confirmed for [" + shortHash() + "]");
                    _lastPublishLogTime.put(_dest, now);
                }
            }
        }
    }

    /**
     * Look up the tunnel nickname for a destination.
     *
     * @param d the destination
     * @return tunnel nickname if configured, or the short base32 hash as fallback
     */
    public String getTunnelName(Destination d) {
        TunnelPoolSettings in = getContext().tunnelManager().getInboundSettings(d.calculateHash());
        String name = (in != null ? in.getDestinationNickname() : null);
        if (name == null) {
            TunnelPoolSettings out = getContext().tunnelManager().getOutboundSettings(d.calculateHash());
            name = (out != null ? out.getDestinationNickname() : null);
        }
        return name != null ? name : "[" + d.calculateHash().toBase32().substring(0,8) + "]";
    }
}
