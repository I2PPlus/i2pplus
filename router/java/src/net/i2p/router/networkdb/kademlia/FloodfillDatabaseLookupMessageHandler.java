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
import net.i2p.util.RandomSource;
import net.i2p.util.Translate;

/**
 * Build a HandleDatabaseLookupMessageJob whenever a DatabaseLookupMessage arrives
 *
 */
public class FloodfillDatabaseLookupMessageHandler implements HandlerJobBuilder {
    private RouterContext _context;
    private FloodfillNetworkDatabaseFacade _facade;
    private Log _log;
    private final long _msgIDBloomXor = RandomSource.getInstance().nextLong(I2NPMessage.MAX_ID_VALUE);

    public FloodfillDatabaseLookupMessageHandler(RouterContext context, FloodfillNetworkDatabaseFacade facade) {
        _context = context;
        _facade = facade;
        _log = context.logManager().getLog(FloodfillDatabaseLookupMessageHandler.class);
        _context.statManager().createRateStat("netDb.lookupsReceived", "NetDb lookups we have received", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsDropped", "NetDb lookups we dropped (throttled)", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        // following are for ../HDLMJ
        _context.statManager().createRateStat("netDb.lookupsHandled", "NetDb lookups we have handled", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatched", "Successful NetDb lookups", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedLeaseSet", "Successful NetDb LeaseSet lookups", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedReceivedPublished", "Successful NetDb lookups (published to us)", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedLocalClosest", "NetDb lookups received for local data (closest peer)", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedLocalNotClosest", "NetDb lookups received for local data (not closest peer)", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedRemoteNotClosest", "NetDb lookups received for remote data (not closest peer)", "NetworkDatabase", new long[] { 60*1000, 60*60*1000l });
    }

    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        _context.statManager().addRateData("netDb.lookupsReceived", 1);

        DatabaseLookupMessage dlm = (DatabaseLookupMessage)receivedMessage;
        if (!_facade.shouldThrottleLookup(dlm.getFrom(), dlm.getReplyTunnel())) {
            Job j = new HandleFloodfillDatabaseLookupMessageJob(_context, dlm, from, fromHash, _msgIDBloomXor);
            //if (false) {
            //    // might as well inline it, all the heavy lifting is queued up in later jobs, if necessary
            //    j.runJob();
            //    return null;
            //} else {
                return j;
            //}
        } else {
            if (_log.shouldWarn()) {
                _log.warn("Dropping " + dlm.getSearchType() + " lookup from [" + dlm.getFrom().toBase64().substring(0,6) + "] " +
                          "for [" + dlm.getSearchKey().toBase64().substring(0,6) + "] and banning for 4h -> Max 9 requests in 3m exceeded");
            }
            _context.banlist().banlistRouter(dlm.getFrom(), " <b>➜</b> Excessive lookup requests", null, null, _context.clock().now() + 4*60*60*1000);
            _context.commSystem().mayDisconnect(dlm.getFrom());
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }
    }
}
