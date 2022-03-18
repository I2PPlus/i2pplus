package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

/**
 *  Track the state of a peer test.
 *  Used only by PeerTestManager.
 */
class PeerTestState {
    private final long _testNonce;
    private final Role _ourRole;
    private final boolean _isIPv6;
    private PeerState2 _alice;
    private InetAddress _aliceIP;
    private int _alicePort;
    private final PeerState _bob;
    private InetAddress _charlieIP;
    private int _charliePort;
    private InetAddress _aliceIPFromCharlie;
    private int _alicePortFromCharlie;
    private SessionKey _aliceIntroKey;
    private SessionKey _aliceCipherKey;
    private SessionKey _aliceMACKey;
    private SessionKey _charlieIntroKey;
    // SSU2 only
    private Hash _aliceHash;
    // SSU2 only
    private Hash _charlieHash;
    private final long _beginTime;
    private long _lastSendTime;
    private long _receiveAliceTime;
    private long _receiveBobTime;
    private long _receiveCharlieTime;
    private final AtomicInteger _packetsRelayed = new AtomicInteger();

    public enum Role {ALICE, BOB, CHARLIE};

    /**
     * @param bob null if role is BOB
     */
    public PeerTestState(Role role, PeerState bob, boolean isIPv6, long nonce, long now) {
        _ourRole = role;
        _bob = bob;
        _isIPv6 = isIPv6;
        _testNonce = nonce;
        _beginTime = now;
    }

    public long getNonce() { return _testNonce; }

    /** Are we Alice, bob, or Charlie. */
    public Role getOurRole() { return _ourRole; }

    /**
     * @return null if we are bob
     * @since 0.9.54
     */
    public PeerState getBob() { return _bob; }

    /**
     * Is this an IPv6 test?
     * @since 0.9.27
     */
    public boolean isIPv6() { return _isIPv6; }

    /**
     * If we are Alice, this will contain the IP that Bob says we
     * can be reached at - the IP Charlie says we can be reached
     * at is _aliceIPFromCharlie
     *
     */
    public InetAddress getAliceIP() { return _aliceIP; }
    /**
     * SSU2 only
     * @since 0.9.54
     */
    public PeerState2 getAlice() { return _alice; }
    /**
     * SSU2 only
     * @since 0.9.54
     */
    public void setAlice(PeerState2 alice) {
        _alice = alice;
    }
    /**
     * @param hash SSU2 only, null for SSU1
     * @since 0.9.54
     */
    public void setAlice(InetAddress ip, int port, Hash hash) {
        _aliceIP = ip;
        _alicePort = port;
        _aliceHash = hash;
    }
    public InetAddress getBobIP() { return _bob.getRemoteIPAddress(); }
    public InetAddress getCharlieIP() { return _charlieIP; }
    /**
     * @param hash SSU2 only, null for SSU1
     * @since 0.9.54
     */
    public void setCharlie(InetAddress ip, int port, Hash hash) {
        _charlieIP = ip;
        _charliePort = port;
        _charlieHash = hash;
    }
    public InetAddress getAliceIPFromCharlie() { return _aliceIPFromCharlie; }
    public void setAliceIPFromCharlie(InetAddress ip) { _aliceIPFromCharlie = ip; }
    /**
     * If we are Alice, this will contain the port that Bob says we
     * can be reached at - the port Charlie says we can be reached
     * at is _alicePortFromCharlie
     *
     */
    public int getAlicePort() { return _alicePort; }
    public int getBobPort() { return _bob.getRemotePort(); }
    public int getCharliePort() { return _charliePort; }
    public void setCharliePort(int charliePort) { _charliePort = charliePort; }

    public int getAlicePortFromCharlie() { return _alicePortFromCharlie; }
    public void setAlicePortFromCharlie(int alicePortFromCharlie) { _alicePortFromCharlie = alicePortFromCharlie; }

    public SessionKey getAliceIntroKey() { return _aliceIntroKey; }
    public void setAliceIntroKey(SessionKey key) { _aliceIntroKey = key; }

    /**
     *  @since 0.9.52
     */
    public SessionKey getAliceCipherKey() { return _aliceCipherKey; }

    /**
     *  @since 0.9.52
     */
    public SessionKey getAliceMACKey() { return _aliceMACKey; }

    /**
     *  @param ck cipher key
     *  @param mk MAC key
     *  @since 0.9.52
     */
    public void setAliceKeys(SessionKey ck, SessionKey mk) {
        _aliceCipherKey = ck;
        _aliceMACKey = mk;
    }

    public SessionKey getCharlieIntroKey() { return _charlieIntroKey; }
    public void setCharlieIntroKey(SessionKey key) { _charlieIntroKey = key; }

    public SessionKey getBobCipherKey() { return _bob.getCurrentCipherKey(); }
    public SessionKey getBobMACKey() { return _bob.getCurrentMACKey(); }

    /** when did this test begin? */
    public long getBeginTime() { return _beginTime; }

    /** when did we last send out a packet? */
    public long getLastSendTime() { return _lastSendTime; }
    public void setLastSendTime(long when) { _lastSendTime = when; }

    /**
     * when did we last hear from alice?
     */
    public long getReceiveAliceTime() { return _receiveAliceTime; }
    public void setReceiveAliceTime(long when) { _receiveAliceTime = when; }

    /** when did we last hear from bob? */
    public long getReceiveBobTime() { return _receiveBobTime; }
    public void setReceiveBobTime(long when) { _receiveBobTime = when; }

    /** when did we last hear from charlie? */
    public long getReceiveCharlieTime() { return _receiveCharlieTime; }
    public void setReceiveCharlieTime(long when) { _receiveCharlieTime = when; }

    /** @return new value */
    public int incrementPacketsRelayed() { return _packetsRelayed.incrementAndGet(); }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("PeerTest ").append(_testNonce)
           .append(_isIPv6 ? " [IPv6]" : " [IPv4]")
           .append(" started ").append(DataHelper.formatTime(_beginTime))
           .append(" as ").append(_ourRole.toString()).append("\n*");
        if (_aliceIP != null)
            buf.append(" [Alice: ").append(_aliceIP).append(':').append(_alicePort).append("]");
        if (_aliceIPFromCharlie != null)
            buf.append(" [from Charlie: ").append(_aliceIPFromCharlie).append(':').append(_alicePortFromCharlie).append("]");
        if (_bob != null)
            buf.append(" [Bob: ").append(_bob.toString()).append("]");
        if (_charlieIP != null)
            buf.append(" [Charlie: ").append(_charlieIP).append(':').append(_charliePort).append("]");
        if (_lastSendTime > 0)
            buf.append("\n* Last send after ").append(_lastSendTime - _beginTime).append("ms");
        if (_receiveAliceTime > 0)
            buf.append("; Rcvd from Alice after ").append(_receiveAliceTime - _beginTime).append("ms");
        if (_receiveBobTime > 0)
            buf.append("; Rcvd from Bob after ").append(_receiveBobTime - _beginTime).append("ms");
        if (_receiveCharlieTime > 0)
            buf.append("; Rcvd from Charlie after ").append(_receiveCharlieTime - _beginTime).append("ms");
        buf.append("; Packets relayed: ").append(_packetsRelayed.get());
        return buf.toString();
    }
}
