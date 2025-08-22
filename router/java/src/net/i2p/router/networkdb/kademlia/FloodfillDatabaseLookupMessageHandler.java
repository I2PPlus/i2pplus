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

        DatabaseLookupMessage dlm = (DatabaseLookupMessage) receivedMessage;
        final Hash ourRouter = _context.routerHash();
        final boolean isSenderUs = ourRouter != null && ourRouter.equals(dlm.getFrom());
        final boolean shouldLog = _log.shouldWarn() || _log.shouldInfo();
        final String fromBase64 = shouldLog ? dlm.getFrom().toBase64() : "";
        final String searchKeyBase64 = shouldLog && dlm.getSearchKey() != null ? dlm.getSearchKey().toBase64() : "";
        final DatabaseLookupMessage.Type type = dlm.getSearchType();

        final boolean floodfillMode = _context.netDb().floodfillEnabled() || _context.getBooleanProperty("router.floodfillParticipant");
        final boolean ourRI = dlm.getSearchKey() != null && dlm.getSearchKey().equals(ourRouter);
        final String searchType = typeToString(type);
        final boolean isRISearch = type == DatabaseLookupMessage.Type.RI;
        final int keyLength = isRISearch ? 6 : 8;
        final boolean isDirect = dlm.getReplyTunnel() == null;
        final boolean isBanned = dlm.getFrom() != null && _context.banlist().isBanlisted(dlm.getFrom());

        RouterInfo info = (RouterInfo) _context.netDb().lookupLocally(dlm.getFrom());
        final String caps = info != null ? info.getCapabilities() : "";
        final boolean isFF = info != null && caps != null && caps.indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0;
        final boolean shouldThrottle = !isSenderUs && _facade.shouldThrottleLookup(dlm.getFrom(), dlm.getReplyTunnel());
        final boolean shouldBan = !isBanned && _facade.shouldBanLookup(dlm.getFrom(), dlm.getReplyTunnel());
        final boolean shouldAccept = isSenderUs || (!shouldThrottle && !shouldBan && (ourRI || (floodfillMode && !isFF) ||
                               (floodfillMode && isFF && type != DatabaseLookupMessage.Type.EXPL && type != DatabaseLookupMessage.Type.ANY)));
        final boolean isSelfLookup = ourRouter.equals(dlm.getSearchKey()) || dlm.getFrom().equals(ourRouter);
        final int maxLookups = isFF ? 30 : 10;

        if ((!isSelfLookup && !floodfillMode) || !shouldAccept) {
            logDroppedLookup(searchType, fromBase64, searchKeyBase64, keyLength, isFF, floodfillMode, isDirect, isBanned, maxLookups);
            _context.statManager().addRateData("netDb.nonFFLookupsDropped", 1);
            return null;
        }

        if (!isSenderUs && isFF && isDirect && (type == DatabaseLookupMessage.Type.EXPL || type == DatabaseLookupMessage.Type.ANY)) {
            logDroppedLookup(searchType, fromBase64, searchKeyBase64, keyLength, isFF, floodfillMode, isDirect, isBanned, maxLookups);
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }

        if (!floodfillMode && !shouldBan) {
            logDroppedLookup(searchType, fromBase64, searchKeyBase64, keyLength, isFF, floodfillMode, isDirect, isBanned, maxLookups);
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }

        if (shouldBan) {
            if (dlm.getFrom() != null) {
                _context.banlist().banlistRouter(dlm.getFrom(), " <b>➜</b> Excessive lookup requests",
                                                 null, null, _context.clock().now() + 60 * 60 * 1000);
                _context.commSystem().mayDisconnect(dlm.getFrom());
            }
            _context.statManager().addRateData("netDb.lookupsDropped", 1);

            if (_log.shouldWarn()) {
                StringBuilder message = new StringBuilder(128);
                message.append("Dropping ").append(isDirect ? "direct " : "").append(searchType).append(" lookup from ")
                       .append(isFF ? "floodfill ": "").append("[").append(fromBase64.substring(0,6)).append("]");

                if (!isDirect && _log.shouldInfo()) {message.append(" via [TunnelId ").append(dlm.getReplyTunnel()).append("]");}
                message.append(" and banning for 1h -> Max 60 requests in 30s or 10/s exceeded");
                _log.warn(message.toString());
            }
            return null;
        }

        if (shouldAccept || isSelfLookup) {
            if (_log.shouldInfo()) {
                _log.info("Replying to " + searchType + " lookup from [" + fromBase64.substring(0,6) + "] for [" +
                          searchKeyBase64.substring(0, keyLength) + "] via [TunnelId " + dlm.getReplyTunnel() + "]");
            }
            return new HandleFloodfillDatabaseLookupMessageJob(_context, dlm, from, fromHash, _msgIDBloomXor);
        }

        logDroppedLookup(searchType, fromBase64, searchKeyBase64, keyLength, isFF, floodfillMode, isDirect, isBanned, maxLookups);
        _context.statManager().addRateData("netDb.lookupsDropped", 1);
        return null;
    }

    private void logDroppedLookup(String searchType, String fromBase64, String searchKeyBase64, int keyLength, boolean isFF,
                                  boolean floodfillMode, boolean isDirect, boolean isBanned, int maxLookups) {
        if (!_log.shouldWarn() || isBanned) {return;}
        StringBuilder msg = new StringBuilder(128);
        msg.append("Dropping ").append(isDirect ? "direct " : "").append(searchType)
           .append(" lookup from ").append(isFF? "floodfill " : "").append("[").append(fromBase64.substring(0,6)).append("]")
           .append(" for [").append(searchKeyBase64, 0, keyLength).append("]");
        if (!floodfillMode) {msg.append(" -> We are not a floodfill");}
        else if (!isFF) {msg.append(" -> Max ").append(maxLookups).append(" requests in 3m or 5/s exceeded");}
        else if (isFF && !isDirect) {
            msg.append(" -> Max ").append(maxLookups).append(" requests in 3m or 5/s exceeded");
        } else if (isFF && isDirect && (searchType.equals("ANY") || searchType.equals("EXPL"))) {
            msg.append(" -> Direct search for Exploratory or Any from floodfill");
        }
        _log.warn(msg.toString());
    }

    private static String typeToString(DatabaseLookupMessage.Type type) {
        switch (type) {
            case EXPL:
                return "Exploratory";
            case RI:
                return "RouterInfo";
            case LS:
                return "LeaseSet";
            case ANY:
                return "Any";
            default:
                return "";
        }
    }

}
