package net.i2p.router.peermanager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.MessageSelector;
import net.i2p.router.ReplyJob;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * Periodically tests selected peers to gather real-time performance data and update peer profiles.
 *
 * <p>This job runs continuously when enabled, selecting peers based on bandwidth tier, reachability,
 * and version compatibility. Tests are performed by sending a DatabaseStoreMessage containing the
 * peer's own RouterInfo back to itself through the tunnel system, measuring response times and
 * connectivity.</p>
 *
 * <p><b>Peer Selection Criteria:</b></p>
 * <ul>
 *   <li>High-bandwidth tiers (O, P, X) with reachability capability</li>
 *   <li>Version 0.9.57+ for compatibility</li>
 *   <li>Configurable concurrency based on system resources</li>
 * </ul>
 *
 * <p><b>Performance Impact:</b></p>
 * <ul>
 *   <li>Adaptive delays based on system load and uptime</li>
 *   <li>CPU throttling when load exceeds 80%</li>
 *   <li>Automatic timeout adjustment based on historical test times</li>
 * </ul>
 *
 * <p><b>Future Considerations:</b></p>
 * <ul>
 *   <li>Evaluate necessity of peer testing in current network conditions</li>
 *   <li>Consider alternative test methods beyond RI self-messaging</li>
 *   <li>Integration with PeerManager.selectPeers() optimization</li>
 * </ul>
 */
public class PeerTestJob extends JobImpl {
    private final Log _log;
    private PeerManager _manager;
    private boolean _keepTesting;
    private final int DEFAULT_PEER_TEST_DELAY = SystemVersion.isSlow() ? 8*1000 : 5*1000;
    public static final String PROP_PEER_TEST_DELAY = "router.peerTestDelay";
    private static final int DEFAULT_PEER_TEST_CONCURRENCY = SystemVersion.isSlow() ? 1 :
                                                             SystemVersion.getCores() <= 2 ? 2 :
                                                             SystemVersion.getCores() >= 8 ? 4 : 3;
    public static final String PROP_PEER_TEST_CONCURRENCY = "router.peerTestConcurrency";
    private static final int DEFAULT_PEER_TEST_TIMEOUT = 750;
    public static final String PROP_PEER_TEST_TIMEOUT = "router.peerTestTimeout";

    /**
     * Creates a new PeerTestJob instance and initializes required statistics.
     *
     * @param context the router context for accessing services like logging and statistics
     */
    public PeerTestJob(RouterContext context) {
        super(context);
        _log = context.logManager().getLog(PeerTestJob.class);
        _keepTesting = false;
        getContext().statManager().createRequiredRateStat("peer.testOK", "Time a successful test takes (ms)", "Peers", RateConstants.TUNNEL_RATES);
        getContext().statManager().createRequiredRateStat("peer.testTooSlow", "Excess time taken by too slow test (ms)", "Peers", RateConstants.TUNNEL_RATES);
        getContext().statManager().createRateStat("peer.testTimeout", "Frequency of test timeouts (no reply)", "Peers", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES });
    }

    /**
     * Gets the average successful peer test time over the last minute.
     *
     * @return average test time in milliseconds, or 0 if no context available
     */
    public int getAvgPeerTestTime() {
        if (getContext() == null)
            return 0;
        RateStat rs = getContext().statManager().getRate("peer.testOK");
        Rate r = rs.getRate(RateConstants.ONE_MINUTE);
        int avgTestTime = (int) r.getLifetimeAverageValue();
        return avgTestTime;
    }

    /**
     * Gets the total average peer test time including both successful and slow tests over the last hour.
     *
     * @return total average test time in milliseconds, or 0 if no context available
     * @since 0.9.49+
     */
    public int getTotalAvgPeerTestTime() {
        if (getContext() == null)
            return 0;
        RateStat ok = getContext().statManager().getRate("peer.testOK");
        Rate rok = ok.getRate(RateConstants.ONE_HOUR);
        RateStat tooslow = getContext().statManager().getRate("peer.testTooSlow");
        Rate rtooslow = tooslow.getRate(RateConstants.ONE_HOUR);
        int totalAvgTestTime = (int) rok.getLifetimeAverageValue() + (int) rtooslow.getLifetimeAverageValue();
        return totalAvgTestTime;
    }

    /**
     * Calculates the delay before starting the next round of peer tests.
     *
     * <p>Delay adapts based on router uptime and system load:
     * <ul>
     *   <li>+1000ms if uptime > 3 hours or CPU load > 80%</li>
     *   <li>Base delay if uptime >= 3 minutes</li>
     *   <li>Inverse ramp-up based on remaining time to 3 minutes</li>
     * </ul>
     * </p>
     *
     * @return delay in milliseconds before next test round
     */
    private long getPeerTestDelay() {
        long uptime = getContext().router().getUptime();
        int testDelay = getContext().getProperty(PROP_PEER_TEST_DELAY, DEFAULT_PEER_TEST_DELAY);
        if (uptime > 3*60*60*1000 || SystemVersion.getCPULoadAvg() > 80)
            return testDelay + 1000;
        else if (uptime >= 3*60*1000)
            return testDelay;
        else
            return testDelay + (3*60*1000 - uptime);
    }

    /**
     * Determines the timeout for individual peer tests with automatic adjustment.
     *
     * <p>Ensures timeout is never set below the average successful test time
     * to avoid false timeouts during normal operation.</p>
     *
     * @return timeout in milliseconds, adjusted if necessary
     */
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

    /**
     * Determines the number of peers to test concurrently based on system capabilities.
     *
     * <p>Concurrency is limited to 1 when CPU load exceeds 80% to prevent system overload.
     * Default value adapts based on system resources (1-4 peers).</p>
     *
     * @return number of peers to test in parallel
     */
    private int getTestConcurrency() {
        int cores = SystemVersion.getCores();
        long memory = SystemVersion.getMaxMemory();
        int testConcurrent = getContext().getProperty(PROP_PEER_TEST_CONCURRENCY, DEFAULT_PEER_TEST_CONCURRENCY);
        if (SystemVersion.getCPULoadAvg() > 80) {testConcurrent = 1;}
        return testConcurrent;
    }

    /**
     * Starts the peer testing process with adaptive initial delay.
     * 
     * <p>Schedules the first test run based on router uptime:
     * <ul>
     *   <li>If uptime < 3 minutes: wait 3 minutes before starting</li>
     *   <li>Otherwise: start immediately with configured delay</li>
     * </ul>
     * </p>
     * 
     * @param manager the peer manager to use for peer selection
     */
    public synchronized void startTesting(PeerManager manager) {
        _manager = manager;
        _keepTesting = true;
        // Schedule initial test with adaptive delay
        this.getTiming().setStartAfter(getContext().clock().now() + getPeerTestDelay());
        getContext().jobQueue().addJob(this);
        long uptime = getContext().router().getUptime();
        if (uptime < 3*60*100) {
            if (_log.shouldInfo()) {_log.info("Peer testing will commence in 3 minutes...");}
        } else if (_log.shouldInfo()) {
            _log.info("Initialising peer tests -> Timeout: " + getTestTimeout() + "ms per peer");
        }
    }

    /**
     * Stops the peer testing process gracefully.
     * 
     * <p>The current test round will complete, but no new rounds will be scheduled.</p>
     */
    public synchronized void stopTesting() {
        _keepTesting = false;
        if (_log.shouldInfo()) {_log.info("Ending peer tests...");}
    }

    public String getName() { return "Test Peers"; }

    /**
     * Main job execution loop that performs peer testing with adaptive scheduling.
     * 
     * <p>Process flow:
     * <ol>
     *   <li>Check if testing should continue</li>
     *   <li>Select peers for testing based on criteria</li>
     *   <li>Test each selected peer</li>
     *   <li>Adapt next run delay based on system conditions</li>
     * </ol>
     * </p>
     * 
     * <p><b>Adaptive Behavior:</b></p>
     * <ul>
     *   <li>Double delay if job lag > 300ms (system overload)</li>
     *   <li>Double delay if CPU load > 80% (resource conservation)</li>
     *   <li>Normal delay otherwise</li>
     * </ul>
     */
    public void runJob() {
        long lag = getContext().jobQueue().getMaxLag();
        boolean keepTesting;
        PeerManager manager;
        synchronized(this) {
            keepTesting = _keepTesting;
            manager = _manager;
        }
        if (!keepTesting) return;
        
        // Select and test peers for this round
        Set<RouterInfo> peers = selectPeersToTest();
        for (RouterInfo peer : peers) {
            testPeer(peer);
        }
        
        // Adapt next run delay based on system performance
        if (lag > 300 || SystemVersion.getCPULoadAvg() > 80) {
            requeue(getPeerTestDelay() * 2);
            if (_log.shouldWarn())
            if (lag > 300) {
                _log.info("High Job lag (" + lag + "ms) -> Increasing delay before next run to " + getPeerTestDelay() * 2 + "ms");
            } else {
                _log.info("High CPU load -> Increasing delay before next run to " + getPeerTestDelay() * 2 + "ms");
            }
        } else {
            requeue(getPeerTestDelay());
        }
        if (_log.shouldInfo())
            _log.info("Next Peer Test run in " + getPeerTestDelay() + "ms");
    }

    /**
     * Selects peers for testing based on performance and capability criteria.
     * 
     * <p><b>Selection Criteria:</b></p>
     * <ul>
     *   <li><b>Primary candidates:</b> Version 0.9.57+, reachable, high bandwidth (O/P/X)</li>
     *   <li><b>Penalized:</b> Low bandwidth tiers (K/L/M/N) or unreachable - set capacity bonus to -30</li>
     *   <li><b>Excluded:</b> Missing local RouterInfo or profile</li>
     * </ul>
     * 
     * <p><b>Performance Impact:</b></p>
     * <ul>
     *   <li>Uses cached lookups to minimize database queries</li>
     *   <li>Pre-parses capabilities to avoid repeated string operations</li>
     *   <li>Logs skipped peers with specific reasons for debugging</li>
     * </ul>
     * 
     * @return set of RouterInfo structures for testing (excluding self)
     */
    private Set<RouterInfo> selectPeersToTest() {
        PeerManager manager;
        synchronized(this) {
            manager = _manager;
        }
        PeerSelectionCriteria criteria = new PeerSelectionCriteria();
        criteria.setMinimumRequired(getTestConcurrency());
        criteria.setMaximumRequired(getTestConcurrency());
        criteria.setPurpose(PeerSelectionCriteria.PURPOSE_TEST);
        List<Hash> peerHashes = manager.selectPeers(criteria);
        Set<RouterInfo> peers = new HashSet<RouterInfo>(peerHashes.size());
        for (Hash peer : peerHashes) {
            // Look up peer information and profile
            RouterInfo peerInfo = getContext().netDb().lookupRouterInfoLocally(peer);
            PeerProfile prof = getContext().profileOrganizer().getProfile(peer);
            String cap = null;
            String bw = "";
            String version = "";
            boolean reachable = false;
            if (peerInfo != null) {
              bw = peerInfo.getBandwidthTier();
              cap = peerInfo.getCapabilities();
              version = peerInfo.getVersion();
              reachable = cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
            }
            
            // Primary candidates: high-bandwidth, reachable, compatible version
            if (peerInfo != null && prof != null && cap != null && reachable && VersionComparator.comp(version, "0.9.57") >= 0 &&
                (bw.equals("O") || bw.equals("P") || bw.equals("X"))) {
                peers.add(peerInfo);
            // Low-bandwidth or unreachable peers: penalize but don't test
            } else if (peerInfo != null && prof != null && cap != null &&
                (!reachable || bw.equals("K") || bw.equals("L") || bw.equals("M") || bw.equals("N"))) {
                prof.setCapacityBonus(-30);
                if (_log.shouldInfo())
                    _log.info("Setting capacity bonus to -30 and skipping test for [" + peer.toBase64().substring(0,6) + "] -> K, L, M, N or unreachable");
            // Missing RouterInfo: cannot test
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
            _log.debug("Initiating peer test of [" + peer.getIdentity().getHash().toBase64().substring(0,6) + "] \n* Outbound: " + outTunnel + "\n* Inbound: " + inTunnel);
        } else if (_log.shouldInfo()) {
            _log.info("Initiating peer test of [" + peer.getIdentity().getHash().toBase64().substring(0,6) + "]");
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
                    if (peerInfo != null && prof != null) {
                        int speedBonus = prof.getSpeedBonus();
                        String cap = peerInfo.getCapabilities();
                        boolean reachable = cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
                        String bw = peerInfo.getBandwidthTier();
                        int timeout = getTestTimeout();
                        float testAvg = prof.getPeerTestTimeAverage();
                        if (prof != null && cap != null && (bw.equals("L") || bw.equals("M") || bw.equals("N") || !reachable)) {
                            try {
                                prof.setCapacityBonus(-30);
                                if (_log.shouldInfo())
                                    _log.info("Setting capacity bonus to -30 for [" + _peer.toBase64().substring(0,6) + "] -> L, M, N or unreachable");
                            } catch (NumberFormatException nfe) {}
                        } else if (timeLeft < 0) {
                            if (_log.shouldInfo())
                                _log.info("[" + _peer.toBase64().substring(0,6) + "] Test reply took too long: " +
                                          (0-timeLeft) + "ms too slow");
                            getContext().statManager().addRateData("peer.testTooSlow", 0 - timeLeft);
                            if (_peer != null && cap != null &&
                                (bw.equals("N") || bw.equals("O") || bw.equals("P") || bw.equals("X"))) {
                                try {
                                    prof.setCapacityBonus(-30);
                                    if (speedBonus >= 9999999)
                                        prof.setSpeedBonus(speedBonus - 9999999);
                                    if (_log.shouldInfo())
                                        _log.info("Setting capacity bonus to -30 for [" + _peer.toBase64().substring(0,6) + "]");
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
                                        _log.info("Setting capacity bonus to -30 for [" + _peer.toBase64().substring(0,6) + "]" +
                                                  " -> Average response is over twice timeout value");
                                } catch (NumberFormatException nfe) {}
                            } else if ((prof.getCapacityBonus() == -30 || prof.getSpeedBonus() < 9999999) && cap != null && reachable &&
                                       testAvg < (timeout * 2) && (bw.equals("O") || bw.equals("P") || bw.equals("X"))) {
                                try {
                                    if (prof.getCapacityBonus() == -30) {
                                        prof.setCapacityBonus(0);
                                        if (_log.shouldInfo())
                                            _log.info("Resetting capacity bonus to 0 for [" + _peer.toBase64().substring(0,6) + "]");
                                    }
                                    if (prof.getSpeedBonus() < 9999999 && cap != null && reachable &&
                                        (bw.equals("N") || bw.equals("O") || bw.equals("P") || bw.equals("X"))) {
                                        prof.setSpeedBonus(speedBonus + 9999999);
                                        if (_log.shouldInfo())
                                            _log.info("Setting speed bonus to 9999999 for [" + _peer.toBase64().substring(0,6) + "]");
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
                    if (prof != null && cap != null && reachable && (bw.equals("O") || bw.equals("P") || bw.equals("X"))) {
                        prof.setSpeedBonus(9999999);
                        if (_log.shouldInfo())
                            _log.info("[" + _peer.getIdentity().getHash().toBase64().substring(0,6) +
                                      "] Setting speed bonus to 9999999 for fast tier router");
                    }
                    if (prof != null && prof.getCapacityBonus() == -30 && cap != null && reachable &&
                        (!bw.equals("L") || !bw.equals("M"))) {
                        try {
                            prof.setCapacityBonus(0);
                            if (_log.shouldInfo())
                                _log.info("Resetting capacity bonus to 0 for [" + _peer.toBase64().substring(0,6) + "]");
                        } catch (NumberFormatException nfe) {}
                        return;
                    } else if (prof != null && cap != null && (!reachable || bw.equals("L") || (bw.equals("M")))) {
                        try {
                            prof.setCapacityBonus(-30);
                            if (_log.shouldInfo())
                                _log.info("Setting capacity bonus to -30 for [" + _peer.toBase64().substring(0,6) + "] -> L or M tier or unreachable");
                        } catch (NumberFormatException nfe) {}
                        return;
                    } else if (prof != null && cap == null) {
                        try {
                            prof.setCapacityBonus(-30);
                            if (_log.shouldInfo())
                                _log.info("Setting capacity bonus to -30 for [" + _peer.toBase64().substring(0,6) +
                                          "] -> No capabilities published in RouterInfo");
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

            if (getShouldFailPeer()) {getContext().profileManager().dbLookupFailed(_peer.getIdentity().getHash());}

            if (_log.shouldDebug()) {
                _log.debug("Test failed (timeout reached) for [" + _peer.toBase64().substring(0,6) + "]" +
                           "\n* " + _sendTunnel + "\n* " + _replyTunnel);
            } else if (_log.shouldInfo()) {
                _log.info("Test failed (timeout reached) for [" + _peer.toBase64().substring(0,6) + "]");
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
                    if (prof != null && cap != null && (!reachable || bw.equals("L") || bw.equals("M") || bw.equals("N"))) {
                        try {
                            prof.setCapacityBonus(-30);
                            prof.setSpeedBonus(0);
                            if (_log.shouldInfo())
                                _log.info("Setting capacity bonus to -30 and speed bonus to 0 for [" +
                                          _peer.toBase64().substring(0,6) + "] -> Slow or unreachable");
                        } catch (NumberFormatException nfe) {}
                        return;
                    }
                }
            }
        }
    }
}
