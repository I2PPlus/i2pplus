package net.i2p.router.peermanager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.ReplyJob;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;

/**
 * Grab some peers that we want to test and probe them briefly to get some
 * more accurate and up to date performance data.  This delegates the peer
 * selection to the peer manager and tests the peer by sending it a useless
 * database store message
 *
 * TODO - What's the point? Disable this? See also notes in PeerManager.selectPeers().
 * TODO - Use something besides sending the peer's RI to itself?
 */
class PeerTestJob extends JobImpl {
    private final Log _log;
    private PeerManager _manager;
    private boolean _keepTesting;
    private final int DEFAULT_PEER_TEST_DELAY = 5*60*1000;
    public static final String PROP_PEER_TEST_DELAY = "router.peerTestDelay";
    private static final int DEFAULT_PEER_TEST_CONCURRENCY = 1;
    public static final String PROP_PEER_TEST_CONCURRENCY = "router.peerTestConcurrency";
    private static final int DEFAULT_PEER_TEST_TIMEOUT = 5*1000;
    public static final String PROP_PEER_TEST_TIMEOUT = "router.peerTestTimeout";
    /** Creates a new instance of PeerTestJob */
    public PeerTestJob(RouterContext context) {
        super(context);
        _log = context.logManager().getLog(PeerTestJob.class);
        _keepTesting = false;
        getContext().statManager().createRequiredRateStat("peer.testOK", "Time a successful test takes (ms)", "Peers", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        getContext().statManager().createRequiredRateStat("peer.testTooSlow", "Excess time taken by too slow test (ms)", "Peers", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        getContext().statManager().createRateStat("peer.testTimeout", "Frequency of test timeouts (no reply)", "Peers", new long[] { 60*1000, 10*60*1000 });
    }

    public int getAvgPeerTestTime() {
        RateStat rs = getContext().statManager().getRate("peer.testOK");
        Rate r = rs.getRate(60*1000);
        int avgTestTime = (int) r.getLifetimeAverageValue();
        return avgTestTime;
    }

    /** how long should we wait before firing off new tests?  */
    private long getPeerTestDelay() {
        int testDelay = getContext().getProperty(PROP_PEER_TEST_DELAY, DEFAULT_PEER_TEST_DELAY);
        return testDelay;
    }
    /** how long to give each peer before marking them as unresponsive? */
    private int getTestTimeout() {
        int testTimeout = getContext().getProperty(PROP_PEER_TEST_TIMEOUT, DEFAULT_PEER_TEST_TIMEOUT);
        if (getAvgPeerTestTime() > testTimeout) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Peer test timeout set below average successful test time, setting to: " + getAvgPeerTestTime() * 2 / 3 + "ms");
            return getAvgPeerTestTime() * 2 / 3;
        } else {
            return testTimeout;
        }
    }
    /** number of peers to test each round */
    private int getTestConcurrency() {
        int testConcurrent = getContext().getProperty(PROP_PEER_TEST_CONCURRENCY, DEFAULT_PEER_TEST_CONCURRENCY);
        return testConcurrent;
    }

    public synchronized void startTesting(PeerManager manager) {
        _manager = manager;
        _keepTesting = true;
        this.getTiming().setStartAfter(getContext().clock().now() + getPeerTestDelay());
        getContext().jobQueue().addJob(this);
        if (_log.shouldLog(Log.INFO))
            _log.info("Initialising peer tests -> Timeout: " + getTestTimeout() + "ms per peer");
    }

    public synchronized void stopTesting() {
        _keepTesting = false;
        if (_log.shouldLog(Log.INFO))
            _log.info("Ending peer tests...");
    }

    public String getName() { return "Test Peers"; }

    public void runJob() {
        if (!_keepTesting) return;
        Set<RouterInfo> peers = selectPeersToTest();
/**
        if (_log.shouldLog(Log.DEBUG))
            if (peers.size() == 1)
                _log.debug("Testing " + peers.size() + " peer");
            else
                _log.debug("Testing " + peers.size() + " peers");
**/
        for (RouterInfo peer : peers) {
//            if (_log.shouldLog(Log.INFO))
//                _log.info("Testing peer [" + peer.getIdentity().getHash().toBase64().substring(0,6) + "]");
            testPeer(peer);
        }
        requeue(getPeerTestDelay());
        if (_log.shouldLog(Log.INFO))
            _log.info("Next test run in " + getPeerTestDelay() + "ms");
    }

    /**
     * Retrieve a group of 0 or more peers that we want to test.
     * Returned list will not include ourselves.
     *
     * @return set of RouterInfo structures
     */
    private Set<RouterInfo> selectPeersToTest() {
        PeerSelectionCriteria criteria = new PeerSelectionCriteria();
        criteria.setMinimumRequired(getTestConcurrency());
        criteria.setMaximumRequired(getTestConcurrency());
        criteria.setPurpose(PeerSelectionCriteria.PURPOSE_TEST);
        List<Hash> peerHashes = _manager.selectPeers(criteria);

/**
        if (_log.shouldLog(Log.DEBUG))
            if (peers.size == 1)
               _log.debug("Peer selection found " + peerHashes.size() + " peer for testing");
            else
                _log.debug("Peer selection found " + peerHashes.size() + " peers for testing");
**/

        Set<RouterInfo> peers = new HashSet<RouterInfo>(peerHashes.size());
        for (Hash peer : peerHashes) {
            RouterInfo peerInfo = getContext().netDb().lookupRouterInfoLocally(peer);
            if (peerInfo != null) {
                peers.add(peerInfo);
                if (getTestConcurrency() != 1) {
                    if (_log.shouldLog(Log.INFO))
                    _log.info("Running " +  getTestConcurrency() + " concurrent peer tests...");
                }
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Peer test failed: No local RouterInfo found for [" + peer.toBase64().substring(0,6) + "]");
            }
        }
        return peers;
    }

    /**
     * Fire off the necessary jobs and messages to test the given peer
     * The message is a store of the peer's RI to itself,
     * with a reply token.
     */
    private void testPeer(RouterInfo peer) {
        TunnelInfo inTunnel = getInboundTunnelId();
        if (inTunnel == null) {
            _log.warn("No tunnels to get peer test replies through!");
            return;
        }
        TunnelId inTunnelId = inTunnel.getReceiveTunnelId(0);

        RouterInfo inGateway = getContext().netDb().lookupRouterInfoLocally(inTunnel.getPeer(0));
        if (inGateway == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("We can't find the gateway to our inbound tunnel?! Impossible?");
            return;
        }

        int timeoutMs = getTestTimeout();
        long expiration = getContext().clock().now() + timeoutMs;

        long nonce = 1 + getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE - 1);
        DatabaseStoreMessage msg = buildMessage(peer, inTunnelId, inGateway.getIdentity().getHash(), nonce, expiration);

        TunnelInfo outTunnel = getOutboundTunnelId();
        if (outTunnel == null) {
            _log.warn("No tunnels to send search out through! Something is wrong...");
            return;
        }

        TunnelId outTunnelId = outTunnel.getSendTunnelId(0);

        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Initiating test of peer [" + peer.getIdentity().getHash().toBase64().substring(0,6)
                       + "]: \n* " + outTunnel + "\n* " + inTunnel);
        } else if (_log.shouldLog(Log.INFO)) {
            _log.info("Initiating test of peer [" + peer.getIdentity().getHash().toBase64().substring(0,6) + "]");
        }

        ReplySelector sel = new ReplySelector(peer.getIdentity().getHash(), nonce, expiration);
        PeerReplyFoundJob reply = new PeerReplyFoundJob(getContext(), peer, inTunnel, outTunnel);
        PeerReplyTimeoutJob timeoutJob = new PeerReplyTimeoutJob(getContext(), peer, inTunnel, outTunnel, sel);

        getContext().messageRegistry().registerPending(sel, reply, timeoutJob);
        getContext().tunnelDispatcher().dispatchOutbound(msg, outTunnelId, null, peer.getIdentity().getHash());
    }

    /**
     * what tunnel will we send the test out through?
     *
     * @return tunnel id (or null if none are found)
     */
    private TunnelInfo getOutboundTunnelId() {
        return getContext().tunnelManager().selectOutboundTunnel();
    }

    /**
     * what tunnel will we get replies through?
     *
     * @return tunnel id (or null if none are found)
     */
    private TunnelInfo getInboundTunnelId() {
        return getContext().tunnelManager().selectInboundTunnel();
    }

    /**
     * Build a message to test the peer with.
     * The message is a store of the peer's RI to itself,
     * with a reply token.
     */
    private DatabaseStoreMessage buildMessage(RouterInfo peer, TunnelId replyTunnel, Hash replyGateway, long nonce, long expiration) {
        DatabaseStoreMessage msg = new DatabaseStoreMessage(getContext());
        msg.setEntry(peer);
        msg.setReplyGateway(replyGateway);
        msg.setReplyTunnel(replyTunnel);
        msg.setReplyToken(nonce);
        msg.setMessageExpiration(expiration);
        return msg;
    }

    /**
     * Simple selector looking for a dbStore of the peer specified
     *
     */
    private class ReplySelector implements MessageSelector {
        private final long _expiration;
        private final long _nonce;
        private final Hash _peer;
        private boolean _matchFound;

        public ReplySelector(Hash peer, long nonce, long expiration) {
            _nonce = nonce;
            _expiration = expiration;
            _peer = peer;
            _matchFound = false;
        }
        public boolean continueMatching() { return false; }
        public long getExpiration() { return _expiration; }
        public boolean isMatch(I2NPMessage message) {
            if (message.getType() == DeliveryStatusMessage.MESSAGE_TYPE) {
                DeliveryStatusMessage msg = (DeliveryStatusMessage)message;
                if (_nonce == msg.getMessageId()) {
                    long timeLeft = _expiration - getContext().clock().now();
                    if (timeLeft < 0) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Took too long to get a reply from peer [" + _peer.toBase64().substring(0,6)
                                      + "]: " + (0-timeLeft) + "ms too slow");
                        getContext().statManager().addRateData("peer.testTooSlow", 0-timeLeft);
                    } else {
                        getContext().statManager().addRateData("peer.testOK", getTestTimeout() - timeLeft);
                    }
                    _matchFound = true;
                    return true;
                }
            }
            return false;
        }
        public boolean matchFound() { return _matchFound; }
        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(64);
            buf.append("Test peer [").append(_peer.toBase64().substring(0,6));
            buf.append("] with nonce: ").append(_nonce);
            return buf.toString();
        }
    }

    /**
     * Called when the peer's response is found
     */
    private class PeerReplyFoundJob extends JobImpl implements ReplyJob {
        private final RouterInfo _peer;
        private final long _testBegin;
        private final TunnelInfo _replyTunnel;
        private final TunnelInfo _sendTunnel;

        public PeerReplyFoundJob(RouterContext context, RouterInfo peer, TunnelInfo replyTunnel, TunnelInfo sendTunnel) {
            super(context);
            _peer = peer;
            _replyTunnel = replyTunnel;
            _sendTunnel = sendTunnel;
            _testBegin = context.clock().now();
        }
        public String getName() { return "Verify Peer Test"; }
        public void runJob() {
            long responseTime = getContext().clock().now() - _testBegin;
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Peer test of [" + _peer.getIdentity().getHash().toBase64().substring(0,6) + "] succeeded (took " +
                           responseTime + "ms)\n* " + _sendTunnel + "\n* " + _replyTunnel);
            } else if (_log.shouldLog(Log.INFO)) {
                _log.info("Peer test of [" + _peer.getIdentity().getHash().toBase64().substring(0,6) + "] succeeded (took " +
                           responseTime + "ms)");
            }
            getContext().profileManager().dbLookupSuccessful(_peer.getIdentity().getHash(), responseTime);
            // we know the tunnels are working
            _sendTunnel.testSuccessful((int)responseTime);
            _replyTunnel.testSuccessful((int)responseTime);
        }

        public void setMessage(I2NPMessage message) {
            // noop
        }

    }
    /**
     * Called when the peer's response times out
     */
    private class PeerReplyTimeoutJob extends JobImpl {
        private final RouterInfo _peer;
        private final TunnelInfo _replyTunnel;
        private final TunnelInfo _sendTunnel;
        private final ReplySelector _selector;

        public PeerReplyTimeoutJob(RouterContext context, RouterInfo peer, TunnelInfo replyTunnel, TunnelInfo sendTunnel, ReplySelector sel) {
            super(context);
            _peer = peer;
            _replyTunnel = replyTunnel;
            _sendTunnel = sendTunnel;
            _selector = sel;
        }
        public String getName() { return "Timeout Peer Test"; }
        private boolean getShouldFailPeer() { return true; }
        public void runJob() {
            if (_selector.matchFound())
                return;

            if (getShouldFailPeer())
                getContext().profileManager().dbLookupFailed(_peer.getIdentity().getHash());

            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Peer test of [" + _peer.getIdentity().getHash().toBase64().substring(0,6) +
                          "] failed\n* " + _sendTunnel + "\n* " + _replyTunnel);
            } else if (_log.shouldLog(Log.INFO)) {
                _log.info("Peer test of [" + _peer.getIdentity().getHash().toBase64().substring(0,6) +
                          "] failed");
            }

            // don't fail the tunnels, as the peer might just plain be down, or
            // otherwise overloaded
            getContext().statManager().addRateData("peer.testTimeout", 1);
        }
    }
}
