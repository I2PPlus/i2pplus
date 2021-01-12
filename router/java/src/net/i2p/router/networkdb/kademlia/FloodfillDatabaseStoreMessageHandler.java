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

/**
 * Create a HandleDatabaseStoreMessageJob whenever a DatabaseStoreMessage arrives
 *
 */
public class FloodfillDatabaseStoreMessageHandler implements HandlerJobBuilder {
    private RouterContext _context;
    private FloodfillNetworkDatabaseFacade _facade;

    public FloodfillDatabaseStoreMessageHandler(RouterContext context, FloodfillNetworkDatabaseFacade facade) {
        _context = context;
        _facade = facade;
        // following are for HFDSMJ
        context.statManager().createRateStat("netDb.storeHandled", "Number of DbStore messages we have handled", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        context.statManager().createRateStat("netDb.storeLeaseSetHandled", "Number of LeaseSet store messages we have handled", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        context.statManager().createRateStat("netDb.storeRouterInfoHandled", "Number of RouterInfo store messages we have handled", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        context.statManager().createRateStat("netDb.storeRecvTime", "Time to handle the local store part of a DbStore", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
    }

    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        Job j = new HandleFloodfillDatabaseStoreMessageJob(_context, (DatabaseStoreMessage)receivedMessage, from, fromHash, _facade);
        if (false) {
            j.runJob();
            return null;
        } else {
            return j;
        }
    }
}
