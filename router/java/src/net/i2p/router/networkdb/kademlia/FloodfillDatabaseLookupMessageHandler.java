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
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

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
        boolean ourRI = dlm.getSearchKey() != null && dlm.getSearchKey().equals(_context.routerHash());
        Hash us = _context.routerHash();
        boolean ffMode = _context.netDb().floodfillEnabled() || _context.getBooleanProperty("router.floodfillParticipant");
        DatabaseLookupMessage.Type type = dlm.getSearchType();
        String searchType = type != null ? type.toString().replace("EXPL", "Exploratory").replace("RI", "RouterInfo") : "";
        boolean isRISearch = searchType.equals("RouterInfo");
        int keyLength = isRISearch ? 6 : 8;

        if ((!_facade.shouldThrottleLookup(dlm.getFrom(), dlm.getReplyTunnel()) &&
             !_facade.shouldBanLookup(dlm.getFrom(), dlm.getReplyTunnel()) &&
             (ffMode || ourRI || type != DatabaseLookupMessage.Type.EXPL)) ||
             _context.routerHash().equals(dlm.getSearchKey()) || dlm.getFrom() == us) {
            if (_log.shouldInfo()) {
                _log.info("Replying to " + searchType.replace("LS", "LeaseSet") +
                          " Lookup from [" + dlm.getFrom().toBase64().substring(0,6) + "] " +
                          "for [" + dlm.getSearchKey().toBase64().substring(0,keyLength) + "] via [TunnelId " + dlm.getReplyTunnel() + "]");
            }
            Job j = new HandleFloodfillDatabaseLookupMessageJob(_context, dlm, from, fromHash, _msgIDBloomXor);
            return j;
        } else if (!ffMode && (type == DatabaseLookupMessage.Type.EXPL || type == DatabaseLookupMessage.Type.ANY)) {
            if (_log.shouldInfo()) {
                _log.warn("Dropping " + searchType.replace("LS", "LeaseSet") +
                          " Lookup from [" + dlm.getFrom().toBase64().substring(0,6) + "] " +
                          "for [" + dlm.getSearchKey().toBase64().substring(0,keyLength) + "] -> " +
                          "We are not a floodfill [TunnelId " + dlm.getReplyTunnel() + "]");
            } else if (_log.shouldWarn()) {
                _log.warn("Dropping " + searchType.replace("LS", "LeaseSet") +
                          " Lookup from [" + dlm.getFrom().toBase64().substring(0,6) + "] " +
                          "for [" + dlm.getSearchKey().toBase64().substring(0,keyLength) + "] -> We are not a floodfill");
            }
            _context.statManager().addRateData("netDb.nonFFLookupsDropped", 1);
            return null;
        } else if (!ffMode && !_facade.shouldBanLookup(dlm.getFrom(), dlm.getReplyTunnel())) {
            if (_log.shouldInfo()) {
                _log.warn("Dropping " + searchType.replace("LS", "LeaseSet") +
                          " Lookup from [" + dlm.getFrom().toBase64().substring(0,6) + "] " +
                          "for [" + dlm.getSearchKey().toBase64().substring(0,keyLength) + "] -> " +
                          "We are not a floodfill [TunnelId " + dlm.getReplyTunnel() + "]");
            } else if (_log.shouldWarn()) {
                _log.warn("Dropping " + searchType.replace("LS", "LeaseSet") +
                          " Lookup from [" + dlm.getFrom().toBase64().substring(0,6) + "] " +
                          "for [" + dlm.getSearchKey().toBase64().substring(0,keyLength) + "] -> We are not a floodfill");
            }
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        } else if (_facade.shouldBanLookup(dlm.getFrom(), dlm.getReplyTunnel())) {
            RouterInfo info = (RouterInfo) _context.netDb().lookupLocally(dlm.getFrom());
            String caps = info != null ? info.getCapabilities() : "";
            boolean isFF = caps != null && caps.indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0;
            if (_log.shouldInfo()) {
                _log.warn("Dropping " + searchType.replace("LS", "LeaseSet") +
                          " Lookup from [" + dlm.getFrom().toBase64().substring(0,6) + "] " +
                          "for [" + dlm.getSearchKey().toBase64().substring(0,keyLength) + "] and banning for 5m -> " +
                          "Max 60 requests in 30s or 10/s exceeded [TunnelId " + dlm.getReplyTunnel() + "]");
            } else if (_log.shouldWarn()) {
                _log.warn("Dropping " + searchType.replace("LS", "LeaseSet") +
                          " Lookup from [" + dlm.getFrom().toBase64().substring(0,6) + "] " +
                          "for [" + dlm.getSearchKey().toBase64().substring(0,keyLength) + "] and banning for 5m -> " +
                          "Max 60 requests in 30s or 10/s exceeded");
            }
            if (dlm.getFrom() != null) {
                _context.banlist().banlistRouter(dlm.getFrom(), " <b>➜</b> Excessive lookup requests", null, null, _context.clock().now() + 5*60*1000);
                _context.commSystem().mayDisconnect(dlm.getFrom());
            }
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        } else {
            RouterInfo info = (RouterInfo) _context.netDb().lookupLocally(dlm.getFrom());
            String caps = info != null ? info.getCapabilities() : "";
            boolean isFF = caps != null && caps.indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0;
            if (_log.shouldInfo()) {
                _log.warn("Dropping " + searchType.replace("LS", "LeaseSet") +
                          " Lookup from [" + dlm.getFrom().toBase64().substring(0,6) + "] " +
                          "for [" + dlm.getSearchKey().toBase64().substring(0,8) + "] -> " +
                          "Max " + (isFF ? "30" : "10") + " requests in 3m or 5/s exceeded [TunnelId " + dlm.getReplyTunnel() + "]" + (isFF ? " (Floodfill)" : ""));
            } else if (_log.shouldWarn()) {
                _log.warn("Dropping " + searchType.replace("LS", "LeaseSet") +
                          " Lookup from [" + dlm.getFrom().toBase64().substring(0,6) + "] " +
                          "for [" + dlm.getSearchKey().toBase64().substring(0,8) + "] -> Max " +
                          (isFF ? "30" : "10") + " requests in 3m or 5/s exceeded" + (isFF ? " (Floodfill)" : ""));
            }
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }
    }
}
