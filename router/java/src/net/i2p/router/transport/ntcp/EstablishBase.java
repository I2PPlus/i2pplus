package net.i2p.router.transport.ntcp;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 * Base class for NTCP connection establishment.
 * Provides common functionality for NTCP 2 handshake state machines,
 * including cryptographic operations, timing calculations, and
 * peer skew management. Inbound NTCP 2 only - OutboundNTCP2State does not extend this.
 *
 * @since 0.9.35 pulled out of EstablishState
 */
abstract class EstablishBase implements EstablishState {

    /**
     * Shared instance marking a handshake that completed and verified successfully.
     */
    public static final VerifiedEstablishState VERIFIED = new VerifiedEstablishState();
    /**
     * Shared instance marking a handshake that ended in corruption.
     */
    public static final FailedEstablishState FAILED = new FailedEstablishState();

    /**
     * Router context, supplied to the subclass that constructs this.
     */
    protected final RouterContext _context;
    /**
     * Log scoped to the subclass that constructs this.
     */
    protected final Log _log;

    /**
     * Key block Bob receives and Alice sends, doubled as padding on inbound handshakes.
     */
    protected final byte[] _X;
    /**
     * Key block Alice receives and Bob sends, null on outbound handshakes.
     */
    protected final byte[] _Y;
    /**
     * OUR clock minus HIS clock, in seconds.
     * Inbound: tsB - tsA - rtt/2, Outbound: tsA - tsB - rtt/2.
     */
    protected long _peerSkew;

    /** Previously received encrypted block (or the IV). */
    protected byte[] _prevEncrypted;

    /** Bytes received so far. */
    protected int _received;

    /**
     * NTCP transport running this handshake.
     */
    protected final NTCPTransport _transport;
    /**
     * Connection being established by this handshake.
     */
    protected final NTCPConnection _con;

    /**
     * AES block and key size in bytes.
     */
    protected static final int AES_SIZE = 16;

    /**
     * Monitor serialising every handshake state change.
     */
    protected final Object _stateLock = new Object();
    /**
     * Current handshake state, replaced under the state lock.
     */
    protected volatile State _state;
    /** Set when the handshake has failed for good. */
    private final AtomicBoolean _isCorrupt = new AtomicBoolean();
    /** Set when the handshake has finished. */
    private final AtomicBoolean _isComplete = new AtomicBoolean();
    /**
     * Why the handshake failed, or null while it is still viable.
     */
    protected String _failReason;

    /**
     * States for NTCP connection establishment process.
     * Tracks the progression through various phases of the handshake.
     */
    protected enum State {
        /** Outbound handshake, waiting for the responder's key. */
        OB_INIT,
        /** Inbound handshake, waiting for the initiator's key. */
        IB_INIT,

        /**
         * Next state IB_NTCP2_GOT_X.
         * @since 0.9.36
         */
        IB_NTCP2_INIT,
        /**
         * Got X part of msg 1
         * Next state IB_NTCP2_GOT_MSG1 or IB_NTCP2_READ_RANDOM on fail.
         * @since 0.9.36
         */
        IB_NTCP2_GOT_X,
        /**
         * Got Noise part of msg 1
         * Next state IB_NTCP2_GOT_PADDING or IB_NTCP2_READ_RANDOM on fail.
         * @since 0.9.36
         */
        IB_NTCP2_GOT_MSG1,
        /**
         * Got msg 1 incl. padding
         * Next state IB_NTCP2_SENT_Y.
         * @since 0.9.36
         */
        IB_NTCP2_GOT_PADDING,
        /**
         * Sent msg 2 and padding
         * Next state IB_NTCP2_GOT_RI.
         * @since 0.9.36
         */
        IB_NTCP2_SENT_Y,
        /**
         * Got msg 3
         * Next state VERIFIED.
         * @since 0.9.36
         */
        IB_NTCP2_GOT_RI,
        /**
         * Got msg 1 and failed AEAD
         * Next state CORRUPT.
         * @since 0.9.36
         */
        IB_NTCP2_READ_RANDOM,

        /** OB: got and verified 4; IB: got and verified 3 and sent 4. */
        VERIFIED,
        /** Handshake data failed verification. */
        CORRUPT
    }

    /**
     * The two terminal states, VERIFIED and CORRUPT.
     */
    protected static final Set<State> STATES_DONE = EnumSet.of(State.VERIFIED, State.CORRUPT);

    /** Creates the instance used only for its constants, with no context. */
    private EstablishBase() {
        _context = null;
        _log = null;
        _X = null;
        _Y = null;
        _transport = null;
        _con = null;
    }

    /**
     * Binds the context, transport and connection, sizing the padding buffer.
     * @param ctx the router context, supplying the log manager
     * @param transport the NTCP transport this handshake runs under
     * @param con the connection being established, whose inbound flag sizes the padding buffer
     */
    protected EstablishBase(RouterContext ctx, NTCPTransport transport, NTCPConnection con) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _transport = transport;
        _con = con;
        if (_con.isInbound()) {
            // _X holds message 1, then gets reused from offset 0 for its
            // padding, so it only has to be as large as the larger of the two,
            // not the sum. Sized from our configured intro version, which is
            // the version an inbound peer must use for its PQ frame; PQ off is
            // a version 2 session, and 880 bytes of padding headroom is the
            // same buffer as before the PQ work.
            int v = NTCPTransport.PQ_INT_VERSION < 3 ? 2 : NTCPTransport.PQ_INT_VERSION;
            _X = SimpleByteCache.acquire(OutboundNTCP2State.msg1BufSize(v));
            _Y = null;
        } else {
            // OutboundNTCP2State does not extend this,
                throw new IllegalStateException();
        }
    }

    /**
     * Change the handshake state and update completion/corruption flags.
     * @param state the new state
     */
protected void changeState(State state) {
          synchronized (_stateLock) {
              _state = state;
              _isCorrupt.set(state == State.CORRUPT);
              _isComplete.set(state == State.VERIFIED);
          }
          // Single point at which a handshake becomes usable, and therefore the
          // single place the transport's latency is captured. Both NTCP2
          // establishment directions reach VERIFIED through here, so recording
          // the interval from connection creation - the same interval both
          // paths already measure for clock skew - gives one sample per
          // session without either direction having to remember to report.
          // Guarded against a null connection (the established-from-construction
          // state) and against a zero-length interval.
          if (state == State.VERIFIED && _con != null) {
              long measuredAt = _context.clock().now();
              int rtt = (int) Math.min(Integer.MAX_VALUE, measuredAt - _con.getCreated());
              _con.noteEstablishmentRtt(rtt);
              // Hand the sample straight to the peer profile as well. This is the
              // one moment a NTCP2 link cost is known, and NTCP2 has no later
              // measurement to refresh it, so waiting for a periodic sampling pass
              // means the profile holds nothing until that pass happens to reach
              // this peer - and even then only stamped with when the pass ran
              // rather than when the handshake did. Recorded here the sample
              // carries its own age and the profile can let it expire honestly.
              //
              // Recorded through the Organizer rather than the profile so the
              // calibrated bounds are consulted at one point, and so a transport
              // is not reaching into peer-management state directly.
              Hash peer = _con.getRemotePeer().calculateHash();
              if (!peer.equals(_context.routerHash())) {
                  _context.profileOrganizer().noteFirstHopRtt(peer, rtt, measuredAt);
              }
          }
      }

    /**
     * Parse the contents of the buffer as part of the handshake.
     * <p>
     * All data must be copied out of the buffer as {@link net.i2p.router.transport.ntcp.Reader#processRead}
     * will return it to the pool.
     * <p>
     * If there are additional data in the buffer after the handshake is complete,
     * the EstablishState is responsible for passing it to NTCPConnection.
     */
    public synchronized void receive(ByteBuffer src) {
        synchronized(_stateLock) {
            if (STATES_DONE.contains(_state))
                throw new IllegalStateException(prefix() + " received unexpected data on " + _con);
        }
        if (_log.shouldDebug())
            _log.debug(prefix() + "\n* Receiving: " + src.remaining() + " Received: " + _received);
    }

    /**
     * Does nothing. Outbound (Alice) must override.
     * We are establishing an outbound connection, so prepare ourselves by
     * queueing up the write of the first part of the handshake.
     */
    public void prepareOutbound() { /* no-op */ }

    /** Did the handshake fail for some reason? */
    public boolean isCorrupt() {
        return _isCorrupt.get();
    }

    /**
     * If synchronized on this, fails with
     * deadlocks from all over via {@code CSFI.isEstablished()}.
     * Also {@code CSFI.getFramedAveragePeerClockSkew()}.
     *
     * @return is the handshake complete and valid?
     */
    public boolean isComplete() {
        return _isComplete.get();
    }

    /**
     * NTCP version.
     * @return 1, 2, or 0 if unknown
     * @since 0.9.35
     */
    public abstract int getVersion();

    /**
     * Release resources on timeout.
     * @param e may be null
     * @since 0.9.16
     */
    public synchronized void close(String reason, Exception e) {
        fail(reason, e);
    }

    /**
     * Fail the handshake. Caller must synch.
     * @param reason the text logged with the warning and returned by
     *               {@link #getFailReason}
     */
    protected void fail(String reason) { fail(reason, null); }

    /**
     * Fail the handshake with exception. Caller must synch.
     * @param reason the text logged with the warning and returned by
     *               {@link #getFailReason}
     * @param e the exception cause, may be null
     */
    protected void fail(String reason, Exception e) { fail(reason, e, false); }

    /**
     * Fail the handshake optionally suppressing skew stat. Caller must synch.
     * @param reason the text logged with the warning and returned by
     *               {@link #getFailReason}
     * @param e the exception cause, may be null
     * @param bySkew true to suppress the skew stat, as when we caused the skew ourselves
     */
    protected void fail(String reason, Exception e, boolean bySkew) {
        _failReason = reason;
        synchronized(_stateLock) {
            if (STATES_DONE.contains(_state))
                return;
            changeState(State.CORRUPT);
        }
        if (_log.shouldDebug()) {
            _log.debug("[NTCP] Handshake failure " + _con + " -> " + reason);
        }
        if (!bySkew)
            _context.statManager().addRateData("ntcp.receiveCorruptEstablishment", 1);
        releaseBufs(false);
    }

    /**
     * Failure reason if the handshake failed, or null if not failed/corrupt.
     * @return failure reason string or null
     */
    public String getFailReason() {
        if (_failReason != null) { return _failReason; }
        if (_isCorrupt.get()) {
            return "State: " + _state;
        }
        // If we're closing but not corrupt, still return state info
        if (_state != State.VERIFIED) {
            return "State: " + _state;
        }
        return null;
    }

    /**
     * Only call once. Caller must synch.
     * @param isVerified true when the handshake completed and was not marked corrupt
     * @since 0.9.16
     */
    protected void releaseBufs(boolean isVerified) {
        // null or longer for OB
        if (_prevEncrypted != null && _prevEncrypted.length == AES_SIZE)
            SimpleByteCache.release(_prevEncrypted);
    }

    /**
     * Logging prefix string.
     * @return string representation of this state
     */
    protected String prefix() { return toString(); }

    /**
     * String representation of this state.
     */
    @Override
    public String toString() {
        // The connection's own form ends with a space when the remote peer is not
        // yet known, which used to render as "ip:port  (CORRUPT)".
        return _con.toString().trim() + " (" + _state + ')';
    }

    /**
     * Establish state that passed signature verification.
     *
     * @since 0.9.8
     */
    private static class VerifiedEstablishState extends EstablishBase {

        /**
         * Starts in the verified state, for a handshake that already succeeded.
         */
        public VerifiedEstablishState() {
            super();
            changeState(State.VERIFIED);
        }

        /**
         * The NTCP 2 version.
         * @return 1, the NTCP 2 protocol version number
         */
        public int getVersion() { return 1; }

        /**
         * Reject any data received after the handshake verified.
         *
         * @throws IllegalStateException always; the state is already verified
         */
        @Override
        public void receive(ByteBuffer src) {
            throw new IllegalStateException("receive() " + src.remaining() + " on verified state, doing nothing!");
        }

        /**
         * Reject handshake preparation after verification.
         *
         * @throws IllegalStateException always; the state is already verified
         */
        @Override
        public void prepareOutbound() {
            throw new IllegalStateException("prepareOutbound() on verified state, doing nothing!");
        }

        /**
         * String representation of the verified state.
         */
        @Override
        public String toString() { return "VerifiedEstablishState: ";}
    }

    /**
     * Establish state that failed, to retry later.
     *
     * @since 0.9.16
     */
    private static class FailedEstablishState extends EstablishBase {

        /**
         * Starts in the corrupt state, for a handshake that already failed.
         */
        public FailedEstablishState() {
            super();
            changeState(State.CORRUPT);
        }

        /**
         * The NTCP 2 version.
         * @return 1, the NTCP 2 protocol version number
         */
        public int getVersion() { return 1; }

        /**
         * Reject any data received after the handshake failed.
         *
         * @throws IllegalStateException always; the state is already corrupt
         */
        @Override
        public void receive(ByteBuffer src) {
            throw new IllegalStateException("receive() " + src.remaining() + " on failed state, doing nothing!");
        }

        /**
         * Reject handshake preparation after failure.
         *
         * @throws IllegalStateException always; the state is already corrupt
         */
        @Override
        public void prepareOutbound() {
            throw new IllegalStateException("prepareOutbound() on failed state, doing nothing!");
        }

        /**
         * String representation of the failed state.
         */
        @Override
        public String toString() { return "FailedEstablishState: ";}
    }

    /**
     * Mark a string for extraction by xgettext and translation.
     * Use this only in static initializers.
     * It does not translate!
     * @param s the string to mark for translation
     * @return s, unchanged; the marker works by naming the literal
     */
    /**
     * The bare cause from a netdb store rejection, stripped of the
     * "Invalid NetDbStore attempt" wrapper, capability flags and router ID.
     *
     * @param iae the rejection from the netdb
     * @return the cause, for log output
     * @since 0.9.71+
     */
    static String storeFailCause(IllegalArgumentException iae) {
        String reason = iae.getMessage();
        if (reason == null)
            return "Unknown";
        String prefix = "Invalid NetDbStore attempt - ";
        if (reason.startsWith(prefix))
            reason = reason.substring(prefix.length());
        int arrow = reason.indexOf(" -> ");
        if (arrow > 0)
            reason = reason.substring(arrow + 4);
        return reason;
    }

    /**
     * Marker function for i18n, returns the string unchanged.
     *
     * @param s the string to mark for translation
     * @return the same string passed in
     */
    protected static final String _x(String s) { // NOSONAR S1845 i18n marker convention
        return s;
    }

}
