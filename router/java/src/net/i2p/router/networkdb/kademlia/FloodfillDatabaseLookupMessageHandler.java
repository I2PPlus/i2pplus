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
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Build a HandleDatabaseLookupMessageJob whenever a DatabaseLookupMessage arrives
 *
 */
public class FloodfillDatabaseLookupMessageHandler implements HandlerJobBuilder {
    private RouterContext _context;
    private FloodfillNetworkDatabaseFacade _facade;
    private Log _log;

    public FloodfillDatabaseLookupMessageHandler(RouterContext context, FloodfillNetworkDatabaseFacade facade) {
        _context = context;
        _facade = facade;
        _log = context.logManager().getLog(FloodfillDatabaseLookupMessageHandler.class);
        _context.statManager().createRateStat("netDb.lookupsReceived", "Number of NetDb lookups we have received", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsDropped", "Number of NetDb lookups we dropped due to throttling", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        // following are for ../HDLMJ
        _context.statManager().createRateStat("netDb.lookupsHandled", "Number of NetDb lookups we have handled", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatched", "Number of NetDb lookups we had the data for", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedLeaseSet", "Number of NetDb LeaseSet lookups we had the data for", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedReceivedPublished", "Number of NetDb lookups we had the data for that were published to us", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedLocalClosest", "Number of NetDb lookups received for local data where we were the closest peer", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedLocalNotClosest", "Number of NetDb lookups received for local data where we were NOT the closest peer", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedRemoteNotClosest", "Number of NetDb lookups received for remote data where we were NOT the closest peer", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
    }

    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        _context.statManager().addRateData("netDb.lookupsReceived", 1);

        DatabaseLookupMessage dlm = (DatabaseLookupMessage)receivedMessage;
        if (!_facade.shouldThrottleLookup(dlm.getFrom(), dlm.getReplyTunnel())) {
            Job j = new HandleFloodfillDatabaseLookupMessageJob(_context, dlm, from, fromHash);
            //if (false) {
            //    // might as well inline it, all the heavy lifting is queued up in later jobs, if necessary
            //    j.runJob();
            //    return null;
            //} else {
                return j;
            //}
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping " + dlm.getSearchType() + " lookup request for [" + dlm.getSearchKey().toBase64().substring(0,6) + "] (throttled)\n* Reply was to: [" + dlm.getFrom().toBase64().substring(0,6) + "] - [Tunnel " + dlm.getReplyTunnel() + "]");
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }
    }
}
