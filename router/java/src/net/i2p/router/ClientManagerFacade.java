package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.Set;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;

/**
 * Manages client connections and LeaseSet operations for I2P applications. Handles client registration, lease authorization, and session management for local services.
 *
 * @author jrandom
 */
public abstract class ClientManagerFacade implements Service {
/** Property key for client only */
    public static final String PROP_CLIENT_ONLY = "i2cp.dontPublishLeaseSet";

    /**
     * Package-private constructor for abstract class.
     */
    protected ClientManagerFacade() {}

    /**
     * Request that a particular client authorize the Leases contained in the
     * LeaseSet, after which the onCreateJob is queued up.  If that doesn't occur
     * within the timeout specified, queue up the onFailedJob.  This call does not
     * block.
     *
     * @param dest Destination from which the LeaseSet's authorization should be requested
     * @param set LeaseSet with requested leases - this object must be updated to contain the
     *            signed version (as well as any changed/added/removed Leases)
     *
     * @param timeout ms to wait before failing
     * @param onCreateJob Job to run after the LeaseSet is authorized
     * @param onFailedJob Job to run after the timeout passes without receiving authorization
     */
    public abstract void requestLeaseSet(Destination dest, LeaseSet set, long timeout, Job onCreateJob, Job onFailedJob);

    /**
     * Request that the client authorize the Leases contained in the
     * LeaseSet, identified by destination hash.
     *
     * @param dest Hash of the destination
     * @param set LeaseSet with requested leases
     */
    public abstract void requestLeaseSet(Hash dest, LeaseSet set);

    /**
     * Instruct the client (or all clients) that they are under attack.  This call
     * does not block.
     *
     * @param dest Destination under attack, or null if all destinations are affected
     * @param reason Why the router thinks that there is abusive behavior
     * @param severity How severe the abuse is, with 0 being not severe and 255 is the max
     */
    public abstract void reportAbuse(Destination dest, String reason, int severity);
    /**
     * Determine if the destination specified is managed locally.  This call
     * DOES block.
     *
     * @param dest Destination to be checked
     * @return true if the destination is local
     */
    public abstract boolean isLocal(Destination dest);
    /**
     * Determine if the destination hash specified is managed locally.  This call
     * DOES block.
     *
     * @param destHash Hash of Destination to be checked
     * @return true if the destination hash is local
     */
    public abstract boolean isLocal(Hash destHash);

    /**
     *  Update the delivery status of a message.
     *
     *  @param fromDest the source destination of the message
     *  @param id the router's ID for this message
     *  @param messageNonce the client's ID for this message
     *  @param status see I2CP MessageStatusMessage for success/failure codes
     */
    public abstract void messageDeliveryStatusUpdate(Destination fromDest, MessageId id,
                                                     long messageNonce, int status);

    /**
     * Receive a message from the network for a local client.
     *
     * @param msg the received client message
     */
    public abstract void messageReceived(ClientMessage msg);

    /**
     * Verify that the client manager is still alive and responding.
     *
     * @return true if the client manager is alive
     */
    public boolean verifyClientLiveliness() { return true; }
    /**
     * Check whether this service is alive.
     *
     * @return true if alive
     */
    public boolean isAlive() { return true; }
    /**
     * Does the client specified want their leaseSet published?
     *
     * @param destinationHash the destination hash to check
     * @return true if the leaseSet should be published
     */
    public boolean shouldPublishLeaseSet(Hash destinationHash) { return true; }


    /**
     * Return the list of locally connected clients
     *
     * @return set of Destination objects
     */
    public Set<Destination> listClients() { return Collections.emptySet(); }

    /**
     * Return the client's current config, or null if not connected.
     *
     * @param dest the client destination
     * @return the client session config, or null
     */
    public abstract SessionConfig getClientSessionConfig(Destination dest);
    /**
     * The session key manager for a given client.
     *
     * @param dest the destination hash
     * @return the session key manager
     */
    public abstract SessionKeyManager getClientSessionKeyManager(Hash dest);
    /**
     * Render the client status HTML.
     */
    public void renderStatusHTML(Writer out) throws IOException { }

    /**
     * Shut down the client manager with a reason message.
     *
     * @param msg the shutdown reason
     * @since 0.8.8
     */
    public abstract void shutdown(String msg);

    /**
     *  Declare that we're going to publish a meta LS for this destination.
     *  Must be called before publishing the leaseset.
     *
     *  @param dest the destination to register
     *  @throws I2PSessionException on duplicate dest
     *  @since 0.9.41
     */
    public void registerMetaDest(Destination dest) throws I2PSessionException {}

    /**
     *  Declare that we're no longer going to publish a meta LS for this destination.
     *
     *  @param dest the destination to unregister
     *  @since 0.9.41
     */
    public void unregisterMetaDest(Destination dest) {}

    /**
     * The FloodfillNetworkDatabaseFacade associated with a particular client destination.
     * This is inside the runner, so it won't be there if the runner isn't ready.
     *
     * @param destHash destination hash associated with the client who's subDb we're looking for
     * @return non-null FloodfillNetworkDatabaseFacade
     * @since 0.9.61
     */
    public abstract FloodfillNetworkDatabaseFacade getClientFloodfillNetworkDatabaseFacade(Hash destHash);

    /**
     * A set of all primary hashes.
     *
     * @return non-null set of Hashes
     * @since 0.9.61
     */
    public abstract Set<Hash> getPrimaryHashes();
}
