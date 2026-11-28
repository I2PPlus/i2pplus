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
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterIdentity;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;
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
    /** The router context handling the store. */
    private RouterContext _context;
    /** The store the profile is checked against. */
    private FloodfillNetworkDatabaseFacade _facade;
    /** Random mask hiding which message ids were seen. */
    private final long _msgIDBloomXor = RandomSource.getInstance().nextLong(I2NPMessage.MAX_ID_VALUE);
    /**
     * Periods for the store counters - one minute, the window the console and the
     * Tuner rules read them on.
     */
    private static final long[] RATES = { RateConstants.ONE_MINUTE };

    /**
     * Create a handler for floodfill database store messages.
     *
     * @param context the router context
     * @param facade the floodfill network database facade
     */
    public FloodfillDatabaseStoreMessageHandler(RouterContext context, FloodfillNetworkDatabaseFacade facade) {
        _context = context;
        _facade = facade;
        // following are for HFDSMJ
        registerStats(context.statManager());
    }

    /**
     * Create the counters the store job writes.
     * <p>
     * {@link net.i2p.stat.StatManager#addRateData(String, long)} looks the name
     * up and drops the write when no such stat was created, so a counter written
     * without its registration reports nothing while looking like a working one.
     * The refused-local-lease-set counter was in that state: the job wrote it on
     * every store of our own client's LeaseSet to the subDB that owns it, and
     * every sample was thrown away, so the misbehaving client's stores were
     * invisible while the job's other store counters reported normally. The name
     * is a constant on the job, shared by its write site and this registration,
     * so the two cannot drift apart.
     * <p>
     * All of them are required, not plain: {@code createRateStat()} is a no-op
     * unless <code>stat.full</code> is set, and its default is false.
     *
     * @param stats the context's stat manager, never null
     * @since 0.9.72+
     */
    static void registerStats(StatManager stats) {
        stats.createRequiredRateStat("netDb.storeHandled", "Number of DbStore messages we have handled", "NetworkDatabase", RATES);
        stats.createRequiredRateStat("netDb.storeLeaseSetHandled", "Number of LeaseSet store messages we have handled", "NetworkDatabase", RATES);
        stats.createRequiredRateStat("netDb.storeRouterInfoHandled", "Number of RouterInfo store messages we have handled", "NetworkDatabase", RATES);
        stats.createRequiredRateStat("netDb.storeRecvTime", "Time to handle the local store part of a DbStore", "NetworkDatabase", RATES);
        stats.createRequiredRateStat(HandleFloodfillDatabaseStoreMessageJob.STAT_LOCAL_LS_TO_LOCAL_CLIENT,
                                     "Local LeaseSet stores refused by the owning client subDB",
                                     "NetworkDatabase", RATES);
        // Removed: netDb.storeLocalLeaseSetAttempt was never emitted, so it read as an
        // absent stat rather than a zero one, and every graph, Tuner rule and health
        // check that referenced it silently no-oped. Nothing here ever measured.
        // The name that IS emitted, STAT_LOCAL_LS_TO_LOCAL_CLIENT above, was written
        // for years without any registration at all, so addRateData() dropped every
        // sample and it read as the same absent stat from the other direction.
    }

    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        DatabaseStoreMessage dsm = (DatabaseStoreMessage) receivedMessage;
        // store to client db if received by that client
        Hash by = dsm.getEntry().getReceivedBy();
        FloodfillNetworkDatabaseFacade netdb;
        if (by != null)
            netdb = (FloodfillNetworkDatabaseFacade) _context.clientNetDb(by);
        else
            netdb = _facade;
        // Notify contact-driven refresh: this peer is alive and actively storing
        if (netdb != null) netdb.contactHeardFrom(fromHash);
        return new HandleFloodfillDatabaseStoreMessageJob(_context, dsm, from, fromHash, netdb, _msgIDBloomXor);
    }
}
