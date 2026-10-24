package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

/**
 * Track the state of a peer test.
 * Used only by {@link PeerTestManager}.
 */
class PeerTestState {
    /** Nonce identifying this peer test. */
    private final long _testNonce;
    /** Our role in the test. */
    private final Role _ourRole;
    /** True for an IPv6 test. */
    private final boolean _isIPv6;
    /** Alice's peer state, when we are Bob. */
    private PeerState2 _alice;
    /** Alice's IP as seen by Bob or Charlie. */
    private InetAddress _aliceIP;
    /** Alice's port as seen by Bob or Charlie. */
    private int _alicePort;
    /** Bob's peer state, when we are Alice or Charlie. */
    private final PeerState _bob;
    /** Charlie's IP as seen by us. */
    private InetAddress _charlieIP;
    /** Charlie's port as seen by us. */
    private int _charliePort;
    /** Alice's IP as Charlie reported it. */
    private InetAddress _aliceIPFromCharlie;
    /** Alice's port as Charlie reported it. */
    private int _alicePortFromCharlie;
    /** Alice's introduction key. */
    private SessionKey _aliceIntroKey;
    /** Alice's cipher key for the test. */
    private SessionKey _aliceCipherKey;
    /** Alice's MAC key for the test. */
    private SessionKey _aliceMACKey;
    /** Charlie's introduction key. */
    private SessionKey _charlieIntroKey;
    /** Alice's router hash, for SSU2 tests. */
    private Hash _aliceHash;
    /** Charlie's router hash, for SSU2 tests. */
    private Hash _charlieHash;
    /** Test data for an SSU2 Bob. */
    private byte[] _testData;
    /** Previous Charlies to avoid reusing, for SSU2 Bob. */
    private final List<Hash> _previousCharlies;
    /** When the test began. */
    private final long _beginTime;
    /** When we last sent a test packet. */
    private long _lastSendTime;
    /** When we last received from Alice. */
    private long _receiveAliceTime;
    /** When we last received from Bob. */
    private long _receiveBobTime;
    /** When we last received from Charlie. */
    private long _receiveCharlieTime;
    /** When we last sent to Alice. */
    private long _sendAliceTime;
    /** When we last sent to Charlie. */
    private long _sendCharlieTime;
    /** Current status of the test. */
    private int _status;
    /** Test packets relayed so far. */
    private final AtomicInteger _packetsRelayed = new AtomicInteger();

    /**
     * Roles for peer testing scenarios.
     * Defines the participant's role in connectivity testing.
     */
    public enum Role {
        /** Initiator peer hash. */
        ALICE,
        /** Relay peer hash. */
        BOB,
        /** Helper peer hash. */
        CHARLIE
    }

    /**
     * Create a new peer test state for the given role.
     *
     * @param role our role in the peer test
     * @param bob Bob's PeerState, or null if we are Bob
     * @param isIPv6 whether this is an IPv6 test
     * @param nonce the test nonce identifier
     * @param now the time when the test begins
     */
    public PeerTestState(Role role, PeerState bob, boolean isIPv6, long nonce, long now) {
        _ourRole = role;
        _bob = bob;
        _isIPv6 = isIPv6;
        _testNonce = nonce;
        _beginTime = now;
        _previousCharlies = role == Role.BOB ? new ArrayList<>(8) : null;
    }

    /**
     * Return the test nonce identifier.
     * @return the nonce identifying this test, as supplied to the constructor
     */
    public long getNonce() { return _testNonce; }

    /**
     * Return our role in the peer test.
     * @return Alice, Bob, or Charlie
     */
    public Role getOurRole() { return _ourRole; }

    /**
     * Return Bob's PeerState.
     * @return Bob's PeerState, or null if we are Bob
     * @since 0.9.54
     */
    public PeerState getBob() { return _bob; }

    /**
     * Whether this is an IPv6 test.
     * @return true for IPv6, false for IPv4
     * @since 0.9.27
     */
    public boolean isIPv6() { return _isIPv6; }

    /**
     * If we are Alice, return the IP that Bob says we can be reached at.
     * The IP that Charlie reports is available via {@link #getAliceIPFromCharlie()}.
     * @return Alice's IP as reported by Bob
     */
    public InetAddress getAliceIP() { return _aliceIP; }
    /**
     * Return Alice's PeerState2 (SSU2 only).
     * @return Alice's SSU2 state, or null for SSU1
     * @since 0.9.54
     */
    public PeerState2 getAlice() { return _alice; }
    /**
     * Alice's PeerState2 (SSU2 only).
     * @param alice Alice's SSU2 state
     * @since 0.9.54
     */
    public void setAlice(PeerState2 alice) {
        _alice = alice;
    }
    /**
     * Alice's IP, port, and hash.
     * @param ip Alice's IP address
     * @param port Alice's port, as seen by the reporter
     * @param hash Alice's Hash (SSU2 only), null for SSU1
     * @since 0.9.54
     */
    public void setAlice(InetAddress ip, int port, Hash hash) {
        _aliceIP = ip;
        _alicePort = port;
        _aliceHash = hash;
    }
    /**
     * Return Bob's IP address.
     * @return Bob's remote IP
     */
    public InetAddress getBobIP() { return _bob.getRemoteIPAddress(); }
    /**
     * Return Charlie's IP address.
     * @return Charlie's IP, or null if not set
     */
    public InetAddress getCharlieIP() { return _charlieIP; }

    /**
     * Return Charlie's Hash (SSU2 only).
     * @return Charlie's Hash, or null for SSU1
     * @since 0.9.57
     */
    public Hash getCharlieHash() { return _charlieHash; }

    /**
     * Charlie's IP, port, and hash.
     * Saves the previous Charlie hash for tracking rotations.
     * @param ip Charlie's IP address
     * @param port Charlie's port, as seen by us
     * @param hash Charlie's Hash (SSU2 only), null for SSU1
     * @since 0.9.54
     */
    public void setCharlie(InetAddress ip, int port, Hash hash) {
        _charlieIP = ip;
        _charliePort = port;
        if (_charlieHash != null && _previousCharlies != null && !_charlieHash.equals(hash))
            _previousCharlies.add(_charlieHash);
        _charlieHash = hash;
    }

    /**
     * Return previous Charlie hashes (SSU2 only, Bob only).
     * Does not include the current Charlie.
     * @return list of previous Charlie hashes, or null if not Bob
     * @since 0.9.57
     */
    public List<Hash> getPreviousCharlies() { return _previousCharlies; }

    /**
     * Return Alice's IP as reported by Charlie.
     * @return Alice's IP from Charlie's perspective
     */
    public InetAddress getAliceIPFromCharlie() { return _aliceIPFromCharlie; }
    /**
     * Alice's IP as reported by Charlie.
     * @param ip Alice's IP from Charlie's perspective
     */
    public void setAliceIPFromCharlie(InetAddress ip) { _aliceIPFromCharlie = ip; }
    /**
     * If we are Alice, return the port that Bob says we can be reached at.
     * The port Charlie reports is available via {@link #getAlicePortFromCharlie()}.
     * @return Alice's port as reported by Bob
     */
    public int getAlicePort() { return _alicePort; }
    /**
     * Return Bob's port.
     * @return Bob's remote port
     */
    public int getBobPort() { return _bob.getRemotePort(); }
    /**
     * Return Charlie's port.
     * @return Charlie's port, or 0 if not set
     */
    public int getCharliePort() { return _charliePort; }
    /**
     * Charlie's port.
     * @param charliePort Charlie's port number
     */
    public void setCharliePort(int charliePort) { _charliePort = charliePort; }

    /**
     * Return Alice's port as reported by Charlie.
     * @return Alice's port from Charlie's perspective
     */
    public int getAlicePortFromCharlie() { return _alicePortFromCharlie; }
    /**
     * Alice's port as reported by Charlie.
     * @param alicePortFromCharlie Alice's port from Charlie's perspective
     */
    public void setAlicePortFromCharlie(int alicePortFromCharlie) { _alicePortFromCharlie = alicePortFromCharlie; }

    /**
     * Return Alice's intro key.
     * @return Alice's intro key, or null if not set
     */
    public SessionKey getAliceIntroKey() { return _aliceIntroKey; }
    /**
     * Alice's intro key.
     * @param key Alice's intro key
     */
    public void setAliceIntroKey(SessionKey key) { _aliceIntroKey = key; }

    /**
     * Return Alice's cipher key.
     * @return the cipher key, or null if not set
     * @since 0.9.52
     */
    public SessionKey getAliceCipherKey() { return _aliceCipherKey; }

    /**
     * Return Alice's MAC key.
     * @return the MAC key, or null if not set
     * @since 0.9.52
     */
    public SessionKey getAliceMACKey() { return _aliceMACKey; }

    /**
     * Alice's cipher and MAC keys.
     * @param ck cipher key
     * @param mk MAC key
     * @since 0.9.52
     */
    public void setAliceKeys(SessionKey ck, SessionKey mk) {
        _aliceCipherKey = ck;
        _aliceMACKey = mk;
    }

    /**
     * Return Charlie's intro key.
     * @return Charlie's intro key, or null if not set
     */
    public SessionKey getCharlieIntroKey() { return _charlieIntroKey; }
    /**
     * Charlie's intro key.
     * @param key Charlie's intro key
     */
    public void setCharlieIntroKey(SessionKey key) { _charlieIntroKey = key; }

    /**
     * Return the time when this test began.
     * @return the begin timestamp
     */
    public long getBeginTime() { return _beginTime; }

    /**
     * Return the time we last sent a packet.
     * @return the last send timestamp
     */
    public long getLastSendTime() { return _lastSendTime; }
    /**
     * Time we last sent a packet.
     * @param when the last send timestamp
     */
    public void setLastSendTime(long when) { _lastSendTime = when; }

    /**
     * Return the time we last received from Alice.
     * @return the receive timestamp
     */
    public long getReceiveAliceTime() { return _receiveAliceTime; }
    /**
     * Time we last received from Alice.
     * @param when the receive timestamp
     */
    public void setReceiveAliceTime(long when) { _receiveAliceTime = when; }

    /**
     * Return the time we last received from Bob.
     * @return the receive timestamp
     */
    public long getReceiveBobTime() { return _receiveBobTime; }
    /**
     * Time we last received from Bob.
     * @param when the receive timestamp
     */
    public void setReceiveBobTime(long when) { _receiveBobTime = when; }

    /**
     * Return the time we last received from Charlie.
     * @return the receive timestamp
     */
    public long getReceiveCharlieTime() { return _receiveCharlieTime; }
    /**
     * Time we last received from Charlie.
     * @param when the receive timestamp
     */
    public void setReceiveCharlieTime(long when) { _receiveCharlieTime = when; }

    /**
     * Return when we last sent to Alice (SSU2 Bob only).
     * @return the send timestamp
     * @since 0.9.57
     */
    public long getSendAliceTime() { return _sendAliceTime; }

    /**
     * When we last sent to Alice (SSU2 Bob only).
     * @param when the send timestamp
     * @since 0.9.57
     */
    public void setSendAliceTime(long when) { _sendAliceTime = when; }

    /**
     * Return when we last sent to Charlie (SSU2 Alice only).
     * @return the send timestamp
     * @since 0.9.57
     */
    public long getSendCharlieTime() { return _sendCharlieTime; }

    /**
     * When we last sent to Charlie (SSU2 Alice only).
     * @param when the send timestamp
     * @since 0.9.57
     */
    public void setSendCharlieTime(long when) { _sendCharlieTime = when; }

    /**
     * Return the status code sent to Alice (SSU2 Bob only).
     * @return the status code
     * @since 0.9.57
     */
    public int getStatus() { return _status; }

    /**
     * Status code sent to Alice (SSU2 Bob only).
     * @param status the SSU2 status code to report to Alice
     * @since 0.9.57
     */
    public void setStatus(int status) { _status = status; }

    /**
     * Test data for retransmission.
     * SSU2 only, used when we are Alice, Bob, or Charlie.
     * @return the test data, or null if not set
     * @since 0.9.57
     */
    public byte[] getTestData() { return _testData; }

    /**
     * Save the test data for retransmission.
     * SSU2 only, used when we are Alice, Bob, or Charlie.
     * @param data the test data
     * @since 0.9.57
     */
    public void setTestData(byte[] data) { _testData = data; }

    /**
     * Increment and return the packets relayed count.
     * @return the incremented count
     */
    public int incrementPacketsRelayed() { return _packetsRelayed.incrementAndGet(); }

    /**
     * String representation of this test state.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("\n* PeerTest ").append(_testNonce)
           .append(_isIPv6 ? " [IPv6]" : " [IPv4]")
           .append(" started ").append(DataHelper.formatTime(_beginTime))
           .append(" as ").append(_ourRole.toString());
        if (_aliceIP != null) {
            buf.append(" [Alice: ");
            if (_ourRole == Role.ALICE)
                buf.append(" LOCAL: ");
            buf.append(_aliceIP).append(':').append(_alicePort).append("]");
            if (_aliceHash != null)
                buf.append(' ').append('[').append(_aliceHash.toBase64().substring(0, 6)).append(']');
        }
        if (_aliceIPFromCharlie != null)
            buf.append(" [from Charlie: ").append(_aliceIPFromCharlie).append(':').append(_alicePortFromCharlie).append("]");
        if (_bob != null)
            buf.append(" [Bob: ").append(_bob.toString()).append("]");
        else
            buf.append(" [Bob: LOCAL]");
        if (_charlieIP != null) {
            buf.append(" [Charlie: ");
            if (_ourRole == Role.CHARLIE)
                buf.append("LOCAL]");
            else
                buf.append(_charlieIP).append(':').append(_charliePort).append("]");
            if (_charlieHash != null)
                buf.append(' ').append('[').append(_charlieHash.toBase64().substring(0, 6)).append(']');
            if (_previousCharlies != null && !_previousCharlies.isEmpty())
                buf.append(" previous: ").append(_previousCharlies);
        }
        if (_lastSendTime > 0)
            buf.append("\n* Last send after ").append(_lastSendTime - _beginTime);
        if (_sendAliceTime > 0)
            buf.append("; Last send to Alice ").append(DataHelper.formatTime(_sendAliceTime));
        if (_receiveAliceTime > 0)
            buf.append("; Received from Alice after ").append(_receiveAliceTime - _beginTime);
        if (_receiveBobTime > 0)
            buf.append("; Received from Bob after ").append(_receiveBobTime - _beginTime);
        if (_sendCharlieTime > 0)
            buf.append("; Last send to Charlie ").append(DataHelper.formatTime(_sendCharlieTime));
        if (_receiveCharlieTime > 0)
            buf.append("; Received from Charlie after ").append(_receiveCharlieTime - _beginTime);
        buf.append("; Packets relayed: ").append(_packetsRelayed.get());
        return buf.toString();
    }
}
