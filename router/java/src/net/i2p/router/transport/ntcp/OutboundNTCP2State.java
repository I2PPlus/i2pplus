package net.i2p.router.transport.ntcp;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;
import com.southernstorm.noise.protocol.NoiseInit;

import net.i2p.crypto.EncType;
import net.i2p.crypto.HKDF;
import net.i2p.crypto.KeyFactory;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.BanLogger;
import net.i2p.router.transport.ntcp.NTCP2Payload.Block;
import net.i2p.util.HexDump;
import net.i2p.util.Log;

/**
 *
 * NTCP 2 only. We are Alice.
 * <p>
 * Also contains static constants and methods used by {@link InboundEstablishState} for NTCP2.
 * Does not extend EstablishBase.
 *
 * @since 0.9.35
 */
class OutboundNTCP2State implements EstablishState {

    /** The router context. */
    private final RouterContext _context;
    /** Logger for the NTCP connection. */
    private final Log _log;
    /** Records why routers were banned. */
    private final BanLogger _banLogger;
    /** The NTCP transport we serve. */
    private final NTCPTransport _transport;
    /** The connection being established. */
    private final NTCPConnection _con;
    /** Scratch buffer for the handshake fields. */
    private final byte[] _tmp;
    /** Bytes received so far. */
    private int _received;
    /** Our clock skew to the peer, in ms. */
    private long _peerSkew;

    /**
     * Static key length in bytes (32).
     */
    public static final int KEY_SIZE = 32;
    /**
     * MAC length in bytes, appended to each encrypted block (16).
     */
    public static final int MAC_SIZE = 16;
    /**
     * IV length in bytes, taken from the tail of the static key (16).
     */
    public static final int IV_SIZE = 16;
    /** Options block size in bytes. */
    public static final int OPTIONS1_SIZE = 16;
    /** NTCP2 message 1 size in bytes (64). */
    public static final int MSG1_SIZE = KEY_SIZE + OPTIONS1_SIZE + MAC_SIZE;
    /**
     * The short handshake message total used by i2pd (287). It compares the message it
     * just built or received against this to decide which padding scale to use.
     */
    static final int MSG_SHORT_MAX = 287;
    /**
     * Exclusive upper bound for the "short" handshake padding, i2pd's
     * 287 - 64 (223). Used with a peer that does not advertise PQ support and
     * may predate the 2026 padding increase.
     */
    static final int PADDING_SHORT_MAX = MSG_SHORT_MAX - MSG1_SIZE;
    /** Largest padding length in message 3. */
    private static final int PADDING3_MAX = 64;

    /**
     * Extra bytes in message 1 for the PQ frame (PQ AEAD MAC + encapsulation
     * key), 0 for the non-PQ version 2. This is the Noise "F" token: the ML-KEM
     * public key written via encryptAndHash(), so it carries one MAC.
     * Mirrors i2pd's SessionRequest ML-KEM frame (keyLen + 16).
     *
     * @param version the negotiated NTCP2 intro version
     * @return the extra byte count, 0 for version 2
     */
    static int pqMsg1Extra(int version) {
        switch (version) {
            case 2:  return 0;
            case 3:  return MAC_SIZE + EncType.MLKEM512_X25519_INT.getPubkeyLen();
            case 4:  return MAC_SIZE + EncType.MLKEM768_X25519_INT.getPubkeyLen();
            case 5:  return MAC_SIZE + EncType.MLKEM1024_X25519_INT.getPubkeyLen();
            default: throw new IllegalArgumentException("Bad version " + version);
        }
    }

    /**
     * Full message 1 length (no padding) for the version: X (32) + options (16)
     * + options MAC (16) + PQ frame. For MLKEM-768 this is 1264, byte-identical
     * to i2pd's SessionRequest (X + encapKey+tag + options+tag).
     *
     * @param version the negotiated NTCP2 intro version
     * @return message 1 byte count excluding padding
     */
    static int msg1Size(int version) {
        return MSG1_SIZE + pqMsg1Extra(version);
    }

    /**
     * Upper bound (exclusive) for the message 1 padding we send, per version,
     * equal to i2pd's maxPaddingLength in CreateSessionRequestMessage: offset +
     * 32 for a PQ frame, clamped so the whole message fits i2pd's 2528-byte
     * handshake budget (944 + MLKEM-1024 key + 16), which is what pulls
     * MLKEM-1024 back down to 880. Version 2 takes i2pd's short bound: i2pd
     * only uses long padding once the peer advertises PQ support (its pq
     * address option), and we reach versions 3-5 for exactly those peers, so a
     * peer predating the 2026 padding increase (Java _X = 256, i2pd m_Buffer =
     * 287) never sees more than 222 bytes from us. Taking i2pd's value for
     * every version also keeps our handshake size distribution the same as
     * theirs.
     *
     * @param version the negotiated NTCP2 intro version
     * @return exclusive upper bound for padlen1 (longest padding = value - 1)
     */
    static int padding1Max(int version) {
        switch (version) {
            case 2:  return PADDING_SHORT_MAX;  // 223, peer may predate long padding
            case 3:  return 880;                // MLKEM-512
            case 4:  return 1264;               // MLKEM-768
            case 5:  return 880;                // MLKEM-1024, clamped by the 2528 budget
            default: throw new IllegalArgumentException("Bad version " + version);
        }
    }

    /**
     * Largest message 1 padding we accept, per session version. Never below
     * {@link #padding1Max(int)}: i2pd's Alice drops to a non-PQ session one
     * time in four (ClientLogin) but keeps long padding because the peer
     * advertises PQ, so a version 2 session may legally carry 880 bytes of
     * padding even though we would only ever send 223 there.
     *
     * @param version the negotiated NTCP2 intro version
     * @return inclusive maximum padlen1 a peer may announce
     */
    static int padding1RecvMax(int version) {
        switch (version) {
            case 2:  return 880;   // long padding from a PQ-capable peer that dropped PQ
            case 3:  return 880;
            case 4:  return 1264;
            case 5:  return 880;
            default: throw new IllegalArgumentException("Bad version " + version);
        }
    }

    /** Options block size in bytes. */
    public static final int OPTIONS2_SIZE = 16;
    /** NTCP2 message 2 size in bytes. */
    public static final int MSG2_SIZE = KEY_SIZE + OPTIONS2_SIZE + MAC_SIZE;

    /**
     * Extra bytes in message 2 for Bob's ML-KEM ciphertext and its MAC, 0 for
     * the non-PQ version 2. Same Noise "F" token as message 1, with the
     * ciphertext lengths instead of the encapsulation key lengths.
     *
     * @param version the negotiated NTCP2 intro version
     * @return the extra byte count, 0 for version 2
     */
    static int pqMsg2Extra(int version) {
        switch (version) {
            case 2:  return 0;
            case 3:  return MAC_SIZE + EncType.MLKEM512_X25519_CT.getPubkeyLen();
            case 4:  return MAC_SIZE + EncType.MLKEM768_X25519_CT.getPubkeyLen();
            case 5:  return MAC_SIZE + EncType.MLKEM1024_X25519_CT.getPubkeyLen();
            default: throw new IllegalArgumentException("Bad version " + version);
        }
    }

    /**
     * Full message 2 length (no padding) for the version: Y (32) + options (16)
     * + options MAC (16) + PQ ciphertext frame.
     *
     * @param version the negotiated NTCP2 intro version
     * @return message 2 byte count excluding padding
     */
    static int msg2Size(int version) {
        return MSG2_SIZE + pqMsg2Extra(version);
    }

    /**
     * Upper bound (exclusive) for the message 2 padding, in both directions,
     * equal to i2pd's maxPaddingLength in CreateSessionCreatedMessage (offset +
     * 32, clamped by the same 2528-byte budget). Bob applies it only when Alice
     * used long padding, otherwise {@link #PADDING_SHORT_MAX}.
     *
     * @param version the negotiated NTCP2 intro version
     * @return exclusive upper bound for padlen2 (longest padding = value - 1)
     */
    static int padding2Max(int version) {
        switch (version) {
            case 2:  return 880;   // non-PQ long padding
            case 3:  return 848;   // MLKEM-512 ciphertext frame
            case 4:  return 1168;  // MLKEM-768
            case 5:  return 880;   // MLKEM-1024, clamped by the 2528 budget
            default: throw new IllegalArgumentException("Bad version " + version);
        }
    }

    /**
     * The padding cap for message 2, scaled to what Alice sent. i2pd's Bob uses
     * long padding only when Alice's message 1 came in longer than the short
     * handshake - ProcessSessionRequestMessage sets m_IsLongPadding - so a peer
     * that kept its message 1 small gets a small answer back, and our reply
     * lands in the same size class as i2pd's Bob.
     *
     * @param version the negotiated NTCP2 intro version
     * @param padlen1 the padding Alice announced in message 1
     * @return exclusive upper bound for padlen2 (longest padding = value - 1)
     */
    static int msg2PaddingMax(int version, int padlen1) {
        return msg1Size(version) + padlen1 > MSG_SHORT_MAX
               ? padding2Max(version)
               : PADDING_SHORT_MAX;
    }

    /**
     * Offset of the plaintext options block within the message 1 buffer. For
     * versions 3-5 the Noise F token - the ML-KEM encapsulation key and its MAC -
     * is written between X and the options, so the options have to be staged
     * past it: writeMessage() lays down the F token first and only then reads
     * the payload, so staging them at KEY_SIZE makes it encrypt the key
     * material instead, and the peer gets a valid-looking tag over garbage
     * options. For version 2 this is KEY_SIZE, as it always was.
     *
     * @param version the negotiated NTCP2 intro version
     * @return offset of the options block within message 1
     */
    static int msg1PayloadOffset(int version) {
        return msg1Size(version) - (OPTIONS1_SIZE + MAC_SIZE);
    }

    /**
     * Bytes needed to hold message 1 or its padding, whichever is longer. The
     * inbound handshake buffer holds message 1 first, then is reused from
     * offset 0 for the padding, so the two do not add up.
     *
     * @param version the NTCP2 intro version to size for
     * @return buffer size in bytes
     */
    static int msg1BufSize(int version) {
        return Math.max(msg1Size(version), padding1RecvMax(version));
    }

    /**
     * Bytes needed by the outbound handshake buffer. Message 1 and its padding
     * are written back to back and sent as one block, so they add up; message 2
     * and its padding are each read from offset 0, so each only has to fit on
     * its own.
     *
     * @param version the negotiated NTCP2 intro version
     * @return buffer size in bytes
     */
    static int tmpSize(int version) {
        return Math.max(msg1Size(version) + padding1Max(version),
                        Math.max(msg2Size(version), padding2Max(version)));
    }

    /** NTCP2 message 3 part 1 size in bytes (48). */
    public static final int MSG3P1_SIZE = KEY_SIZE + MAC_SIZE;
    /** Size of the options field in message 3. */
    private static final int OPTIONS3_SIZE = 12;
    /** In SECONDS. */
    public static final long MAX_SKEW = 60;
    /** Shared empty array for zero-length values. */
    private static final byte[] ZEROLEN = new byte[0];
    /**
     * All-zero key marking a static key or IV as unset.
     */
    public static final byte[] ZEROKEY = new byte[KEY_SIZE];
    /** For SipHash keygen. */
    private static final byte[] SIPHASH = DataHelper.getASCII("siphash");

    /** Lock for the handshake state. */
    private final Object _stateLock = new Object();
    /** Current state of the handshake. */
    private State _state;
    /** Reason the handshake failed, null until then. */
    private String _failReason;

    /** Handshake state for the ratchet keys. */
    private final HandshakeState _handshakeState;
    /** Alice's router info. */
    private final RouterInfo _aliceRI;
    /** Size of Alice's router info, in bytes. */
    private final int _aliceRISize;
    /** Padding length of message 2. */
    private int _padlen2;
    /** Padding length of message 3. */
    private final int _padlen3;
    /** Hash of Bob's handshake. */
    private final SessionKey _bobHash;
    /** IV of Bob's handshake. */
    private final byte[] _bobIV;
    /** Version of the handshake. */
    private final int _version;

    /**
     * State of the outbound NTCP2 handshake.
     */
    private enum State {
        /** Nothing sent yet. */
        OB_INIT,
        /** Sent 1. */
        OB_SENT_X,
        /** Sent 1, got 2 but not padding. */
        OB_GOT_HXY,
        /** Sent 1, got 2 incl. padding. */
        OB_GOT_PADDING,
        /** Sent 1, got 2 incl. padding, sent 3. */
        VERIFIED,
        /** The handshake failed a check. */
        CORRUPT
    }

    /**
     * Create a new outbound NTCP2 state.
     * @param ctx supplies the log and the ban logger
     * @param transport supplies the XDH factory and the transport settings
     * @param con the connection being established; supplies the remote
     *            address, the protocol version, and the peer hash
     * @throws IllegalArgumentException on bad address in the connection
     */
    public OutboundNTCP2State(RouterContext ctx, NTCPTransport transport, NTCPConnection con) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _banLogger = new BanLogger();
        _banLogger.initialize(ctx);
        _transport = transport;
        _con = con;
        _state = State.OB_INIT;
        _version = con.getVersion();
        try {
            NoiseInit.PatternID pattern;
            KeyFactory hkf;
            switch (_version) {
                case 2:
                    pattern = NoiseInit.PatternID.XK;
                    _handshakeState = new HandshakeState(pattern, HandshakeState.INITIATOR, _transport.getXDHFactory());
                    break;
                case 3:
                    pattern = NoiseInit.PatternID.XKHFS_512;
                    hkf = ctx.eciesEngine().getHybridKeyFactory(EncType.MLKEM512_X25519);
                    _handshakeState = new HandshakeState(pattern, HandshakeState.INITIATOR, _transport.getXDHFactory(), hkf);
                    break;
                case 4:
                    pattern = NoiseInit.PatternID.XKHFS_768;
                    hkf = ctx.eciesEngine().getHybridKeyFactory(EncType.MLKEM768_X25519);
                    _handshakeState = new HandshakeState(pattern, HandshakeState.INITIATOR, _transport.getXDHFactory(), hkf);
                    break;
                case 5:
                    pattern = NoiseInit.PatternID.XKHFS_1024;
                    hkf = ctx.eciesEngine().getHybridKeyFactory(EncType.MLKEM1024_X25519);
                    _handshakeState = new HandshakeState(pattern, HandshakeState.INITIATOR, _transport.getXDHFactory(), hkf);
                    break;
                default:
                    throw new IllegalArgumentException("Bad version " + _version);
            }
        } catch (GeneralSecurityException gse) {
            throw new IllegalStateException("Bad protocol", gse);
        }
        // _tmp holds msg 1 and its padding for the write, then msg 2 and, from
        // offset 0 again, msg 2's padding for the reads.
        _tmp = new byte[tmpSize(_version)];
        // save because we must know length
        _aliceRI = ctx.router().getRouterInfo();
        if (_aliceRI == null)
            throw new IllegalStateException("No RouterInfo yet");
        _aliceRISize = _aliceRI.toByteArray().length;
        _padlen3 = _context.random().nextInt(PADDING3_MAX);

        Hash h = _con.getRemotePeer().calculateHash();
        _bobHash = new SessionKey(h.getData());
        String s = _con.getRemoteAddress().getOption("i");
        if (s == null)
            throw new IllegalArgumentException("No NTCP2 IV");
        _bobIV = Base64.decode(s);
        if (_bobIV == null || _bobIV.length != IV_SIZE ||
            DataHelper.eqCT(_bobIV, 0, ZEROKEY, 0, IV_SIZE))
            throw new IllegalArgumentException("Bad NTCP2 IV");
    }

    /**
     * Moves to the next handshake state.
     *
     * @param state the new state
     */
    private void changeState(State state) {
        synchronized (_stateLock) {
            _state = state;
        }
    }

    /**
     * Parse the contents of the buffer as part of the handshake.
     * <p>
     * All data must be copied out of the buffer as {@link net.i2p.router.transport.ntcp.Reader#processRead}
     * will return it to the pool.
     */
    @Override
    public synchronized void receive(ByteBuffer src) {
        if (_state == State.VERIFIED || _state == State.CORRUPT)
            throw new IllegalStateException(this + " received unexpected data on " + _con);
        if (_log.shouldDebug())
            _log.debug(this + "\n* Receiving: " + src.remaining() + " Received: " + _received);
        if (!src.hasRemaining())
            return; // nothing to receive
        receiveOutbound(src);
    }

    /** Did the handshake fail for some reason? */
    public boolean isCorrupt() {
        synchronized (_stateLock) {
            return _state == State.CORRUPT;
        }
    }

    /**
     * Don't synchronize this, deadlocks all over.
     *
     * @return is the handshake complete and valid?
     */
    public boolean isComplete() {
        synchronized (_stateLock) {
            return _state == State.VERIFIED;
        }
    }

    /**
     * NTCP version.
     * @return 2-5
     */
    public int getVersion() { return _version; }

    /**
     * Check if the connection is in initial state and ready for {@link #prepareOutbound()}.
     *
     * @return whether initial state
     * @since 0.9.59
     */
    public boolean isInitialState() {
        return _state == State.OB_INIT;
    }

    /**
     * Check if the connection is in a state where prepareOutbound() has already been called
     * to avoid duplicate calls that cause IllegalStateException.
     * @return whether prepare outbound called
     */
    public boolean isPrepareOutboundCalled() {
        return _state == State.OB_SENT_X || _state == State.OB_GOT_HXY ||
               _state == State.OB_GOT_PADDING || _state == State.VERIFIED;
    }

    /**
     * We are Alice.
     * We are establishing an outbound connection, so prepare ourselves by
     * writing the first message in the handshake.
     * Encrypt X and write X, the options block, and the padding.
     * Save last half of encrypted X as IV for message 2 AES.
     *
     * @throws IllegalStateException if the connection is not in the initial outbound state
     */
    public synchronized void prepareOutbound() {
        if (!(_state == State.OB_INIT)) {
            throw new IllegalStateException(this + " -> Unexpected prepareOutbound()");
        }
        if (_log.shouldDebug())
            _log.debug(this + "\n* Send X");
        // write options directly to tmp, at the end of the message so the PQ
        // token written by writeMessage() cannot clobber them, encrypt in-place
        // network ID cross-check, proposal 147
        int off = msg1PayloadOffset(_version);
        _tmp[off] = (byte) (_context.router().getNetworkID());
        _tmp[off + 1] = NTCPTransport.NTCP2_INT_VERSION;
        // Draw the padding from i2pd's per-version range so our handshake is the
        // same size distribution as theirs, and never larger than the peer's
        // handshake buffer.
        int padlen1 = _context.random().nextInt(padding1Max(_version));
        DataHelper.toLong(_tmp, off + 2, 2, padlen1);
        int msg3p2len = NTCP2Payload.BLOCK_HEADER_SIZE + 1 + _aliceRISize +
                        NTCP2Payload.BLOCK_HEADER_SIZE + OPTIONS3_SIZE +
                        NTCP2Payload.BLOCK_HEADER_SIZE + _padlen3 +
                        MAC_SIZE;
        DataHelper.toLong(_tmp, off + 4, 2, msg3p2len);
        _tmp[off + 6] = 0;
        _tmp[off + 7] = 0;
        long now = (_context.clock().now() + 500) / 1000;
        DataHelper.toLong(_tmp, off + 8, 4, now);
        Arrays.fill(_tmp, off + 12, off + 16, (byte) 0);

        // set keys
        String s = _con.getRemoteAddress().getOption("s");
        if (s == null) {
            fail("No NTCP2 S");
            return;
        }
        byte[] bk = Base64.decode(s);
        if (bk == null || bk.length != KEY_SIZE ||
            (bk[KEY_SIZE - 1] & 0x80) != 0 ||
            DataHelper.eqCT(bk, 0, ZEROKEY, 0, KEY_SIZE)) {
            fail("Bad NTCP2 S: " + s);
            return;
        }
        _handshakeState.getRemotePublicKey().setPublicKey(bk, 0);
        _handshakeState.getLocalKeyPair().setKeys(_transport.getNTCP2StaticPrivkey(), 0,
                                                  _transport.getNTCP2StaticPubkey(), 0);
        // output to _tmp
        int written;
        try {
            _handshakeState.start();
            if (_log.shouldDebug())
                _log.debug("After start: " + _handshakeState.toString());
            // encrypt in-place; for PQ versions this also inserts the hybrid
            // (ML-KEM) public key token between X and the options, so the
            // message is longer than MSG1_SIZE and the payload has to come from
            // past that token - see msg1PayloadOffset()
            written = _handshakeState.writeMessage(_tmp, 0, _tmp, off, OPTIONS1_SIZE);
        } catch (GeneralSecurityException gse) {
            boolean gseNotNull = gse.getMessage() != null && !gse.getMessage().equals("null");
            // buffer length error
            fail("Bad message #1 out" + (gseNotNull ? " * GeneralSecurityException: " + gse.getMessage() : ""), gse);
            return;
        } catch (RuntimeException re) {
            fail("Bad message #1 out * RuntimeException: " + re.getMessage(), re);
            return;
        }
        if (_log.shouldDebug())
            _log.debug("After message #1: " + _handshakeState.toString());
        // PQ marker
        if (_version != 2)
            _tmp[KEY_SIZE - 1] |= (byte) 0x80;

        // encrypt key before writing
        _context.aes().encrypt(_tmp, 0, _tmp, 0, _bobHash, _bobIV, KEY_SIZE);
        // overwrite _bobIV with last 16 encrypted bytes, CBC for message 2
        System.arraycopy(_tmp, KEY_SIZE - IV_SIZE, _bobIV, 0, IV_SIZE);
        // add padding, after the hybrid key token for PQ versions
        if (padlen1 > 0) {
            _context.random().nextBytes(_tmp, written, padlen1);
            _handshakeState.mixHash(_tmp, written, padlen1);
            if (_log.shouldDebug())
                _log.debug("After mixhash padding " + padlen1 + " message #1: " + _handshakeState.toString());
        }

        changeState(State.OB_SENT_X);
        // send it all at once
        _con.wantsWrite(_tmp, 0, written + padlen1);
    }

    /**
     * We are Alice, so receive these bytes as part of an outbound connection.
     * This method receives message 2, and sends message 3.
     * <p>
     * IV (CBC from msg 1) must be in _bobIV
     * <p>
     * All data must be copied out of the buffer as {@link net.i2p.router.transport.ntcp.Reader#processRead}
     * will return it to the pool.
     * <p>
     * Caller must synch.
     *
     * @param src the buffer holding the received message 2 bytes
     */
    private void receiveOutbound(ByteBuffer src) {
        // Read in message #2 except for the padding
        if (_state == State.OB_SENT_X && src.hasRemaining()) {
            // For PQ versions, message 2 also carries Bob's ML-KEM
            // ciphertext and its MAC
            int msg2len = msg2Size(_version);
            int toGet = Math.min(src.remaining(), msg2len - _received);
            src.get(_tmp, _received, toGet);
            _received += toGet;
            if (_received < msg2len)
                return;
            _context.aes().decrypt(_tmp, 0, _tmp, 0, _bobHash, _bobIV, KEY_SIZE);
            if (DataHelper.eqCT(_tmp, 0, ZEROKEY, 0, KEY_SIZE)) {
                fail("Bad message #2, Y = 0");
                return;
            }
            byte[] options2 = new byte[OPTIONS2_SIZE];
            try {
                _handshakeState.readMessage(_tmp, 0, msg2len, options2, 0);
            } catch (GeneralSecurityException gse) {
                fail("Bad message #2, Y = " + Base64.encode(_tmp, 0, KEY_SIZE), gse);
                return;
            } catch (RuntimeException re) {
                fail("Bad message #2, Y = " + Base64.encode(_tmp, 0, KEY_SIZE), re);
                return;
            }
            if (_log.shouldDebug())
                _log.debug("After message #2: " + _handshakeState.toString());
            _padlen2 = (int) DataHelper.fromLong(options2, 2, 2);
            if (_padlen2 > padding2Max(_version)) {
                // _tmp is sized for this ceiling and reused from offset 0 for
                // the padding; the limit is i2pd's maxPaddingLength for msg 2
                fail("Padlen too large: " + _padlen2);
                return;
            }
            long tsB = DataHelper.fromLong(options2, 8, 4);
            long now = _context.clock().now();
            // rtt from sending #1 to receiving #2
            long rtt = now - _con.getCreated();
            _peerSkew = (now - (tsB * 1000) - (rtt / 2) + 500) / 1000;
            if (_peerSkew > MAX_SKEW || _peerSkew < 0 - MAX_SKEW) {
                long diff = 1000 * Math.abs(_peerSkew);
                if (_context.clock().getUpdatedSuccessfully()) {
                    // usual case
                    _context.statManager().addRateData("ntcp.invalidOutboundSkew", diff);
                    fail("Clock skew (" + _peerSkew + "s)", null, true);
                    // Only banlist if we know what time it is
                    byte[] ip = _con.getRemoteIP();
                    int port = _con.getRemotePort();
                    String ipPort = (ip != null && ip.length == 4) ?
                        (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff) + ":" + port :
                        "UNKNOWN";
                    _banLogger.logBan(_con.getRemotePeer().calculateHash(), ipPort, "Excessive clock skew: " + DataHelper.formatDuration(diff), 0);
                    _context.banlist().banlistRouter(DataHelper.formatDuration(diff),
                                                     _con.getRemotePeer().calculateHash(),
                                                     " <b>➜</b> Excessive clock skew ({0})");  // _x in IES
                    _transport.setLastBadSkew(_peerSkew);
                    return;
                }
                // Adjust the clock one time in desperation
                // We are Alice, he is Bob, adjust to match Bob
                _context.clock().setOffset(1000 * (0 - _peerSkew), true);
                _peerSkew = 0;
                _log.logAlways(Log.WARN, "NTP failure, NTCP adjusted clock by " + DataHelper.formatDuration(diff) +
                                         " * Source router: [" + _con.getRemotePeer().calculateHash().toBase64().substring(0, 6) + "]");
            }
            changeState(State.OB_GOT_HXY);
            _received = 0;
        }

        // Read in the padding for message #2
        if (_state == State.OB_GOT_HXY && src.hasRemaining()) {
            int toGet = Math.min(src.remaining(), _padlen2 - _received);
            src.get(_tmp, _received, toGet);
            _received += toGet;
            if (_received < _padlen2)
                return;
            if (_padlen2 > 0) {
                _handshakeState.mixHash(_tmp, 0, _padlen2);
                if (_log.shouldDebug())
                    _log.debug("After mixhash padding " + _padlen2 + " message #2: " + _handshakeState.toString());
            }
            changeState(State.OB_GOT_PADDING);
            if (src.hasRemaining()) {
                // Outbound conn can never have extra data after msg 2
                fail("Extra data after message #2: " + src.remaining());
                return;
            }
            prepareOutbound3();
            return;
        }

        // check for remaining data
        if ((_state == State.VERIFIED || _state == State.CORRUPT) && src.hasRemaining() && _log.shouldWarn())
            _log.warn("Received unexpected " + src.remaining() + " bytes on " + this);
    }

    /**
     * We are Alice.
     * Write the 3rd message.
     * <p>
     * Caller must synch.
     */
    private void prepareOutbound3() {
        // create msg 3 part 2 payload
        // payload without MAC
        int msg3p2len = NTCP2Payload.BLOCK_HEADER_SIZE + 1 + _aliceRISize +
                        NTCP2Payload.BLOCK_HEADER_SIZE + OPTIONS3_SIZE +
                        NTCP2Payload.BLOCK_HEADER_SIZE + _padlen3;

        // total for parts 1 and 2 with mac
        byte[] tmp = new byte[MSG3P1_SIZE + msg3p2len + MAC_SIZE];
        List<Block> blocks = new ArrayList<>(3);
        Block block = new NTCP2Payload.RIBlock(_aliceRI, false);
        blocks.add(block);
        byte[] opts = new byte[OPTIONS3_SIZE];
        opts[0] = NTCPConnection.PADDING_MIN_DEFAULT_INT;
        opts[1] = NTCPConnection.PADDING_MAX_DEFAULT_INT;
        opts[2] = NTCPConnection.PADDING_MIN_DEFAULT_INT;
        opts[3] = NTCPConnection.PADDING_MAX_DEFAULT_INT;
        DataHelper.toLong(opts, 4, 2, NTCPConnection.DUMMY_DEFAULT);
        DataHelper.toLong(opts, 6, 2, NTCPConnection.DUMMY_DEFAULT);
        DataHelper.toLong(opts, 8, 2, NTCPConnection.DELAY_DEFAULT);
        DataHelper.toLong(opts, 10, 2, NTCPConnection.DELAY_DEFAULT);
        block = new NTCP2Payload.OptionsBlock(opts);
        blocks.add(block);
        // all zeros is fine here
        block = new NTCP2Payload.PaddingBlock(_padlen3);
        blocks.add(block);
        // we put it at the offset so it doesn't get overwritten by HandshakeState
        // when it copies the static key in there
        int newoff = NTCP2Payload.writePayload(tmp, MSG3P1_SIZE, blocks);
        int expect = MSG3P1_SIZE + msg3p2len;
        if (newoff != expect)
            throw new IllegalStateException("Message #3 size mismatch -> Expected " + expect + " bytes, got " + newoff);
        try {
            _handshakeState.writeMessage(tmp, 0, tmp, MSG3P1_SIZE, msg3p2len);
        } catch (GeneralSecurityException gse) {
            // buffer length error
            if (!_log.shouldWarn())
                _log.error("Bad message #3 out", gse);
            fail("Bad message #3 out", gse);
            return;
        } catch (RuntimeException re) {
            if (!_log.shouldWarn())
                _log.error("Bad message #3 out", re);
            fail("Bad message #3 out", re);
            return;
        }
        // send it all at once
        if (_log.shouldDebug())
            _log.debug("Sending message #3, part 1 is:\n* " + HexDump.dump(tmp, 0, MSG3P1_SIZE));
        _con.wantsWrite(tmp);
        if (_log.shouldDebug())
            _log.debug("After message #3: " + _handshakeState.toString());
        setDataPhase();
    }

/**
 * NTCP 2 only. We are Alice.
 * State machine for establishing outbound NTCP2 connections as Alice.
 * Manages cryptographic handshake, key exchange, and transition to data phase.
 *
 * @since 0.9.35
 */
    private void setDataPhase() {
        // Data phase ChaChaPoly keys
        CipherStatePair ckp = _handshakeState.split();
        CipherState rcvr = ckp.getReceiver();
        CipherState sender = ckp.getSender();

        // Data phase SipHash keys
        byte[][] sipkeys = generateSipHashKeys(_context, _handshakeState);
        byte[] sip_ab = sipkeys[0];
        byte[] sip_ba = sipkeys[1];

        if (_log.shouldDebug()) {
            _log.debug("Finished establishment for " + this);
        }
        // skew in seconds
        _con.finishOutboundEstablishment(sender, rcvr, sip_ab, sip_ba, _peerSkew);
        changeState(State.VERIFIED);
        // no extra data possible
        releaseBufs(true);
        _handshakeState.destroy();
        Arrays.fill(sip_ab, (byte) 0);
        Arrays.fill(sip_ba, (byte) 0);
    }

    /**
     * KDF for SipHash.
     *
     * @param ctx the router context, supplying the HKDF implementation
     * @param state the handshake state whose chaining key and handshake hash are the KDF inputs
     * @return rv[0] is sip_ab, rv[1] is sip_ba
     */
    static byte[][] generateSipHashKeys(RouterContext ctx, HandshakeState state) {
        // TODO use noise HMAC or HKDF method instead?
        // ask_master = HKDF(ck, zerolen, info="ask")
        HKDF hkdf = new HKDF(ctx);
        byte[] ask_master = new byte[32];
        hkdf.calculate(state.getChainingKey(), ZEROLEN, "ask", ask_master);
        byte[] tmp = new byte[32 + SIPHASH.length];
        byte[] hash = state.getHandshakeHash();
        System.arraycopy(hash, 0, tmp, 0, 32);
        System.arraycopy(SIPHASH, 0, tmp, 32, SIPHASH.length);
        byte[] sip_master = new byte[32];
        hkdf.calculate(ask_master, tmp, sip_master);
        Arrays.fill(ask_master, (byte) 0);
        Arrays.fill(tmp, (byte) 0);
        byte[] sip_ab = new byte[32];
        byte[] sip_ba = new byte[32];
        hkdf.calculate(sip_master, ZEROLEN, sip_ab, sip_ba, 0);
        Arrays.fill(sip_master, (byte) 0);
        return new byte[][] { sip_ab, sip_ba };
    }

    /**
     * Release resources on timeout.
     * @param e may be null
     * @since 0.9.16
     */
    public synchronized void close(String reason, Exception e) {fail(reason, e);}
    /**
     * Fail the handshake with the given reason.
     * @param reason stored as the fail reason and reported by
     * {@link #getFailReason}
     */
    protected void fail(String reason) {fail(reason, null);}
    /**
     * Fail the handshake with the given reason and exception.
     * @param reason stored as the fail reason and reported by
     * {@link #getFailReason}
     * @param e the exception cause, may be null
     */
    protected void fail(String reason, Exception e) {fail(reason, e, false);}

    /**
     * Fail the handshake, optionally skew-related.
     * @param reason stored as the fail reason and reported by
     * {@link #getFailReason}
     * @param e the exception cause, may be null
     * @param bySkew true to suppress the skew stat, as when we caused the skew ourselves
     */
    protected synchronized void fail(String reason, Exception e, boolean bySkew) {
        if (_state == State.CORRUPT || _state == State.VERIFIED) {return;}
        changeState(State.CORRUPT);
        _failReason = reason;
        if (_log.shouldDebug()) {
            _log.debug("[NTCP] Handshake failure " + _handshakeState);
            _log.debug(this + "\n* " + reason, e);
        }
        _handshakeState.destroy();
        if (!bySkew) {
            _context.statManager().addRateData("ntcp.receiveCorruptEstablishment", 1);
        }
        releaseBufs(false);
    }

    /** Failure reason if the handshake failed. */
    @Override
    public String getFailReason() {
        if (_failReason != null) { return _failReason; }
        return "State: " + _state;
    }

    /**
     * Only call once.
     * <p>
     * Caller must synch.
     *
     * @param isVerified whether the handshake data was verified; currently
     *                   unused
     */
    private void releaseBufs(boolean isVerified) {
        Arrays.fill(_tmp, (byte) 0);
    }

    /**
     * String representation of this state.
     */
    @Override
    public String toString() {
        // Trimmed for the same reason as EstablishBase.toString() - the connection's
        // own form ends with a space when the remote peer is not yet known.
        return _con.toString().trim() + (_state != State.CORRUPT ? " (" + _state + ')' : "");
    }
}
