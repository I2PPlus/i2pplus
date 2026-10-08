package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.router.tunnel.pool.GhostPeerManager;
import net.i2p.router.tunnel.pool.TunnelPool;

/**
 * Manages tunnel creation, maintenance, and selection for encrypted routing. Coordinates exploratory and client-specific tunnels for secure message delivery.
 *
 */
public interface TunnelManagerFacade extends Service {

    /**
     * Pick a random inbound exploratory tunnel
     *
     * @return null if none
     */
    TunnelInfo selectInboundTunnel();

    /**
     * Pick a random inbound tunnel from the given destination's pool
     *
     * @param destination if null, returns inbound exploratory tunnel
     * @return null if none
     */
    TunnelInfo selectInboundTunnel(Hash destination);

    /**
     * Pick a random outbound exploratory tunnel
     *
     * @return null if none
     */
    TunnelInfo selectOutboundTunnel();

    /**
     * Pick a random outbound tunnel from the given destination's pool
     *
     * @param destination if null, returns outbound exploratory tunnel
     * @return null if none
     */
    TunnelInfo selectOutboundTunnel(Hash destination);

    /**
     * Pick a random outbound tunnel from any pool — client pools first,
     * then exploratory.
     *
     * @return null if none
     */
    TunnelInfo selectAnyOutboundTunnel();

    /**
     * Pick a random inbound tunnel from any pool — client pools first,
     * then exploratory.
     *
     * @return null if none
     */
    TunnelInfo selectAnyInboundTunnel();

    /**
     * Pick the inbound exploratory tunnel with the gateway closest to the given hash.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectInboundExploratoryTunnel(Hash closestTo);

    /**
     * Pick the inbound tunnel with the gateway closest to the given hash
     * from the given destination's pool.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param destination if null, returns inbound exploratory tunnel
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectInboundTunnel(Hash destination, Hash closestTo);

    /**
     * Pick the outbound exploratory tunnel with the endpoint closest to the given hash.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectOutboundExploratoryTunnel(Hash closestTo);

    /**
     * Pick the outbound tunnel with the endpoint closest to the given hash
     * from the given destination's pool.
     * By using this instead of the random selectTunnel(),
     * we force some locality in OBEP-IBGW connections to minimize
     * those connections network-wide.
     *
     * @param destination if null, returns outbound exploratory tunnel
     * @param closestTo non-null
     * @return null if none
     * @since 0.8.10
     */
    public TunnelInfo selectOutboundTunnel(Hash destination, Hash closestTo);

    /**
     * Is a tunnel a valid member of the pool?
     *
     * @param client hash of the destination whose pool is checked
     * @param tunnel candidate, rejected if failed or expired
     * @return true only if unfailed, unexpired and listed in that client's
     *              inbound or outbound pool, matching the tunnel's direction
     */
    public boolean isValidTunnel(Hash client, TunnelInfo tunnel);

    /**
     * How many tunnels are we participating in?
     *
     * @return number of tunnels where we are a gateway or an endpoint
     */
    public int getParticipatingCount();
    /**
     * How many free inbound tunnels do we have available?
     *
     * @return number of valid, unfailed inbound exploratory tunnels
     */
    public int getFreeTunnelCount();
    /**
     * How many outbound tunnels do we have available?
     *
     * @return number of valid, unfailed outbound exploratory tunnels
     */
    public int getOutboundTunnelCount();
    /**
     * How many free inbound client tunnels do we have available?
     *
     * @return total over all destinations of GOOD (tested and passed)
     *               inbound client tunnels
     */
    public int getInboundClientTunnelCount();
    /**
     * How many outbound client tunnels do we have available?
     *
     * @return total over all destinations of GOOD (tested and passed)
     *               outbound client tunnels
     */
    public int getOutboundClientTunnelCount();
    /**
     * How many outbound client tunnels in this pool?
     *
     * @param destination hash of the destination owning the outbound pool
     * @return GOOD tunnel count, or the count including untested tunnels
     *              while bootstrapping, or 0 if no pool is registered
     */
    public int getOutboundClientTunnelCount(Hash destination);
    /**
     * How many inbound client tunnels in this pool?
     *
     * @param destination hash of the destination owning the inbound pool
     * @return GOOD tunnel count, or the count including untested tunnels
     *              while bootstrapping, or 0 if no pool is registered
     */
    public int getInboundClientTunnelCount(Hash destination);
    /**
     * The share ratio of tunnel bandwidth allocated to the network.
     *
     * @return the share ratio
     */
    public double getShareRatio();

    /**
     * When does the last tunnel we are participating in expire?
     *
     * @return expiration of the latest-expiring participating tunnel in
     *                    milliseconds since the epoch, or -1 if we participate in none
     */
    public long getLastParticipatingExpiration();

    /**
     * Count how many inbound tunnel requests we have received but not yet processed
     *
     * @return number of inbound build requests waiting in the queue
     */
    public int getInboundBuildQueueSize();

    /**
     *  Peers that should not be allowed to be in another tunnel.
     *
     *  @return Set of peers that should not be allowed to be in another tunnel
     */
    public Set<Hash> selectPeersInTooManyTunnels();

    /**
     * The client connected (or updated their settings), so make sure we have the tunnels
     * for them, and whenever necessary, ask them to authorize leases.
     *
     * @param client connecting destination, whose hash keys the pools created here
     * @param settings per-destination inbound and outbound pool settings, which
     *                 replace those of any pools that already exist
     */
    public void buildTunnels(Destination client, ClientTunnelSettings settings);

    /**
     *  Must be called AFTER deregistration by the client manager.
     *
     *  @param client destination whose pools are scheduled for removal, deferred
     *                so its tunnels keep operating until they expire
     *  @since 0.9.48
     */
    public void removeTunnels(Destination client);

    /**
     *  Add another destination to the same tunnels.
     *  Must have same encryption key and a different signing key.
     *
     *  @param dest alias destination, which gets its own pools flagged as aliased
     *  @param settings settings for the alias pools, retained under the primary hash
     *  @param existingClient primary destination whose tunnels are being shared
     *  @throws IllegalArgumentException if not
     *  @return success
     *  @since 0.9.21
     */
    public boolean addAlias(Destination dest, ClientTunnelSettings settings, Destination existingClient);

    /**
     *  Remove another destination to the same tunnels.
     *
     *  @param dest alias destination to drop from the primary's alias set
     *  @since 0.9.21
     */
    public void removeAlias(Destination dest);

    /**
     * The inbound settings for the exploratory pool.
     *
     * @return the inbound settings
     */
    public TunnelPoolSettings getInboundSettings();
    /**
     * The outbound settings for the exploratory pool.
     *
     * @return the outbound settings
     */
    public TunnelPoolSettings getOutboundSettings();
    /**
     * The inbound settings for the given client.
     *
     * @param client the client hash
     * @return the inbound settings
     */
    public TunnelPoolSettings getInboundSettings(Hash client);
    /**
     * The outbound settings for the given client.
     *
     * @param client the client hash
     * @return the outbound settings
     */
    public TunnelPoolSettings getOutboundSettings(Hash client);
    /**
     * The inbound settings for the exploratory pool.
     *
     * @param settings the inbound settings
     */
    public void setInboundSettings(TunnelPoolSettings settings);
    /**
     * The outbound settings for the exploratory pool.
     *
     * @param settings the outbound settings
     */
    public void setOutboundSettings(TunnelPoolSettings settings);
    /**
     * The inbound settings for the given client.
     *
     * @param client the client hash
     * @param settings the inbound settings
     */
    public void setInboundSettings(Hash client, TunnelPoolSettings settings);
    /**
     * The outbound settings for the given client.
     *
     * @param client the client hash
     * @param settings the outbound settings
     */
    public void setOutboundSettings(Hash client, TunnelPoolSettings settings);
    /**
     * For TunnelRenderer in router console
     *
     * @param out list that the client inbound, client outbound and both
     *            exploratory pools are appended to
     */
    public void listPools(List<TunnelPool> out);
    /**
     * For TunnelRenderer in router console
     *
     * @return snapshot of the inbound pools, keyed by destination hash
     */
    public Map<Hash, TunnelPool> getInboundClientPools();
    /**
     * For TunnelRenderer in router console
     *
     * @return snapshot of the outbound pools, keyed by destination hash
     */
    public Map<Hash, TunnelPool> getOutboundClientPools();
    /**
     * For TunnelRenderer in router console
     *
     * @return the shared inbound exploratory pool, never null
     */
    public TunnelPool getInboundExploratoryPool();
    /**
     * For TunnelRenderer in router console
     *
     * @return the shared outbound exploratory pool, never null
     */
    public TunnelPool getOutboundExploratoryPool();

    /**
     *  Inbound tunnel pool for the given client, or null if none.
     *
     *  @param client hash of the destination that owns the pool
     *  @return pool or null
     *  @since 0.9.34
     */
    public TunnelPool getInboundPool(Hash client);

    /**
     *  Outbound tunnel pool for the given client, or null if none.
     *
     *  @param client hash of the destination that owns the pool
     *  @return pool or null
     *  @since 0.9.34
     */
    public TunnelPool getOutboundPool(Hash client);

    /**
     *  Run both of a client's pools' ensure logic on demand instead of
     *  waiting for the next build-timer interval.  Called from the data
     *  phase when a send failed for pool-empty reasons (message expired
     *  while queued, no tunnels available) — a liveness signal that the
     *  pools need rebuilding now.  Each pool's internal ensure throttle
     *  still applies, so a failure storm cannot become a build storm.
     *
     *  <p>The pool maps are keyed by the <b>local</b> client (source)
     *  destination hash, never the remote destination: passing a remote
     *  hash finds no pools and silently does nothing.
     *
     *  <p>Additive ABI: this method and its {@code int} return both postdate
     *  0.9.70+ (no release ever shipped a {@code void} variant), so
     *  implementations and callers only ever saw this signature; callers may
     *  still ignore the returned count.
     *
     *  @param client the LOCAL client destination hash whose pools should rebuild
     *  @return how many pools were nudged; 0 when none are registered for the client
     *  @since 0.9.71+
     */
    public int ensurePoolsFor(Hash client);

    /**
     * Fail every tunnel that depends on a peer we can no longer reach:
     * outbound tunnels with it as first hop, inbound tunnels with it as last hop.
     *
     * @param peer hash of the unreachable peer
     * @since 0.8.13
     */
    public void fail(Hash peer);

    /**
     * The ghost peer manager for tracking peers with consistent tunnel build timeouts.
     *
     * @return the GhostPeerManager instance
     * @since 0.9.68+
     */
    public GhostPeerManager getGhostPeerManager();

    /**
     * Report a data-phase send failure for a specific tunnel.
     * Called from the I2CP message dispatch path when a message fails
     * through a known tunnel (e.g. expired, local overflow, no tunnels
     * available).  Increments the tunnel's failure counter and triggers
     * replacement builds when the threshold is reached, so the pool
     * rotates away from the failing tunnel faster than TestJob alone.
     *
     * @param tunnel the outbound tunnel that carried the failed message
     * @param status the I2CP MessageStatusMessage failure code
     * @since 0.9.71+
     */
     public void reportSendFailure(TunnelInfo tunnel, int status);

    /**
     * Force a tunnel to fail immediately and trigger a replacement build.
     * Used when rotation is saturated and the pool has no viable
     * alternative tunnels (e.g., all tunnels in the pool match the cached tunnel).
     * This bypasses the incremental failure counter and directly removes
     * the tunnel, ensuring the pool builds a replacement without waiting
     * for multiple failure reports.
     *
     * @param tunnel the outbound tunnel to force-fail
     * @since 0.9.71+
     */
    public void forceTunnelFailure(TunnelInfo tunnel);
}
