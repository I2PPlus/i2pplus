package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.util.RandomSource;

/**
 * Handles incoming DatabaseStoreMessage for floodfill network database operations.
 * <p>
 * Creates and dispatches HandleFloodfillDatabaseStoreMessageJob instances to process
 * database store requests received from remote peers. Routes messages to the appropriate
 * network database facade based on the received-by field, supporting both floodfill
 * and client database contexts.
 * <p>
 * Maintains statistics for store operations including handling counts, processing times,
 * and lease-specific metrics. Provides the entry point for all floodfill database
 * store message processing in the Kademlia network database system.
 */
public class FloodfillDatabaseStoreMessageHandler implements HandlerJobBuilder {
    private RouterContext _context;
    private FloodfillNetworkDatabaseFacade _facade;
    private final long _msgIDBloomXor = RandomSource.getInstance().nextLong(I2NPMessage.MAX_ID_VALUE);

    public FloodfillDatabaseStoreMessageHandler(RouterContext context, FloodfillNetworkDatabaseFacade facade) {
        _context = context;
        _facade = facade;
        // following are for HFDSMJ
        context.statManager().createRateStat("netDb.storeHandled", "Number of DbStore messages we have handled", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        context.statManager().createRateStat("netDb.storeLeaseSetHandled", "Number of LeaseSet store messages we have handled", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        context.statManager().createRateStat("netDb.storeRouterInfoHandled", "Number of RouterInfo store messages we have handled", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        context.statManager().createRateStat("netDb.storeRecvTime", "Time to handle the local store part of a DbStore", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        context.statManager().createRateStat("netDb.storeLocalLeaseSetAttempt", "Number of attempted local LeaseSet store messages", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
    }

    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        DatabaseStoreMessage dsm = (DatabaseStoreMessage)receivedMessage;
        // store to client db if received by that client
        Hash by = dsm.getEntry().getReceivedBy();
        FloodfillNetworkDatabaseFacade netdb;
        if (by != null)
            netdb = (FloodfillNetworkDatabaseFacade) _context.clientNetDb(by);
        else
            netdb = _facade;
        Job j = new HandleFloodfillDatabaseStoreMessageJob(_context, dsm, from, fromHash, netdb, _msgIDBloomXor);
        return j;
    }
}
