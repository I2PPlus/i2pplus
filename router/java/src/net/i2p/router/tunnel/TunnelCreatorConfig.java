package net.i2p.router.tunnel;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelTestStatus;
import net.i2p.router.networkdb.kademlia.MessageWrapper.OneTimeSession;
import net.i2p.util.Log;

/**
 * Coordinate the info that the tunnel creator keeps track of, including what
 * peers are in the tunnel and what their configuration is
 *
 * See PooledTunnelCreatorConfig for the non-abstract class
 */
public abstract class TunnelCreatorConfig implements TunnelInfo {
    /**
     * The router context.
     */
    protected final RouterContext _context;
    /** Only necessary for client tunnels. */
    private final Hash _destination;
    /** Gateway first. */
    private final HopConfig[] _config;
    /** Gateway first. */
    private final Hash[] _peers;
    private volatile long _expiration;
    private List<Integer> _order;
    private long _replyMessageId;
    private final boolean _isInbound;
    private final AtomicInteger _messagesProcessed = new AtomicInteger();
    private long _verifiedBytesTransferred;
    private long _lastTransferredTime;
    private final AtomicInteger _failures = new AtomicInteger();
    private volatile TunnelTestStatus _testStatus = TunnelTestStatus.UNTESTED;

    private volatile boolean _reused;
    private volatile int _priority;
    private long _peakThroughputCurrentTotal;
    private long _peakThroughputLastCoallesce = System.currentTimeMillis();
    private Hash _blankHash;
    private SessionKey[] _ChaReplyKeys;
    private byte[][] _ChaReplyADs;
    private final SessionKey[] _AESReplyKeys;
    private final byte[][] _AESReplyIVs;
    // short record OBEP only
    private OneTimeSession _garlicReplyKeys;
    private Log _log;

    /**
     *  IV length for {@link #getAESReplyIV}
     *  @since 0.9.48 moved from HopConfig
     */
    public static final int REPLY_IV_LENGTH = 16;

    // Make configurable? - but can't easily get to pool options from here
    /**
     * Maximum consecutive test failures before the tunnel is retired.
     */
    public static final int MAX_CONSECUTIVE_TEST_FAILURES = 3;
    private static final int LATENCY_SAMPLE_SIZE = 3;
    private volatile int _lastLatency = -1;
    private final int[] _latencyHistory = new int[LATENCY_SAMPLE_SIZE];
    private volatile int _latencyIdx = 0;
    private volatile int _latencyCount = 0;
    private volatile boolean _needsExpeditedTest = false;
    /**
     *  Recent-traffic test exemptions used.
     *  A tunnel with recent verified data gets a few free passes on test
     *  failures (reply-path false negatives), but not unlimited — after
     *  {@link TestJob#MAX_RECENT_EXEMPTIONS} exemptions, failures count
     *  normally so the tunnel doesn't become immortal.
     *  @since 0.9.69+
     */
    private volatile int _recentTestExemptions;
    /** Optional pool nickname for log display. */
    private String _destinationNickname;

    /**
     *  For exploratory only (null destination)
     *  @param length 1 minimum (0 hop is length 1)
     */
    public TunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound) {
        this(ctx, length, isInbound, null);
    }

    /**
     *  Optional display nickname for this tunnel (e.g. pool name like I2PSnark)
     *  @since 0.9.70+
     */
    public void setDestinationNickname(String name) { _destinationNickname = name; }

    /**
     *  Pool nickname for this tunnel, or null if not set.
     *  @return the pool nickname if set, null otherwise
     *  @since 0.9.70+
     */
    public String getDestinationNickname() { return _destinationNickname; }

    /**
     * Allocates the hop configs and peer arrays for the given length.
     *
     * @param length 1 minimum (0 hop is length 1)
     * @param destination null for exploratory
     */
    public TunnelCreatorConfig(RouterContext ctx, int length, boolean isInbound, Hash destination) {
        _context = ctx;
        if (length <= 0) {throw new IllegalArgumentException("0 length? 0 hop tunnels are 1 length!");}
        _config = new HopConfig[length];
        _peers = new Hash[length];
        for (int i = 0; i < length; i++) {_config[i] = new HopConfig();}
        _isInbound = isInbound;
        _destination = destination;
        _AESReplyKeys = new SessionKey[length];
        _AESReplyIVs = new byte[length][];
    }

    /**
     * How many hops are there in the tunnel?
     * INCLUDING US.
     * i.e. one more than the TunnelCreatorConfig length.
     * @return the length
     */
    public int getLength() {return _config.length;}

    /**
     * The tunnel pool options.
     *
     * @return the options
     */
    public Properties getOptions() {return null;}

    /**
     * Retrieve the config for the given hop.  the gateway is
     * hop 0.
     * @return the config
     */
    public HopConfig getConfig(int hop) {return _config[hop];}

    /**
     * Retrieve the tunnelId that the given hop receives messages on.
     * the gateway is hop 0.
     *
     * @return the receive tunnel id
     */
    public TunnelId getReceiveTunnelId(int hop) {return _config[hop].getReceiveTunnel();}

    /**
     * Retrieve the tunnelId that the given hop sends messages on.
     * the gateway is hop 0.
     *
     * @return the send tunnel id
     */
    public TunnelId getSendTunnelId(int hop) {return _config[hop].getSendTunnel();}

    /** Retrieve the peer at the given hop (the gateway is hop 0). */
    public Hash getPeer(int hop) {return _peers[hop];}
    /**
     * The peer at the given hop.
     */
    public void setPeer(int hop, Hash peer) {_peers[hop] = peer;}

    /**
     *  For convenience
     *  @return getPeer(0)
     *  @since 0.8.9
     */
    public Hash getGateway() {return _peers[0];}

    /**
     *  For convenience
     *  @return getPeer(getLength() - 1)
     *  @since 0.8.9
     */
    public Hash getEndpoint() {return _peers[_peers.length - 1];}

    /**
     *  For convenience
     *  @return isInbound() ? getGateway() : getEndpoint()
     *  @since 0.8.9
     */
    public Hash getFarEnd() {return _peers[_isInbound ? 0 : _peers.length - 1];}

    /** Is this an inbound tunnel? */
    public boolean isInbound() {return _isInbound;}

    /**
     *  If this is a client tunnel, what destination is it for?
     *  @return null for exploratory
     */
    public Hash getDestination() {return _destination;}

    /**
     * The tunnel expiration, in ms since the epoch.
     *
     * @return the expiration
     */
    public long getExpiration() {return _expiration;}
    /**
     * The tunnel expiration, in ms since the epoch.
     */
    public void setExpiration(long when) {_expiration = when;}

    /** Component ordering in the new style request. */
    public List<Integer> getReplyOrder() {return _order;}
    /**
     * The component ordering for the new style request.
     */
    public void setReplyOrder(List<Integer> order) {_order = order;}

    /** The message ID for the new style reply. */
    public long getReplyMessageId() {return _replyMessageId;}
    /**
     * The message ID for the new style reply.
     */
    public void setReplyMessageId(long id) {_replyMessageId = id;}

    /** Take note of a message being pumped through this tunnel. */
    public void incrementProcessedMessages() {_messagesProcessed.incrementAndGet();}
    /**
     * The number of messages pumped through this tunnel.
     *
     * @return the processed messages count
     */
    public int getProcessedMessagesCount() {return _messagesProcessed.get();}

    /**
     *  This calls profile manager tunnelDataPushed1m() for each peer
     */
    public synchronized void incrementVerifiedBytesTransferred(int bytes) {
        _verifiedBytesTransferred += bytes;
        _peakThroughputCurrentTotal += bytes;
        long now = System.currentTimeMillis();
        _lastTransferredTime = now;
        long timeSince = now - _peakThroughputLastCoallesce;
        if (timeSince >= 60*1000) {
            long tot = _peakThroughputCurrentTotal;
            int normalized = (int) (tot * 60d*1000d / timeSince);
            _peakThroughputLastCoallesce = now;
            _peakThroughputCurrentTotal = 0;
            // Capture peers and context for iteration outside the lock
            if (_context != null && _peers.length > 0) {
                int start = _isInbound ? 0 : 1;
                int end = _isInbound ? _peers.length - 1 : _peers.length;
                Hash[] peersCopy = Arrays.copyOfRange(_peers, start, end);
                _context.jobQueue().addJob(new JobImpl(_context) {
                    /**
                     * Update the per-peer throughput profiles with the normalized total.
                     */
                    @Override
                    public void runJob() {
                        for (Hash peer : peersCopy) {
                            _context.profileManager().tunnelDataPushed1m(peer, normalized);
                        }
                    }
                    /**
                     * The name of this job.
                     *
                     * @return the name
                     */
                    @Override
                    public String getName() { return "TunnelCreatorConfig profile update"; }
                });
            }
        }
    }

    /**
     * The total verified bytes transferred on this tunnel.
     *
     * @return the verified bytes transferred
     */
    public synchronized long getVerifiedBytesTransferred() {return _verifiedBytesTransferred;}

    /**
     * When we last sent or received data on this tunnel
     * @return the last transferred
     */
    public synchronized long getLastTransferred() { return _lastTransferredTime; }

    /**
     *  When the tunnel last carried real (non-test) traffic, or 0 if never.
     *  Updated only at the real traffic delivery sites — inbound data arrival
     *  (InboundEndpointProcessor) and outbound message dispatch
     *  (OutboundClientMessageOneShotJob) — never by TestJob, so test traffic
     *  cannot pollute the proof that the tunnel works.
     *  @since 0.9.71+
     */
    private volatile long _lastRealTraffic;

    /**
     *  Record that the tunnel carried real traffic.
     *  @since 0.9.71+
     */
    public void recordRealTraffic() {_lastRealTraffic = System.currentTimeMillis();}

    /**
     *  When the tunnel last carried real traffic.
     *  @return the timestamp, or 0 if it never carried real traffic
     *  @since 0.9.71+
     */
    public long getLastRealTraffic() {return _lastRealTraffic;}

    /**
     * The tunnel failed a test, so (maybe) stop using it
     *
     * @return false if we stopped using it, true if still ok
     */
    public boolean tunnelFailed() {
        boolean rv = _failures.incrementAndGet() <= MAX_CONSECUTIVE_TEST_FAILURES;
        if (!rv) {_reused = true;} // don't allow it to be rebuilt
        return rv;
    }

    /**
     *  Increment the failure count without triggering pool removal or reuse flag.
     *  Used when a previously GOOD tunnel fails a retest — we want to track
     *  the failure for selection deprioritization but keep the tunnel alive
     *  for further testing and data delivery.
     *  @since 0.9.69+
     */
    public void incrementTestFailures() {
        _failures.incrementAndGet();
    }

    /**
     * The tunnel failed completely, so definitely stop using it
     *
     * @since 0.9.53
     */
    public void tunnelFailedCompletely() {
        _failures.addAndGet(MAX_CONSECUTIVE_TEST_FAILURES + 1);
        _reused = true; // don't allow it to be rebuilt
    }

    /**
     * Has the tunnel failed completely?
     *
     * @return the tunnel failed
     * @since 0.9.53
     */
    public boolean getTunnelFailed() {return _failures.get() > MAX_CONSECUTIVE_TEST_FAILURES;}

    /**
     * The consecutive failure count.
     *
     * @return the tunnel failures
     */
    public int getTunnelFailures() {return _failures.get();}

    /**
     *  Reset the consecutive failure counter and mark the tunnel as GOOD.
     *  Used when a data-carrying tunnel fails a test — the data proves it
     *  works, so the tunnel should remain selectable and avoid pruning.
     *  @since 0.9.69+
     */
    public void clearTestFailures() {
        _failures.set(0);
        _testStatus = TunnelTestStatus.GOOD;
    }

    /**
     * The number of recent-traffic test exemptions used.
     * @return the recent test exemptions
     * @since 0.9.69+
     */
    public int getRecentTestExemptions() {return _recentTestExemptions;}

    /**
     *  Increment the recent-traffic test exemption counter.
     *  @since 0.9.69+
     */
    public void incrementRecentTestExemptions() {_recentTestExemptions++;}

    /**
     * Mark the tunnel as GOOD, recording the latency of the successful test.
     */
    public void testSuccessful(int ms) {
        _failures.set(0);
        _recentTestExemptions = 0;
        _testStatus = TunnelTestStatus.GOOD;
        addLatencySample(ms);
    }

    /**
     *  Did we reuse this tunnel?
     *  @since 0.8.11
     */
    public boolean wasReused() {return _reused;}

    /**
     *  Note that we reused this tunnel
     *  @since 0.8.11
     */
    public void setReused() {_reused = true;}

    /**
     *  Outbound message priority - for outbound tunnels only
     *  @return -25 to +25, default 0
     *  @since 0.9.4
     */
    public int getPriority() {return _priority;}

    /**
     *  Outbound message priority - for outbound tunnels only
     *  @param priority -25 to +25, default 0
     *  @since 0.9.4
     */
    public void setPriority(int priority) {_priority = priority;}

    /**
     *  Key and IV to encrypt the reply sent for the tunnel creation crypto.
     *
     *  @throws IllegalArgumentException if iv not 16 bytes
     *  @since 0.9.48 moved from HopConfig
     */
    public void setAESReplyKeys(int hop, SessionKey key, byte[] iv) {
        if (iv.length != REPLY_IV_LENGTH) {throw new IllegalArgumentException();}
        _AESReplyKeys[hop] = key;
        _AESReplyIVs[hop] = iv;
    }

    /**
     *  Key to encrypt the reply sent for the tunnel creation crypto.
     *  Null for short build record.
     *
     *  @return key or null
     *  @throws IllegalArgumentException if iv not 16 bytes
     *  @since 0.9.48 moved from HopConfig
     */
    public SessionKey getAESReplyKey(int hop) {return _AESReplyKeys[hop];}

    /**
     *  IV used to encrypt the reply sent for the tunnel creation crypto.
     *  Null for short build record.
     *
     *  @return 16 bytes or null
     *  @since 0.9.48 moved from HopConfig
     */
    public byte[] getAESReplyIV(int hop) {return _AESReplyIVs[hop];}

    /**
     * Checksum for blank record
     * @return the blank hash
     * @since 0.9.48
     */
    public Hash getBlankHash() {return _blankHash;}

    /**
     *  Checksum for blank record
     *  @since 0.9.48
     */
    public void setBlankHash(Hash h) {_blankHash = h;}

    /**
     *  The latency of the last completed test.
     *  @param ms latency in milliseconds
     *  @since 0.9.68+
     */
    public void setLastLatency(int ms) {
        addLatencySample(ms);
    }

    /**
     * The last recorded test latency.
     *
     * @return latency in milliseconds, or -1 if not available
     * @since 0.9.68+
     */
    public int getLastLatency() {
        return _lastLatency;
    }

    /**
     * Add a latency sample from a test result.
     * @param ms latency in milliseconds
     * @since 0.9.69+
     */
    public synchronized void addLatencySample(int ms) {
        _latencyHistory[_latencyIdx] = ms;
        _latencyIdx = (_latencyIdx + 1) % LATENCY_SAMPLE_SIZE;
        if (_latencyCount < LATENCY_SAMPLE_SIZE) _latencyCount++;
        _lastLatency = ms;
    }

    /**
     * The average latency of the last 3 tests.
     * @return average latency in ms, or -1 if no tests yet
     * @since 0.9.69+
     */
    public int getAverageLatency() {
        int count = _latencyCount;
        if (count == 0) return -1;
        int samples = Math.min(count, LATENCY_SAMPLE_SIZE);
        int sum = 0;
        for (int i = 0; i < samples; i++) sum += _latencyHistory[i];
        return sum / samples;
    }

    /**
     * Whether at least 3 latency samples have been collected.
     *
     * @return true if we have at least 3 latency samples
     * @since 0.9.69+
     */
    public boolean hasEnoughLatencyTests() {
        return _latencyCount >= LATENCY_SAMPLE_SIZE;
    }

    /**
     * Whether the tunnel needs an expedited test due to slow detection.
     *
     * @return true if tunnel needs an expedited test due to slow detection
     * @since 0.9.69+
     */
    public boolean needsExpeditedTest() { return _needsExpeditedTest; }

    /**
     * Clear the expedited test flag after running the expedited test.
     * @since 0.9.69+
     */
    public void clearExpeditedTest() { _needsExpeditedTest = false; }

    /**
     * The tunnel appeared slow, so flag it for an expedited test.
     * @since 0.9.69+
     */
    public void requestExpeditedTest() { _needsExpeditedTest = true; }

    /**
     * The current test status of this tunnel for UI display.
     * @return the current test status (UNTESTED, TESTING, GOOD, FAILING, or FAILED)
     * @since 0.9.68+
     */
    public TunnelTestStatus getTestStatus() {
        return _testStatus;
    }

    /**
     * Called when a test is started.
     * @since 0.9.68+
     */
    public void setTestStarted() {
        _testStatus = TunnelTestStatus.TESTING;
    }

    /**
     * Called when a test fails.
     * Updates the test status based on consecutive failure count.
     * Only mark as FAILED after MAX_CONSECUTIVE_TEST_FAILURES (3) failures -
     * before that, it's just FAILING and still counts as a valid tunnel.
     * @since 0.9.68+
     */
    public void setTestFailed() {
        int failures = _failures.get();
        if (failures >= MAX_CONSECUTIVE_TEST_FAILURES) {
            _testStatus = TunnelTestStatus.FAILED;
        } else if (failures >= 1) {
            _testStatus = TunnelTestStatus.FAILING;
        } else {
            _testStatus = TunnelTestStatus.GOOD;
        }
    }

    /**
     * Mark tunnel as scheduled for early expiry (pruned from pool).
     * @since 0.9.69+
     */
    public void setTestTooSlow() {
        _testStatus = TunnelTestStatus.TOO_SLOW;
    }

    /**
     * Mark tunnel as scheduled for early expiry due to pool being over budget.
     * @since 0.9.69+
     */
    public void setTestOverBudget() {
        _testStatus = TunnelTestStatus.OVER_BUDGET;
    }

    /**
     * The number of consecutive test failures.
     * @return the count of consecutive failures
     * @since 0.9.68+
     */
    public int getConsecutiveFailures() {
        return _failures.get();
    }

    /**
     *  The ECIES reply key and associated data for the given hop.
     *  @since 0.9.48
     */
    public void setChaChaReplyKeys(int hop, SessionKey key, byte[] ad) {
        if (_ChaReplyKeys == null) {
            _ChaReplyKeys = new SessionKey[_config.length];
            _ChaReplyADs = new byte[_config.length][];
        }
        _ChaReplyKeys[hop] = key;
        _ChaReplyADs[hop] = ad;
    }

    /**
     * Is it an ECIES hop?
     * @return whether e c
     * @since 0.9.48
     */
    public boolean isEC(int hop) {
        if (_ChaReplyKeys == null) {return false;}
        return _ChaReplyKeys[hop] != null;
    }

    /**
     * The ECIES reply key for the given hop, or null.
     * @return the cha cha reply key
     * @since 0.9.48
     */
    public SessionKey getChaChaReplyKey(int hop) {
        if (_ChaReplyKeys == null) {return null;}
        return _ChaReplyKeys[hop];
    }

    /**
     * The ECIES reply associated data for the given hop, or null.
     * @return the cha cha reply a d
     * @since 0.9.48
     */
    public byte[] getChaChaReplyAD(int hop) {
        if (_ChaReplyADs == null) {return null;}
        return _ChaReplyADs[hop];
    }

    /**
     * ECIES short OBEP record only.
     * @since 0.9.51
     */
    public void setGarlicReplyKeys(OneTimeSession keys) {_garlicReplyKeys = keys;}

    /**
     * ECIES short OBEP record only.
     * @return null for ElGamal or ECIES long record or non-OBEP
     * @since 0.9.51
     */
    public OneTimeSession getGarlicReplyKeys() {return _garlicReplyKeys;}

    /**
     * Human-readable description of the tunnel, its peers, and its state.
     */
    @Override
    public String toString() {
        // H0:1235 -> H1:2345 -> H2:2345
        if (_log == null) {_log = _context.logManager().getLog(TunnelCreatorConfig.class);}
        StringBuilder buf = new StringBuilder(128);
        if (_isInbound) {buf.append("Inbound");}
        else {buf.append("Outbound");}
        if (_destination == null) {buf.append(" Exploratory tunnel");}
        else {
            buf.append(" Client tunnel [");
            if (_destinationNickname != null) {
                buf.append(_destinationNickname).append(" / ");
            }
            buf.append(_destination.toBase32().substring(0, 8)).append("]");
        }
        int fails = _failures.get();
        if (fails > 1) {buf.append(" (").append(fails).append(" consecutive failures)");}
        if (_log.shouldInfo()) {
            buf.append("\n* Gateway: ");
            for (int i = 0; i < _peers.length; i++) {
                buf.append("[" + _peers[i].toBase64().substring(0,6) + "]");
                buf.append(isEC(i) ? " EC:" : " ElG:");
                long id = _config[i].getReceiveTunnelId();
                if (id != 0) {
                    // don't show for "me" at OBGW or IBEP
                    if (!_isInbound || i != _peers.length - 1) {buf.append(isEC(i) ? " EC:" : " ElG:");}
                    else {buf.append(' ');}
                    buf.append(id);
                } else {buf.append(" local");}
                id = _config[i].getSendTunnelId();
                if (id != 0) {buf.append('.').append(id);}
                else if (_isInbound || i == 0) {buf.append(".local");}
                if (i + 1 < _peers.length) {buf.append(" -> ");}
            }
            if (_lastTransferredTime > 0)
                buf.append("\n* Last traffic: ").append(DataHelper.formatTime(_lastTransferredTime));
            buf.append("\n* Expires: ").append(DataHelper.formatTime(_expiration));
            if (_replyMessageId > 0) {buf.append("; [ReplyMsgID ").append(_replyMessageId).append("]");}
            int msgs = _messagesProcessed.get();
            if (msgs > 0) {
                buf.append(" with ").append(msgs).append(" messages (")
                   .append(_verifiedBytesTransferred).append(" bytes)");
            }
        }
        return buf.toString();
    }

    /**
     * @since 0.9.51
     */
    public String toStringFull() {
        StringBuilder buf = new StringBuilder(1024);
        buf.append(toString());
        for (int i = 0; i < _peers.length; i++) {
            if (i == 0) {buf.append("\n* Gateway ");}
            else if (i == _peers.length - 1) {buf.append("\n* Endpoint ");}
            else {buf.append("\n* Hop ").append(i);}
            buf.append(": ").append(_config[i]);
        }
        if (_garlicReplyKeys != null) {
            buf.append("\n* Garlic Reply Key: ").append(_garlicReplyKeys.key).append("\n* Tag: ").append(_garlicReplyKeys.rtag);
        }
        return buf.toString();
    }

}
