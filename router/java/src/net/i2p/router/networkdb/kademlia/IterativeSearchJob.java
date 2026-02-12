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
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.kademlia.XORComparator;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.Job;
import net.i2p.router.LeaseSetKeys;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.ReplyJob;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.util.MaskedIPSet;
import net.i2p.router.util.RandomIterator;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Traditional Kademlia iterative search with enhanced robustness.
 * <p>
 * Continues searching when initial lookup fails by iteratively querying
 * closer-to-the-key peers returned in DatabaseSearchReplyMessages. Unlike traditional
 * Kademlia, doesn't stop when no closer keys are available - keeps searching
 * until timeout or maximum search limit is reached.
 * <p>
 * Key differences from FloodOnlySearchJob:
 * - Chases peers in DSRM immediately rather than waiting
 * - Searches one peer at a time with individual per-search timeouts
 * - Uses total timeout rather than per-search timeouts
 * - Longer overall timeout for better success rates
 * - Reduces search traffic by half compared to parallel approaches
 * <p>
 * Advantages over flood-only approaches:
 * - More robust in large networks with incomplete floodfill knowledge
 * - Better success rates through sequential searching
 * - Reduced network traffic through single-peer queries
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

    private static final int MAX_NON_FF = 5;
    /** Max number of peers to query */
    private static final int TOTAL_SEARCH_LIMIT = 14;
    /** Max number of peers to query if we are ff */
    private static final int TOTAL_SEARCH_LIMIT_WHEN_FF = 10;
    /** Quadruple limits when under attack (netDb attack mode) */
    private static final int TOTAL_SEARCH_LIMIT_ATTACK = 40;
    private static final int TOTAL_SEARCH_LIMIT_WHEN_FF_ATTACK = 40;
    /** Extra peers to get from peer selector, as we may discard some before querying */
    private static final int EXTRA_PEERS = 2;
    private static final int IP_CLOSE_BYTES = 3;
    /** Per-peer timeout in ms - doubled when under attack */
    private static final int MAX_SEARCH_TIME = 15*1000;
    private static final int MAX_SEARCH_TIME_ATTACK = 30*1000;
    /**
     *  The time before we give up and start a new search - much shorter than the message's expire time
     *  Longer than the typ. response time of 1.0 - 1.5 sec, but short enough that we move
     *  on to another peer quickly.
     */
    private final long _singleSearchTime;

    /**
     * The default single search time
     */
    private static final long SINGLE_SEARCH_TIME = 4*1000;
    private static final long MIN_SINGLE_SEARCH_TIME = 1000;

    /** The actual expire time for a search message */
    private static final long SINGLE_SEARCH_MSG_TIME = 15*1000;

    /**
     *  Use instead of CONCURRENT_SEARCHES in super() which is final.
     *  For now, we don't do concurrent, but we keep SINGLE_SEARCH_TIME very short,
     *  so we have effective concurrency in that we fail a search quickly.
     */
    private int _maxConcurrent;
    /**
     * The default _maxConcurrent
     */
    private static final int MAX_CONCURRENT = SystemVersion.isSlow() ? 2 : 4;

    public static final String PROP_ENCRYPT_RI = "router.encryptRouterLookups";

    /** only on fast boxes, for now */
    public static final boolean DEFAULT_ENCRYPT_RI = NativeBigInteger.isNative();

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
        int known = ctx.netDb().getKnownRouters();
        double buildSuccess = ctx.profileOrganizer().getTunnelBuildSuccess();
        boolean isUnderAttack = buildSuccess < 0.40;
        int totalSearchLimit = (facade.floodfillEnabled() && ctx.router().getUptime() > 30*60*1000) ?
                                TOTAL_SEARCH_LIMIT_WHEN_FF : TOTAL_SEARCH_LIMIT;
        if (isUnderAttack) {
            totalSearchLimit = TOTAL_SEARCH_LIMIT_ATTACK;
        }
        boolean isHidden = ctx.router().isHidden();
        boolean isSlow = SystemVersion.isSlow();
        long lag = ctx.jobQueue().getMaxLag();
        _timeoutMs = isUnderAttack ? Math.min(timeoutMs * 3, MAX_SEARCH_TIME_ATTACK) : Math.min(timeoutMs * 3, MAX_SEARCH_TIME);
        _expiration = _timeoutMs + ctx.clock().now();
        _rkey = ctx.routingKeyGenerator().getRoutingKey(key);
        _toTry = new TreeSet<Hash>(new XORComparator<Hash>(_rkey));
        _totalSearchLimit = ctx.getProperty("netdb.searchLimit", totalSearchLimit);
        _ipSet = new MaskedIPSet(2 * (_totalSearchLimit + EXTRA_PEERS));
        _singleSearchTime = ctx.getProperty("netdb.singleSearchTime", SINGLE_SEARCH_TIME);
        _unheardFrom = new HashSet<Hash>(CONCURRENT_SEARCHES);
        _failedPeers = new HashSet<Hash>(_totalSearchLimit);
        _skippedPeers = new HashSet<Hash>(4);
        _sentTime = new ConcurrentHashMap<Hash, Long>(_totalSearchLimit);
        _fromLocalDest = fromLocalDest;
        int baseConcurrent = (ctx.router().getUptime() > 30*60*1000 || known > 1000) ? MAX_CONCURRENT : MAX_CONCURRENT + 1;
        if (isUnderAttack) {
            baseConcurrent *= 2;
        }
        _maxConcurrent = ctx.getProperty("netdb.maxConcurrent", baseConcurrent);
        if (fromLocalDest != null && !isLease && _log.shouldWarn()) {
            _log.warn("IterativeSearch for RouterInfo [" + key.toBase64().substring(0,6) + "] down client tunnel " + fromLocalDest, new Exception());
        }
        // All createRateStat in FNDF
    }

    @Override
    public void runJob() {
        if (_facade.isNegativeCached(_key)) {
            if (_log.shouldDebug()) {
                _log.debug("Not searching for negative cached key for Router [" + _key.toBase64().substring(0,6) + "]");
            }
            cancelJob();
            return;
        }

        if (getContext().banlist().isBanlisted(_key)) {
            if (_log.shouldDebug()) {
                _log.debug("Not searching for banlisted Router [" + _key.toBase64().substring(0,6) + "]");
            }
            cancelJob();
            return;
        }

        String MIN_VERSION = "0.9.65";
        boolean isHidden = getContext().router().isHidden();
        RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(_key);
        RouterInfo isUs = getContext().netDb().lookupRouterInfoLocally(getContext().routerHash());
        long uptime = getContext().router().getUptime();
        boolean enableReverseLookups = getContext().getBooleanProperty("routerconsole.enableReverseLookups");

        if (ri != null && ri != isUs) {
            String v = ri.getVersion();
            String caps = ri.getCapabilities();
            boolean uninteresting = caps != null && (caps.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0 ||
                                    caps.indexOf(Router.CAPABILITY_BW12) >= 0 ||
                                    caps.indexOf(Router.CAPABILITY_BW32) >= 0 ||
                                    (v.equals("") || VersionComparator.comp(v, MIN_VERSION) < 0)) &&
                                    !isHidden && getContext().netDb().getKnownRouters() > 1000;

            if (enableReverseLookups) {
                RouterAddress address = null;
                for (RouterAddress ra : ri.getAddresses()) {
                    if (ra.getTransportStyle().contains("SSU")) {
                        address = ra;
                        break;
                    }
                }
                if (address != null) {
                    String ipAddress = address.getHost();
                    if (ipAddress != null && !ipAddress.isEmpty()) {
                        String rdns = getContext().commSystem().getCanonicalHostName(ipAddress);
                        if (_log.shouldInfo()) {
                            _log.info("Reverse DNS for " + ipAddress + ": " + rdns);
                        }
                    }
                }
            }

            if (uninteresting) {
                if (_log.shouldInfo()) {
                    _log.info("Skipping search for uninteresting Router [" + _key.toBase64().substring(0,6) + "]");
                }
                cancelJob();
                return;
            }
        }

        // Pick some floodfill peers and send out the searches
        List<Hash> floodfillPeers;
        KBucketSet<Hash> ks = _facade.getKBuckets();
        if (ks != null) {
            // Ideally we would add the key to an exclude list, so we don't try to query a ff peer for itself,
            // but we're passing the rkey not the key, so we do it below instead in certain cases.
            floodfillPeers = ((FloodfillPeerSelector)_facade.getPeerSelector()).selectFloodfillParticipants(_rkey, _totalSearchLimit + EXTRA_PEERS, ks);
        } else {floodfillPeers = new ArrayList<Hash>(_totalSearchLimit);}

        /*
         * For testing or local networks... we will pretend that the specified router is floodfill,
         * and always closest-to-the-key. May be set after startup but can't be changed or unset later.
         * Warning - experts only!
         */
        String alwaysQuery = getContext().getProperty("netDb.alwaysQuery");
        if (alwaysQuery != null) {
            if (_alwaysQueryHash == null) {
                byte[] b = Base64.decode(alwaysQuery);
                if (b != null && b.length == Hash.HASH_LENGTH) {_alwaysQueryHash = Hash.create(b);}
            }
        }

        if (floodfillPeers.isEmpty()) {
            // Ask anybody, they may not return the answer but they will return a few ff peers we can go look up,
            // so this situation should be temporary
            if (_log.shouldWarn() && uptime > 2*60*1000) {
                _log.warn("Cannot query remote floodfills -> None known (this should resolve shortly)");
            }
            List<Hash> all = new ArrayList<Hash>(_facade.getAllRouters());
            if (all.isEmpty()) {
                if (_log.shouldLog(Log.ERROR) && uptime > 3*60*1000) {
                  _log.error("No peers in NetDb - reseed required");
                }
                failed();
                return;
            }
            Iterator<Hash> iter = new RandomIterator<Hash>(all);
            // Limit non-FF to 3, because we don't sort the FFs ahead of the non-FFS,
            // so once we get some FFs we want to be sure to query them
            for (int i = 0; iter.hasNext() && i < MAX_NON_FF; i++) {floodfillPeers.add(iter.next());}
        }
        final boolean empty;
        // Outside sync to avoid deadlock
        final Hash us = getContext().routerHash();
        synchronized(this) {
            _toTry.addAll(floodfillPeers);
            // Don't ask ourselves or the target
            _toTry.remove(us);
            _toTry.remove(_key);
            empty = _toTry.isEmpty();
        }
        if (empty) {
            if (_log.shouldWarn()) {
                _log.warn("IterativeSearch for " + (_isLease ? "LeaseSet " : "Router") +
                          " [" + _key.toBase64().substring(0,6) + "] failed -> No Floodfills in NetDb");
            }
            failed(); // No floodfill peers, fail
            return;
        }
        // This OutNetMessage is never used or sent (setMessage() is never called), it's only
        // so we can register a reply selector.
        MessageSelector replySelector = new IterativeLookupSelector(getContext(), this);
        ReplyJob onReply = new FloodOnlyLookupMatchJob(getContext(), this);
        Job onTimeout = new FloodOnlyLookupTimeoutJob(getContext(), this);
        _out = getContext().messageRegistry().registerPending(replySelector, onReply, onTimeout);
        if (_log.shouldInfo())
            _log.info("New IterativeSearch for " + (_isLease ? "LeaseSet" : "Router") +
                      " [" + _key.toBase64().substring(0,6) + "]" +
                      "\n* Querying: "  + DataHelper.toString(_toTry).substring(0,6) + "]" +
                      "; Routing key: [" + _rkey.toBase64().substring(0,6) + "]" +
                      "; Timeout: " + DataHelper.formatDuration(_timeoutMs) +
                      " (DbId: " + _facade + ")");
        retry();
    }

    /**
     *  Send lookups to one or more peers, up to the configured concurrent and total limits
     */
    private void retry() {
        long now = getContext().clock().now();
        if (_expiration < now) {
            cancelJob();
            return;
        }
        if (_expiration - MIN_SINGLE_SEARCH_TIME < now) { // not enough time left to bother
          cancelJob();
          return;
        }
        if (_expiration - 1500 < now)  {_expiration = 1500;}
        while (true) {
            Hash peer = null;
            final int done, pend;
            synchronized (this) {
                if (_dead) {return;}
                pend = _unheardFrom.size();
                if (pend >= _maxConcurrent) {return;}
                done = _failedPeers.size();
            }
            if (done >= _totalSearchLimit) {
                cancelJob();
                return;
            }
            // Even if pend and todo are empty, we don't fail, as there may be more peers coming via newPeerToTry()
            if (done + pend >= _totalSearchLimit) {return;}
            synchronized(this) {
                if (_alwaysQueryHash != null && !_unheardFrom.contains(_alwaysQueryHash) &&
                    !_failedPeers.contains(_alwaysQueryHash)) {
                    /*
                     * For testing or local networks... we will pretend that the specified router is floodfill,
                     * and always closest-to-the-key. May be set after startup but can't be changed or unset later.
                     * Warning - experts only!
                     */
                    peer = _alwaysQueryHash;
                } else {
                    if (_toTry.isEmpty()) {return;}
                    for (Iterator<Hash> iter = _toTry.iterator(); iter.hasNext(); ) {
                        Hash h = iter.next();
                        iter.remove();
                        Set<String> peerIPs = new MaskedIPSet(getContext(), h, IP_CLOSE_BYTES);
                        if (!_ipSet.containsAny(peerIPs)) {
                            _ipSet.addAll(peerIPs);
                            peer = h;
                            break;
                        }
                        if (_log.shouldInfo()) {
                            _log.info("Skipping query: Router [" +  h.toBase64().substring(0,6) + "] is too close to others");
                        }
                        _skippedPeers.add(h);
                        // go around again
                    }
                    if (peer == null) {return;}
                }
                _unheardFrom.add(peer);
            }
            sendQuery(peer, done + pend);
        }
    }

    /**
     *  Send a DLM to the peer
     *
     *  @param peer who to send to
     *  @param previouslyTried how many did we send to before this one?
     *  @since 0.9.53 added previouslyTried param
     */
    private void sendQuery(Hash peer, int previouslyTried) {
        final RouterContext ctx = getContext();
        TunnelManagerFacade tm = ctx.tunnelManager();
        RouterInfo ri = ctx.netDb().lookupRouterInfoLocally(peer);
        if (ri != null && ctx.commSystem().getStatus() != Status.DISCONNECTED) {
            /*
             * Now that most of the netdb is Ed RIs and EC LSs, don't even bother
             * querying old floodfills that don't know about those sig types.
             * This is also more recent than the version that supports encrypted replies,
             * so we won't request unencrypted replies anymore either.
             */
            if (!StoreJob.shouldStoreTo(ri)) {
                failed(peer, false);
                if (_log.shouldDebug()) {
                    _log.debug("Not sending query to old Router [" + ri.toBase64().substring(0,6) + "]");
                }
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
            // For all tunnel selections, the first time we pick the tunnel with the far-end closest
            // to the target. After that we pick a random tunnel, or else we'd pick the same tunnels every time.
            if (previouslyTried <= 0) {outTunnel = tm.selectOutboundTunnel(_fromLocalDest, peer);}
            else {outTunnel = tm.selectOutboundTunnel(_fromLocalDest);}
            if (outTunnel == null) {
                if (previouslyTried <= 0) {outTunnel = tm.selectOutboundExploratoryTunnel(peer);}
                else {outTunnel = tm.selectOutboundTunnel();}
            }
            LeaseSetKeys lsk = ctx.keyManager().getKeys(_fromLocalDest);
            supportsRatchet = lsk != null &&
                                  (lsk.isSupported(EncType.ECIES_X25519) || lsk.getPQDecryptionKey() != null) &&
                              DatabaseLookupMessage.supportsRatchetReplies(ri);
            supportsElGamal = !supportsRatchet &&
                              lsk != null &&
                              lsk.isSupported(EncType.ELGAMAL_2048);
            if (supportsElGamal || supportsRatchet) {
                // garlic encrypt to dest SKM
                if (previouslyTried <= 0) {replyTunnel = tm.selectInboundTunnel(_fromLocalDest, peer);}
                else {replyTunnel = tm.selectInboundTunnel(_fromLocalDest);}
                isClientReplyTunnel = replyTunnel != null;
                if (!isClientReplyTunnel) {
                    if (previouslyTried <= 0) {replyTunnel = tm.selectInboundExploratoryTunnel(peer);}
                    else {replyTunnel = tm.selectInboundTunnel();}
                }
            } else {
                // We don't have a way to request/get a ECIES-tagged reply, so send it to the router SKM
                isClientReplyTunnel = false;
                if (previouslyTried <= 0) {replyTunnel = tm.selectInboundExploratoryTunnel(peer);}
                else {replyTunnel = tm.selectInboundTunnel();}
            }
            isDirect = false;
        } else if ((!_isLease) && ri != null && ctx.commSystem().isEstablished(peer)) {
            /**
             * If it's a RI lookup, not from a client, and we're already connected, just ask directly
             * This also saves the ElG encryption for us and the decryption for the ff
             *
             * There's no anonymity reason to use an expl. tunnel... the main reason is to limit
             * connections to the ffs. But if we're already connected, do it the fast and easy way.
             */
            outTunnel = null;
            replyTunnel = null;
            isClientReplyTunnel = false;
            isDirect = true;
            if (_facade.isClientDb() && _log.shouldWarn()) {
                _log.warn("Warning! Direct search selected in a client NetDb context! (DbId: " + _facade + ")");
            }
            ctx.statManager().addRateData("netDb.RILookupDirect", 1);
        } else {
            if (previouslyTried <= 0) {
                outTunnel = tm.selectOutboundExploratoryTunnel(peer);
                replyTunnel = tm.selectInboundExploratoryTunnel(peer);
            } else {
                outTunnel = tm.selectOutboundTunnel();
                replyTunnel = tm.selectInboundTunnel();
            }
            isClientReplyTunnel = false;
            isDirect = false;
            ctx.statManager().addRateData("netDb.RILookupDirect", 0);
        }
        if ((!isDirect) && (replyTunnel == null || outTunnel == null)) {
            failed();
            return;
        }

        /*
         * As explained above, it's hard to keep the key itself out of the ff list, so let's just
         * skip it for now if the outbound tunnel is zero-hop. Yes, that means we aren't doing
         * double-lookup for a floodfill if it happens to be closest to itself and we are using
         * zero-hop exploratory tunnels. If we don't, the OutboundMessageDistributor ends up logging
         * errors for not being able to send to the floodfill, if we don't have an older netdb entry.
         */
        if (outTunnel != null && outTunnel.getLength() <= 1) {
            if (peer != null && peer.equals(_key)) {
                failed(peer, false);
                if (_log.shouldWarn()) {
                    _log.warn("Not sending zero-hop self-lookup of [" + peer.toBase64().substring(0,6) + "]");
                }
                return;
            }
            if (peer != null && _facade.lookupLocallyWithoutValidation(peer) == null) {
                failed(peer, false);
                // Log at debug level - this is expected when peers are banned/removed from NetDB
                if (_log.shouldDebug()) {
                    _log.debug("Not sending zero-hop lookup of [" + peer.toBase64().substring(0,6) + "] to UNKNOWN");
                }
                return;
            }
        }

        DatabaseLookupMessage dlm = new DatabaseLookupMessage(ctx, true);
        if (isDirect) {dlm.setFrom(ctx.routerHash());}
        else {
            dlm.setFrom(replyTunnel.getPeer(0));
            dlm.setReplyTunnel(replyTunnel.getReceiveTunnelId(0));
        }
        long now = ctx.clock().now();
        dlm.setMessageExpiration(now + SINGLE_SEARCH_MSG_TIME);
        dlm.setSearchKey(_key);
        dlm.setSearchType(_isLease ? DatabaseLookupMessage.Type.LS : DatabaseLookupMessage.Type.RI);

        if (_log.shouldDebug()) {
            int tries;
            synchronized(this) {tries = _unheardFrom.size() + _failedPeers.size();}
            if (_key != null && peer != null) {
                _log.debug("IterativeSearch for " + (_isLease ? "LeaseSet " : "Router ") +
                           " [" + _key.toBase64().substring(0,6) + "] (attempt " + tries + ")" +
                           "\n* Querying: [" + peer.toBase64().substring(0,6) + "]" +
                           "; Direct? " + isDirect + "; Reply via client tunnel? " + isClientReplyTunnel);
            }
        }
        if (peer != null) {_sentTime.put(peer, Long.valueOf(now));}

        EncType type = ri != null ? ri.getIdentity().getPublicKey().getType() : null;
        boolean encryptElG = ctx.getProperty(PROP_ENCRYPT_RI, DEFAULT_ENCRYPT_RI);
        I2NPMessage outMsg = null;
        if (isDirect) {} // never wrap
        else if (_isLease || (encryptElG && type == EncType.ELGAMAL_2048 && ctx.jobQueue().getMaxLag() < 300) || type == EncType.ECIES_X25519) {
            /*
             * Full ElG is fairly expensive so only do it for LS lookups and for RI lookups on fast boxes.
             * If we have the ff RI, garlic encrypt it.
             */
            if (ri != null) {
                // Request encrypted reply - now covered by version check above, which is more recent
                if (!(type == EncType.ELGAMAL_2048 || (type == EncType.ECIES_X25519 && DatabaseLookupMessage.USE_ECIES_FF))) {
                    failed(peer, false);
                    if (_log.shouldWarn()) {
                        _log.warn("Can't do encrypted lookup to [" + peer.toBase64().substring(0,6) + "] with EncType " + type);
                    }
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
                        if (_log.shouldWarn()) {
                            _log.warn("Can't do encrypted lookup to [" + peer.toBase64().substring(0,6) +
                                      "] -> Router does not support AEAD replies");
                        }
                        return;
                    }
                    supportsRatchet = ratchet1 && ratchet2;
                    sess = MessageWrapper.generateSession(ctx, ctx.sessionKeyManager(), SINGLE_SEARCH_MSG_TIME, !supportsRatchet);
                }
                if (sess != null) {
                    if (sess.tag != null) {
                        if (_log.shouldDebug()) {
                            _log.debug("Requesting AES reply from [" + peer.toBase64().substring(0,6) + "]"
                            + "\n* Session key: [" + sess.key.toBase64().substring(0,6) + "] Tag: [" + sess.tag.toString() + "]");
                        }
                        dlm.setReplySession(sess.key, sess.tag);
                    } else {
                        if (_log.shouldDebug()) {
                            _log.debug("Requesting AEAD reply from [" + peer.toBase64().substring(0,6) + "]"
                            + "\n* Session key: [" + sess.key.toBase64().substring(0,6) + "] Tag: [" + sess.rtag.toString() + "]");
                        }
                        dlm.setReplySession(sess.key, sess.rtag);
                    }
                } else { // client went away, but send it anyway
                    if (_log.shouldWarn()) {_log.warn("Failed encrypt to " + ri);}
                }

                outMsg = MessageWrapper.wrap(ctx, dlm, ri);
                // ElG can take a while so do a final check before we send it, a response may have come in.
                if (_dead) {
                    if (_log.shouldDebug()) {
                        _log.debug("Aborting send - finished while wrapping message to [" + peer.toBase64().substring(0,6) + "]");
                    }
                    return;
                }
                if (_log.shouldDebug()) {
                    _log.debug("Encrypted DbLookupMsg for [" + _key.toBase64().substring(0,6) +
                               "] sent to [" + peer.toBase64().substring(0,6) + "]");
                }
            }
        }
        if (outMsg == null) {outMsg = dlm;}
        if (isDirect) {
            if (_facade.isClientDb() && _log.shouldWarn()) {
                _log.warn("Warning! Sending direct search message in a client NetDb context! (DbId: " + _facade + ")" + outMsg );
            }
            OutNetMessage m = new OutNetMessage(ctx, outMsg, outMsg.getMessageExpiration(),
                                                OutNetMessage.PRIORITY_MY_NETDB_LOOKUP, ri);
            ctx.commSystem().processMessage(m);
        } else {ctx.tunnelDispatcher().dispatchOutbound(outMsg, outTunnel.getSendTunnelId(0), peer);}

        // The timeout job is always run (never cancelled)
        // Note that the timeout is much shorter than the message expiration (see above)
        Job j = new IterativeTimeoutJob(ctx, peer, this);

        // Set timeout based on resp. time from profile
        PeerProfile prof = getContext().profileOrganizer().getProfileNonblocking(peer);
        long exp = _singleSearchTime;
        if (prof != null && prof.getIsExpandedDB()) {
            RateStat dbrt = prof.getDbResponseTime();
            if (dbrt != null) {
                Rate r = dbrt.getRate(RateConstants.ONE_HOUR);
                if (r != null) {
                    long avg = (long) r.getAvgOrLifetimeAvg();
                    if (avg > 0) {
                        // We don't calculate RTO so just use a multiple of the RTT
                        exp = Math.min(exp, Math.max(MIN_SINGLE_SEARCH_TIME, (isDirect ? 2 : 3) * avg));
                    }
                }
            }
        }

        long expire = Math.min(_expiration, now + exp);
        j.getTiming().setStartAfter(expire);
        getContext().jobQueue().addJob(j);
    }

    @Override
    public String getName() {return "Start Iterative Search";}

    /**
     *  Note that the peer did not respond with a DSM (either a DSRM, timeout, or failure).
     *  This is not necessarily a total failure of the search.
     *  @param timedOut if true, will blame the peer's profile
     */
    void failed(Hash peer, boolean timedOut) {
        boolean isNewFail;
        if (_dead || getContext().banlist().isBanlisted(peer)) {return;}
        synchronized (this) {
            _unheardFrom.remove(peer);
            isNewFail = _failedPeers.add(peer);
        }
        if (isNewFail) {
            boolean isKnown = _facade.lookupLocallyWithoutValidation(peer) != null;
            if (timedOut) {
                getContext().profileManager().dbLookupFailed(peer);
                if (_log.shouldInfo()) {
                    if (peer != null) {_log.info("IterativeSearch for " + (isKnown ? "known " : "") + "Router [" + peer.toBase64().substring(0,6) + "] timed out");}
                    else {_log.info("IterativeSearch for Router [unknown] timed out");}
                }
            } else {
                if (_log.shouldInfo()) {
                    if (peer != null) {_log.info("IterativeSearch for " + (isKnown ? "known " : "") + "Router [" + peer.toBase64().substring(0,6) + "] failed");}
                    else {_log.info("IterativeSearch for Router [unknown] failed");}
                }
            }
        }
        retry();
    }

    /**
     *  A new (floodfill) peer was discovered that may have the answer.
     *  @param peer may not actually be new
     */
    void newPeerToTry(Hash peer) {
        // Don't ask ourselves or the target
        if (peer.equals(getContext().routerHash()) || peer.equals(_key) || getContext().banlist().isBanlisted(peer)) {
            if (getContext().banlist().isBanlisted(peer) && _log.shouldInfo()) {
                _log.info("Not querying banlisted peer [" + peer.toBase64().substring(0,6) + "] received from DbSearchReplyMsg");
            }
            return;
        }
        RouterInfo ri = getContext().netDb().lookupRouterInfoLocally(peer);
        if (ri != null && !FloodfillNetworkDatabaseFacade.isFloodfill(ri)) {
            if (_log.shouldInfo()) {_log.info("Not querying non-Floodfill peer [" + peer.toBase64().substring(0,6) + "] received from DbSearchReplyMsg");}
            return;
        }
        synchronized (this) {
            if (_failedPeers.contains(peer) || _unheardFrom.contains(peer) || _skippedPeers.contains(peer)) {return;} // already tried or skipped
            if (!_toTry.add(peer)) {return;} // already in the list
        }
        if (_log.shouldInfo()) {
            _log.info("Received new Router [" + peer.toBase64().substring(0,6) + "] from DbSearchReplyMsg " + (ri != null ? " -> Already known" : ""));
        }
        retry();
    }

    /**
     *  Hash of the dest this query is from
     *  @return null for router
     *  @since 0.9.13
     */
    public Hash getFromHash() {return _fromLocalDest;}

    /**
     *  Did we send a request to this peer?
     *  @since 0.9.13
     */
    public boolean wasQueried(Hash peer) {
        synchronized (this) {return _unheardFrom.contains(peer) || _failedPeers.contains(peer);}
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
     * Cancels the job without performing full failure handling.
     * @since 0.9.65+
     */
    void cancelJob() {
        synchronized (this) {
            if (_dead) {return;}
            _dead = true;
        }
        _facade.complete(_key);
    }

    /**
     *  Dropped by the job queue
     *  @since 0.9.31
     */
    @Override
    public void dropped() {failed();}

    /**
     *  Total failure
     */
    @Override
    void failed() {
        synchronized (this) {
            if (_dead) {return;}
            _dead = true;
        }
        _facade.complete(_key);
        if (getContext().commSystem().getStatus() != Status.DISCONNECTED) {_facade.lookupFailed(_key);}
        getContext().messageRegistry().unregisterPending(_out);
        int tries;
        final List<Hash> unheard;
        synchronized(this) {
            tries = _unheardFrom.size() + _failedPeers.size();
            unheard = new ArrayList<Hash>(_unheardFrom);
        }
        // Blame the unheard-from (others already blamed in failed() above)
        for (Hash h : unheard) {getContext().profileManager().dbLookupFailed(h);}
        long time = System.currentTimeMillis() - _created;
        if (_log.shouldInfo()) {
            long timeRemaining = _expiration - getContext().clock().now();
            _log.info("IterativeSearch for "  + (_isLease ? "LeaseSet " : "Router") + " [" + _key.toBase64().substring(0,6) + "] failed" +
                      "\n* Peers queried: " + tries + "; Time taken: " + time + "ms (" + timeRemaining + "ms remaining)");
        }
        if (tries > 0) {
            // Don't bias the stats with immediate fails
            getContext().statManager().addRateData("netDb.failedTime", time);
            getContext().statManager().addRateData("netDb.failedRetries", tries - 1);
        }
        for (Job j : _onFailed) {getContext().jobQueue().addJob(j);}
        _onFailed.clear();
    }

    @Override
    void success() {
        /*
         * Sadly, we don't know for sure which one replied.
         * If the reply is after expiration (which moves the hash from _unheardFrom to _failedPeers),
         * we will credit the wrong one.
         */
        int tries;
        Hash peer = null;

        synchronized(this) {
            if (_dead) {return;}
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
            if (timeSent != null) {
                getContext().profileManager().dbLookupSuccessful(peer, getContext().clock().now() - timeSent.longValue());
            }
        }
        long time = System.currentTimeMillis() - _created;
        if (_log.shouldInfo()) {
            _log.info("IterativeSearch for " + (_isLease ? "LeaseSet " : "Router") +
                      " [" + _key.toBase64().substring(0,6) + "] succeeded" +
                      "\n* Peers queried: " + tries + "; Time taken: " + time + "ms");
        }
        getContext().statManager().addRateData("netDb.successTime", time);
        getContext().statManager().addRateData("netDb.successRetries", tries - 1);
        for (Job j : _onFind) {getContext().jobQueue().addJob(j);}
        _onFind.clear();
    }

}
