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
 * Manage all interactions with clients
 *
 * @author jrandom
 */
public abstract class ClientManagerFacade implements Service {
    public static final String PROP_CLIENT_ONLY = "i2cp.dontPublishLeaseSet";

    /**
     * Request that a particular client authorize the Leases contained in the
     * LeaseSet, after which the onCreateJob is queued up.  If that doesn't occur
     * within the timeout specified, queue up the onFailedJob.  This call does not
     * block.
     *
     * @param dest Destination from which the LeaseSet's authorization should be requested
     * @param set LeaseSet with requested leases - this object must be updated to contain the
     *            signed version (as well as any changed/added/removed Leases)
     * @param timeout ms to wait before failing
     * @param onCreateJob Job to run after the LeaseSet is authorized
     * @param onFailedJob Job to run after the timeout passes without receiving authorization
     */
    public abstract void requestLeaseSet(Destination dest, LeaseSet set, long timeout, Job onCreateJob, Job onFailedJob);

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
     */
    public abstract boolean isLocal(Destination dest);
    /**
     * Determine if the destination hash specified is managed locally.  This call
     * DOES block.
     *
     * @param destHash Hash of Destination to be checked
     */
    public abstract boolean isLocal(Hash destHash);

    /**
     *  @param id the router's ID for this message
     *  @param messageNonce the client's ID for this message
     *  @param status see I2CP MessageStatusMessage for success/failure codes
     */
    public abstract void messageDeliveryStatusUpdate(Destination fromDest, MessageId id,
                                                     long messageNonce, int status);

    public abstract void messageReceived(ClientMessage msg);

    public boolean verifyClientLiveliness() { return true; }
    public boolean isAlive() { return true; }
    /**
     * Does the client specified want their leaseSet published?
     */
    public boolean shouldPublishLeaseSet(Hash destinationHash) { return true; }


    /**
     * Return the list of locally connected clients
     *
     * @return set of Destination objects
     */
    public Set<Destination> listClients() { return Collections.emptySet(); }

    /**
     * Return the client's current config, or null if not connected
     *
     */
    public abstract SessionConfig getClientSessionConfig(Destination dest);
    public abstract SessionKeyManager getClientSessionKeyManager(Hash dest);
    public void renderStatusHTML(Writer out) throws IOException { }

    /** @since 0.8.8 */
    public abstract void shutdown(String msg);

    /**
     *  Declare that we're going to publish a meta LS for this destination.
     *  Must be called before publishing the leaseset.
     *
     *  @throws I2PSessionException on duplicate dest
     *  @since 0.9.41
     */
    public void registerMetaDest(Destination dest) throws I2PSessionException {}

    /**
     *  Declare that we're no longer going to publish a meta LS for this destination.
     *
     *  @since 0.9.41
     */
    public void unregisterMetaDest(Destination dest) {}

    /**
     * get the FloodfillNetworkDatabaseFacade associated with a particular client destination.
     * This is inside the runner, so it won't be there if the runner isn't ready.
     * 
     * @param destHash destination hash associated with the client who's subDb we're looking for
     * @return non-null FloodfillNetworkDatabaseFacade
     * @since 0.9.60
     */
    public abstract FloodfillNetworkDatabaseFacade getClientFloodfillNetworkDatabaseFacade(Hash destHash);

    /**
     * get all of the FloodfillNetworkDatabaseFacades for all of the clients.
     * 
     * @return non-null set of FloodfillNetworkDatabaseFacades
     * @since 0.9.60
     */
    public abstract Set<FloodfillNetworkDatabaseFacade> getClientFloodfillNetworkDatabaseFacades();

    /**
     * get a set of all primary hashes
     * 
     * @return non-null set of Hashes
     * @since 0.9.60
     */
    public abstract Set<Hash> getPrimaryHashes();
}
