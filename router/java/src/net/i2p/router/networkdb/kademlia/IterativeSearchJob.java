package net.i2p.router.networkdb.kademlia;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.crypto.EncType;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.kademlia.XORComparator;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.Job;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.router.util.RandomIterator;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

import net.i2p.router.NetworkDatabaseFacade;
import net.i2p.router.Router;
import net.i2p.router.peermanager.PeerProfile;

/**
 * A traditional Kademlia search that continues to search
 * when the initial lookup fails, by iteratively searching the
 * closer-to-the-key peers returned by the query in a DSRM.
 *
 * Unlike traditional Kad, it doesn't stop when there are no
 * closer keys, it keeps going until the timeout or max number
 * of searches is reached.
 *
 * Differences from FloodOnlySearchJob:
 * Chases peers in DSRM's immediately.
 * FOSJ searches the two closest in parallel and then stops.
 * There is no per-search timeout, only a total timeout.
 * Here, we search one at a time, and must have a separate per-search timeout.
 *
 * Advantages: Much more robust than FOSJ, especially in a large network
 * where not all floodfills are known. Longer total timeout.
 * Halves search traffic for successful searches, as this doesn't do
 * two searches in parallel like FOSJ does.
 *
 * Public only for JobQueue, not a public API, not for external use.
 *
 * @since 0.8.9
 */
public class IterativeSearchJob extends FloodSearchJob {
    /** peers not sent to yet, sorted closest-to-the-routing-key */
    private final SortedSet<Hash> _toTry;
    /** query sent, no reply yet */
    private final Set<Hash> _unheardFrom;
    /** query sent, failed, timed out, or got DSRM */
    private final Set<Hash> _failedPeers;
    /** the time the query was sent to a peer, which we need to update profiles correctly */
    private final Map<Hash, Long> _sentTime;
    /** the routing key */
    private final Hash _rkey;
    /** this is a marker to register with the MessageRegistry, it is never sent */
    private OutNetMessage _out;
    private final Hash _fromLocalDest;
    /** testing */
    private static Hash _alwaysQueryHash;
    /** Max number of peers to query */
    private final int _totalSearchLimit;
    private final MaskedIPSet _ipSet;
    private final Set<Hash> _skippedPeers;

//    private static final int MAX_NON_FF = 3;
    private static final int MAX_NON_FF = 4;
    /** Max number of peers to query */
//    private static final int TOTAL_SEARCH_LIMIT = 5;
    private static final int TOTAL_SEARCH_LIMIT = 4;
    /** Max number of peers to query if we are ff */
    private static final int TOTAL_SEARCH_LIMIT_WHEN_FF = 3;
    /** Extra peers to get from peer selector, as we may discard some before querying */
//    private static final int EXTRA_PEERS = 1;
    private static final int EXTRA_PEERS = 2;
    private static final int IP_CLOSE_BYTES = 3;
    /** TOTAL_SEARCH_LIMIT * SINGLE_SEARCH_TIME, plus some extra */
//    private static final int MAX_SEARCH_TIME = 30*1000;
    private static final int MAX_SEARCH_TIME = 20*1000;
    /**
     *  The time before we give up and start a new search - much shorter than the message's expire time
     *  Longer than the typ. response time of 1.0 - 1.5 sec, but short enough that we move
     *  on to another peer quickly.
     */
    private final long _singleSearchTime;
    /**
     * The default single search time
     */
    private static final long SINGLE_SEARCH_TIME = 3*1000;
    /** the actual expire time for a search message */
    private static final long SINGLE_SEARCH_MSG_TIME = 20*1000;
    /**
     *  Use instead of CONCURRENT_SEARCHES in super() which is final.
     *  For now, we don't do concurrent, but we keep SINGLE_SEARCH_TIME very short,
     *  so we have effective concurrency in that we fail a search quickly.
     */
    private final int _maxConcurrent;
    /**
     * The default _maxConcurrent
     */
    private static final int MAX_CONCURRENT = 1;

    public static final String PROP_ENCRYPT_RI = "router.encryptRouterLookups";

    /** only on fast boxes, for now */
    public static final boolean DEFAULT_ENCRYPT_RI =
            SystemVersion.isX86() && /* SystemVersion.is64Bit() && */
            !SystemVersion.isApache() && !SystemVersion.isGNU() &&
            NativeBigInteger.isNative();

    /**
     *  Lookup using exploratory tunnels
     */
    public IterativeSearchJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade, Hash key,
                              Job onFind, Job onFailed, int timeoutMs, boolean isLease) {
        this(ctx, facade, key, onFind, onFailed, timeoutMs, isLease, null);
    }

    /**
     *  Lookup using the client's tunnels.
     *  Do not use for RI lookups down client tunnels,
     *  as the response will be dropped in InboundMessageDistributor.
     *  @param fromLocalDest use these tunnels for the lookup, or null for exploratory
     *  @since 0.9.10
     */
    public IterativeSearchJob(RouterContext ctx, FloodfillNetworkDatabaseFacade facade, Hash key,
                              Job onFind, Job onFailed, int timeoutMs, boolean isLease, Hash fromLocalDest) {
        super(ctx, facade, key, onFind, onFailed, timeoutMs, isLease);
        RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(key);
        String v = ri.getVersion();
        String MIN_VERSION = "0.9.48";
        boolean uninteresting = ri.getCapabilities().indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 ||
                                ri.getAddresses().isEmpty() || ri.getCapabilities().indexOf(Router.CAPABILITY_BW12) >= 0 ||
                                ri.getCapabilities().indexOf(Router.CAPABILITY_BW32) >= 0 || VersionComparator.comp(v, MIN_VERSION) < 0;
        // these override the settings in super
        if (isLease)
            _timeoutMs = Math.min(timeoutMs * 2, MAX_SEARCH_TIME * 2);
        else if (uninteresting || VersionComparator.comp(v, MIN_VERSION) < 0)
            _timeoutMs = Math.min(timeoutMs / 2, MAX_SEARCH_TIME / 2);
        else
            _timeoutMs = Math.min(timeoutMs, MAX_SEARCH_TIME);
        _expiration = _timeoutMs + ctx.clock().now();
        _rkey = ctx.routingKeyGenerator().getRoutingKey(key);
        _toTry = new TreeSet<Hash>(new XORComparator<Hash>(_rkey));
        int known = ctx.netDb().getKnownRouters();
        int totalSearchLimit = (facade.floodfillEnabled() && ctx.router().getUptime() > 30*60*1000) ?
                                TOTAL_SEARCH_LIMIT_WHEN_FF : TOTAL_SEARCH_LIMIT;
        if (isLease)
            totalSearchLimit *= 3;
        else if (known < 2000)
            totalSearchLimit *= 2;
        else if (uninteresting) {
            totalSearchLimit /= 2;
        }
        _totalSearchLimit = ctx.getProperty("netdb.searchLimit", totalSearchLimit);
        _ipSet = new MaskedIPSet(2 * (_totalSearchLimit + EXTRA_PEERS));
        _singleSearchTime = ctx.getProperty("netdb.singleSearchTime", SINGLE_SEARCH_TIME);
        _maxConcurrent = ctx.getProperty("netdb.maxConcurrent", MAX_CONCURRENT);
        _unheardFrom = new HashSet<Hash>(CONCURRENT_SEARCHES);
        _failedPeers = new HashSet<Hash>(_totalSearchLimit);
        _skippedPeers = new HashSet<Hash>(4);
        _sentTime = new ConcurrentHashMap<Hash, Long>(_totalSearchLimit);
        _fromLocalDest = fromLocalDest;
        if (fromLocalDest != null && !isLease && _log.shouldLog(Log.WARN))
            _log.warn("Search for RouterInfo [" + key.toBase64().substring(0,6) + "] down client tunnel " + fromLocalDest, new Exception());
        // all createRateStat in FNDF
    }

    @Override
    public void runJob() {
        if (_facade.isNegativeCached(_key)) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Not searching for negative cached key [" + _key.toBase64().substring(0,6) + "]");
            failed();
            return;
        }
        // pick some floodfill peers and send out the searches
        List<Hash> floodfillPeers;
        KBucketSet<Hash> ks = _facade.getKBuckets();
        if (ks != null) {
            // Ideally we would add the key to an exclude list, so we don't try to query a ff peer for itself,
            // but we're passing the rkey not the key, so we do it below instead in certain cases.
            floodfillPeers = ((FloodfillPeerSelector)_facade.getPeerSelector()).selectFloodfillParticipants(_rkey, _totalSearchLimit + EXTRA_PEERS, ks);
        } else {
            floodfillPeers = new ArrayList<Hash>(_totalSearchLimit);
        }

        // For testing or local networks... we will
        // pretend that the specified router is floodfill, and always closest-to-the-key.
        // May be set after startup but can't be changed or unset later.
        // Warning - experts only!
        String alwaysQuery = getContext().getProperty("netDb.alwaysQuery");
        if (alwaysQuery != null) {
            if (_alwaysQueryHash == null) {
                byte[] b = Base64.decode(alwaysQuery);
                if (b != null && b.length == Hash.HASH_LENGTH)
                    _alwaysQueryHash = Hash.create(b);
            }
        }

        if (floodfillPeers.isEmpty()) {
            // ask anybody, they may not return the answer but they will return a few ff peers we can go look up,
            // so this situation should be temporary
            if (_log.shouldLog(Log.WARN))
                _log.warn("Running NetDb searches against the Floodfill peers, but we don't know any");
            List<Hash> all = new ArrayList<Hash>(_facade.getAllRouters());
            if (all.isEmpty()) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("No peers in NetDb - reseed required");
                failed();
                return;
            }
            Iterator<Hash> iter = new RandomIterator<Hash>(all);
            // Limit non-FF to 3, because we don't sort the FFs ahead of the non-FFS,
            // so once we get some FFs we want to be sure to query them
            for (int i = 0; iter.hasNext() && i < MAX_NON_FF; i++) {
                floodfillPeers.add(iter.next());
            }
        }
        final boolean empty;
        // outside sync to avoid deadlock
        final Hash us = getContext().routerHash();
        synchronized(this) {
            _toTry.addAll(floodfillPeers);
            // don't ask ourselves or the target
            _toTry.remove(us);
            _toTry.remove(_key);
            empty = _toTry.isEmpty();
        }
        if (empty) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("[Job " + getJobId() + "] IterativeSearch for [" + _key.toBase64().substring(0,6) + "] failed - no Floodfills in NetDb");
            // no floodfill peers, fail
            failed();
            return;
        }
        // This OutNetMessage is never used or sent (setMessage() is never called), it's only
        // so we can register a reply selector.
        MessageSelector replySelector = new IterativeLookupSelector(getContext(), this);
        ReplyJob onReply = new FloodOnlyLookupMatchJob(getContext(), this);
        Job onTimeout = new FloodOnlyLookupTimeoutJob(getContext(), this);
        _out = getContext().messageRegistry().registerPending(replySelector, onReply, onTimeout);
        if (_log.shouldLog(Log.INFO))
            _log.info("[Job " + getJobId() + "] New IterativeSearch for " +
                      (_isLease ? "LeaseSet [" : "Router [") + _key.toBase64().substring(0,6) + "]" +
                      "\n* Querying: "  + DataHelper.toString(_toTry).substring(0,6) + "]" +
                      " Routing key: [" + _rkey.toBase64().substring(0,6) + "]" +
                      " Timeout: " + DataHelper.formatDuration(_timeoutMs));
        retry();
    }

    /**
     *  Send lookups to one or more peers, up to the configured concurrent and total limits
     */
    private void retry() {
        long now = getContext().clock().now();
        if (_expiration < now) {
            failed();
            return;
        }
        if (_expiration - 500 < now)  {
            // not enough time left to bother
            return;
        }
        while (true) {
            Hash peer = null;
            final int done, pend;
            synchronized (this) {
                if (_dead) return;
                pend = _unheardFrom.size();
                if (pend >= _maxConcurrent)
                    return;
                done = _failedPeers.size();
            }
            if (done >= _totalSearchLimit) {
                failed();
                return;
            }
            // even if pend and todo are empty, we don't fail, as there may be more peers
            // coming via newPeerToTry()
            if (done + pend >= _totalSearchLimit)
                return;
            synchronized(this) {
                if (_alwaysQueryHash != null &&
                        !_unheardFrom.contains(_alwaysQueryHash) &&
                        !_failedPeers.contains(_alwaysQueryHash)) {
                    // For testing or local networks... we will
                    // pretend that the specified router is floodfill, and always closest-to-the-key.
                    // May be set after startup but can't be changed or unset later.
                    // Warning - experts only!
                    peer = _alwaysQueryHash;
                } else {
                    if (_toTry.isEmpty())
                        return;
                    for (Iterator<Hash> iter = _toTry.iterator(); iter.hasNext(); ) {
                        Hash h = iter.next();
                        iter.remove();
                        Set<String> peerIPs = new MaskedIPSet(getContext(), h, IP_CLOSE_BYTES);
                        if (!_ipSet.containsAny(peerIPs)) {
                            _ipSet.addAll(peerIPs);
                            peer = h;
                            break;
                        }
                        if (_log.shouldLog(Log.INFO))
                            _log.info("[Job " + getJobId() + "] Skipping query: Router [" +  h.toBase64().substring(0,6) + "] too close to others");
                        _skippedPeers.add(h);
                        // go around again
                    }
                    if (peer == null)
                        return;
                }
                _unheardFrom.add(peer);
            }
            sendQuery(peer);
        }
    }

    /**
     *  Send a DLM to the peer
     */
    private void sendQuery(Hash peer) {
            final RouterContext ctx = getContext();
            TunnelManagerFacade tm = ctx.tunnelManager();
            RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
            if (ri != null && ctx.commSystem().getStatus() != Status.DISCONNECTED) {
                // Now that most of the netdb is Ed RIs and EC LSs, don't even bother
                // querying old floodfills that don't know about those sig types.
                // This is also more recent than the version that supports encrypted replies,
                // so we won't request unencrypted replies anymore either.
                if (!StoreJob.shouldStoreTo(ri)) {
                    failed(peer, false);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("[Job " + getJobId() + "] Not sending query to old Router [" + ri.toBase64().substring(0,6) + "]");
                    return;
                }
            }
            TunnelInfo outTunnel;
            TunnelInfo replyTunnel;
            boolean isClientReplyTunnel;
            boolean isDirect;
            boolean supportsRatchet = false;
            boolean supportsElGamal = true;
            if (_fromLocalDest != null) {
                outTunnel = tm.selectOutboundTunnel(_fromLocalDest, peer);
                if (outTunnel == null)
                    outTunnel = tm.selectOutboundExploratoryTunnel(peer);
                LeaseSetKeys lsk = ctx.keyManager().getKeys(_fromLocalDest);
                supportsRatchet = lsk != null &&
                                  lsk.isSupported(EncType.ECIES_X25519) &&
                                  DatabaseLookupMessage.supportsRatchetReplies(ri);
                supportsElGamal = !supportsRatchet &&
                                  lsk != null &&
                                  lsk.isSupported(EncType.ELGAMAL_2048);
                if (supportsElGamal || supportsRatchet) {
                    // garlic encrypt to dest SKM
                    replyTunnel = tm.selectInboundTunnel(_fromLocalDest, peer);
                    isClientReplyTunnel = replyTunnel != null;
                    if (!isClientReplyTunnel)
                        replyTunnel = tm.selectInboundExploratoryTunnel(peer);
                } else {
                    // We don't have a way to request/get a ECIES-tagged reply,
                    // so send it to the router SKM
                    isClientReplyTunnel = false;
                    replyTunnel = tm.selectInboundExploratoryTunnel(peer);
                }
                isDirect = false;
            } else if ((!_isLease) && ri != null && ctx.commSystem().isEstablished(peer)) {
                // If it's a RI lookup, not from a client, and we're already connected, just ask directly
                // This also saves the ElG encryption for us and the decryption for the ff
                // There's no anonymity reason to use an expl. tunnel... the main reason
                // is to limit connections to the ffs. But if we're already connected,
                // do it the fast and easy way.
                outTunnel = null;
                replyTunnel = null;
                isClientReplyTunnel = false;
                isDirect = true;
                ctx.statManager().addRateData("netDb.RILookupDirect", 1);
            } else {
                outTunnel = tm.selectOutboundExploratoryTunnel(peer);
                replyTunnel = tm.selectInboundExploratoryTunnel(peer);
                isClientReplyTunnel = false;
                isDirect = false;
                ctx.statManager().addRateData("netDb.RILookupDirect", 0);
            }
            if ((!isDirect) && (replyTunnel == null || outTunnel == null)) {
                failed();
                return;
            }

            // As explained above, it's hard to keep the key itself out of the ff list,
            // so let's just skip it for now if the outbound tunnel is zero-hop.
            // Yes, that means we aren't doing double-lookup for a floodfill
            // if it happens to be closest to itself and we are using zero-hop exploratory tunnels.
            // If we don't, the OutboundMessageDistributor ends up logging erors for
            // not being able to send to the floodfill, if we don't have an older netdb entry.
            if (outTunnel != null && outTunnel.getLength() <= 1) {
                if (peer.equals(_key)) {
                    failed(peer, false);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("[Job " + getJobId() + "] Not doing zero-hop self-lookup of [" + peer.toBase64().substring(0,6) + "]");
                    return;
                }
                if (_facade.lookupLocallyWithoutValidation(peer) == null) {
                    failed(peer, false);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("[Job " + getJobId() + "] Not doing zero-hop lookup to unknown [" + peer.toBase64().substring(0,6) + "]");
                    return;
                }
            }

            DatabaseLookupMessage dlm = new DatabaseLookupMessage(ctx, true);
            if (isDirect) {
                dlm.setFrom(ctx.routerHash());
            } else {
                dlm.setFrom(replyTunnel.getPeer(0));
                dlm.setReplyTunnel(replyTunnel.getReceiveTunnelId(0));
            }
            long now = ctx.clock().now();
            dlm.setMessageExpiration(now + SINGLE_SEARCH_MSG_TIME);
            dlm.setSearchKey(_key);
            dlm.setSearchType(_isLease ? DatabaseLookupMessage.Type.LS : DatabaseLookupMessage.Type.RI);

            if (_log.shouldLog(Log.DEBUG)) {
                int tries;
                synchronized(this) {
                    tries = _unheardFrom.size() + _failedPeers.size();
                }
                _log.debug("[Job " + getJobId() + "] IterativeSearch for " + (_isLease ? "LeaseSet " : "Router ") +
                          "[" + _key.toBase64().substring(0,6) + "] (attempt " + tries + ")" +
                          "\n* Querying: [" + peer.toBase64().substring(0,6) + "]" +
                          " Direct? " + isDirect + " Reply via client tunnel? " + isClientReplyTunnel);
            }
            _sentTime.put(peer, Long.valueOf(now));

            EncType type = ri != null ? ri.getIdentity().getPublicKey().getType() : null;
            boolean encryptElG = ctx.getProperty(PROP_ENCRYPT_RI, DEFAULT_ENCRYPT_RI);
            I2NPMessage outMsg = null;
            if (isDirect) {
                // never wrap
            } else if (_isLease ||
                       (encryptElG && type == EncType.ELGAMAL_2048 && ctx.jobQueue().getMaxLag() < 300) ||
                       type == EncType.ECIES_X25519) {
                // Full ElG is fairly expensive so only do it for LS lookups
                // and for RI lookups on fast boxes.
                // if we have the ff RI, garlic encrypt it
                if (ri != null) {
                    // request encrypted reply
                    // now covered by version check above, which is more recent
                    //if (DatabaseLookupMessage.supportsEncryptedReplies(ri)) {
                    if (!(type == EncType.ELGAMAL_2048 || (type == EncType.ECIES_X25519 && DatabaseLookupMessage.USE_ECIES_FF))) {
                        failed(peer, false);
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("[Job " + getJobId() + "] Can't do encrypted lookup to [" + peer.toBase64().substring(0,6) + "] with EncType " + type);
                        return;
                    }

                    MessageWrapper.OneTimeSession sess;
                    if (isClientReplyTunnel) {
                        sess = MessageWrapper.generateSession(ctx, _fromLocalDest, SINGLE_SEARCH_MSG_TIME, !supportsRatchet);
                    } else {
                        EncType ourType = ctx.keyManager().getPublicKey().getType();
                        boolean ratchet1 = ourType.equals(EncType.ECIES_X25519);
                        boolean ratchet2 = DatabaseLookupMessage.supportsRatchetReplies(ri);
                        if (ratchet1 && !ratchet2) {
                            failed(peer, false);
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("[Job " + getJobId() + "] Can't do encrypted lookup to [" + peer.toBase64().substring(0,6) + "] -> does not support AEAD replies");
                            return;
                        }
                        supportsRatchet = ratchet1 && ratchet2;
                        sess = MessageWrapper.generateSession(ctx, ctx.sessionKeyManager(), SINGLE_SEARCH_MSG_TIME, !supportsRatchet);
                    }
                    if (sess != null) {
                        if (sess.tag != null) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("[Job " + getJobId() + "] Requesting AES reply from [" + peer.toBase64().substring(0,6) + "]"
                                + "\n* Session key: [" + sess.key.toBase64().substring(0,6) + "] Tag: [" + sess.tag.toString().substring(0,6) + "]");
                            dlm.setReplySession(sess.key, sess.tag);
                        } else {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("[Job " + getJobId() + "] Requesting AEAD reply from [" + peer.toBase64().substring(0,6) + "]"
                                + "\n* Session key: [" + sess.key.toBase64().substring(0,6) + "] Tag: [" + sess.rtag.toString().substring(0,6) + "]");
                            dlm.setReplySession(sess.key, sess.rtag);
                        }
                    } else {
                        if (_log.shouldWarn())
                            _log.warn("[Job " + getJobId() + "] Failed encrypt to " + ri);
                        // client went away, but send it anyway
                    }

                    outMsg = MessageWrapper.wrap(ctx, dlm, ri);
                    // ElG can take a while so do a final check before we send it,
                    // a response may have come in.
                    if (_dead) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("[Job " + getJobId() + "] Aborting send - finished while wrapping msg to [" + peer.toBase64().substring(0,6) + "]");
                        return;
                    }
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("[Job " + getJobId() + "] Encrypted DbLookupMsg for [" + _key.toBase64().substring(0,6) + "] sent to [" + peer.toBase64().substring(0,6) + "]");
                }
            }
            if (outMsg == null)
                outMsg = dlm;
            if (isDirect) {
                OutNetMessage m = new OutNetMessage(ctx, outMsg, outMsg.getMessageExpiration(),
                                                    OutNetMessage.PRIORITY_MY_NETDB_LOOKUP, ri);
                // Should always succeed, we are connected already
                //m.setOnFailedReplyJob(onFail);
                //m.setOnFailedSendJob(onFail);
                //m.setOnReplyJob(onReply);
                //m.setReplySelector(selector);
                //getContext().messageRegistry().registerPending(m);
                ctx.commSystem().processMessage(m);
            } else {
                ctx.tunnelDispatcher().dispatchOutbound(outMsg, outTunnel.getSendTunnelId(0), peer);
            }

            // The timeout job is always run (never cancelled)
            // Note that the timeout is much shorter than the message expiration (see above)
            Job j = new IterativeTimeoutJob(ctx, peer, this);
            long expire = Math.min(_expiration, now + _singleSearchTime);
            j.getTiming().setStartAfter(expire);
            getContext().jobQueue().addJob(j);

    }

    @Override
    public String getName() { return "Start Iterative Search"; }

    /**
     *  Note that the peer did not respond with a DSM
     *  (either a DSRM, timeout, or failure).
     *  This is not necessarily a total failure of the search.
     *  @param timedOut if true, will blame the peer's profile
     */
    void failed(Hash peer, boolean timedOut) {
        boolean isNewFail;
        synchronized (this) {
            if (_dead) return;
            _unheardFrom.remove(peer);
            isNewFail = _failedPeers.add(peer);
        }
        if (isNewFail) {
            if (timedOut) {
                getContext().profileManager().dbLookupFailed(peer);
                if (_log.shouldLog(Log.INFO))
                    _log.info("[Job " + getJobId() + "] Search for Router [" + peer.toBase64().substring(0,6) + "] timed out");
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("[Job " + getJobId() + "] Search for Router [" + peer.toBase64().substring(0,6) + "] failed");
            }
        }
        retry();
    }

    /**
     *  A new (floodfill) peer was discovered that may have the answer.
     *  @param peer may not actually be new
     */
    void newPeerToTry(Hash peer) {
        // don't ask ourselves or the target
        if (peer.equals(getContext().routerHash()) ||
            peer.equals(_key))
            return;
        if (getContext().banlist().isBanlistedForever(peer)) {
            if (_log.shouldLog(Log.INFO))
                _log.info("[Job " + getJobId() + "] Banlisted peer from DbSearchReplyMsg [" + peer.toBase64().substring(0,6) + "]");
            return;
        }
        RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(peer);
        if (ri != null && !FloodfillNetworkDatabaseFacade.isFloodfill(ri)) {
            if (_log.shouldLog(Log.INFO))
                _log.info("[Job " + getJobId() + "] Non-Floodfill peer from DbSearchReplyMsg [" + peer.toBase64().substring(0,6) + "]");
            return;
        }
        synchronized (this) {
            if (_failedPeers.contains(peer) ||
                _unheardFrom.contains(peer) ||
                _skippedPeers.contains(peer))
                return;  // already tried or skipped
            if (!_toTry.add(peer))
                return;  // already in the list
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("[Job " + getJobId() + "] New Router [" + peer.toBase64().substring(0,6) + "] from DbSearchReplyMsg - Known? " + (ri != null));
        retry();
    }

    /**
     *  Hash of the dest this query is from
     *  @return null for router
     *  @since 0.9.13
     */
    public Hash getFromHash() {
        return _fromLocalDest;
    }

    /**
     *  Did we send a request to this peer?
     *  @since 0.9.13
     */
    public boolean wasQueried(Hash peer) {
        synchronized (this) {
            return _unheardFrom.contains(peer) || _failedPeers.contains(peer);
        }
    }

    /**
     *  When did we send the query to the peer?
     *  @return context time, or -1 if never sent
     */
    long timeSent(Hash peer) {
        Long rv = _sentTime.get(peer);
        return rv == null ? -1 : rv.longValue();
    }

    /**
     *  Dropped by the job queue
     *  @since 0.9.31
     */
    @Override
    public void dropped() {
        failed();
    }

    /**
     *  Total failure
     */
    @Override
    void failed() {
        synchronized (this) {
            if (_dead) return;
            _dead = true;
        }
        _facade.complete(_key);
        if (getContext().commSystem().getStatus() != Status.DISCONNECTED)
            _facade.lookupFailed(_key);
        getContext().messageRegistry().unregisterPending(_out);
        int tries;
        final List<Hash> unheard;
        synchronized(this) {
            tries = _unheardFrom.size() + _failedPeers.size();
            unheard = new ArrayList<Hash>(_unheardFrom);
        }
        // blame the unheard-from (others already blamed in failed() above)
        for (Hash h : unheard) {
            getContext().profileManager().dbLookupFailed(h);
        }
        long time = System.currentTimeMillis() - _created;
        if (_log.shouldLog(Log.INFO)) {
            long timeRemaining = _expiration - getContext().clock().now();
            _log.info("[Job " + getJobId() + "] IterativeSearch for [" + _key.toBase64().substring(0,6) + "] failed" +
                      "\n* Peers queried: " + tries + " Time taken: " + (time / 1000) + "s (" + (timeRemaining / 1000) + "s remaining)");
        }
        if (tries > 0) {
            // don't bias the stats with immediate fails
            getContext().statManager().addRateData("netDb.failedTime", time);
            getContext().statManager().addRateData("netDb.failedRetries", tries - 1);
        }
        for (Job j : _onFailed) {
            getContext().jobQueue().addJob(j);
        }
        _onFailed.clear();
    }

    @Override
    void success() {
        // Sadly, we don't know for sure which one replied.
        // If the reply is after expiration (which moves the hash from _unheardFrom to _failedPeers),
        // we will credit the wrong one.
        int tries;
        Hash peer = null;
        synchronized(this) {
            if (_dead) return;
            _dead = true;
            _success = true;
            tries = _unheardFrom.size() + _failedPeers.size();
            if (_unheardFrom.size() == 1) {
                peer = _unheardFrom.iterator().next();
                _unheardFrom.clear();
            }
        }
        _facade.complete(_key);
        if (peer != null) {
            Long timeSent = _sentTime.get(peer);
            if (timeSent != null)
                getContext().profileManager().dbLookupSuccessful(peer, getContext().clock().now() - timeSent.longValue());
        }
        long time = System.currentTimeMillis() - _created;
        if (_log.shouldLog(Log.INFO))
            _log.info("[Job " + getJobId() + "] IterativeSearch for [" + _key.toBase64().substring(0,6) + "] succeeded" +
                      "\n* Peers queried: " + tries + " Time taken: " + (time / 1000) + "s");
        getContext().statManager().addRateData("netDb.successTime", time);
        getContext().statManager().addRateData("netDb.successRetries", tries - 1);
        for (Job j : _onFind) {
            getContext().jobQueue().addJob(j);
        }
        _onFind.clear();
    }
}
