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
import java.util.Date;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.TunnelId;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterKeyGenerator;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.SendMessageDirectJob;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Receive DatabaseStoreMessage data and store it in the local net db
 *
 */
class HandleFloodfillDatabaseStoreMessageJob extends JobImpl {
    private final Log _log;
    private final DatabaseStoreMessage _message;
    private final RouterIdentity _from;
    private Hash _fromHash;
    private final FloodfillNetworkDatabaseFacade _facade;
    private final static int REPLY_TIMEOUT = 60*1000;
    private final static int MESSAGE_PRIORITY = OutNetMessage.PRIORITY_NETDB_REPLY;
    // must be lower than LIMIT_ROUTERS in StartExplorersJob
    // because exploration does not register a reply job
//    private static final int LIMIT_ROUTERS = SystemVersion.isSlow() ? 1500 : 4000;
    private static final int LIMIT_ROUTERS = SystemVersion.isSlow() ? 2000 : 5000;

    /**
     * @param receivedMessage must never have reply token set if it came down a tunnel
     */
    public HandleFloodfillDatabaseStoreMessageJob(RouterContext ctx, DatabaseStoreMessage receivedMessage,
                                                  RouterIdentity from, Hash fromHash,
                                                  FloodfillNetworkDatabaseFacade facade) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _message = receivedMessage;
        _from = from;
        _fromHash = fromHash;
        _facade = facade;
    }

    public void runJob() {
        long recvBegin = System.currentTimeMillis();

        String invalidMessage = null;
        // set if invalid store but not his fault
        boolean dontBlamePeer = false;
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
            if (_log.shouldDebug())
                _log.debug("Handling NetDbStore of LeaseSet" + _message);

            try {
                // Never store a leaseSet for a local dest received from somebody else.
                // This generally happens from a FloodfillVerifyStoreJob.
                // If it is valid, it shouldn't be newer than what we have - unless
                // somebody has our keys...
                // This could happen with multihoming - where it's really important to prevent
                // storing the other guy's leaseset, it will confuse us badly.
                if (getContext().clientManager().isLocal(key)) {
                    //getContext().statManager().addRateData("netDb.storeLocalLeaseSetAttempt", 1, 0);
                    // throw rather than return, so that we send the ack below (prevent easy attack)
                    dontBlamePeer = true;
                    throw new IllegalArgumentException("Router [" + key.toBase64().substring(0, 6) + "] attempted to store LOCAL LeaseSet");
                }
                LeaseSet ls = (LeaseSet) entry;
                //boolean oldrar = ls.getReceivedAsReply();
                //boolean oldrap = ls.getReceivedAsPublished();
                // If this was received as a response to a query,
                // FloodOnlyLookupMatchJob called setReceivedAsReply(),
                // and we are seeing this only as a duplicate,
                // so we don't set the receivedAsPublished() flag.
                // Otherwise, mark it as something we received unsolicited, so we'll answer queries
                // for it.  This flag must NOT get set on entries that we
                // receive in response to our own lookups.
                // See ../HDLMJ for more info
                if (!ls.getReceivedAsReply())
                    ls.setReceivedAsPublished(true);
                //boolean rap = ls.getReceivedAsPublished();
                //if (_log.shouldInfo())
                //    _log.info("oldrap? " + oldrap + " oldrar? " + oldrar + " newrap? " + rap);
                LeaseSet match = _facade.store(key, ls);
                if (match == null) {
                    wasNew = true;
                } else if (match.getEarliestLeaseDate() < ls.getEarliestLeaseDate()) {
                    wasNew = true;
                    // If it is in our keyspace and we are talking to it
                    //if (match.getReceivedAsPublished())
                    //    ls.setReceivedAsPublished(true);
                } else if (type != DatabaseEntry.KEY_TYPE_LEASESET &&
                           match.getType() != DatabaseEntry.KEY_TYPE_LEASESET) {
                    LeaseSet2 ls2 = (LeaseSet2) ls;
                    LeaseSet2 match2 = (LeaseSet2) match;
                    if (match2.getPublished() < ls2.getPublished()) {
                        wasNew = true;
                        //if (match.getReceivedAsPublished())
                        //    ls.setReceivedAsPublished(true);
                    } else {
                        wasNew = false;
                    }
                } else {
                    wasNew = false;
                    // The FloodOnlyLookupSelector goes away after the first good reply
                    // So on the second reply, FloodOnlyMatchJob is not called to set ReceivedAsReply.
                    // So then we think it's an unsolicited store.
                    // So we should skip this.
                    // If the 2nd reply is newer than the first, ReceivedAsPublished will be set incorrectly,
                    // that will hopefully be rare.
                    // A more elaborate solution would be a List of recent ReceivedAsReply LeaseSets, with receive time ?
                    // A real unsolicited store is likely to be new - hopefully...
                    //if (!ls.getReceivedAsReply())
                    //    match.setReceivedAsPublished(true);
                }
            } catch (UnsupportedCryptoException uce) {
                invalidMessage = uce.getMessage();
                dontBlamePeer = true;
            } catch (IllegalArgumentException iae) {
                invalidMessage = iae.getMessage();
            }
        } else if (type == DatabaseEntry.KEY_TYPE_ROUTERINFO) {
            RouterInfo ri = (RouterInfo) entry;
            String cap = ri.getCapabilities();
            boolean isFF = cap.contains("f");

            getContext().statManager().addRateData("netDb.storeRouterInfoHandled", 1);
            if (_fromHash == null && _from != null)
                _fromHash = _from.getHash();

            boolean isUs = getContext().routerHash().equals(key);
            if (!key.equals(_fromHash) && !isUs) {
                if (_message.getReceivedAsReply()) {
                    ri.setReceivedAsReply();
                    if (_message.getReplyToken() > 0)
                        ri.setReceivedAsPublished(true);
                } else {
                    ri.setReceivedAsPublished(true);
                }
            }
            if (_log.shouldInfo()) {
                String req = ((_message.getReplyToken() > 0) ? " reply req." : "") +
                             ((_fromHash == null && ri.getReceivedAsPublished()) ? " unsolicited" : "");
                if (_fromHash == null)
                    _log.info("Handling NetDbStore of " + cap + (isFF ? " Floodfill" : " Router") + " [" + key.toBase64().substring(0,6) +
                              "] \n* Published " + DataHelper.formatTime(ri.getPublished()) + req);
                else if (_fromHash.equals(key))
                    _log.info("Handling NetDbStore of " + cap + (isFF ? " Floodfill" : " Router") + " [" + key.toBase64().substring(0,6) +
                              "] \n* Published " + DataHelper.formatTime(ri.getPublished()) + " from that router" + req);
                else
                    _log.info("Handling NetDbStore of " + cap + (isFF ? " Floodfill" : " Router") + " [" + key.toBase64().substring(0,6) +
                              "] \n* Published " + DataHelper.formatTime(ri.getPublished()) + " from: [" + _fromHash.toBase64().substring(0,6) + "] " + req);
            }
            try {
                // Never store our RouterInfo received from somebody else.
                // This generally happens from a FloodfillVerifyStoreJob.
                // If it is valid, it shouldn't be newer than what we have - unless
                // somebody has our keys...
                if (isUs) {
                    //getContext().statManager().addRateData("netDb.storeLocalRouterInfoAttempt", 1, 0);
                    // This is initiated by PeerTestJob from another peer
                    // throw rather than return, so that we send the ack below (prevent easy attack)
                    dontBlamePeer = true;
                    throw new IllegalArgumentException("Router [" + key.toBase64().substring(0, 6) + "] attempted to store our RouterInfo");
                }
                boolean shouldStore = true;
                if (ri.getReceivedAsPublished()) {
                    // these are often just dup stores from concurrent lookups
                    prevNetDb = (RouterInfo) _facade.lookupLocallyWithoutValidation(key);
                    boolean isUnreachable = cap.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0;
                    boolean isSlow = cap.contains("K") || cap.contains("L") || cap.contains("M") || cap.contains("N");
                    String MIN_VERSION = "0.9.58";
                    String v = ri.getVersion();
                    boolean noSSU = true;
                    boolean isOld = VersionComparator.comp(v, MIN_VERSION) < 0;
                    for (RouterAddress ra : ri.getAddresses()) {
                        if (ra.getTransportStyle().equals("SSU") ||
                            ra.getTransportStyle().equals("SSU2")) {
                            noSSU = false;
                            break;
                        }
                    }
                    if (isBanned) {
                        shouldStore = false;
                        wasNew = false;
                        if (_log.shouldWarn()) {
                            _log.warn("Dropping unsolicited NetDbStore of banned " + cap + (isFF ? " Floodfill" : " Router") +
                                      " [" + key.toBase64().substring(0,6) + "]" + ((isFF && noSSU) ? " -> SSU transport disabled" : ""));
                        }
                    } else if ((isFF && noSSU) || (isFF && isUnreachable)) {
                        shouldStore = false;
                        wasNew = false;
                        if (isFF && noSSU) {
                            getContext().banlist().banlistRouter(key, " <b>➜</b> Floodfill with SSU disabled", null, null, now + 4*60*60*1000);
                            if (_log.shouldWarn())
                                _log.warn("Dropping unsolicited NetDbStore of " + cap + " Floodfill [" + key.toBase64().substring(0,6) +
                                          "] and banning for 4h -> SSU transport disabled");
                        } else {
                            getContext().banlist().banlistRouter(key, " <b>➜</b> Floodfill is unreachable/firewalled", null, null, now + 4*60*60*1000);
                            if (_log.shouldWarn())
                                _log.warn("Dropping unsolicited NetDbStore of " + cap + " Floodfill [" + key.toBase64().substring(0,6) +
                                          "] and banning for 4h -> Unreachable");
                        }
                    } else if (prevNetDb == null) { // actually new
                        if (isUnreachable && isOld) {
                            shouldStore = false;
                            wasNew = false;
                            if (_log.shouldWarn()) {
                                _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                          " [" + key.toBase64().substring(0,6) + "] -> Unreachable (" + v + ")");
                            }
                        } else if (isUnreachable && isSlow) {
                            shouldStore = false;
                            wasNew = false;
                            if (_log.shouldWarn()) {
                                _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                          " [" + key.toBase64().substring(0,6) + "] -> Unreachable and slow");
                            }
                        } else if (isOld && isSlow) {
                            shouldStore = false;
                            wasNew = false;
                            if (_log.shouldWarn()) {
                                _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                          " [" + key.toBase64().substring(0,6) + "] -> Slow (" + v + ")");
                            }
                        }
                        int count = _facade.getDataStore().size();
                        if (count > LIMIT_ROUTERS) {
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
                                        if (isUnreachable || isOld || noSSU)
//                                            pdrop *= 3;
                                            pdrop *= 8;
                                        if (pdrop > 0 && (pdrop >= 128 || getContext().random().nextInt(128) < pdrop)) {
                                            if (_log.shouldWarn())
                                                _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                                          " [" + key.toBase64().substring(0,6) + "] with distance " + distance +
                                                          " -> Drop probability: " + (pdrop * 100 / 128) + "%");
                                            shouldStore = false;
                                            // still flood if requested
                                            if (_message.getReplyToken() > 0)
                                                wasNew = true;
                                        }
                                    } else {
                                        // almost midnight, recheck with tomorrow's keys
                                        rkey = gen.getNextRoutingKey(key).getData();
                                        ourRKey = gen.getNextRoutingKey(getContext().routerHash()).getData();
                                        distance = (((rkey[0] ^ ourRKey[0]) & 0xff) << 8) |
                                                    ((rkey[1] ^ ourRKey[1]) & 0xff);
                                        if (distance >= 256) {
                                            int pdrop = Math.min(110, (128 * count / LIMIT_ROUTERS) - 128);
                                            if (isUnreachable || isOld || noSSU)
//                                                pdrop *= 3;
                                                pdrop *= 8;
                                            if (pdrop > 0 && (pdrop >= 128 || getContext().random().nextInt(128) < pdrop)) {
                                                if (_log.shouldWarn())
                                                    _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                                              " [" + key.toBase64().substring(0,6) + "] with distance " + distance);
                                                shouldStore = false;
                                                // still flood if requested
                                                if (_message.getReplyToken() > 0)
                                                    wasNew = true;
                                            }
                                        }
                                    }
                                }
                                if (shouldStore && _log.shouldDebug())
                                    _log.debug("Allowing unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                               " [" + key.toBase64().substring(0,6) + "] with distance " + distance);
                            } else {
                                if ((isFF && noSSU) || (isFF && isUnreachable)) {
                                    shouldStore = false;
                                    if (_log.shouldWarn()) {
                                        if (isFF && noSSU) {
                                            _log.warn("Dropping unsolicited NetDbStore of new Floodfill [" + key.toBase64().substring(0,6) +
                                                      "] -> SSU transport disabled");
                                        } else {
                                            _log.warn("Dropping unsolicited NetDbStore of new " + cap + " Floodfill [" + key.toBase64().substring(0,6) +
                                                      "] -> Unreachable");
                                        }
                                    }
                                }
                                if (isUnreachable && isOld) {
                                    shouldStore = false;
                                    if (_log.shouldWarn()) {
                                        _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                                  " [" + key.toBase64().substring(0,6) + "] -> Unreachable (" + v + ")");
                                    }
                                }
                                // non-ff
                                // up to 100% drop rate
                                int pdrop = (128 * count / LIMIT_ROUTERS) - 128;
                                if (isUnreachable || isOld || noSSU)
//                                    pdrop *= 3;
                                    pdrop *= 8;
                                if (pdrop > 0 && (pdrop >= 128 || getContext().random().nextInt(128) < pdrop)) {
                                    if (_log.shouldWarn())
                                        _log.warn("Dropping unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                                  " [" + key.toBase64().substring(0,6) + "] -> Drop probability: " + (pdrop * 100 / 128) + "%");
                                    shouldStore = false;
                                    // don't bother checking ban/blocklists.
                                    //wasNew = true;
                                }
                            }
                        }
                        if (shouldStore && _log.shouldWarn())
                            _log.warn("Handling unsolicited NetDbStore of new " + cap + (isFF ? " Floodfill" : " Router") +
                                      " [" + key.toBase64().substring(0,6) + "]");
                    } else if (prevNetDb.getPublished() >= ri.getPublished()) {
                        shouldStore = false;
                    }
                }
                if (shouldStore) {
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
                            if (_log.shouldInfo())
                                _log.warn("Blocklisting new peer [" + key.toBase64().substring(0,6) + "] \n" + ri);
                            else if (_log.shouldWarn())
                                _log.warn("Blocklisting new peer [" + key.toBase64().substring(0,6) + "]");
                            wasNew = false; // don't flood
                            shouldStore = false; // don't call heardAbout()
                        }
                    } else if (!forever) {
                        Collection<RouterAddress> oldAddr = prevNetDb.getAddresses();
                        Collection<RouterAddress> newAddr = ri.getAddresses();
                        if ((!newAddr.equals(oldAddr)) && getContext().blocklist().isBlocklisted(ri)) {
                            if (_log.shouldInfo())
                                _log.warn("New address received, blocklisting old peer [" + key.toBase64().substring(0,6) + "] \n" + ri);
                            else if (_log.shouldWarn())
                                _log.warn("New address received, blocklisting old peer [" + key.toBase64().substring(0,6) + "]");
                            wasNew = false; // don't flood
                            shouldStore = false; // don't call heardAbout()
                        }
                    }
                }
                if (shouldStore && ri.getCapabilities().indexOf(Router.CAPABILITY_REACHABLE) >= 0)
                    getContext().profileManager().heardAbout(key);
            } catch (UnsupportedCryptoException uce) {
                invalidMessage = uce.getMessage();
                dontBlamePeer = true;
            } catch (IllegalArgumentException iae) {
                invalidMessage = iae.getMessage();
            }
        } else {
            if (_log.shouldError())
                _log.error("Invalid DbStoreMessage data type - " + entry.getType() + ": " + _message);
            // don't ack or flood
            return;
        }

        long recvEnd = System.currentTimeMillis();
        getContext().statManager().addRateData("netDb.storeRecvTime", recvEnd-recvBegin);

        // ack even if invalid
        // in particular, ack our own RI (from PeerTestJob)
        // TODO any cases where we shouldn't?
        if (_message.getReplyToken() > 0)
            sendAck(key);
        long ackEnd = System.currentTimeMillis();

        if (_from != null)
            _fromHash = _from.getHash();
        if (_fromHash != null) {
            if (invalidMessage == null || dontBlamePeer) {
                getContext().profileManager().dbStoreReceived(_fromHash, wasNew);
                getContext().statManager().addRateData("netDb.storeHandled", ackEnd-recvEnd);
            } else {
                if (invalidMessage.contains("published over")) {
                    dontBlamePeer = true;
                    if (_log.shouldWarn())
                        _log.warn("Received stale RouterInfo from [" + _fromHash.toBase64().substring(0,6) + "] \n* " + invalidMessage);
                // Should we record in the profile?
                } else if (_log.shouldDebug()) {
                    _log.warn("Received INVALID data packet from [" + _fromHash.toBase64().substring(0,6) + "] \n* " + invalidMessage + _from);
                } else if (_log.shouldWarn()) {
                    _log.warn("Received INVALID data packet from [" + _fromHash.toBase64().substring(0,6) + "] \n* " + invalidMessage);
                }
            }
        } else if (invalidMessage != null && !dontBlamePeer) {
            if (_log.shouldWarn()) {
                if (invalidMessage.contains("published over"))
                    _log.warn("Received stale RouterInfo from [unknown] \n* " + invalidMessage);
                else
                    _log.warn("Received INVALID data packet from [unknown] \n* " + invalidMessage);
                }
        }

        // flood it
        if (invalidMessage == null &&
            _facade.floodfillEnabled() &&
            _message.getReplyToken() > 0) {
            if (wasNew) {
                // DOS prevention
                // Note this does not throttle the ack above
                if (_facade.shouldThrottleFlood(key)) {
                    if (_log.shouldWarn())
                        _log.warn("Too many recent stores, not flooding key: " + key);
                    getContext().statManager().addRateData("netDb.floodThrottled", 1);
                    return;
                }
                long floodBegin = System.currentTimeMillis();
                _facade.flood(entry);
                // ERR: see comment in HandleDatabaseLookupMessageJob regarding hidden mode
                //else if (!_message.getRouterInfo().isHidden())
                long floodEnd = System.currentTimeMillis();
                getContext().statManager().addRateData("netDb.storeFloodNew", floodEnd-floodBegin, 60*1000);
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
        // TODO just set to 0?
        // TODO we have no session to garlic wrap this with, needs new message
        msg.setArrival(getContext().clock().now() - getContext().random().nextInt(3*1000));
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
                if (_log.shouldWarn())
                    _log.warn("Received a DbStoreMessage with Reply token, but we're not a Floodfill\n* From: " + _from +
                              "\n* From Hash: " + _fromHash + "\n* Message: " + _message, new Exception());
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
            Job send = new SendMessageDirectJob(getContext(), msg, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY);
            send.runJob();
            if (msg2 != null) {
                Job send2 = new SendMessageDirectJob(getContext(), msg2, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY);
                send2.runJob();
            }
            return;
        }
        boolean isEstab = getContext().commSystem().isEstablished(toPeer);
        if (!isEstab && replyTunnel != null) {
            DatabaseEntry entry = _message.getEntry();
            int type = entry.getType();
            if (type == DatabaseEntry.KEY_TYPE_LEASESET || type == DatabaseEntry.KEY_TYPE_LS2) {
                // As of 0.9.42,
                // if reply GW and tunnel are in the LS, we can pick a different one from the LS,
                // so look for one that's connected to reduce connections
                LeaseSet ls = (LeaseSet) entry;
                int count = ls.getLeaseCount();
                if (count > 1) {
                    boolean found = false;
                    for (int i = 0; i < count; i++) {
                        Lease lease = ls.getLease(i);
                        if (lease.getGateway().equals(toPeer) && lease.getTunnelId().equals(replyTunnel)) {
                            found = true;
                            break;
                        }
                    }
                    if (found) {
                        //_log.warn("Looking for alternate to " + toPeer + " reply gw in LS with " + count + " leases");
                        for (int i = 0; i < count; i++) {
                            Lease lease = ls.getLease(i);
                            Hash gw = lease.getGateway();
                            if (gw.equals(toPeer))
                                continue;
                            if (lease.isExpired())
                                continue;
                            if (getContext().commSystem().isEstablished(gw)) {
                                // switch to use this lease instead
                                toPeer = gw;
                                replyTunnel = lease.getTunnelId();
                                isEstab = true;
                                break;
                            }
                        }
                        if (_log.shouldWarn()) {
                            if (isEstab)
                                _log.warn("Switched to alternative connected peer [" + toPeer.toBase64().substring(0,6) + "] in LeaseSet with " + count + " leases");
                            else
                                _log.warn("Alternative connected peer not found in LeaseSet with " + count + " leases");
                        }
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("Reply gateway " + toPeer + ' ' + replyTunnel + " not found in LeaseSet with " + count + " leases");
                    }
                }
            }
        }
        if (isEstab) {
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
            Job send = new SendMessageDirectJob(getContext(), out1, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY);
            send.runJob();
            if (msg2 != null) {
                Job send2 = new SendMessageDirectJob(getContext(), out2, toPeer, REPLY_TIMEOUT, MESSAGE_PRIORITY);
                send2.runJob();
            }
            return;
        }

            // pick tunnel with endpoint closest to toPeer
            TunnelInfo outTunnel = getContext().tunnelManager().selectOutboundExploratoryTunnel(toPeer);
            if (outTunnel == null) {
                if (_log.shouldWarn())
                    _log.warn("No Outbound tunnel could be found");
                return;
            }
            getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnel.getSendTunnelId(0),
                                                             replyTunnel, toPeer);
            if (msg2 != null)
                getContext().tunnelDispatcher().dispatchOutbound(msg2, outTunnel.getSendTunnelId(0),
                                                                 replyTunnel, toPeer);
    }

    public String getName() { return "Handle Floodfill DbStoreMessage"; }

    @Override
    public void dropped() {
        getContext().messageHistory().messageProcessingError(_message.getUniqueId(), _message.getClass().getName(), "Dropped due to overload");
    }
}
