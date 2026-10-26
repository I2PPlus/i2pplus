package net.i2p.router.networkdb.kademlia;

import net.i2p.data.Hash;
import net.i2p.router.NetworkDatabaseFacade;

/**
 * This class implements an interface for managing many netDbs as part of a
 * single I2P instance, each representing its own view of the network. This
 * allows the I2P clients to operate agnostic of what information the other
 * clients are obtaining from the network database, and prevents information
 * intended for clients from entering the table used by the router for
 * Floodfill operations.
 * <p>
 * The benefit of this is that we can use this to provide an effective barrier
 * against "Context-Confusion" attacks which exploit the fact that messages sent
 * to clients can update the routing table used by a floodfill, providing
 * evidence that the floodfill hosts the corresponding client. When applied
 * correctly, so that every client uses a unique subDb, the entire class of attack
 * should be neutralized.
 * <p>
 * The drawback of this is that it makes the netDb less efficient. Clients and
 * Floodfills who share a netDb can update the tables used by those netDbs when
 * a Client encounters an entry obtained by a Floodfill or vice-versa. Clients also
 * must sometimes search the netDb for keys that are owned by other clients or by
 * a co-located Floodfill, if one exists.
 * <p>
 * In some contexts, it makes sense to view all the tables at once, especially when
 * viewing information from the UI. The functions 'getLeases*', 'getRouters*', and
 * 'getLocalClient*' are provided for this purpose.
 * <p>
 * In the future, this could also be extended to provide Whanau-like functionality
 * by determining when clients and the local floodfill disagree on the content of a
 * leaseSet.
 * <p>
 * See implementation: FloodfillNetworkDatabaseSegmentor.
 *
 * @since 0.9.61
 */
public abstract class SegmentedNetworkDatabaseFacade {
    /**
     * Constructor for segmented NetDb implementations, which supply their segments after construction.
     */
    public SegmentedNetworkDatabaseFacade() {}


    /**
     * The main netDb, the one which is used if we're a floodfill.
     *
     * @return may be null if main netDb is not initialized
     * @since 0.9.61
     */
    public abstract NetworkDatabaseFacade mainNetDB();

    /**
     * A client netDb for a given client Hash identifier. Implementations
     * fall back to the main netDb when no client sub-database exists.
     *
     * @param dbid the destination hash naming which client sub-database to use
     * @return the client netDb, or the main netDb as a fallback; may be null
     *         if that netDb is not initialized
     * @since 0.9.61
     */
    public abstract NetworkDatabaseFacade clientNetDB(Hash dbid);

    /**
     * Shut down the network databases.
     *
     * @since 0.9.61
     */
    public abstract void shutdown();

    /**
     * Start up the network databases.
     *
     * @since 0.9.61
     */
    public abstract void startup();
}
