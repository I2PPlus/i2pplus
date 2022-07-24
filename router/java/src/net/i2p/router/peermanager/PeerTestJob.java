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
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

import net.i2p.data.Hash;
import net.i2p.data.Base64;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.router.Router;

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
    private final int DEFAULT_PEER_TEST_DELAY = 5*1000;
    public static final String PROP_PEER_TEST_DELAY = "router.peerTestDelay";
    private static final int DEFAULT_PEER_TEST_CONCURRENCY = 3;
    public static final String PROP_PEER_TEST_CONCURRENCY = "router.peerTestConcurrency";
    private static final int DEFAULT_PEER_TEST_TIMEOUT = 1000;
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
        if (getContext() == null)
            return 0;
        RateStat rs = getContext().statManager().getRate("peer.testOK");
        Rate r = rs.getRate(60*1000);
        int avgTestTime = (int) r.getLifetimeAverageValue();
        return avgTestTime;
    }

    /** @since 0.9.49+ */
    public int getTotalAvgPeerTestTime() {
        if (getContext() == null)
            return 0;
        RateStat ok = getContext().statManager().getRate("peer.testOK");
        Rate rok = ok.getRate(60*60*1000);
        RateStat tooslow = getContext().statManager().getRate("peer.testTooSlow");
        Rate rtooslow = ok.getRate(60*60*1000);
        int totalAvgTestTime = (int) rok.getLifetimeAverageValue() + (int) rtooslow.getLifetimeAverageValue();
        return totalAvgTestTime;
    }

    /** how long should we wait before firing off new tests?  */
    private long getPeerTestDelay() {
        long uptime = getContext().router().getUptime();
        int testDelay = getContext().getProperty(PROP_PEER_TEST_DELAY, DEFAULT_PEER_TEST_DELAY);
        if (uptime > 3*60*60*1000)
            return testDelay + 1000;
        else if (uptime >= 3*60*1000)
            return testDelay;
        else
            return testDelay + (3*60*1000 - uptime);
    }
    /** how long to give each peer before marking them as unresponsive? */
    private int getTestTimeout() {
        int testTimeout = getContext().getProperty(PROP_PEER_TEST_TIMEOUT, DEFAULT_PEER_TEST_TIMEOUT);
        if (testTimeout < getAvgPeerTestTime()) {
            if (_log.shouldWarn())
                _log.warn("Peer test timeout set below successful test average, setting to: " + getAvgPeerTestTime() + "ms");
            return getAvgPeerTestTime();
        } else {
            return testTimeout;
        }
    }
    /** number of peers to test each round */
    private int getTestConcurrency() {
        int cores = SystemVersion.getCores();
        long memory = SystemVersion.getMaxMemory();
        int testConcurrent = getContext().getProperty(PROP_PEER_TEST_CONCURRENCY, DEFAULT_PEER_TEST_CONCURRENCY);
        if (cores >=4 && memory >= 512*1024*1024)
            testConcurrent = getContext().getProperty(PROP_PEER_TEST_CONCURRENCY, 4);
        return testConcurrent;
    }

    public synchronized void startTesting(PeerManager manager) {
        _manager = manager;
        _keepTesting = true;
        this.getTiming().setStartAfter(getContext().clock().now() + getPeerTestDelay());
        getContext().jobQueue().addJob(this);
        long uptime = getContext().router().getUptime();
        if (uptime < 3*60*100) {
            if (_log.shouldInfo())
                _log.info("Peer testing will commence in 3 minutes...");
        } else {
            if (_log.shouldInfo())
                _log.info("Initialising peer tests -> Timeout: " + getTestTimeout() + "ms per peer");
        }
    }

    public synchronized void stopTesting() {
        _keepTesting = false;
        if (_log.shouldInfo())
            _log.info("Ending peer tests...");
    }

    public String getName() { return "Test Peers"; }

    public void runJob() {
        long lag = getContext().jobQueue().getMaxLag();
        if (!_keepTesting) return;
        Set<RouterInfo> peers = selectPeersToTest();
        for (RouterInfo peer : peers) {
            testPeer(peer);
        }
        if (lag > 300) {
            requeue(getPeerTestDelay() * 2);
            if (_log.shouldWarn())
                _log.info("High job lag detected (" + lag + "ms) - increasing delay before next run to " + getPeerTestDelay() * 2 + "ms");
        } else {
            requeue(getPeerTestDelay());
        }
        if (_log.shouldInfo())
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
        Set<RouterInfo> peers = new HashSet<RouterInfo>(peerHashes.size());
        for (Hash peer : peerHashes) {
            RouterInfo peerInfo = getContext().netDb().lookupRouterInfoLocally(peer);
            PeerProfile prof = getContext().profileOrganizer().getProfile(peer);
            String cap = peerInfo.getCapabilities();
            boolean reachable = cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
            String bw = peerInfo.getBandwidthTier();
            String version = peerInfo.getVersion();
            if (peerInfo != null && cap != null && reachable && VersionComparator.comp(version, "0.9.54") >= 0 &&
                (bw.equals("N") || bw.equals("O") || bw.equals("P") || bw.equals("X"))) {
                peers.add(peerInfo);
            } else if (peerInfo != null && cap != null && (!reachable || bw.equals("K") || bw.equals("L") || bw.equals("M"))) {
                prof.setCapacityBonus(-30);
                if (_log.shouldInfo())
                    _log.info("[" + peer.toBase64().substring(0,6) + "] Setting capacity bonus to -30 and skipping test -> K, L, M or unreachable");
            } else if (peerInfo == null) {
                if (_log.shouldInfo())
                    _log.info("Test of [" + peer.toBase64().substring(0,6) + "] failed: No local RouterInfo");
            }
        }
        if (getTestConcurrency() != 1) {
            if (_log.shouldInfo())
                _log.info("Running " +  getTestConcurrency() + " concurrent peer tests");
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
            if (_log.shouldWarn())
                _log.warn("We can't find the gateway to our inbound tunnel?! Impossible?");
            return;
        }

        int timeoutMs = getTestTimeout();
        long expiration = getContext().clock().now() + timeoutMs;

        long nonce = 1 + getContext().random().nextLong(I2NPMessage.MAX_ID_VALUE - 1);
        DatabaseStoreMessage msg = buildMessage(peer, inTunnelId, inGateway.getIdentity().getHash(), nonce, expiration);

        TunnelInfo outTunnel = getOutboundTunnelId();
        if (outTunnel == null) {
            _log.warn("No tunnels to send search out through! We have a problem, Houston!");
            return;
        }

        TunnelId outTunnelId = outTunnel.getSendTunnelId(0);

        if (_log.shouldDebug()) {
            _log.debug("[" + peer.getIdentity().getHash().toBase64().substring(0,6) +
                       "] Initiating peer test:\n* " + outTunnel + "\n* " + inTunnel);
        } else if (_log.shouldInfo()) {
            _log.info("[" + peer.getIdentity().getHash().toBase64().substring(0,6) + "] Initiating peer test");
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
                    PeerProfile prof = getContext().profileOrganizer().getProfile(_peer);
                    RouterInfo peerInfo = getContext().netDb().lookupRouterInfoLocally(_peer);
                    if (peerInfo != null) {
                        int speedBonus = prof.getSpeedBonus();
                        String cap = peerInfo.getCapabilities();
                        boolean reachable = cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
                        String bw = peerInfo.getBandwidthTier();
                        int timeout = getTestTimeout();
                        float testAvg = prof.getPeerTestTimeAverage();
                        if (prof != null && cap != null && reachable && (bw.equals("L"))) {
                            try {
                                prof.setCapacityBonus(-30);
                                if (_log.shouldInfo())
                                    _log.info("[" + _peer.toBase64().substring(0,6) + "] Setting capacity bonus to -30 for L tier router");
                            } catch (NumberFormatException nfe) {}
                        } else if (timeLeft < 0) {
                            if (_log.shouldInfo())
                                _log.info("[" + _peer.toBase64().substring(0,6) + "] Test reply took too long: " +
                                          (0-timeLeft) + "ms too slow");
                            getContext().statManager().addRateData("peer.testTooSlow", 0-timeLeft);
                            if (_peer != null && cap != null && reachable &&
                                (bw.equals("N") || bw.equals("O") || bw.equals("P") || bw.equals("X"))) {
                                try {
                                    prof.setCapacityBonus(-30);
                                    if (speedBonus >= 9999999)
                                        prof.setSpeedBonus(speedBonus - 9999999);
                                    if (_log.shouldInfo())
                                        _log.info("[" + _peer.toBase64().substring(0,6) + "] Setting capacity bonus to -30");
                                } catch (NumberFormatException nfe) {}
                            }
                        } else {
                            getContext().statManager().addRateData("peer.testOK", getTestTimeout() - timeLeft);
                            if (testAvg > (timeout * 2) && (bw.equals("N") || bw.equals("O") || bw.equals("P") || bw.equals("X"))) {
                                try {
                                    prof.setCapacityBonus(-30);
                                    if (speedBonus >= 9999999)
                                        prof.setSpeedBonus(speedBonus - 9999999);
                                    if (_log.shouldInfo())
                                        _log.info("[" + _peer.toBase64().substring(0,6) + "] Setting capacity bonus to -30"  +
                                                  " - avg response is over twice timeout value");
                                } catch (NumberFormatException nfe) {}
                            } else if ((prof.getCapacityBonus() == -30 || prof.getSpeedBonus() < 9999999) && cap != null && reachable &&
                                       testAvg < (timeout * 2) && (bw.equals("N") || bw.equals("O") || bw.equals("P") || bw.equals("X"))) {
                                try {
                                    if (prof.getCapacityBonus() == -30) {
                                        prof.setCapacityBonus(0);
                                        if (_log.shouldInfo())
                                            _log.info("[" + _peer.toBase64().substring(0,6) + "] Resetting capacity bonus to 0");
                                    }
                                    if (prof.getSpeedBonus() < 9999999 && cap != null && reachable &&
                                        (bw.equals("N") || bw.equals("O") || bw.equals("P") || bw.equals("X"))) {
                                        prof.setSpeedBonus(speedBonus + 9999999);
                                        if (_log.shouldInfo())
                                            _log.info("[" + _peer.toBase64().substring(0,6) + "] Setting speed bonus to 9999999");
                                    }
                                } catch (NumberFormatException nfe) {}
                                _matchFound = true;
                                return true;
                            }
                        }
                    }
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
            if (_log.shouldDebug()) {
                _log.debug("[" + _peer.getIdentity().getHash().toBase64().substring(0,6) + "] Test succeeded in " +
                           responseTime + "ms\n* " + _sendTunnel + "\n* " + _replyTunnel);
            } else if (_log.shouldInfo()) {
                _log.info("[" + _peer.getIdentity().getHash().toBase64().substring(0,6) + "] Test succeeded in " +
                          responseTime + "ms");
            }
            getContext().profileManager().dbLookupSuccessful(_peer.getIdentity().getHash(), responseTime);
            // we know the tunnels are working
            _sendTunnel.testSuccessful((int)responseTime);
            _replyTunnel.testSuccessful((int)responseTime);

            Hash h = _peer.getIdentity().getHash();
            if (h != null) {
                RouterInfo peerInfo = getContext().netDb().lookupRouterInfoLocally(h);
                if (peerInfo != null) {
                    String cap = peerInfo.getCapabilities();
                    boolean reachable = cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
                    String bw = peerInfo.getBandwidthTier();
                    PeerProfile prof = getContext().profileOrganizer().getProfile(h);
                    if (cap != null && reachable && (bw.equals("N") || bw.equals("O") || bw.equals("P") || bw.equals("X"))) {
                        prof.setSpeedBonus(9999999);
                        if (_log.shouldInfo())
                            _log.info("[" + _peer.getIdentity().getHash().toBase64().substring(0,6) + "] Setting speed bonus to 9999999");
                    }
                    if (prof != null && prof.getCapacityBonus() == -30 && cap != null && reachable && (!bw.equals("L"))) {
                        try {
                            prof.setCapacityBonus(0);
                            if (_log.shouldInfo())
                                _log.info("[" + _peer.getIdentity().getHash().toBase64().substring(0,6) + "] Resetting capacity bonus to 0");
                        } catch (NumberFormatException nfe) {}
                        return;
                    } else if (prof != null && cap != null && (bw.equals("L"))) {
                        try {
                            prof.setCapacityBonus(-30);
                            if (_log.shouldInfo())
                                _log.info("[" + _peer.getIdentity().getHash().toBase64().substring(0,6) + "] Setting capacity bonus to -30 for L tier router");
                        } catch (NumberFormatException nfe) {}
                        return;
                    } else if (prof != null && cap == null) {
                        try {
                            prof.setCapacityBonus(-30);
                            if (_log.shouldInfo())
                                _log.info("[" + _peer.getIdentity().getHash().toBase64().substring(0,6) + "] Setting capacity bonus to -30 for unknown class router");
                        } catch (NumberFormatException nfe) {}
                        return;
                    }
                }
            }
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

            if (_log.shouldDebug()) {
                _log.debug("[" + _peer.getIdentity().getHash().toBase64().substring(0,6) + "] Test failed (timeout reached)" +
                           "\n* " + _sendTunnel + "\n* " + _replyTunnel);
            } else if (_log.shouldInfo()) {
                _log.info("[" + _peer.getIdentity().getHash().toBase64().substring(0,6) + "] Test failed (timeout reached)");
            }

            // don't fail the tunnels, as the peer might just plain be down, or otherwise overloaded
            getContext().statManager().addRateData("peer.testTimeout", 1);

            Hash h = _peer.getIdentity().getHash();
            if (h != null) {
                PeerProfile prof = getContext().profileOrganizer().getProfile(h);
                RouterInfo peerInfo = getContext().netDb().lookupRouterInfoLocally(h);
                if (peerInfo != null) {
                    String cap = peerInfo.getCapabilities();
                    boolean reachable = cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
                    String bw = peerInfo.getBandwidthTier();
                    if (prof != null && cap != null) {
                        try {
                            prof.setCapacityBonus(-30);
                            prof.setSpeedBonus(0);
                            if (_log.shouldInfo())
                                _log.info("[" + _peer.getIdentity().getHash().toBase64().substring(0,6) + "] Setting capacity bonus to -30 and speed bonus to 0");
                        } catch (NumberFormatException nfe) {}
                        return;
                    }
                }
            }
        }
    }
}
