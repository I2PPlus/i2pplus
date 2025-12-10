package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.i2p.crypto.EncType;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.DatabaseSearchReplyMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.kademlia.KBucketSet;
import net.i2p.router.RouterContext;
import net.i2p.util.SystemVersion;

/**
 * Represents an exploratory search job for a particular key in the Kademlia network database.
 * &lt;p&gt;
 * This job performs an exploratory search until it gets a Database Search Reply Message (DSRM).
 * It selects peers to send queries to based on floodfill capabilities and excludes peers already
 * queried or close to the target key to avoid redundancy.
 * &lt;p&gt;
 * The concurrency level ("bredth") for this search is calculated once during construction based
 * on system and network conditions, and does not change during the lifetime of the job.
 */
public class ExploreJob extends SearchJob {

    private final FloodfillPeerSelector _peerSelector;
    private final boolean _isRealExplore;
    private volatile Hash _lastReplyFrom;

    /**
     * Maximum duration for each exploration in milliseconds.
     * Explorations run relatively long since they seek multiple peers without expecting a quick success.
     */
    private static final long MAX_EXPLORE_TIME = 40 * 1000;

    /** Configuration property key for controlling the maximum concurrent exploratory searches. */
    static final String PROP_EXPLORE_BREDTH = "router.exploreBredth";

    /** Default number of concurrent exploratory searches, adjusted for slower systems. */
    private static final int EXPLORE_BREDTH = SystemVersion.isSlow() ? 1 : 2;

    /**
     * Maximum number of closest peers to exclude in queries.
     * This is intentionally larger to include floodfill and previously queried peers.
     */
    static final int MAX_CLOSEST = 20; // Note: hides a field in superclass, intended override

    /** Timeout per floodfill peer in milliseconds; shorter to avoid delays with unresponsive peers. */
    static final int PER_FLOODFILL_PEER_TIMEOUT = 5 * 1000; // Note: hides a field in superclass, intended override

    /** Cached concurrency level (bredth) for this exploratory search instance. */
    private final int _bredth;

    /**
     * Constructs a new ExploreJob with given parameters and calculates concurrency level.
     *
     * @param context       Router context providing environment and config access
     * @param facade        Facade handling the Kademlia network database operations
     * @param key           Target key to explore/search for
     * @param isRealExplore True if this is a standard exploration (no floodfills returned),
     *                      false if a standard lookup (floodfills returned, useful when floodfill count is low)
     * @param msgIDBloomXor XOR value for message ID Bloom filter (used internally)
     */
    public ExploreJob(RouterContext context, KademliaNetworkDatabaseFacade facade, Hash key, boolean isRealExplore, long msgIDBloomXor) {
        // Parameters 'null' for leaseSet and replyTunnel because this job is purely exploratory.
        super(context, facade, key, null, null, MAX_EXPLORE_TIME, false, false, msgIDBloomXor);
        _peerSelector = (FloodfillPeerSelector) (_facade.getPeerSelector());
        _isRealExplore = isRealExplore;
        _bredth = calculateBredth();
    }

    /**
     * Builds the database lookup message to send to a peer.
     * &lt;p&gt;
     * Unlike normal searches, this method explicitly excludes certain peers: those previously queried,
     * and those close to the target key in routing space, to avoid repeatedly querying the same nodes.
     * The floodfill peers are also handled specially.
     *
     * @param replyTunnelId Tunnel ID for receiving replies, or null to send direct (no encryption)
     * @param replyGateway  Gateway for the reply tunnel, or null for direct sending
     * @param expiration    Absolute time when the message expires
     * @param peer          RouterInfo object representing the peer to which the message will be sent
     *
     * @return an encrypted or plain DatabaseLookupMessage wrapped as an I2NPMessage,
     *         or null on error
     */
    @Override
    protected I2NPMessage buildMessage(TunnelId replyTunnelId, Hash replyGateway, long expiration, RouterInfo peer) {
        final RouterContext ctx = getContext();
        DatabaseLookupMessage msg = new DatabaseLookupMessage(ctx, true);
        msg.setSearchKey(getState().getTarget());
        msg.setFrom(replyGateway);

        Set<Hash> dontIncludePeers = getState().getClosestAttempted(MAX_CLOSEST);
        msg.setMessageExpiration(expiration);

        if (replyTunnelId != null) {
            msg.setReplyTunnel(replyTunnelId);
        }

        int available = MAX_CLOSEST - dontIncludePeers.size();

        msg.setSearchType(_isRealExplore ? DatabaseLookupMessage.Type.EXPL : DatabaseLookupMessage.Type.RI);

        KBucketSet<Hash> ks = _facade.getKBuckets();
        Hash rkey = ctx.routingKeyGenerator().getRoutingKey(getState().getTarget());

        if (available > 0) {
            /*
             * We create a defensive copy because selectNearestExplicit adds our own hash to the exclusion set,
             * but we must not include our own hash in the message to avoid leaking identity linkage.
             */
            Set<Hash> dontInclude = new HashSet<>(dontIncludePeers);
            List<Hash> peers = _peerSelector.selectNearestExplicit(rkey, available, dontInclude, ks);
            dontIncludePeers.addAll(peers);
        }

        if (_log.shouldDebug()) {
            StringBuilder buf = new StringBuilder();
            buf.append("Excluding ").append(dontIncludePeers.size() - 1)
               .append(" closest peers from exploration\n* Excluded: ");
            for (Hash h : dontIncludePeers) {
                buf.append("[").append(h.toBase64().substring(0, 6)).append("] ");
            }
            _log.debug(buf.toString());
        }
        msg.setDontIncludePeers(dontIncludePeers);

        final RouterIdentity ident = peer.getIdentity();
        final EncType type = ident.getPublicKey().getType();
        final boolean encryptElG = ctx.getProperty(IterativeSearchJob.PROP_ENCRYPT_RI, IterativeSearchJob.DEFAULT_ENCRYPT_RI);
        final I2NPMessage outMsg;

        // Cache hash snippet for logging only when logging is enabled to save computation cost.
        final String hash = (_log.shouldInfo() || _log.shouldDebug()) ? ident.calculateHash().toBase64().substring(0, 6) : "";

        if (replyTunnelId != null &&
            ((encryptElG && type == EncType.ELGAMAL_2048) || (type == EncType.ECIES_X25519 && DatabaseLookupMessage.USE_ECIES_FF))) {

            EncType ourType = ctx.keyManager().getPublicKey().getType();
            boolean ratchet1 = ourType.equals(EncType.ECIES_X25519);
            boolean ratchet2 = DatabaseLookupMessage.supportsRatchetReplies(peer);

            // Request encrypted reply if supported and conditions met
            if (DatabaseLookupMessage.supportsEncryptedReplies(peer) && (ratchet2 || !ratchet1)) {
                boolean supportsRatchet = ratchet1 && ratchet2;
                MessageWrapper.OneTimeSession sess = MessageWrapper.generateSession(ctx, ctx.sessionKeyManager(), MAX_EXPLORE_TIME, !supportsRatchet);

                if (sess != null) {
                    if (sess.tag != null) {
                        if (_log.shouldInfo()) {
                            _log.info("Requesting AES reply from [" + hash + "] \n* Session Key: " + sess.key + "\n* " + sess.tag);
                        }
                        msg.setReplySession(sess.key, sess.tag);
                    } else {
                        if (_log.shouldInfo()) {
                            _log.info("Requesting AEAD reply from [" + hash + "] \n* Session Key: " + sess.key + "\n* " + sess.rtag);
                        }
                        msg.setReplySession(sess.key, sess.rtag);
                    }
                } else {
                    if (_log.shouldWarn()) {_log.warn("Failed encrypt to " + peer);}
                    // Client may have become unreachable, send anyway
                }
            }
            outMsg = MessageWrapper.wrap(ctx, msg, peer);

            if (_log.shouldDebug()) {
                _log.debug("Encrypted Exploratory DbLookupMessage for [" + getState().getTarget().toBase64().substring(0, 6) +
                           "] sent to [" + hash + "]");
            }
        } else {
            if (_log.shouldDebug()) {
                _log.debug("Direct exploratory DbLookupMessage to [" + ident.calculateHash().toBase64().substring(0, 6) + "]\n*" + msg);
            }
            outMsg = msg;
        }
        return outMsg;
    }

    /**
     * Returns the maximum number of concurrent exploratory searches to run.
     * &lt;p&gt;
     * This value is computed once at construction based on configuration and network state.
     *
     * @return maximum concurrent exploratory searches (bredth)
     */
    @Override
    protected int getBredth() {return _bredth;}

    /**
     * Computes the concurrency level (bredth) for exploratory searches based on
     * configured property, network size, and system speed.
     * &lt;p&gt;
     * If the "router.exploreBredth" property is defined and parseable, it is used.
     * Otherwise, network size and system speed heuristics determine the bredth.
     *
     * @return computed maximum concurrency level for exploration
     */
    private int calculateBredth() {
        String exploreBredth = getContext().getProperty(PROP_EXPLORE_BREDTH);
        if (exploreBredth != null) {
            try {
                int val = Integer.parseInt(exploreBredth);
                if (_log.shouldInfo()) {
                    _log.info("Initiating Exploratory Search -> Max " + val + " concurrent (custom configuration)");
                }
                return val;
            } catch (NumberFormatException e) {
                if (_log.shouldWarn()) {
                    _log.warn("Invalid " + PROP_EXPLORE_BREDTH + " property: " + exploreBredth + ", using default: " + EXPLORE_BREDTH, e);
                }
                return EXPLORE_BREDTH;
            }
        } else if (getContext().netDb().getKnownRouters() < 1500) {
            int val = EXPLORE_BREDTH * 3;
            if (_log.shouldInfo()) {
                _log.info("Initiating Exploratory Search -> Max " + val + " concurrent (less than 1500 RouterInfos stored on disk)");
            }
            return val;
        } else {
            if (_log.shouldInfo()) {
                _log.info("Initiating Exploratory Search -> Max " + EXPLORE_BREDTH + " concurrent");
            }
            return EXPLORE_BREDTH;
        }
    }

    /**
     * Called when the search has returned new peers that were previously unknown.
     * &lt;p&gt;
     * Logs the number of newly discovered peers and updates the last explore timestamp.
     *
     * @param numNewPeers number of newly discovered peers
     */
    @Override
    protected void newPeersFound(int numNewPeers) {
        if (_log.shouldInfo()) {
            if (numNewPeers > 0) {
                _log.info("Found " + numNewPeers + " new peer" + (numNewPeers == 1 ? "" : "s") + " via Exploratory Search");
            } else {_log.info("Found no new peers via Exploratory Search");}
        }
        _facade.setLastExploreNewDate(getContext().clock().now());
    }

    /**
     * @since 0.9.67
     */
    @Override
    void replyFound(DatabaseSearchReplyMessage message, Hash peer) {
        _lastReplyFrom = peer;
        super.replyFound(message, peer); // This starts a SearchReplyJob
    }
    /**
     * This is called from SearchReplyJob
     * @return true if peer was new
     * @since 0.9.67
     */
    @Override
    boolean add(Hash peer) {
        Hash from = _lastReplyFrom;
        if (from != null) {
            final RouterContext ctx = getContext();
            if (ctx.commSystem().isEstablished(from)) {
                RouterInfo ri = _facade.lookupRouterInfoLocally(from);
                if (ri != null) {
                    if (_log.shouldDebug()) {_log.debug("Direct followup to " + from + " for " + peer);}
                    DirectLookupJob j = new DirectLookupJob(getContext(), (FloodfillNetworkDatabaseFacade) _facade, peer, ri, null, null);
                    j.runJob(); // inline (SearchReplyJob thread)
                    return true;
                }
            }
        }
        return super.add(peer);
    }

    /*
     * The inherited searchNext() method from SearchJob is sufficient here.
     * Overriding it to check for kbucket filling isn't necessary due to the vast keyspace.
     */

    @Override
    public String getName() {return "Explore Kademlia NetDb";}

}
