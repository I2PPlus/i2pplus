package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.DataHelper;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Processes and stores DatabaseStoreMessage data in the floodfill network database.
 * <p>
 * Handles incoming database store operations for both RouterInfo and LeaseSet
 * entries with comprehensive validation, filtering, and storage logic. Implements
 * sophisticated acceptance criteria including version checks, capability validation,
 * reachability assessment, and distance-based routing for floodfill operations.
 * <p>
 * Provides DOS protection through throttling, banlist enforcement, and probabilistic
 * dropping based on database load and peer characteristics. Supports both floodfill
 * and client database contexts with role-specific handling for each entry type.
 * <p>
 * Manages acknowledgment responses, data flooding to other floodfills, and
 * maintains detailed statistics for store operation performance and security.
 */
class HandleFloodfillDatabaseStoreMessageJob extends JobImpl {
    private final Log _log;
    private final DatabaseStoreMessage _message;
    private final RouterIdentity _from;
    private Hash _fromHash;
    private final FloodfillNetworkDatabaseFacade _facade;
    private final static int REPLY_TIMEOUT = 60*1000;
    private final static int MESSAGE_PRIORITY = OutNetMessage.PRIORITY_NETDB_REPLY;
    // Must be lower than LIMIT_ROUTERS in StartExplorersJob because exploration does not register a reply job
    private static final int LIMIT_ROUTERS = SystemVersion.isSlow() ? 1000 : 4000;
    private final long _msgIDBloomXor;
    private static final int RESEND_DELAY = 500;

    // Abuse tracking for flood throttle offenders @since 0.9.68+
    private static final int FLOOD_ABUSE_THRESHOLD = 5; // Violations before ban
    private static final long FLOOD_ABUSE_WINDOW = 5*60*1000; // 5 minutes
    private static final long FLOOD_ABUSE_BAN_TIME = 4*60*60*1000; // 4 hours
    private static final ConcurrentHashMap<Hash, AtomicInteger> _floodAbuseCounters = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Hash, Long> _floodAbuseBannedUntil = new ConcurrentHashMap<>();

    // Periodic cleanup for abuse tracking @since 0.9.68+
    private static final SimpleTimer.TimedEvent _abuseCleanupEvent = new SimpleTimer.TimedEvent() {
        public void timeReached() {
            long now = System.currentTimeMillis();
            _floodAbuseBannedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
    };

    static {
        // Start cleanup timer for abuse tracking @since 0.9.68+
        SimpleTimer2.getInstance().addPeriodicEvent(_abuseCleanupEvent, 5*60*1000);
    }

    /**
     * @param receivedMessage must never have reply token set if it came down a tunnel
     */
    public HandleFloodfillDatabaseStoreMessageJob(RouterContext ctx, DatabaseStoreMessage receivedMessage,
                                                  RouterIdentity from, Hash fromHash,
                                                  FloodfillNetworkDatabaseFacade facade, long msgIDBloomXor) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
        _facade = facade;
        _msgIDBloomXor = msgIDBloomXor;
    }

    public void runJob() {
        long recvBegin = System.currentTimeMillis();

        String invalidMessage = null;
        boolean dontBlamePeer = false; // set if invalid store but not his fault
        boolean wasNew = false;
        RouterInfo prevNetDb = null;
        Hash key = _message.getKey();
        DatabaseEntry entry = _message.getEntry();
        int type = entry.getType();
        long now = getContext().clock().now();
        boolean isBanned = getContext().banlist().isBanlistedForever(key) ||
                           getContext().banlist().isBanlisted(key) ||
                           getContext().banlist().isBanlistedHostile(key);
        if (DatabaseEntry.isLeaseSet(type)) {
            getContext().statManager().addRateData("netDb.storeLeaseSetHandled", 1);
            if (_log.shouldDebug()) {
                _log.debug("[" + _facade + "] Handling NetDbStore of LeaseSet" + _message);
            }

            try {
                /**
                 *  With the introduction of segmented netDb, the handling of
                 *  local LeaseSets has changed substantially, based on the role
                 *  being assumed.
                 *  Role #1) The 'floodfill' netDb when the router is a FloodFill
                 *           The LS will be checked to make sure it arrived directly,
                 *           and handled as a normal LS.
                 *  Role #2) The 'floodfill' netDb when the router is *NOT* an I2P
                 *           network Floodfill.
                 *           In this case, the 'floodfill' netDb primarily stores RouterInfos.
                 *           However, there are a number of normal cases where it might contain
                 *           one or more LeaseSets:
                 *             1. We used to be a floodfill but aren't anymore
                 *             2. We performed a lookup without an active session locally(It won't be RAP)
                 *  Role #3) Client netDb will only receive LeaseSets from their client
                 *           tunnels, and clients will only publish their LeaseSet out
                 *           their client tunnel.
                 *           In this role, the only LeaseSet store that should be rejected
                 *           is the subDb's client's own LeaseSet.
                 *
                 *           Currently, the 'floodfill' netDb will be excluded
                 *           from directly receiving a client LeaseSet, due to the
                 *           way the selection of FloodFill routers are selected
                 *           when flooding a LS.
                 *           But even if the host router does not directly receive the
                 *           LeaseSets of the clients it hosts, those LeaseSets will
                 *           usually be flooded back to it.
                 */
                LeaseSet ls = (LeaseSet) entry;
                /**
                 *  If this was received as a response to a query,
                 *  FloodOnlyLookupMatchJob called setReceivedAsReply(),
                 *  and we are seeing this only as a duplicate,
                 *  so we don't set the receivedAsPublished() flag.
                 *  Otherwise, mark it as something we received unsolicited, so we'll answer queries
                 *  for it.  This flag must NOT get set on entries that we
                 *  receive in response to our own lookups.
                 *  See ../HDLMJ for more info
                 */
                if (!_facade.isClientDb()) {
                    if (!ls.getReceivedAsReply()) {ls.setReceivedAsPublished();}
                } else {
                    /**
                     *  This is where we deal with what happens if a client subDB tries to store
                     *  a leaseSet which it is the owner/publisher of.
                     *  Look up a ls hash in the netDbSegmentor, and compare it to the _facade that we have.
                     *  If they are equal, reject the store.
                     */
                    if (getContext().netDbSegmentor().clientNetDB(ls.getHash()).equals(_facade)) {
                        getContext().statManager().addRateData("netDb.storeLocalLeaseSetToLocalClient", 1, 0);
                        dontBlamePeer = true;
                        throw new IllegalArgumentException("Peer attempted to store LOCAL LeaseSet [" +
                                                           key.toBase32().substring(0,8) + "]" +
                                                           "(DbId: " + _facade + ")");
                    }
                }

                LeaseSet match = _facade.store(key, ls);
                if (match == null || KademliaNetworkDatabaseFacade.isNewer(ls, match)) {wasNew = true;}
                /**
                 *  The FloodOnlyLookupSelector goes away after the first good reply
                 *  So on the second reply, FloodOnlyMatchJob is not called to set ReceivedAsReply.
                 *  So then we think it's an unsolicited store.
                 *  So we should skip this.
                 *  If the 2nd reply is newer than the first, ReceivedAsPublished will be set incorrectly,
                 *  that will hopefully be rare.
                 *  A more elaborate solution would be a List of recent ReceivedAsReply LeaseSets, with receive time ?
                 *  A real unsolicited store is likely to be new - hopefully...
                 */
                else {wasNew = false;}
            } catch (UnsupportedCryptoException uce) {
               if (_log.shouldError()) {_log.error("Unsupported Encryption: " + uce.getMessage());}
                invalidMessage = uce.getMessage();
                dontBlamePeer = true;
            } catch (IllegalArgumentException iae) {invalidMessage = iae.getMessage();}
        } else if (type == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            RouterInfo ri = (RouterInfo) entry;
            if (_log.shouldDebug()) {
                _log.debug("[" + _facade + "] Starting handling of dbStore of RouterInfo " + _message);
            }
            String cap = ri.getCapabilities();
            boolean isFF = cap.contains("f");
            getContext().statManager().addRateData("netDb.storeRouterInfoHandled", 1);
            if (_fromHash == null && _from != null) {_fromHash = _from.getHash();}

            boolean isUs = getContext().routerHash().equals(key);
            if (!key.equals(_fromHash) && !isUs) {
                if (_message.getReceivedAsReply()) {
                    ri.setReceivedAsReply();
                    if (_message.getReplyToken() > 0) {ri.setReceivedAsPublished();}
                } else {ri.setReceivedAsPublished();}
            }
            if (_log.shouldInfo()) {
                String req = ((_message.getReplyToken() > 0) ? " reply req." : "") +
                             ((_fromHash == null && ri.getReceivedAsPublished()) ? " unsolicited" : "");
                if (_fromHash == null)
                    _log.info("[" + _facade + "] Handling NetDbStore of " + cap + (isFF ? " Floodfill" : " Router") + " [" + key.toBase64().substring(0,6) +
                              "] \n* Published " + DataHelper.formatTime(ri.getPublished()) + req);
                else if (_fromHash.equals(key))
                    _log.info("[" + _facade + "] Handling NetDbStore of " + cap + (isFF ? " Floodfill" : " Router") + " [" + key.toBase64().substring(0,6) +
                              "] \n* Published " + DataHelper.formatTime(ri.getPublished()) + " from that router" + req);
                else
                    _log.info("[" + _facade + "] Handling NetDbStore of " + cap + (isFF ? " Floodfill" : " Router") + " [" + key.toBase64().substring(0,6) +
                              "] \n* Published " + DataHelper.formatTime(ri.getPublished()) + " from: [" + _fromHash.toBase64().substring(0,6) + "] " + req);
            }
            try {
                /**
                 *  Never store our RouterInfo received from somebody else.
                 *  This generally happens from a FloodfillVerifyStoreJob.
                 *  If it is valid, it shouldn't be newer than what we have - unless
                 *  somebody has our keys...
                 */
                if (isUs) {
                    //getContext().statManager().addRateData("netDb.storeLocalRouterInfoAttempt", 1, 0);
                    /**
                     *  This is initiated by PeerTestJob from another peer
                     *  throw rather than return, so that we send the ack below (prevent easy attack)
                     */
                    dontBlamePeer = true;
                    throw new IllegalArgumentException("Router [" + key.toBase64().substring(0, 6) + "] attempted to store our RouterInfo");
                }
                /**
                 *  If we're in the client netDb context, log a warning since this is not expected.
                 *  This is probably impossible but log it if we ever see it so it can be investigated.
                 */
                if (_facade.isClientDb() && _log.shouldWarn()) {
                    _log.warn("Handling RouterInfo [" + key.toBase64().substring(0,6) + "] store request in client NetDb context of router");
                }
                boolean shouldStore = true;
                if (ri.getReceivedAsPublished()) {
                    // these are often just dup stores from concurrent lookups
                    prevNetDb = (RouterInfo) _facade.lookupLocallyWithoutValidation(key);
                    boolean isUnreachable = cap.indexOf(Router.CAPABILITY_REACHABLE) < 0;
                    boolean isSlow = cap.contains("K") || cap.contains("L") || cap.contains("M") || cap.contains("N");
                    boolean isFast = cap.contains("P") || cap.contains("Q") || cap.contains("X");
                    String MIN_VERSION = "0.9.64";
                    String v = ri.getVersion();
                    boolean isOld = VersionComparator.comp(v, MIN_VERSION) < 0;
                    boolean isInvalidVersion = VersionComparator.comp(v, "2.5.0") >= 0;
                    String country = "unknown";
                    boolean noCountry = true;
                    long uptime = getContext().router().getUptime();
                    boolean notFrom = !key.equals(_fromHash);
                    boolean logged = false;
                    boolean bypassThrottle = false;

                    if (isBanned) {
                        shouldStore = false;
                        wasNew = false;
                        if (_log.shouldWarn()) {
                            logged = true;
                            _log.warn("Dropping unsolicited NetDbStore of banned " + cap + (isFF ? " Floodfill" : " Router") +
                                      " [" + key.toBase64().substring(0,6) + "]");
                        }
                    } else if (isInvalidVersion) {
                        shouldStore = false;
                        wasNew = false;
                        if (_log.shouldWarn()) {
                            logged = true;
                            _log.warn("Dropping unsolicited NetDbStore of " + cap + (isFF ? " Floodfill" : " Router") +
                                      " [" + key.toBase64().substring(0,6) + "] -> Invalid Router version: " + v);
                        }
                        // Do NOT ban for version issues - banning floodfills cuts off NetDB access
                        // and banning routers for version mismatches causes cascading network isolation
                    } else if (isFast && !isOld && prevNetDb == null) {
                        shouldStore = true;
                        bypassThrottle = true;
                    } else if (prevNetDb == null) { // actually new
                        if (isUnreachable && isOld) {
                            shouldStore = false;
                            wasNew = false;
                            if (_log.shouldWarn()) {
                                logged = true;
                                _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                          " [" + key.toBase64().substring(0,6) + "] -> " + v);
                            }
                        } else if (isUnreachable && isSlow) {
                            shouldStore = false;
                            wasNew = false;
                            if (_log.shouldWarn()) {
                                logged = true;
                                _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                          " [" + key.toBase64().substring(0,6) + "] -> Unreachable and slow");
                            }
                        } else if (isUnreachable) {
                            shouldStore = false;
                            wasNew = false;
                            if (_log.shouldWarn()) {
                                logged = true;
                                _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                          " [" + key.toBase64().substring(0,6) + "] -> Unreachable");
                            }
                        } else if (isOld && isSlow) {
                            shouldStore = false;
                            wasNew = false;
                            if (_log.shouldWarn()) {
                                logged = true;
                                _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                          " [" + key.toBase64().substring(0,6) + "] -> Slow (" + v + ")");
                            }
                        }
                        int count = _facade.getDataStore().size();
                        if (count > LIMIT_ROUTERS && !bypassThrottle) {
                            if (_facade.floodfillEnabled()) {
                                // determine if they're "close enough"
                                // we will still ack and flood by setting wasNew = true even if we don't store locally
                                // so even just-reseeded new routers will get stored to the right place
                                RouterKeyGenerator gen = getContext().routerKeyGenerator();
                                byte[] rkey = gen.getRoutingKey(key).getData();
                                byte[] ourRKey = getContext().routerHash().getData();
                                int distance = (((rkey[0] ^ ourRKey[0]) & 0xff) << 8) |
                                                ((rkey[1] ^ ourRKey[1]) & 0xff);
                                // they have to be within 1/256 of the keyspace
                                if (distance >= 256) {
                                    long until = gen.getTimeTillMidnight();
                                    if (until > FloodfillNetworkDatabaseFacade.NEXT_RKEY_RI_ADVANCE_TIME) {
                                        // appx. 90% max drop rate so even just-reseeded new routers will make it eventually
                                        int pdrop = Math.min(110, (128 * count / LIMIT_ROUTERS) - 128);
                                        if (isUnreachable || isOld) {pdrop *= 5;}
                                        else if (notFrom) {pdrop *= 10;}
                                        if (pdrop > 0 && (pdrop >= 128 || getContext().random().nextInt(128) < pdrop)) {
                                            if (_log.shouldWarn() && !logged) {
                                                logged = true;
                                                int percentage = Math.min(pdrop * 100 / 128, 100);
                                                _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                                          " [" + key.toBase64().substring(0,6) + "] with distance " + distance +
                                                          " -> Drop probability: " + percentage + "%");
                                            }
                                            shouldStore = false;
                                            if (_message.getReplyToken() > 0) {wasNew = true;} // still flood if requested
                                        }
                                    } else {
                                        // almost midnight, recheck with tomorrow's keys
                                        rkey = gen.getNextRoutingKey(key).getData();
                                        ourRKey = gen.getNextRoutingKey(getContext().routerHash()).getData();
                                        distance = (((rkey[0] ^ ourRKey[0]) & 0xff) << 8) |
                                                    ((rkey[1] ^ ourRKey[1]) & 0xff);
                                        if (distance >= 256) {
                                            int pdrop = Math.min(110, (128 * count / LIMIT_ROUTERS) - 128);
                                            if (isUnreachable || isOld) {pdrop *= 5;}
                                            else if (notFrom) {pdrop *= 10;}
                                            if (pdrop > 0 && (pdrop >= 128 || getContext().random().nextInt(128) < pdrop)) {
                                                if (_log.shouldWarn() && !logged) {
                                                    _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                                              " [" + key.toBase64().substring(0,6) + "] with distance " + distance);
                                                }
                                                shouldStore = false;
                                                if (_message.getReplyToken() > 0) {wasNew = true;} // still flood if requested
                                            }
                                        }
                                    }
                                }
                                if (shouldStore && _log.shouldDebug()) {
                                    _log.debug("[" + _facade + "] Allowing unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                               " [" + key.toBase64().substring(0,6) + "] with distance " + distance);
                                }
                            } else {
                                if (isFF && isUnreachable) {
                                    shouldStore = false;
                                    if (_log.shouldWarn() && !logged) {
                                        logged = true;
                                        _log.warn("Dropping unsolicited NetDbStore of new " + cap + " Floodfill [" + key.toBase64().substring(0,6) +
                                                  "] -> Unreachable");
                                        }
                                } else if (isUnreachable && isOld && !logged) {
                                    shouldStore = false;
                                    if (_log.shouldWarn()) {
                                        logged = true;
                                        _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                                  " [" + key.toBase64().substring(0,6) + "] -> Unreachable (" + v + ")");
                                    }
                                }
                                // non-ff - up to 100% drop rate
                                int pdrop = (128 * count / LIMIT_ROUTERS) - 128;
                                if (isUnreachable || isOld) {pdrop *= 5;}
                                else if (notFrom) {pdrop *= 10;}
                                if (pdrop > 0 && (pdrop >= 128 || getContext().random().nextInt(128) < pdrop)) {
                                    if (_log.shouldWarn() && !logged) {
                                        logged = true;
                                        int percentage = Math.min(pdrop * 100 / 128, 100);
                                        _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                                  " [" + key.toBase64().substring(0,6) + "] -> Drop probability: " + percentage + "%");
                                    }
                                    shouldStore = false;
                                    // don't bother checking ban/blocklists.
                                    //wasNew = true;
                                }
                            }
                        }
                        if (shouldStore && _log.shouldInfo()) {
                            _log.info("Handling unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                      " [" + key.toBase64().substring(0,6) + "]");
                        }
                    } else if (prevNetDb.getPublished() >= ri.getPublished()) {shouldStore = false;}
                    else {
                        if (_log.shouldInfo()) {
                            _log.info("[" + _facade + "]"
                                      + " Newer RouterInfo [" + key.toBase64().substring(0,6) + "] encountered in DbStore Message"
                                      + ") with a newer published date (" + ri.getPublished() + ") than our local copy ("
                                      + prevNetDb.getPublished() + ")");
                        }
                        if (!ri.getIdentity().getPublicKey().equals(prevNetDb.getIdentity().getPublicKey())) {
                            if (_log.shouldWarn()) {
                                _log.warn("Dropping unsolicited NetDbStore of " + cap + (isFF ? " Floodfill" : " Router") +
                                          " [" + key.toBase64().substring(0,6) + "] and banning for 8h -> Inconsistent public keys");
                            }
                            getContext().banlist().banlistRouter(key, " <b>➜</b> Inconsistent public keys", null, null, now + 8*60*60*1000);
                            shouldStore = false;
                        } else if (!ri.getIdentity().getSigningPublicKey().equals(prevNetDb.getIdentity().getSigningPublicKey())) {
                            if (_log.shouldWarn()) {
                                _log.warn("Dropping unsolicited NetDbStore of " + cap + (isFF ? " Floodfill" : " Router") +
                                          " [" + key.toBase64().substring(0,6) + "] and banning for 8h -> Inconsistent signing keys");
                            }
                            getContext().banlist().banlistRouter(key, " <b>➜</b> Inconsistent signing keys", null, null, now + 8*60*60*1000);
                            shouldStore = false;
                        }
                    }
                }
                if (shouldStore) {
                    if (_facade.isClientDb() && _log.shouldWarn()) {
                        _log.warn("[" + _facade + "] Storing RouterInfo [" + key.toBase64().substring(0,6) +
                                  "] to client NetDb -> This is rare, should have been handled by IBMD");
                    }
                    prevNetDb = _facade.store(key, ri);
                    wasNew = ((null == prevNetDb) || (prevNetDb.getPublished() < ri.getPublished()));
                }
                // Check new routerinfo address against blocklist
                if (wasNew) {
                    // TODO should we not flood temporarily banned routers either?
                    boolean forever = getContext().banlist().isBanlistedForever(key);
                    if (forever || isBanned) {
                        wasNew = false; // don't flood
                        shouldStore = false; // don't call heardAbout()
                    }
                    if (prevNetDb == null) {
                        if (!forever && getContext().blocklist().isBlocklisted(ri)) {
                            if (_log.shouldInfo()) {_log.warn("Blocklisting new peer [" + key.toBase64().substring(0,6) + "] \n" + ri);}
                            else if (_log.shouldWarn()) {_log.warn("Blocklisting new peer [" + key.toBase64().substring(0,6) + "]");}
                            wasNew = false; // don't flood
                            shouldStore = false; // don't call heardAbout()
                        }
                    } else if (!forever) {
                        Collection<RouterAddress> oldAddr = prevNetDb.getAddresses();
                        Collection<RouterAddress> newAddr = ri.getAddresses();
                        if ((!newAddr.equals(oldAddr)) && getContext().blocklist().isBlocklisted(ri)) {
                            if (_log.shouldInfo()) {
                                _log.warn("New address received, blocklisting old peer [" + key.toBase64().substring(0,6) + "] \n" + ri);
                            } else if (_log.shouldWarn()) {
                                _log.warn("New address received, blocklisting old peer [" + key.toBase64().substring(0,6) + "]");
                            }
                            wasNew = false; // don't flood
                            shouldStore = false; // don't call heardAbout()
                        }
                    }
                }
                if (shouldStore && ri.getCapabilities().indexOf(Router.CAPABILITY_REACHABLE) >= 0) {
                    getContext().profileManager().heardAbout(key);
                }
            } catch (UnsupportedCryptoException uce) {
                invalidMessage = uce.getMessage();
                dontBlamePeer = true;
            } catch (IllegalArgumentException iae) {invalidMessage = iae.getMessage();}
        } else {
            if (_log.shouldError()) {
                _log.error("[" + _facade + "] Invalid DbStoreMessage data type - " + entry.getType() + ": " + _message);
            }
            return; // don't ack or flood
        }

        long recvEnd = System.currentTimeMillis();
        getContext().statManager().addRateData("netDb.storeRecvTime", recvEnd-recvBegin);

        // ack even if invalid
        // in particular, ack our own RI (from PeerTestJob)
        // TODO any cases where we shouldn't?
        if (_message.getReplyToken() > 0) {sendAck(key);}
        long ackEnd = System.currentTimeMillis();

        if (_from != null) {_fromHash = _from.getHash();}
        if (_fromHash != null) {
            if (invalidMessage == null || dontBlamePeer) {
                getContext().profileManager().dbStoreReceived(_fromHash, wasNew);
                getContext().statManager().addRateData("netDb.storeHandled", ackEnd-recvEnd);
            } else {
                if (invalidMessage.contains("published over")) {
                    dontBlamePeer = true;
                    if (_log.shouldWarn()) {
                        _log.warn("Received STALE RouterInfo from [" + _fromHash.toBase64().substring(0,6) + "] \n* " + invalidMessage);
                    }
                // Should we record in the profile?
                } else if (_log.shouldDebug()) {
                    _log.warn("Received INVALID data packet from [" + _fromHash.toBase64().substring(0,6) + "] \n* " + invalidMessage + _from);
                } else if (_log.shouldWarn()) {
                    _log.warn("Received INVALID data packet from [" + _fromHash.toBase64().substring(0,6) + "] \n* " + invalidMessage);
                }
            }
        } else if (invalidMessage != null && !dontBlamePeer) {
            if (_log.shouldWarn()) {
                if (invalidMessage.contains("published over")) {
                    _log.warn("Received STALE RouterInfo from [unknown] \n* " + invalidMessage);
                } else {
                    _log.warn("Received INVALID data packet from [unknown] \n* " + invalidMessage);
                }
            }
        }

        // flood it
        if (invalidMessage == null && _facade.floodfillEnabled() && _message.getReplyToken() > 0) {
            if (wasNew) {
                // DOS prevention
                // Note this does not throttle the ack above
                if (_facade.shouldThrottleFlood(key)) {
                    // Log the sender's hash for abuse tracking @since 0.9.68+
                    String senderHash = _fromHash != null ? _fromHash.toBase64().substring(0, 6) : "unknown";
                    if (_log.shouldWarn()) {
                        _log.warn("Too many recent stores from [" + senderHash + "] for key: " + key);
                    }
                    getContext().statManager().addRateData("netDb.floodThrottled", 1);

                    // Track abuse and ban persistent offenders @since 0.9.68+
                    if (_fromHash != null) {
                        long banTime = System.currentTimeMillis();
                        Long bannedUntil = _floodAbuseBannedUntil.get(_fromHash);
                        if (bannedUntil != null && bannedUntil > banTime) {
                            // Already banned, skip
                            return;
                        }

                        AtomicInteger abuseCount = _floodAbuseCounters.computeIfAbsent(_fromHash,
                            k -> new AtomicInteger(0));
                        int count = abuseCount.incrementAndGet();

                        if (count >= FLOOD_ABUSE_THRESHOLD) {
                            // Ban for 4 hours @since 0.9.68+
                            long banExpiry = banTime + FLOOD_ABUSE_BAN_TIME;
                            _floodAbuseBannedUntil.put(_fromHash, banExpiry);
                            abuseCount.set(0); // Reset counter after ban
                            if (_log.shouldWarn()) {
                                _log.warn("Banning router [" + senderHash + "] for 4h - excessive flood abuse (" +
                                          count + " violations)");
                            }
                            getContext().banlist().banlistRouter(_fromHash,
                                "Flood abuse - excessive NetDb stores", null, null, banExpiry);
                        }
                    }
                    return;
                }
                // Flood in separate thread to avoid blocking main thread
                Runnable floodTask = new Runnable() {
                    public void run() {
                        long floodBegin = System.currentTimeMillis();
                        _facade.flood(entry);
                        long floodEnd = System.currentTimeMillis();
                        getContext().statManager().addRateData("netDb.storeFloodNew", floodEnd-floodBegin, 60*1000);
                    }
                };
                new Thread(floodTask, "Flood Worker").start();
            } else {
                // don't flood it *again*
                getContext().statManager().addRateData("netDb.storeFloodOld", 1);
            }
        }
    }

    private void sendAck(Hash storedKey) {
        DeliveryStatusMessage msg = new DeliveryStatusMessage(getContext());
        msg.setMessageId(_message.getReplyToken());
        // Randomize for a little protection against clock-skew fingerprinting.
        // But the "arrival" isn't used for anything, right?
        // TODO we have no session to garlic wrap this with, needs new message
        msg.setArrival(getContext().clock().now() - getContext().random().nextInt(RESEND_DELAY));
        // may be null
        TunnelId replyTunnel = _message.getReplyTunnel();
        // A store of our own RI, only if we are not FF
        DatabaseStoreMessage msg2;
        if (_facade.floodfillEnabled() ||
            storedKey.equals(getContext().routerHash())) {
            // don't send our RI if the store was our RI (from PeerTestJob)
            msg2 = null;
        } else {
            // we aren't ff, send a go-away message
            msg2 = new DatabaseStoreMessage(getContext());
            RouterInfo me = getContext().router().getRouterInfo();
            msg2.setEntry(me);
            if (_fromHash != null && _from != null) {
                if (_log.shouldWarn()) {
                    _log.warn("Received a DbStoreMessage with Reply token, but we're not a Floodfill\n* From: " + _from +
                              "\n* From Hash: " + _fromHash + "\n* Message: " + _message, new Exception());
                }
            }
        }
        Hash toPeer = _message.getReplyGateway();
        boolean toUs = getContext().routerHash().equals(toPeer);
        // to reduce connection congestion, send directly if connected already,
        // else through an exploratory tunnel.
        if (toUs && replyTunnel != null) {
            // if we are the gateway, act as if we received it
            TunnelGatewayMessage tgm = new TunnelGatewayMessage(getContext());
            tgm.setMessage(msg);
            tgm.setTunnelId(replyTunnel);
            tgm.setMessageExpiration(msg.getMessageExpiration());
            getContext().tunnelDispatcher().dispatch(tgm);
            if (msg2 != null) {
                TunnelGatewayMessage tgm2 = new TunnelGatewayMessage(getContext());
                tgm2.setMessage(msg2);
                tgm2.setTunnelId(replyTunnel);
                tgm2.setMessageExpiration(msg.getMessageExpiration());
                getContext().tunnelDispatcher().dispatch(tgm2);
            }
            return;
        }
        if (toUs) {
            if (_facade.isClientDb()) {
                _log.error("[" + _facade + "] ERROR! SendMessageDirectJob (toUs) attempted in Client NetDb!\n* Message: " + msg);
                return;
            }
            Job send = new SendMessageDirectJob(getContext(), msg, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY, _msgIDBloomXor);
            send.runJob();
            if (msg2 != null) {
                Job send2 = new SendMessageDirectJob(getContext(), msg2, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY, _msgIDBloomXor);
                send2.runJob();
            }
            return;
        }

        DatabaseEntry entry = _message.getEntry();
        int type = entry.getType();
        // only send direct for RI replies or non-tunnel
        boolean isEstab = (type == DatabaseEntry.KEY_TYPE_ROUTERINFO || replyTunnel == null) &&
                          getContext().commSystem().isEstablished(toPeer);

        if (isEstab && !_facade.isClientDb()) {
            I2NPMessage out1 = msg;
            I2NPMessage out2 = msg2;
            if (replyTunnel != null) {
                // wrap reply in a TGM
                TunnelGatewayMessage tgm = new TunnelGatewayMessage(getContext());
                tgm.setMessage(msg);
                tgm.setTunnelId(replyTunnel);
                tgm.setMessageExpiration(msg.getMessageExpiration());
                out1 = tgm;
                if (out2 != null) {
                    TunnelGatewayMessage tgm2 = new TunnelGatewayMessage(getContext());
                    tgm2.setMessage(msg2);
                    tgm2.setTunnelId(replyTunnel);
                    tgm2.setMessageExpiration(msg.getMessageExpiration());
                    out2 = tgm2;
                }
            }
            Job send = new SendMessageDirectJob(getContext(), out1, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY, _msgIDBloomXor);
            send.runJob();
            if (msg2 != null) {
                Job send2 = new SendMessageDirectJob(getContext(), out2, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY, _msgIDBloomXor);
                send2.runJob();
            }
            return;
        }

        // pick tunnel with endpoint closest to toPeer
        TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundExploratoryTunnel(toPeer);
        if (outTunnel == null) {
            if (_log.shouldWarn()) {_log.warn("No Outbound tunnel could be found");}
            return;
        }
        getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnel.getSendTunnelId(0), replyTunnel, toPeer);
        if (msg2 != null) {
            getContext().tunnelDispatcher().dispatchOutbound(msg2, outTunnel.getSendTunnelId(0), replyTunnel, toPeer);
        }
    }

    public String getName() {return "Handle Floodfill DbStoreMessage";}

    @Override
    public void dropped() {
        getContext().messageHistory().messageProcessingError(_message.getUniqueId(),
                                     _message.getClass().getName(), "Dropped due to overload");
    }

}
