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

    // Adaptive peer testing for attack mitigation @since 0.9.68+
    private static final boolean ADAPTIVE_PEER_TEST = true;
    public static final String PROP_ADAPTIVE_PEER_TEST = "router.adaptivePeerTest";
    private static final double LOW_SUCCESS_THRESHOLD = 0.40; // 40%
    private static final long ADAPTIVE_CHECK_INTERVAL = 60 * 1000; // Check every minute
    private long _lastAdaptiveCheck;

    // State tracking for adaptive behavior @since 0.9.68+
    private volatile boolean _aggressiveMode = false;

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
      * <p>Concurrency is limited to 1 when CPU load exceeds 80% or job queue is backed up.
      * Default value adapts based on system resources (1-4 peers).
      * Aggressive mode doubles concurrency under attack if system can handle it.</p>
      *
       * @return number of peers to test in parallel
      */
    private int getTestConcurrency() {
        int cores = SystemVersion.getCores();
        long memory = SystemVersion.getMaxMemory();
        int testConcurrent = getContext().getProperty(PROP_PEER_TEST_CONCURRENCY, DEFAULT_PEER_TEST_CONCURRENCY);

        // Always reduce under high CPU load
        if (SystemVersion.getCPULoadAvg() > 80) {
            return 1;
        }

        // Don't increase concurrency if job queue is backed up @since 0.9.68+
        long maxLag = getContext().jobQueue().getMaxLag();
        if (maxLag > 300) {
            // Queue is backed up, use minimum concurrency
            return Math.min(testConcurrent, 2);
        }

        if (_aggressiveMode) {
            // Double concurrency under attack to identify failures faster,
            // but only if system is healthy enough @since 0.9.68+
            return Math.min(testConcurrent * 2, 8);
        }
        return testConcurrent;
    }

    /**
     * Override concurrency to use aggressive mode under attack.
     * Aggressive mode tests more peers concurrently to identify failures faster.
     * @return number of peers to test in parallel
     * @since 0.9.68+
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
     *   <li>Enable aggressive testing when build success < 40% (attack mitigation)</li>
     *   <li>Increase test concurrency under attack to identify failing peers faster</li>
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

        // Adaptive peer testing: enable aggressive mode under attack @since 0.9.68+
        boolean adaptiveEnabled = getContext().getProperty(PROP_ADAPTIVE_PEER_TEST, ADAPTIVE_PEER_TEST);
        if (adaptiveEnabled) {
            adaptToNetworkConditions();
        }

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
     * Adaptive peer testing based on tunnel build success.
     * Under attack (low build success), run more aggressive testing to identify
     * failing peers faster and exclude them from tunnel builds.
     * @since 0.9.68+
     */
    private void adaptToNetworkConditions() {
        long now = System.currentTimeMillis();
        if (now - _lastAdaptiveCheck < ADAPTIVE_CHECK_INTERVAL) {
            return; // Only check once per minute
        }
        _lastAdaptiveCheck = now;

        double buildSuccess = getContext().profileOrganizer().getTunnelBuildSuccess();
        boolean wasAggressive = _aggressiveMode;
        _aggressiveMode = buildSuccess < LOW_SUCCESS_THRESHOLD;

        if (_aggressiveMode && !wasAggressive) {
            // Transitioned to aggressive mode
            if (_log.shouldWarn()) {
                _log.warn("Low tunnel build success (" + (int)(buildSuccess * 100) + "%) -> Enabling aggressive peer testing");
            }
        } else if (!_aggressiveMode && wasAggressive) {
            // Transitioned back to normal
            if (_log.shouldInfo()) {
                _log.info("Tunnel build success recovered (" + (int)(buildSuccess * 100) + "%) -> Returning to normal peer testing");
            }
        }
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
            PeerData data = new PeerData(getContext(), peer);
            
            // Primary candidates: high-bandwidth, reachable, compatible version
            if (data.routerInfo != null && data.profile != null && data.capabilities != null && data.isReachable && 
                VersionComparator.comp(data.routerInfo.getVersion(), "0.9.57") >= 0 &&
                (data.bandwidthTier.equals("O") || data.bandwidthTier.equals("P") || data.bandwidthTier.equals("X"))) {
                peers.add(data.routerInfo);
            // Low-bandwidth or unreachable peers: penalize but don't test
            } else if (data.routerInfo != null && data.profile != null && data.capabilities != null &&
                (!data.isReachable || data.bandwidthTier.equals("K") || data.bandwidthTier.equals("L") || 
                 data.bandwidthTier.equals("M") || data.bandwidthTier.equals("N"))) {
                data.profile.setCapacityBonus(-30);
                if (_log.shouldInfo())
                    _log.info("Setting capacity bonus to -30 and skipping test for [" + data.shortHash + "] -> K, L, M, N or unreachable");
            // Missing RouterInfo: cannot test
            } else if (data.routerInfo == null) {
                if (_log.shouldInfo())
                    _log.info("Test of [" + data.shortHash + "] failed: No local RouterInfo");
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

        String shortHash = peer.getIdentity().getHash().toBase64().substring(0, 6);
        if (_log.shouldDebug()) {
            _log.debug("Initiating peer test of [" + shortHash + "] \n* Outbound: " + outTunnel + "\n* Inbound: " + inTunnel);
        } else if (_log.shouldInfo()) {
            _log.info("Initiating peer test of [" + shortHash + "]");
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
     * Cached peer data to eliminate repeated lookups and string parsing.
     * 
     * <p>This class consolidates all frequently accessed peer information into a single
     * object, reducing database queries and improving performance during peer testing.
     * All data is immutable once created, making it thread-safe for concurrent testing.</p>
     * 
     * <p><b>Performance Benefits:</b></p>
     * <ul>
     *   <li>Single netDb lookup instead of multiple calls</li>
     *   <li>Pre-parsed capability flags (no repeated string operations)</li>
     *   <li>Cached short hash for logging (avoids repeated base64 encoding)</li>
     * </ul>
     */
    private static class PeerData {
        /** The peer's RouterInfo from the network database (null if not found locally) */
        final RouterInfo routerInfo;
        /** The peer's performance profile (null if no profile exists) */
        final PeerProfile profile;
        /** Shortened hash identifier for logging (first 6 chars of base64) */
        final String shortHash;
        /** Whether the peer has the reachable capability flag */
        final boolean isReachable;
        /** The peer's bandwidth tier (O, P, X, K, L, M, N) or empty string */
        final String bandwidthTier;
        /** Raw capabilities string from RouterInfo (may be null) */
        final String capabilities;
        
        /**
         * Creates a new PeerData instance by looking up all necessary information.
         * 
         * @param ctx the router context for accessing network database and profiles
         * @param peerHash the hash of the peer to gather data for
         */
        PeerData(RouterContext ctx, Hash peerHash) {
            // Generate short hash once for all logging operations
            this.shortHash = peerHash.toBase64().substring(0, 6);
            
            // Single database lookup to avoid repeated calls
            this.routerInfo = ctx.netDb().lookupRouterInfoLocally(peerHash);
            this.profile = ctx.profileOrganizer().getProfile(peerHash);
            
            if (routerInfo != null) {
                this.bandwidthTier = routerInfo.getBandwidthTier();
                this.capabilities = routerInfo.getCapabilities();
                // Pre-parse reachability to avoid repeated string operations
                this.isReachable = capabilities != null && 
                                 capabilities.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
            } else {
                // Safe defaults for missing RouterInfo
                this.bandwidthTier = "";
                this.capabilities = "";
                this.isReachable = false;
            }
        }
    }

    /**
     * Simple selector looking for a dbStore of the peer specified
     *
     */
    private class ReplySelector implements MessageSelector {
        private final long _expiration;
        private final long _nonce;
        private final Hash _peer;
        private final String _shortHash;
        private boolean _matchFound;

        public ReplySelector(Hash peer, long nonce, long expiration) {
            _nonce = nonce;
            _expiration = expiration;
            _peer = peer;
            _shortHash = peer.toBase64().substring(0, 6);
            _matchFound = false;
        }
        public boolean continueMatching() { return false; }
        public long getExpiration() { return _expiration; }
        public boolean isMatch(I2NPMessage message) {
            if (message.getType() != DeliveryStatusMessage.MESSAGE_TYPE) {
                return false;
            }
            
            DeliveryStatusMessage msg = (DeliveryStatusMessage)message;
            if (_nonce != msg.getMessageId()) {
                return false;
            }
            
            PeerData data = new PeerData(getContext(), _peer);
            if (data.routerInfo == null || data.profile == null) {
                return false;
            }
            
            long timeLeft = _expiration - getContext().clock().now();
            int speedBonus = data.profile.getSpeedBonus();
            int timeout = getTestTimeout();
            float testAvg = data.profile.getPeerTestTimeAverage();
            
            if (isSlowTier(data)) {
                handleSlowTier(data.profile);
            } else if (timeLeft < 0) {
                handleTimeout(data, speedBonus, timeLeft);
            } else {
                return handleSuccessfulTest(data, speedBonus, timeout, testAvg, timeLeft);
            }
            
            return false;
        }
        
        private boolean isSlowTier(PeerData data) {
            return data.bandwidthTier.equals("L") || data.bandwidthTier.equals("M") || 
                   data.bandwidthTier.equals("N") || !data.isReachable;
        }
        
        private void handleSlowTier(PeerProfile prof) {
            try {
                prof.setCapacityBonus(-30);
                if (_log.shouldInfo())
                    _log.info("Setting capacity bonus to -30 for [" + _shortHash + "] -> L, M, N or unreachable");
            } catch (NumberFormatException nfe) {}
        }
        
        private void handleTimeout(PeerData data, int speedBonus, long timeLeft) {
            if (_log.shouldInfo())
                _log.info("[" + _shortHash + "] Test reply took too long: " + (0-timeLeft) + "ms too slow");
            
            getContext().statManager().addRateData("peer.testTooSlow", 0 - timeLeft);
            
            if (isHighBandwidthTier(data)) {
                try {
                    data.profile.setCapacityBonus(-30);
                    if (speedBonus >= 9999999)
                        data.profile.setSpeedBonus(speedBonus - 9999999);
                    if (_log.shouldInfo())
                        _log.info("Setting capacity bonus to -30 for [" + _shortHash + "]");
                } catch (NumberFormatException nfe) {}
            }
        }
        
        private boolean isHighBandwidthTier(PeerData data) {
            return data.bandwidthTier.equals("N") || data.bandwidthTier.equals("O") || 
                   data.bandwidthTier.equals("P") || data.bandwidthTier.equals("X");
        }
        
        private boolean handleSuccessfulTest(PeerData data, int speedBonus, int timeout, float testAvg, long timeLeft) {
            getContext().statManager().addRateData("peer.testOK", getTestTimeout() - timeLeft);
            
            if (testAvg > (timeout * 2) && isHighBandwidthTier(data)) {
                try {
                    data.profile.setCapacityBonus(-30);
                    if (speedBonus >= 9999999)
                        data.profile.setSpeedBonus(speedBonus - 9999999);
                    if (_log.shouldInfo())
                        _log.info("Setting capacity bonus to -30 for [" + _shortHash + "]" +
                                  " -> Average response is over twice timeout value");
                } catch (NumberFormatException nfe) {}
                return false;
            }
            
            if ((data.profile.getCapacityBonus() == -30 || data.profile.getSpeedBonus() < 9999999) && 
                data.capabilities != null && data.isReachable && testAvg < (timeout * 2) && 
                isHighOrMidBandwidthTier(data)) {
                try {
                    if (data.profile.getCapacityBonus() == -30) {
                        data.profile.setCapacityBonus(0);
                        if (_log.shouldInfo())
                            _log.info("Resetting capacity bonus to 0 for [" + _shortHash + "]");
                    }
                    if (data.profile.getSpeedBonus() < 9999999 && data.capabilities != null && 
                        data.isReachable && isHighBandwidthTier(data)) {
                        data.profile.setSpeedBonus(speedBonus + 9999999);
                        if (_log.shouldInfo())
                            _log.info("Setting speed bonus to 9999999 for [" + _shortHash + "]");
                    }
                } catch (NumberFormatException nfe) {}
                _matchFound = true;
                return true;
            }
            
            return false;
        }
        
        private boolean isHighOrMidBandwidthTier(PeerData data) {
            return data.bandwidthTier.equals("O") || data.bandwidthTier.equals("P") || 
                   data.bandwidthTier.equals("X") || data.bandwidthTier.equals("N");
        }
        public boolean matchFound() { return _matchFound; }
        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(64);
            buf.append("Test peer [").append(_shortHash);
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
            String shortHash = _peer.getIdentity().getHash().toBase64().substring(0, 6);
            if (_log.shouldDebug()) {
                _log.debug("[" + shortHash + "] Test succeeded in " +
                           responseTime + "ms\n* " + _sendTunnel + "\n* " + _replyTunnel);
            } else if (_log.shouldInfo()) {
                _log.info("[" + shortHash + "] Test succeeded in " + responseTime + "ms");
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

            String shortHash = _peer.getIdentity().getHash().toBase64().substring(0, 6);
            if (getShouldFailPeer()) {getContext().profileManager().dbLookupFailed(_peer.getIdentity().getHash());}

            if (_log.shouldDebug()) {
                _log.debug("Test failed (timeout reached) for [" + shortHash + "]" +
                           "\n* " + _sendTunnel + "\n* " + _replyTunnel);
            } else if (_log.shouldInfo()) {
                _log.info("Test failed (timeout reached) for [" + shortHash + "]");
            }

            // don't fail the tunnels, as the peer might just plain be down, or otherwise overloaded
            getContext().statManager().addRateData("peer.testTimeout", 1);

            PeerData data = new PeerData(getContext(), _peer.getIdentity().getHash());
            if (data.routerInfo != null && data.profile != null && data.capabilities != null && 
                (!data.isReachable || data.bandwidthTier.equals("L") || data.bandwidthTier.equals("M") || data.bandwidthTier.equals("N"))) {
                try {
                    data.profile.setCapacityBonus(-30);
                    data.profile.setSpeedBonus(0);
                    if (_log.shouldInfo())
                        _log.info("Setting capacity bonus to -30 and speed bonus to 0 for [" +
                                  shortHash + "] -> Slow or unreachable");
                } catch (NumberFormatException nfe) {}
            }
        }
    }
}
