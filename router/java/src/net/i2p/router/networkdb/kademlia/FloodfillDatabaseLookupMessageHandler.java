package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.stat.RateConstants;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * Handler for DatabaseLookupMessage received on floodfills.
 *
 * <p>This class verifies incoming lookup messages, applies throttling and banning policies,
 * and builds appropriate jobs for accepted lookups. Unacceptable lookups are logged and dropped.</p>
 *
 * <p>It tracks lookup statistics such as received, dropped, handled, and matched lookups via the statManager.</p>
 *
 * <p>Supports floodfill and non-floodfill router modes, with special handling for floodfill peers and
 * exploratory lookups.</p>
 */
public class FloodfillDatabaseLookupMessageHandler implements HandlerJobBuilder {
    private RouterContext _context;
    private FloodfillNetworkDatabaseFacade _facade;
    private Log _log;
    private final long _msgIDBloomXor = RandomSource.getInstance().nextLong(I2NPMessage.MAX_ID_VALUE);
    private static final long[] RATES = RateConstants.BASIC_RATES;
    private final Set<Hash> _loggedBans = ConcurrentHashMap.newKeySet();

    /**
     * Constructs a new handler for floodfill DatabaseLookupMessages.
     *
     * @param context the router context providing system state and utilities
     * @param facade the network database facade used for lookup management and throttling
     */
    public FloodfillDatabaseLookupMessageHandler(RouterContext context, FloodfillNetworkDatabaseFacade facade) {
        _context = context;
        _facade = facade;
        _log = context.logManager().getLog(FloodfillDatabaseLookupMessageHandler.class);
        _context.statManager().createRateStat("netDb.lookupsReceived", "NetDb lookups we have received", "NetworkDatabase", RATES);
        _context.statManager().createRateStat("netDb.lookupsDropped", "NetDb lookups we dropped (throttled)", "NetworkDatabase", RATES);
        // following are for ../HDLMJ
        _context.statManager().createRateStat("netDb.lookupsHandled", "NetDb lookups we have handled", "NetworkDatabase", RATES);
        _context.statManager().createRateStat("netDb.lookupsMatched", "Successful NetDb lookups", "NetworkDatabase", RATES);
        _context.statManager().createRateStat("netDb.lookupsMatchedLeaseSet", "Successful NetDb LeaseSet lookups", "NetworkDatabase", RATES);
        _context.statManager().createRateStat("netDb.lookupsMatchedReceivedPublished", "Successful NetDb lookups (published to us)", "NetworkDatabase", RATES);
        _context.statManager().createRateStat("netDb.lookupsMatchedLocalClosest", "NetDb lookups received for local data (closest peer)", "NetworkDatabase", RATES);
        _context.statManager().createRateStat("netDb.lookupsMatchedLocalNotClosest", "NetDb lookups received for local data (not closest peer)", "NetworkDatabase", RATES);
        _context.statManager().createRateStat("netDb.lookupsMatchedRemoteNotClosest", "NetDb lookups received for remote data (not closest peer)", "NetworkDatabase", RATES);
    }

    /**
     * Creates a job to handle an incoming DatabaseLookupMessage, or returns null if the lookup should be dropped.
     *
     * <p>This method performs several checks, including: floodfill participation, sender identity,
     * whether the lookup is for this router, throttling, banning, and lookup type compatibility.
     * Logs details about accepted or dropped lookups and updates statistics.</p>
     *
     * @param receivedMessage the incoming message, expected to be a DatabaseLookupMessage
     * @param from the identity of the sender router
     * @param fromHash the hash of the sender router
     * @return a Job to handle the lookup if accepted, or null if dropped
     * @throws ClassCastException if receivedMessage is not a DatabaseLookupMessage
     */
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
        final boolean isBanned = dlm.getFrom() != null &&
            (_context.banlist().isBanlisted(dlm.getFrom()) ||
             _context.banlist().isBanlistedHostile(dlm.getFrom()) ||
             _context.banlist().isBanlistedForever(dlm.getFrom()));

        RouterInfo info = (RouterInfo) _context.netDb().lookupLocally(dlm.getFrom());
        final String caps = info != null ? info.getCapabilities() : "";
        final boolean isFF = info != null && caps != null && caps.indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0;
        final boolean shouldThrottle = !isSenderUs && _facade.shouldThrottleLookup(dlm.getFrom(), dlm.getReplyTunnel());
        final boolean shouldBan = !isBanned && _facade.shouldBanLookup(dlm.getFrom(), dlm.getReplyTunnel());
        final boolean shouldAccept = shouldAcceptLookup(isSenderUs, shouldThrottle, shouldBan, ourRI, floodfillMode, isFF, type);
        final boolean isSelfLookup = ourRouter.equals(dlm.getSearchKey()) || dlm.getFrom().equals(ourRouter);
        final int maxLookups = isFF ? 60 : 30;
        final long uptime = _context.router().getUptime();

        DatabaseLookupMessage processedDLM = preProcessDatabaseLookup(dlm, fromHash);
        if (processedDLM == null) {return null;}

        if (shouldBan && uptime > 10*60*1000) {
            if (dlm.getFrom() != null) {
                _context.banlist().banlistRouter(dlm.getFrom(), " <b>➜</b> Excessive lookup requests" + (isFF ? " (Floodfill)" : ""),
                                                 null, null, _context.clock().now() + 10 * 60 * 1000);
                _context.commSystem().mayDisconnect(dlm.getFrom());
            }
            _context.statManager().addRateData("netDb.lookupsDropped", 1);

            if (_log.shouldWarn() && dlm.getFrom() != null && _loggedBans.add(dlm.getFrom())) {
                StringBuilder message = new StringBuilder(128);
                message.append("Dropping ").append(isDirect ? "direct " : "").append(searchType).append(" lookup from ")
                       .append(isFF ? "floodfill ": "").append("[").append(fromBase64.substring(0,6)).append("]");

                if (!isDirect && _log.shouldInfo()) {message.append(" via [TunnelId ").append(dlm.getReplyTunnel()).append("]");}
                message.append(" and banning for 10m -> Max 60 requests in 30s or 10/s exceeded");
                _log.warn(message.toString());
            }
            return null;
        } else if (shouldBan) {
            return null;
        }

        if ((!isSelfLookup && !floodfillMode) || !shouldAccept) {
            if (_log.shouldWarn()) {
                logDroppedLookup(searchType, fromBase64, searchKeyBase64, keyLength, isFF, floodfillMode, isDirect, isBanned, maxLookups);
            }
            _context.statManager().addRateData("netDb.nonFFLookupsDropped", 1);
            return null;
        }

        if (!isSenderUs && isFF && isDirect && (type == DatabaseLookupMessage.Type.EXPL || type == DatabaseLookupMessage.Type.ANY)) {
            if (_log.shouldWarn()) {
                logDroppedLookup(searchType, fromBase64, searchKeyBase64, keyLength, isFF, floodfillMode, isDirect, isBanned, maxLookups);
            }
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }

        if (!floodfillMode && !shouldBan) {
            if (_log.shouldWarn()) {
                logDroppedLookup(searchType, fromBase64, searchKeyBase64, keyLength, isFF, floodfillMode, isDirect, isBanned, maxLookups);
            }
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }

        if (isSelfLookup) {

            if (_log.shouldInfo()) {
                if (dlm.getReplyTunnel() != null) {
                    _log.info("Replying to " + searchType + " lookup from [" + fromBase64.substring(0,6) + "] for [" +
                              searchKeyBase64.substring(0, keyLength) + "] via [TunnelId " + dlm.getReplyTunnel() + "]");
                } else {
                    _log.info("Replying to direct " + searchType + " lookup from [" + fromBase64.substring(0,6) + "] for [" +
                              searchKeyBase64.substring(0, keyLength) + "]");
                }
            }
            return new HandleFloodfillDatabaseLookupMessageJob(_context, dlm, from, fromHash, _msgIDBloomXor);
        }

        if (_log.shouldWarn()) {
            logDroppedLookup(searchType, fromBase64, searchKeyBase64, keyLength, isFF, floodfillMode, isDirect, isBanned, maxLookups);
        }
        _context.statManager().addRateData("netDb.lookupsDropped", 1);
        return null;
    }

    /**
     * Handle special cases:
     * - Fix loopback DLM bug (pre-0.9.67) where from was set to self
     * - Reject direct floodfill-to-floodfill lookups of type EXPL or ANY
     *
     * @return the corrected DLM, or null to drop the message
     */
    private DatabaseLookupMessage preProcessDatabaseLookup(DatabaseLookupMessage dlm, Hash fromHash) {
        final DatabaseLookupMessage.Type type = dlm.getSearchType();
        final String searchType = typeToString(type);

        if (dlm.getReplyTunnel() == null) {
            // Fix loopback bug (pre-0.9.67)
            if (dlm.getFrom().equals(_context.routerHash())) {
                if (fromHash != null) {
                    if (_log.shouldWarn()) {
                        _log.warn("Fixing direct " + searchType + " lookup from us, actually from [" + fromHash.toBase64().substring(0,6) + "]");
                    }
                    DatabaseLookupMessage newdlm = new DatabaseLookupMessage(_context);
                    newdlm.setFrom(fromHash);
                    newdlm.setSearchType(dlm.getSearchType());
                    newdlm.setSearchKey(dlm.getSearchKey());
                    Set<Hash> dont = dlm.getDontIncludePeers();
                    if (dont != null) {newdlm.setDontIncludePeers(dont);}
                    return newdlm;
                } else {
                    if (_log.shouldWarn()) {
                        _log.warn("Dropping direct " + searchType + " lookup from our own router");
                    }
                    _context.statManager().addRateData("netDb.lookupsDropped", 1);
                    return null;
                }
            }

            // Block direct EXPL/ANY lookups from floodfills
            if (dlm.getSearchType() == DatabaseLookupMessage.Type.EXPL ||
                dlm.getSearchType() == DatabaseLookupMessage.Type.ANY) {
                RouterInfo to = _facade.lookupRouterInfoLocally(dlm.getFrom());
                if (to != null && to.getCapabilities().indexOf(FloodfillNetworkDatabaseFacade.CAPABILITY_FLOODFILL) >= 0) {
                    if (_log.shouldWarn()) {
                        _log.warn("Dropping direct " + searchType + " lookup from floodfill [" + dlm.getFrom().toBase64().substring(0,6) + "]");
                    }
                    _context.statManager().addRateData("netDb.lookupsDropped", 1);
                    return null;
                }
            }
        }

        return dlm;
    }

    /**
     * Logs warning messages when a lookup is dropped due to throttling, banning, or mode restrictions.
     * Does nothing if warning logs are disabled or the sender is already banned.
     *
     * @param searchType the type string of the lookup (e.g. "Exploratory", "RouterInfo")
     * @param fromBase64 the base64 representation of the sender router hash (truncated to 6 chars)
     * @param searchKeyBase64 the base64 representation of the lookup search key (truncated)
     * @param keyLength the length of key substring to log (6 if RouterInfo or 8 chars otherwise)
     * @param isFF true if the sender router is a floodfill
     * @param floodfillMode true if the local router is participating as floodfill
     * @param isDirect true if the lookup is being sent directly (no tunnel)
     * @param isBanned true if the sender router is banned
     * @param maxLookups max allowed lookups in time window for floodfill or non-floodfill
     */
    private void logDroppedLookup(String searchType, String fromBase64, String searchKeyBase64, int keyLength, boolean isFF,
                                  boolean floodfillMode, boolean isDirect, boolean isBanned, int maxLookups) {
        if (!_log.shouldInfo() || isBanned) {return;}
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
        _log.info(msg.toString());
    }

    /**
     * Determines whether a lookup request should be accepted based on sender identity,
     * throttling and banning status, router participation mode, and type of lookup.
     *
     * @param isSenderUs true if the sender is the local router itself (loopback)
     * @param shouldThrottle true if the lookup request should be throttled (rate limited)
     * @param shouldBan true if the lookup request should be banned (blocked)
     * @param ourRI true if the lookup is for this router's identity
     * @param floodfillMode true if the local router participates as floodfill
     * @param isFF true if the sender router is a floodfill
     * @param type the type of the lookup message
     * @return true if the lookup is accepted, false if dropped
     *
     * @since 0.9.67+
     */
    private boolean shouldAcceptLookup(boolean isSenderUs, boolean shouldThrottle, boolean shouldBan,
                                       boolean ourRI, boolean floodfillMode, boolean isFF,
                                       DatabaseLookupMessage.Type type) {
        return isSenderUs || (!shouldThrottle && !shouldBan && (ourRI || (floodfillMode && !isFF) ||
               (floodfillMode && isFF && type != DatabaseLookupMessage.Type.EXPL && type != DatabaseLookupMessage.Type.ANY)));
    }

    /**
     * Converts a DatabaseLookupMessage.Type enum to a human-readable string.
     *
     * @param type the lookup message type
     * @return a descriptive string representation of the type
     *
     * @since 0.9.67+
     */
    private static String typeToString(DatabaseLookupMessage.Type type) {
        switch (type) {
            case EXPL: return "Exploratory";
            case RI:   return "RouterInfo";
            case LS:   return "LeaseSet";
            case ANY:  return "Any";
            default:   return "";
        }
    }

}
