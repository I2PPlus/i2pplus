package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.internal.I2CPMessageQueue;
import net.i2p.internal.InternalClientManager;
import net.i2p.router.ClientManagerFacade;
import net.i2p.router.ClientMessage;
import net.i2p.router.Job;

import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.util.Log;

/**
 * Base impl of the client facade.
 *
 */
public class ClientManagerFacadeImpl extends ClientManagerFacade implements InternalClientManager {
    /** Logger for this class. */
    private final Log _log;
    /** The manager doing the work, null while rebuilding. */
    private ClientManager _manager;
    /** The router running the clients. */
    private final RouterContext _context;
    /** Throttle lease expiration logging - only log once per client per interval. */
    private static final long LOG_THROTTLE = 10L * 60 * 1000; // 10 minutes
    /** When each client's expiration was last logged. */
    private final ConcurrentHashMap<Hash, Long> _lastExpiredLogTime = new ConcurrentHashMap<>();
    /** Note that this is different than the property the client side uses, {@code i2cp.tcp.port}. */
    public static final String PROP_CLIENT_PORT = "i2cp.port";
    /** Default port. */
    public static final int DEFAULT_PORT = I2PClient.DEFAULT_LISTEN_PORT;
    /** Note that this is different than the property the client side uses, {@code i2cp.tcp.host}. */
    public static final String PROP_CLIENT_HOST = "i2cp.hostname";
    /** Default host. */
    public static final String DEFAULT_HOST = "127.0.0.1";
    /**
     * Creates the facade without starting anything; call {@link #startup()} to listen for I2CP connections.
     * @param context the router context this facade and the manager it creates are scoped to
     */
    public ClientManagerFacadeImpl(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(ClientManagerFacadeImpl.class);
    }

    /**
     * Start the client manager to accept I2CP connections.
     */
    @Override
    public synchronized void startup() {
        _log.info("Starting up the Router Client Manager...");
        int port = _context.getProperty(PROP_CLIENT_PORT, DEFAULT_PORT);
        _manager = new ClientManager(_context, port);
        _manager.start();
    }

    /**
     * Shut down the client manager, disconnecting all clients.
     */
    @Override
    public synchronized void shutdown() {shutdown("Router shutdown");}

    /**
     * Disconnect all clients with the given error message.
     *
     * @param msg message to send to the clients
     * @since 0.8.8
     */
    public synchronized void shutdown(String msg) {
        if (_manager != null) {_manager.shutdown(msg);}
    }
    /** Restart the service. */

    public synchronized void restart() {
        if (_manager != null) {_manager.restart();}
        else {startup();}
    }

    /**
     * Whether the client manager is running.
     *
     * @return true if the manager exists and is running
     */
    @Override
    public boolean isAlive() {return _manager != null && _manager.isAlive();}

    /** Longest to wait for a rebuild - up to 10 minutes. */
    private static final long MAX_TIME_TO_REBUILD = 10L*60*1000;

    /**
     * Minimum time to lease expiry across all clients.
     * Used by RouterWatchdog to adjust the sleep interval.
     *
     * @return minimum time to expiry in milliseconds, or Long.MAX_VALUE if no leases
     */
    public long getMinTimeToLeaseExpiry() {
        if (_manager == null) return Long.MAX_VALUE;
        long now = _context.clock().now();
        long minTimeToExpiry = Long.MAX_VALUE;

        for (Destination dest : _manager.getRunnerDestinations()) {
            ClientConnectionRunner runner = _manager.getRunner(dest);
            if ((runner == null) || (runner.getIsDead())) {continue;}
            LeaseSet ls = runner.getLeaseSet(dest.calculateHash());
            if (ls == null) {continue;}

            long latestLeaseDate = ls.getLatestLeaseDate();
            long timeToExpiry = latestLeaseDate - now;
            if (timeToExpiry > 0 && timeToExpiry < minTimeToExpiry) {
                minTimeToExpiry = timeToExpiry;
            }
        }

        return minTimeToExpiry;
    }

    /**
     * Verify that client lease sets are still live, requesting renewal when
     * they have expired or are about to expire.
     */
    @Override
    public boolean verifyClientLiveliness() {
        if (_manager == null) return true;
        boolean lively = true;
        long now = _context.clock().now();
        long renewalWindow = 90L * 1000; // 90s before expiration
        // Destination hashes with a live runner this pass, used below to prune
        // the log-throttle map so it cannot outgrow the set of real clients.
        Set<Hash> active = new HashSet<>();

        for (Destination dest : _manager.getRunnerDestinations()) {
            ClientConnectionRunner runner = _manager.getRunner(dest);
            if ((runner == null) || (runner.getIsDead())) {continue;}
            LeaseSet ls = runner.getLeaseSet(dest.calculateHash());
            if (ls == null) {continue;} // still building

            long latestLeaseDate = ls.getLatestLeaseDate();
            long earliestLeaseDate = ls.getEarliestLeaseDate();
            long timeToExpiration = earliestLeaseDate - now; // Use earliest for proactive renewal
            long timeSinceExpiration = now - latestLeaseDate; // Use latest to check if ALL expired

            // Check if ALL leases are expired (use latestLeaseDate)
            if (latestLeaseDate < now) {
                // All leases have expired
                Hash destHash = dest.calculateHash();
                active.add(destHash);
                long lastLog = _lastExpiredLogTime.getOrDefault(destHash, 0L);
                boolean shouldLog = (now - lastLog) > LOG_THROTTLE;

                if (timeSinceExpiration > MAX_TIME_TO_REBUILD) {
                    if (shouldLog) {
                        if (_log.shouldError()) {
                            _log.error("Client [" + dest.toBase32().substring(0,8) + "] has LeaseSet that expired " +
                                       DataHelper.formatDuration(timeSinceExpiration) + " ago");
                        }
                        _lastExpiredLogTime.put(destHash, now);
                    }
                    lively = false;
                }
                // Request renewal immediately
                if (_manager != null) {
                    if (_log.shouldDebug()) {
                        _log.debug("Requesting LeaseSet renewal for [" + dest.toBase32().substring(0,8) + "]  -> All leases expired");
                    }
                    _manager.requestLeaseSet(dest.calculateHash(), null);
                }
            }
            // Check if we should proactively renew (90s before expiration)
            else if (timeToExpiration < renewalWindow) {
                // At least one lease is still valid, but we're within renewal window
                if (_log.shouldInfo()) {
                    _log.info("Client [" + dest.toBase32().substring(0,8) + "] leases expiring in " +
                              DataHelper.formatDuration(timeToExpiration) + " -> Requesting renewal...");
                }
                if (_manager != null) {
                    _manager.requestLeaseSet(dest.calculateHash(), null);
                }
            }
        }
        // The throttle only matters for clients that still have a runner; drop
        // entries for destinations that have gone away so the map stays bounded
        // by the live client set instead of every client ever seen.
        pruneStaleExpiredLogEntries(_lastExpiredLogTime, active);
        return lively;
    }

    /**
     * Remove lease-expiration log-throttle entries whose destination no longer
     * has a live runner. The throttle is only consulted for currently-connected
     * clients (see {@link #verifyClientLiveliness}), so an entry for a
     * disconnected destination can never be read again — retaining it would let
     * the map grow with every I2CP client the router has ever served.
     *
     * @param map    the throttle map to prune in place
     * @param active the destination hashes that currently have a live runner
     * @return the number of stale entries removed
     * @since 0.9.71+
     */
    static int pruneStaleExpiredLogEntries(ConcurrentHashMap<Hash, Long> map, Set<Hash> active) {
        int before = map.size();
        map.keySet().retainAll(active);
        return before - map.size();
    }

    /**
     * Request that a particular client authorize the Leases contained in the LeaseSet,
     * after which the onCreateJob is queued up.  If that doesn't occur within the timeout
     * specified, queue up the onFailedJob.  This call does not block.
     * <p>
     * UNUSED, the call below without jobs is always used.
     *
     * @param dest Destination from which the LeaseSet's authorization should be requested
     * @param set LeaseSet with requested leases - this object must be updated to contain the
     *            signed version (as well as any changed/added/removed Leases)
     *            The LeaseSet contains Leases only; it is unsigned and does not have the destination set.
     *
     * @param timeout ms to wait before failing
     * @param onCreateJob Job to run after the LeaseSet is authorized
     * @param onFailedJob Job to run after the timeout passes without receiving authorization
     */
    @Override
    public void requestLeaseSet(Destination dest, LeaseSet set, long timeout, Job onCreateJob, Job onFailedJob) {
        if (_manager != null) {_manager.requestLeaseSet(dest, set, timeout, onCreateJob, onFailedJob);}
        else {_log.error("No client manager -> Cannot request a LeaseSet");}
    }

    /**
     * Request that a particular client authorize the Leases contained in the LeaseSet.
     *
     * @param dest Destination from which the LeaseSet's authorization should be requested
     * @param set LeaseSet with requested leases - this object must be updated to contain the
     *            signed version (as well as any changed/added/removed Leases).
     *            The LeaseSet contains Leases only; it is unsigned and does not have the destination set.
     */
    @Override
    public void requestLeaseSet(Hash dest, LeaseSet set) {
        if (_manager != null) {_manager.requestLeaseSet(dest, set);}
    }

    /**
     * Instruct the client (or all clients) that they are under attack.  This call does not block.
     *
     * @param dest Destination under attack, or null if all destinations are affected
     * @param reason Why the router thinks that there is abusive behavior
     * @param severity How severe the abuse is, with 0 being not severe and 255 is the max
     */
    @Override
    public void reportAbuse(Destination dest, String reason, int severity) {
        if (_manager != null) {_manager.reportAbuse(dest, reason, severity);}
        else if (_log.shouldError()) {_log.error("No client manager -> Cannot report abuse");}
    }

    /**
     * Determine if the destination specified is managed locally.  This call DOES block.
     *
     * @param dest Destination to be checked
     * @return true if a local client is running for that destination
     */
    public boolean isLocal(Destination dest) {
        if (_manager != null) {return _manager.isLocal(dest);}
        else if (_log.shouldDebug()) {_log.debug("No client manager -> Destination is not local");}
        return false;
    }

    /**
     * Determine if the destination specified is managed locally.  This call DOES block.
     *
     * @param destHash Hash of Destination to be checked
     * @return true if a local client is running for that destination
     */
    @Override
    public boolean isLocal(Hash destHash) {
        if (_manager != null) {return _manager.isLocal(destHash);}
        else if (_log.shouldDebug()) {_log.debug("No client manager -> Destination is not local");}
        return false;
    }

    /**
     * Whether the lease set for the destination should be published.
     *
     * @return whether publish lease set
     */
    @Override
    public boolean shouldPublishLeaseSet(Hash destinationHash) {
        return _manager.shouldPublishLeaseSet(destinationHash);
    }

    /**
     * Report whether a client message was delivered or lost.
     *
     * @param id the router's ID for this message
     * @param messageNonce the client's ID for this message, greater than zero
     * @param status see I2CP MessageStatusMessage for success/failure codes
     */
    @Override
    public void messageDeliveryStatusUpdate(Destination fromDest, MessageId id, long messageNonce, int status) {
        if (_manager != null) {_manager.messageDeliveryStatusUpdate(fromDest, id, messageNonce, status);}
        else if (_log.shouldError()) {_log.error("No client manager -> Dropping delivery status update");}
    }
    public void messageReceived(ClientMessage msg) {
        if (_manager != null) {_manager.messageReceived(msg);}
        else if (_log.shouldError()) {_log.error("No client manager -> Dropping client message");}
    }

    /**
     * Return the client's current config, or null if not connected.
     *
     * @return the client session config
     */
    public SessionConfig getClientSessionConfig(Destination dest) {
        if (_manager != null) {return _manager.getClientSessionConfig(dest);}
        else if (_log.shouldError()) {_log.error("No client manager -> Returning no session config");}
        return null;
    }

    /**
     * Return the client's current manager or null if not connected.
     *
     * @return the client session key manager
     */
    @Override
    public SessionKeyManager getClientSessionKeyManager(Hash dest) {
        if (_manager != null) {return _manager.getClientSessionKeyManager(dest);}
        else if (_log.shouldError()) {_log.error("No client manager -> Returning no session key manager");}
        return null;
    }

    /**
     * Return the list of locally connected clients.
     *
     * @return set of Destination objects
     */
    @Override
    public Set<Destination> listClients() {
        if (_manager != null) {return _manager.listClients();}
        else {return Collections.emptySet();}
    }

    /**
     * The InternalClientManager interface.
     * Connect to the router, receiving a message queue to talk to the router with.
     *
     * @throws I2PSessionException if the router isn't ready
     * @since 0.8.3
     */
    public I2CPMessageQueue connect() throws I2PSessionException {
        if (_manager != null) {return _manager.internalConnect();}
        throw new I2PSessionException("No Client Manager yet");
    }

    /**
     * Declare that we're going to publish a meta LS for this destination.
     * Must be called before publishing the leaseset.
     *
     * @throws I2PSessionException on duplicate dest
     * @since 0.9.41
     */
    @Override
    public void registerMetaDest(Destination dest) throws I2PSessionException {
        if (_manager != null) {_manager.registerMetaDest(dest);}
    }

    /**
     * Declare that we're no longer going to publish a meta LS for this destination.
     *
     * @since 0.9.41
     */
    @Override
    public void unregisterMetaDest(Destination dest) {
        if (_manager != null) {_manager.unregisterMetaDest(dest);}
    }

    /**
     * The FloodfillNetworkDatabaseFacade associated with a particular client destination.
     * This is inside the runner, so it won't be there if the runner isn't ready.
     *
     * @param destHash destination hash associated with the client who's subDb we're looking for
     * @return the netdb or null
     * @since 0.9.61
     */
    @Override
    public FloodfillNetworkDatabaseFacade getClientFloodfillNetworkDatabaseFacade(Hash destHash) {
        if (_manager != null) {return _manager.getClientFloodfillNetworkDatabaseFacade(destHash);}
        else {return null;}
    }

    /**
     * All the primary hashes for all the clients as a set.
     *
     * @return all the hashes or an empty set, non-null
     * @since 0.9.61
     */
    @Override
    public Set<Hash> getPrimaryHashes() {
        if (_manager != null) {return _manager.getPrimaryHashes();}
        else {return Collections.emptySet();}
    }

    // ==================== Tuner delegation ====================

    /**
     * Gets the cap on the client writer's outgoing message queue.
     * @return the current client writer queue limit, in messages
     * @since 0.9.70+
     */
    public static int getWriterQueueSize() { return ClientWriterRunner.getQueueSize(); }

    /**
     * Sets the cap on the client writer's outgoing message queue.
     * @param val the requested queue limit in messages, clamped to 32..2048
     * @since 0.9.70+
     */
    public static void setWriterQueueSize(int val) { ClientWriterRunner.setQueueSize(val); }

}
