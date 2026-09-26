package net.i2p.router.peermanager;

import java.util.ArrayList;
import java.util.Collections;
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
    /** Whether to continue testing. */
    private boolean _keepTesting;
    private final List<Hash> _priorityPeers = new ArrayList<>();
    /** Default delay between peer tests. */
    private static final long DEFAULT_PEER_TEST_DELAY = 5*60*1000L;
    /**
     * PROP_PEER_TEST_DELAY.
     */
    public static final String PROP_PEER_TEST_DELAY = "router.peerTestDelay";
    /** Default number of peers to test concurrently. */
    private static final int DEFAULT_PEER_TEST_CONCURRENCY = 1;
    /**
     * PROP_PEER_TEST_CONCURRENCY.
     */
    public static final String PROP_PEER_TEST_CONCURRENCY = "router.peerTestConcurrency";
    /** Default timeout in milliseconds. */
    private static final int DEFAULT_PEER_TEST_TIMEOUT = 10000;
    /**
     * PROP_PEER_TEST_TIMEOUT.
     */
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
        getContext().statManager().createRequiredRateStat("peer.testTimeout", "Frequency of test timeouts (no reply)", "Peers", new long[] { RateConstants.ONE_MINUTE, RateConstants.TEN_MINUTES, RateConstants.ONE_HOUR });
    }

    /**
     * Average successful peer test time over the last minute.
     *
     * @return average test time in milliseconds, or 0 if no context available
     */
    public int getAvgPeerTestTime() {
        if (getContext() == null)
            return 0;
        RateStat rs = getContext().statManager().getRate("peer.testOK");
        Rate r = rs.getRate(RateConstants.ONE_MINUTE);
        return (int) r.getLifetimeAverageValue();
    }

    /**
     * Total average peer test time over the last hour, including slow tests.
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
        return (int) rok.getLifetimeAverageValue() + (int) rtooslow.getLifetimeAverageValue();
    }

    /** Uptime threshold for the aggressive startup profiling phase (4 hours). */
    private static final long STARTUP_PHASE_MS = 4 * 60 * 60 * 1000L;
    /** Uptime threshold for the warm-up decay phase (12 hours). */
    private static final long WARMUP_PHASE_MS = 12 * 60 * 60 * 1000L;
    /** Delay during the aggressive startup phase. */
    private static final long STARTUP_DELAY_MS = 3 * 1000L;
    /** Delay during the warm-up decay phase. */
    private static final long WARMUP_DELAY_MS = 5 * 1000L;
    /** Delay during steady state. */
    private static final long STEADY_DELAY_MS = 8 * 1000L;

    /**
     * Calculates the delay before starting the next round of peer tests.
     *
     * <p>Three-phase decay based on uptime:</p>
     * <ul>
     *   <li>0–4 hours (startup): 3s — aggressively profile all fast peers</li>
     *   <li>4–12 hours (warm-up): 5s — ramp down while keeping data fresh</li>
     *   <li>12+ hours (steady): 8s — maintenance cadence</li>
     * </ul>
     *
     * <p>User override via {@link #PROP_PEER_TEST_DELAY} is respected for the steady-state
     * value only; startup and warm-up phases use hardcoded values.</p>
     *
     * @return delay in milliseconds before next test round
     */
    private long getPeerTestDelay() {
        long uptime = getContext().router().getUptime();
        if (uptime < STARTUP_PHASE_MS) {
            return STARTUP_DELAY_MS;
        } else if (uptime < WARMUP_PHASE_MS) {
            return WARMUP_DELAY_MS;
        } else {
            long configured = getContext().getProperty(PROP_PEER_TEST_DELAY, DEFAULT_PEER_TEST_DELAY);
            return Math.max(configured, STEADY_DELAY_MS);
        }
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
        int avgTestTime = getAvgPeerTestTime();
        // Never reduce below the configured default — only auto-increase
        // if the average successful test time exceeds it
        if (avgTestTime > testTimeout)
            return avgTestTime + 5000;
        return testTimeout;
    }

    /**
     * Determines the number of peers to test concurrently based on router uptime.
     *
     * <p>Three-phase decay:</p>
     * <ul>
     *   <li>0–4 hours (startup): 4 — profile all fast peers quickly</li>
     *   <li>4–12 hours (warm-up): 3 — ramp down</li>
     *   <li>12+ hours (steady): 1 — maintenance cadence</li>
     * </ul>
     *
     * <p>Concurrency is limited to 1 when CPU load exceeds 95% to prevent system overload.</p>
     *
     * @return number of peers to test in parallel
     */
    private int getTestConcurrency() {
        if (SystemVersion.getCPULoadAvg() > 95) {return 1;}
        long uptime = getContext().router().getUptime();
        if (uptime < STARTUP_PHASE_MS) {
            return 4;
        } else if (uptime < WARMUP_PHASE_MS) {
            return 3;
        } else {
            return getContext().getProperty(PROP_PEER_TEST_CONCURRENCY, DEFAULT_PEER_TEST_CONCURRENCY);
        }
    }

    /**
     * Starts the peer testing process with adaptive initial delay.
     *
     * <p>Schedules the first test run based on router uptime:</p>
     * <ul>
     *   <li>If uptime &lt; 3 minutes: wait 3 minutes before starting</li>
     *   <li>Otherwise: start immediately with configured delay</li>
     * </ul>
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
        if (uptime < 3*60*1000L) {
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

    /**
     * Schedule peers for priority testing (e.g., from slow tunnels).
     * These peers will be tested on the next test cycle before other peers.
     *
     * @param peers list of peer hashes to test urgently
     * @since 0.9.69+
     */
    public synchronized void schedulePriorityTests(List<Hash> peers) {
        if (peers == null || peers.isEmpty()) return;
        synchronized (_priorityPeers) {
            for (Hash peer : peers) {
                if (!_priorityPeers.contains(peer)) {
                    _priorityPeers.add(peer);
                }
            }
        }
        if (_log.shouldInfo()) {
            _log.info("Scheduled " + peers.size() + " priority peer tests");
        }
    }

    /**
     * Name of this job.
     *
     * @return the name
     */
    public String getName() { return "Test Peers"; }

    /**
     * Main job execution loop that performs peer testing with adaptive scheduling.
     *
     * Process flow:
     * <ol>
     *   <li>Check if testing should continue</li>
     *   <li>Select peers for testing based on criteria</li>
     *   <li>Test each selected peer</li>
     *   <li>Adapt next run delay based on system conditions</li>
     * </ol>
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
        synchronized(this) {
            keepTesting = _keepTesting;
        }
        if (!keepTesting) return;

        try {
            // Select and test peers for this round
            Set<RouterInfo> peers;
            try {
                peers = selectPeersToTest();
            } catch (Exception e) {
                if (_log.shouldWarn())
                    _log.warn("Error selecting peers to test", e);
                peers = Collections.emptySet();
            }
            for (RouterInfo peer : peers) {
                testPeer(peer);
            }
        } finally {
            // Always requeue — even on exception — so the job never dies.
            // A dead peer test job means startup profiling stalls permanently.
            long baseDelay = getPeerTestDelay();
            long delay;
            if (lag > 2000) {
                // System under heavy load — skip this round entirely
                if (_log.shouldWarn()) {
                    _log.warn("Extreme Job lag (" + lag + "ms) -> skipping peer test round");
                }
                delay = baseDelay * 5;
            } else if (lag > 300 || SystemVersion.getCPULoadAvg() > 80) {
                // Scale delay proportionally: 500ms lag → 2×, 1000ms lag → 3×
                double multiplier = 1.0 + (lag / 500.0);
                delay = Math.min((long)(baseDelay * multiplier), 5 * 60 * 1000L);
                if (_log.shouldWarn()) {
                    _log.info("High Job lag (" + lag + "ms) -> scaling delay to " + delay + "ms (base: " + baseDelay + "ms)");
                }
            } else {
                delay = baseDelay;
            }
            requeue(delay);
            if (_log.shouldInfo())
                _log.info("Next Peer Test run in " + delay + "ms");
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
        ProfileOrganizer organizer = getContext().profileOrganizer();
        Set<RouterInfo> peers = new HashSet<>();

        // First, test priority peers (from slow tunnels)
        List<Hash> priorityPeers;
        synchronized (_priorityPeers) {
            priorityPeers = new ArrayList<>(_priorityPeers);
            _priorityPeers.clear();
        }
        if (!priorityPeers.isEmpty()) {
            for (Hash peer : priorityPeers) {
                PeerData data = new PeerData(getContext(), peer);
                if (data.routerInfo != null) {
                    peers.add(data.routerInfo);
                    if (_log.shouldDebug()) {
                        _log.debug("Testing priority peer: " + peer.toBase32().substring(0, 6));
                    }
                }
            }
        }

        // Now get regular peers, prioritizing by tier: fast > high-cap > all.
        // All profiled peers with a RouterInfo are eligible; we sort by tier
        // priority then by staleness so the most important and most stale
        // peers are tested first.
        int needed = getTestConcurrency() - peers.size();
        if (needed > 0) {
            // Use non-blocking selection to avoid stalling on reorganize()'s
            // write lock.  If the lock is held, skip this round and requeue.
            List<Hash> peerHashes = manager.selectTestPeersNonBlocking(needed * 4);
            if (peerHashes == null) {
                if (_log.shouldDebug())
                    _log.debug("Read lock held by reorganize -> skipping peer test round");
                return peers;
            }

            List<PeerData> validCandidates = new ArrayList<>();
            for (Hash peer : peerHashes) {
                PeerData data = new PeerData(getContext(), peer);

                // Skip if already testing as priority
                if (priorityPeers.contains(peer)) continue;
                // Need RouterInfo and profile to test
                if (data.routerInfo == null || data.profile == null) continue;
                // Skip incompatible versions
                if (VersionComparator.comp(data.routerInfo.getVersion(), "0.9.57") < 0) continue;

                validCandidates.add(data);
            }

            // Sort: tier priority (fast=0, high-cap=1, other=2),
            // then by tunnelTestTimeAvgLastUpdate ascending (never tested first).
            // Uses persisted EWMA timestamp so untested peers are prioritized after restart.
            validCandidates.sort((a, b) -> {
                int aTier = organizer.isFast(a.profile.getPeer()) ? 0
                          : organizer.isHighCapacity(a.profile.getPeer()) ? 1 : 2;
                int bTier = organizer.isFast(b.profile.getPeer()) ? 0
                          : organizer.isHighCapacity(b.profile.getPeer()) ? 1 : 2;
                int tierCmp = aTier - bTier;
                if (tierCmp != 0) return tierCmp;
                return Long.compare(
                    a.profile.getTunnelTestTimeAvgLastUpdate(),
                    b.profile.getTunnelTestTimeAvgLastUpdate()
                );
            });

            // Add top candidates up to concurrency limit
            for (PeerData data : validCandidates) {
                if (peers.size() >= getTestConcurrency()) break;
                peers.add(data.routerInfo);
            }
        }

        if (getTestConcurrency() != 1 && !peers.isEmpty() && _log.shouldInfo())
            _log.info("Running " + peers.size() + " concurrent peer tests (" + priorityPeers.size() + " priority)");
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
                _log.warn("Gateway to our Inbound tunnel not found -> Probably a dead tunnel");
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
        PeerProfile prof = getContext().profileOrganizer().getProfile(peer.getIdentity().getHash());
        if (prof != null) {
            prof.setLastTestStarted(getContext().clock().now());
        }
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
     * What tunnel will we send the test out through?
     *
     * @return tunnel id (or null if none are found)
     */
    private TunnelInfo getOutboundTunnelId() {
        return getContext().tunnelManager().selectOutboundTunnel();
    }

    /**
     * What tunnel will we get replies through?
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

        /**
         * ReplySelector.
         */
        public ReplySelector(Hash peer, long nonce, long expiration) {
            _nonce = nonce;
            _expiration = expiration;
            _peer = peer;
            _shortHash = peer.toBase64().substring(0, 6);
            _matchFound = false;
        }
        /**
         * Whether to keep matching replies for this test.
         */
        public boolean continueMatching() { return false; }
        /**
         * Expiration time of this reply match.
         *
         * @return the expiration
         */
        public long getExpiration() { return _expiration; }
        /**
         * Whether the message matches this test.
         *
         * @return whether match
         */
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
            int timeout = getTestTimeout();
            float testAvg = data.profile.getPeerTestTimeAverage();

            if (isSlowTier(data)) {
                handleSlowTier(data.profile);
            } else if (timeLeft < 0) {
                handleTimeout(data, timeLeft);
            } else {
                return handleSuccessfulTest(data, timeout, testAvg, timeLeft);
            }

            return false;
        }

        private boolean isSlowTier(PeerData data) {
            return data.bandwidthTier.equals("L") || data.bandwidthTier.equals("M") ||
                   data.bandwidthTier.equals("N") || !data.isReachable;
        }

        private void handleSlowTier(PeerProfile prof) {
            try {
                getContext().profileOrganizer().demoteIfHighLatency(_peer);
                if (_log.shouldInfo())
                    _log.info("Demoting [" + _shortHash + "] -> L, M, N or unreachable");
            } catch (NumberFormatException nfe) { /* ignored */ }
        }

        private void handleTimeout(PeerData data, long timeLeft) {
            if (_log.shouldInfo())
                _log.info("[" + _shortHash + "] Test reply took too long: " + (0-timeLeft) + "ms too slow");

            getContext().statManager().addRateData("peer.testTooSlow", 0 - timeLeft);

            if (isHighBandwidthTier(data)) {
                try {
                    data.profile.setLowLatency(false);
                    getContext().profileOrganizer().demoteIfNotLowLatency(_peer);
                    if (_log.shouldInfo())
                        _log.info("Demoting [" + _shortHash + "] -> test timeout");
                } catch (NumberFormatException nfe) { /* ignored */ }
            }
        }

        private boolean isHighBandwidthTier(PeerData data) {
            return data.bandwidthTier.equals("N") || data.bandwidthTier.equals("O") ||
                   data.bandwidthTier.equals("P") || data.bandwidthTier.equals("X");
        }

        private boolean handleSuccessfulTest(PeerData data, int timeout, float testAvg, long timeLeft) {
            getContext().statManager().addRateData("peer.testOK", getTestTimeout() - timeLeft);

            // Record ping response time so peer pre-qualification can confirm this peer has been tested
            float responseMs = getTestTimeout() - timeLeft;
            data.profile.updatePeerTestTimeAverage(responseMs);

            if (testAvg > (timeout * 2L) && isHighBandwidthTier(data)) {
                try {
                    data.profile.setLowLatency(false);
                    getContext().profileOrganizer().demoteIfNotLowLatency(_peer);
                    if (_log.shouldInfo())
                        _log.info("Demoting [" + _shortHash + "]" +
                                  " -> Average response is over twice timeout value");
                } catch (NumberFormatException nfe) { /* ignored */ }
                return false;
            }

            if (!data.profile.isLowLatency() &&
                data.capabilities != null && data.isReachable && testAvg < (timeout * 2) &&
                isHighOrMidBandwidthTier(data)) {
                try {
                    if (!data.profile.isLowLatency() && data.capabilities != null &&
                        data.isReachable && isHighBandwidthTier(data)) {
                        data.profile.setLowLatency(true);
                        if (_log.shouldInfo())
                            _log.info("Setting low latency flag for [" + _shortHash + "]");
                    }
                } catch (NumberFormatException nfe) { /* ignored */ }
                _matchFound = true;
                return true;
            }

            return false;
        }

        private boolean isHighOrMidBandwidthTier(PeerData data) {
            return data.bandwidthTier.equals("O") || data.bandwidthTier.equals("P") ||
                   data.bandwidthTier.equals("X") || data.bandwidthTier.equals("N");
        }
        /**
         * Whether a matching reply was found.
         */
        public boolean matchFound() { return _matchFound; }
        /**
         * String form of this selector for logging.
         */
        @Override
        public String toString() {
            return "Test peer [" + _shortHash + "] with nonce: " + _nonce;
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

        /**
         * PeerReplyFoundJob.
         */
        public PeerReplyFoundJob(RouterContext context, RouterInfo peer, TunnelInfo replyTunnel, TunnelInfo sendTunnel) {
            super(context);
            _peer = peer;
            _replyTunnel = replyTunnel;
            _sendTunnel = sendTunnel;
            _testBegin = context.clock().now();
        }
        /**
         * Name of this job.
         *
         * @return the name
         */
        public String getName() { return "Verify Peer Test"; }
        /**
         * Process the matched reply and record the test result.
         */
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
                    boolean reachable = cap != null && cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
                    String bw = peerInfo.getBandwidthTier();
                    PeerProfile prof = getContext().profileOrganizer().getProfile(h);
                    if (prof != null && cap != null && reachable && (bw.equals("O") || bw.equals("P") || bw.equals("X"))) {
                        prof.setLowLatency(true);
                        if (_log.shouldInfo())
                            _log.info("[" + _peer.getIdentity().getHash().toBase64().substring(0,6) +
                                      "] Setting low latency flag for fast tier router");
                    }
                    if (prof != null && prof.getCapacityBonus() == -30 && cap != null && reachable) {
                        try {
                            prof.setCapacityBonus(0);
                            if (_log.shouldInfo())
                                _log.info("Resetting capacity bonus to 0 for [" + _peer.toBase64().substring(0,6) + "]");
                        } catch (NumberFormatException nfe) { /* ignored */ }
                        return;
                    } else if (prof != null && cap != null && (!reachable || bw.equals("L") || (bw.equals("M")))) {
                        try {
                            getContext().profileOrganizer().demoteIfHighLatency(h);
                            if (_log.shouldInfo())
                                _log.info("Demoting [" + _peer.toBase64().substring(0,6) + "] -> L or M tier or unreachable");
                        } catch (NumberFormatException nfe) { /* ignored */ }
                        return;
                    } else if (prof != null && cap == null) {
                        try {
                            getContext().profileOrganizer().demoteIfHighLatency(h);
                            if (_log.shouldInfo())
                                _log.info("Demoting [" + _peer.toBase64().substring(0,6) +
                                          "] -> No capabilities published in RouterInfo");
                        } catch (NumberFormatException nfe) { /* ignored */ }
                        return;
                    } else if (prof != null && cap != null &&
                               (cap.indexOf(Router.CAPABILITY_CONGESTION_MODERATE) >= 0 ||
                                cap.indexOf(Router.CAPABILITY_CONGESTION_SEVERE) >= 0)) {
                        try {
                            getContext().profileOrganizer().demoteIfCongested(h);
                            if (_log.shouldInfo())
                                _log.info("Demoting [" + _peer.toBase64().substring(0,6) +
                                          "] -> Congestion cap (D/E) detected");
                        } catch (NumberFormatException nfe) { /* ignored */ }
                        return;
                    }
                }
            }
        }

        /**
         * Store the message for this job.
         */
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

        /**
         * PeerReplyTimeoutJob.
         */
        public PeerReplyTimeoutJob(RouterContext context, RouterInfo peer, TunnelInfo replyTunnel, TunnelInfo sendTunnel, ReplySelector sel) {
            super(context);
            _peer = peer;
            _replyTunnel = replyTunnel;
            _sendTunnel = sendTunnel;
            _selector = sel;
        }
        /**
         * Name of this job.
         *
         * @return the name
         */
        public String getName() { return "Timeout Peer Test"; }
        private boolean getShouldFailPeer() { return true; }
        /**
         * Run the timeout handling job.
         */
        public void runJob() {
            if (_selector.matchFound())
                return;

            String shortHash = _peer.getIdentity().getHash().toBase64().substring(0, 6);
            if (getShouldFailPeer()) {getContext().profileManager().dbLookupFailed(_peer.getIdentity().getHash());} // NOSONAR S2589 always true

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
                    data.profile.setLowLatency(false);
                    getContext().profileOrganizer().demoteIfNotLowLatency(_peer.getIdentity().getHash());
                    if (_log.shouldInfo())
                        _log.info("Demoting [" +
                                  shortHash + "] -> Slow or unreachable");
                } catch (NumberFormatException nfe) { /* ignored */ }
            }
        }
    }
}
