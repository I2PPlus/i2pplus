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
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.stat.RateConstants;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * Handler for DatabaseLookupMessage received on floodfills.
 * <p>This class verifies incoming lookup messages, applies throttling and banning policies,
 * and builds appropriate jobs for accepted lookups. Unacceptable lookups are logged and dropped.</p>
 * <p>It tracks lookup statistics such as received, dropped, handled, and matched lookups via the statManager.</p>
 * <p>Supports floodfill and non-floodfill router modes, with special handling for floodfill peers and
 * exploratory lookups.</p>
 */
public class FloodfillDatabaseLookupMessageHandler implements HandlerJobBuilder {
    /** The router answering lookups. */
    private RouterContext _context;
    /** The facade holding the keys being looked up. */
    private FloodfillNetworkDatabaseFacade _facade;
    /** Logger for this class. */
    private Log _log;
    /** Records peers that send bad lookups. */
    private BanLogger _banLogger;
    /** Xor mask for this router's lookup message ids. */
    private final long _msgIDBloomXor = RandomSource.getInstance().nextLong(I2NPMessage.MAX_ID_VALUE);
    /** Rate windows kept for the lookup statistics. */
    private static final long[] RATES = RateConstants.BASIC_RATES;
    /** How long a peer that exceeds the lookup rate is banlisted for. */
    private static final long LOOKUP_BAN_MS = 30 * 60 * 1000L;
    /** A peer is not banned for excessive lookups until the router has been up this long. */
    private static final long BAN_AFTER_UPTIME_MS = 5 * 60 * 1000L;

    /**
     * Handler for floodfill DatabaseLookupMessages.
     *
     * @param context the router context providing system state and utilities
     * @param facade the network database facade used for lookup management and throttling
     */
    public FloodfillDatabaseLookupMessageHandler(RouterContext context, FloodfillNetworkDatabaseFacade facade) {
        _context = context;
        _facade = facade;
        _log = context.logManager().getLog(FloodfillDatabaseLookupMessageHandler.class);
        _banLogger = new BanLogger();
        _banLogger.initialize(context);
        _context.statManager().createRateStat("netDb.lookupsReceived", "NetDb lookups we have received", "NetworkDatabase", RATES);
        _context.statManager().createRateStat("netDb.lookupsDropped", "NetDb lookups we dropped (throttled)", "NetworkDatabase", RATES);
        // following are for ../HDLMJ
        _context.statManager().createRequiredRateStat("netDb.lookupsHandled", "NetDb lookups we have handled", "NetworkDatabase", RATES);
        _context.statManager().createRequiredRateStat("netDb.lookupsMatched", "Successful NetDb lookups", "NetworkDatabase", RATES);
        _context.statManager().createRequiredRateStat("netDb.lookupsMatchedLeaseSet", "Successful NetDb LeaseSet lookups", "NetworkDatabase", RATES);
        _context.statManager().createRateStat("netDb.lookupsMatchedReceivedPublished", "Successful NetDb lookups (published to us)", "NetworkDatabase", RATES);
        // Removed: netDb.lookupsMatchedLocalClosest (no emission; sendClosest delegates to super and the caller does not distinguish). It was never emitted, so it read as an
        // absent stat rather than a zero one, and every graph, Tuner rule and health
        // check that referenced it silently no-oped. Nothing here was measured.
        // Removed: netDb.lookupsMatchedLocalNotClosest (same). It was never emitted, so it read as an
        // absent stat rather than a zero one, and every graph, Tuner rule and health
        // check that referenced it silently no-oped. Nothing here was measured.
        // Removed: netDb.lookupsMatchedRemoteNotClosest (same). It was never emitted, so it read as an
        // absent stat rather than a zero one, and every graph, Tuner rule and health
        // check that referenced it silently no-oped. Nothing here was measured.
    }

    /**
     * Creates a job to handle an incoming DatabaseLookupMessage, or returns null if the lookup should be dropped.
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
    @Override
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
        if (shouldThrottle && _log.shouldDebug()) {
            _log.debug("Throttling lookup from [" + dlm.getFrom().toShortBase64() + "]");
        }
        final boolean shouldAccept = shouldAcceptLookup(isSenderUs, shouldThrottle, shouldBan, ourRI, floodfillMode, isFF, type);
        final long uptime = _context.router().getUptime();
        final long bantime = _context.clock().now() + LOOKUP_BAN_MS;
        // The reason a throttled lookup is dropped, with the caps the lookup was
        // actually measured against rather than a copy of them.
        final LookupThrottler throttler = _facade.lookupThrottler();
        final String rateReason = throttler != null ?
                                  " -> Over " + throttler.getMaxLookups() + " lookups in " +
                                  DataHelper.formatDuration(throttler.getCleanTimeMs()) + " or " +
                                  LookupThrottler.getBurstThreshold() + " in " +
                                  DataHelper.formatDuration(LookupThrottler.getBurstWindowMs()) :
                                  " -> Over the lookup rate";

        DatabaseLookupMessage processedDLM = preProcessDatabaseLookup(dlm, fromHash);
        if (processedDLM == null) {return null;}

        /**
         * The `from` field of a lookup is whatever the sender wrote in it and is
         * authenticated by nothing, so it may name any router on the network.
         * Ban the peer the message actually arrived on instead, and ban nobody
         * when the lookup came in through a reply tunnel and gives us no such
         * peer to prove.
         */
        final Hash banTarget = lookupBanTarget(fromHash);
        if (shouldBan && uptime > BAN_AFTER_UPTIME_MS && banTarget != null) {
            String ipPort = getIPFromHash(banTarget);
            _context.banlist().banlistRouter(banTarget, "Excessive lookups" + (isFF ? " (Floodfill)" : ""),
                                             null, null, bantime);
            _banLogger.logBan(banTarget, ipPort != null ? ipPort : "UNKNOWN", "Excessive lookups" + (isFF ? " (Floodfill)" : ""), bantime);
            _context.commSystem().mayDisconnect(banTarget);

            if (_log.shouldWarn() && dlm.getFrom() != null) {
                StringBuilder message = new StringBuilder(128);
                message.append("Dropping ").append(isDirect ? "direct " : "").append(searchType).append(" lookup from ")
                       .append(isFF ? "floodfill " : "").append("[").append(fromBase64.substring(0,6)).append("]");

                if (!isDirect && _log.shouldInfo()) {message.append(" via [TunnelId ").append(dlm.getReplyTunnel()).append("]");}
                message.append(" and banning for ").append(DataHelper.formatDuration(LOOKUP_BAN_MS)).
                       append(" -> Over the lookup rate").
                       append("\n* Max lookups: ").append(LookupBanHammer.getMaxLookups()).
                       append(" in ").append(DataHelper.formatDuration(LookupBanHammer.getCountWindowMs())).
                       append(" * Max burst: ").append(LookupBanHammer.getBurstThreshold()).
                       append(" in ").append(DataHelper.formatDuration(LookupBanHammer.getBurstWindowMs()));
                _log.info(message.toString());
            }

            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        } else if (shouldBan) {
            if (_log.shouldDebug() && banTarget == null && dlm.getFrom() != null) {
                _log.debug("Dropping " + (isDirect ? "direct " : "") + searchType + " lookup -> " +
                           "Over the lookup limit but no provable peer to ban");
            }
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }

        if (!shouldAccept) {
            if (_log.shouldWarn()) {
                logDroppedLookup(searchType, fromBase64, searchKeyBase64, keyLength, isFF, floodfillMode, isDirect, isBanned,
                                 rateReason, type);
            }
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }

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

    /**
     * Handle special cases:
     * - Fix loopback DLM bug (pre-0.9.67) where from was set to self
     * - Reject direct floodfill-to-floodfill lookups of type EXPL or ANY.
     *
     * @param dlm the received lookup message, never modified - corrections return a copy
     * @param fromHash the peer the message actually arrived from, or null if unknown
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
                        _log.warn("Fixing direct " + searchType + " lookup from us, actually from [" + fromHash.toShortBase64() + "]");
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
                        _log.warn("Dropping direct " + searchType + " lookup from floodfill [" + dlm.getFrom().toShortBase64() + "]");
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
     * @param rateReason the throttling reason, naming the caps the lookup was measured against
     * @param type the lookup type, used to name a direct exploratory search
     */
    private void logDroppedLookup(String searchType, String fromBase64, String searchKeyBase64, int keyLength, boolean isFF,
                                  boolean floodfillMode, boolean isDirect, boolean isBanned, String rateReason,
                                  DatabaseLookupMessage.Type type) {
        if (!_log.shouldInfo() || isBanned) {return;}
        StringBuilder msg = new StringBuilder(128);
        msg.append("Dropping ").append(isDirect ? "direct " : "").append(searchType)
           .append(" lookup from ").append(isFF? "floodfill " : "").append("[").append(fromBase64.substring(0,6)).append("]")
           .append(" for [").append(searchKeyBase64, 0, keyLength).append("]");
        if (!floodfillMode) {msg.append(" -> We are not a floodfill");}
        else if (!isFF || !isDirect) {msg.append(rateReason);}
        else if (type == DatabaseLookupMessage.Type.EXPL || type == DatabaseLookupMessage.Type.ANY) {
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
     * @param type the lookup message type, where exploratory and any lookups are rejected
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
     * Which router an excessive-lookup ban may name. The <code>from</code> field
     * of a DatabaseLookupMessage is written by whoever sent it and is
     * authenticated by nothing, so it may name any router on the network, and a
     * ban against it punishes a router that never exceeded anything. Only the
     * peer the message actually arrived on can be named; a lookup delivered
     * through a reply tunnel gives us no such peer, so nothing is banned and the
     * lookup is simply dropped.
     *
     * @param fromHash the peer the lookup arrived from, null when it came in through a reply tunnel
     * @return the hash to banlist, or null when nothing in the message proves a peer to ban
     * @since 0.9.72+
     */
    static Hash lookupBanTarget(Hash fromHash) {
        return fromHash;
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

    /**
     * Extract IP:port from {@link RouterInfo} for a peer hash.
     * @param h the peer hash whose RouterInfo is looked up locally
     * @return the IP from hash
     */
    private String getIPFromHash(Hash h) {
        try {
            RouterInfo ri = _context.netDb().lookupRouterInfoLocally(h);
            if (ri != null) {
                for (RouterAddress ra : ri.getAddresses()) {
                    if (ra != null) {
                        String host = ra.getHost();
                        int port = ra.getPort();
                        if (host != null && port > 0) {
                            return host + ":" + port;
                        }
                    }
                }
            }
        } catch (Exception e) { /* ignored */ }
        return null;
    }

}
