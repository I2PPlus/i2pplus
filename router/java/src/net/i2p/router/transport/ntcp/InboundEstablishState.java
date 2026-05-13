package net.i2p.router.transport.ntcp;

import static net.i2p.router.transport.ntcp.OutboundNTCP2State.*;

import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;
import com.southernstorm.noise.protocol.NoiseInit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.i2p.crypto.EncType;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.BanLogger;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.CommSystemFacadeImpl;
import net.i2p.util.Addresses;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SystemVersion;
import net.i2p.util.VersionComparator;

/**
 * NTCP 2 inbound connection establishment state machine.
 * Handles the server-side (Bob) of the NTCP 2 handshake process,
 * including cryptographic operations, peer verification, and
 * transition to the data phase.
 *
 * <p>This class manages the complete inbound NTCP 2 connection lifecycle:
 * <ul>
 *   <li>Receives and processes Alice's handshake messages (1 and 3)</li>
 *   <li>Sends Bob's responses (messages 2 and 4)</li>
 *   <li>Performs Noise protocol XK handshake with Elligator2</li>
 *   <li>Validates peer RouterInfo and signatures</li>
 *   <li>Establishes data phase keys (ChaChaPoly + SipHash)</li>
 *   <li>Handles clock skew detection and peer banlisting</li>
 * </ul>
 *
 * <p>State transitions:
 * <pre>
 * IB_INIT -&gt; IB_NTCP2_INIT -&gt; IB_NTCP2_GOT_X -&gt; IB_NTCP2_GOT_PADDING
 *      -&gt; IB_NTCP2_SENT_Y -&gt; IB_NTCP2_GOT_RI -&gt; VERIFIED
 * </pre>
 *
 * <p>Threading: All methods are synchronized. Caller must hold locks when
 * invoking methods that modify state.
 *
 * @since 0.9.35 pulled out of EstablishState
 */
class InboundEstablishState extends EstablishBase implements NTCP2Payload.PayloadCallback {

    /** Current encrypted block we are reading (IB only) or an IV buf used at the end for OB */
    private byte _curEncrypted[];
    /** Size of Alice's RouterIdentity in bytes */
    private int _aliceIdentSize;
    /** Alice's RouterIdentity, set after gotRI() validation succeeds */
    private RouterIdentity _aliceIdent;
    /** Buffer for accumulating decrypted message 3 payload (aliceIndexSize + aliceIdent + tsA + padding + aliceSig) */
    private final ByteArrayOutputStream _sz_aliceIdent_tsA_padding_aliceSig;
    /** Expected size of _sz_aliceIdent_tsA_padding_aliceSig when complete */
    private int _sz_aliceIdent_tsA_padding_aliceSigSize;
    /** Flag to ensure releaseBufs() is called only once */
    private boolean _released;

    //// NTCP2 things
    /** Noise protocol handshake state for XK pattern */
    private HandshakeState _handshakeState;
    /** Length of padding specified in message 1 options, used for probing resistance */
    private int _padlen1;
    /** Length of message 3 part 2 (RI + signature) */
    private int _msg3p2len;
    /** Reason code for handshake failure; negative means success, non-negative is failure reason */
    private int _msg3p2FailReason = -1;
    /** Temporary buffer for receiving message 3 */
    private ByteArray _msg3tmp;
    /** Cached payload buffer for message 3 processing to reduce pool overhead */
    private ByteArray _payloadTmp;
    /** Alice's negotiated options from message 3 */
    private NTCP2Options _hisPadding;
    private int _version = 2;

    /** Reusable options array for message 1 processing to eliminate per-call allocation */


    /** Buffer size for reading data phase packets (16 KB), same as I2PTunnelRunner */
    private static final int BUFFER_SIZE = 16*1024;
    /** Maximum number of read buffers to cache (32 on slow devices, 64 otherwise) */
    private static final int MAX_DATA_READ_BUFS = SystemVersion.isSlow() ? 32 : 64;
    /** Cache for read buffers */
    private static final ByteCache _dataReadBufs = ByteCache.getInstance(MAX_DATA_READ_BUFS, BUFFER_SIZE);

    /** Maximum padding for probing resistance strategy (128 bytes) */
    private static final int PADDING1_FAIL_MAX = 128;
    /** Maximum padding length in message 2 (64 bytes) */
    private static final int PADDING2_MAX = 64;
    /** Minimum RouterInfo size: DSA signature (40) + overhead (8+1+1+2+387) */
    private static final int RI_MIN = 387 + 8 + 1 + 1 + 2 + 40;
    /** Minimum message 3 part 2 size: header (1+2+1) + RI_MIN + MAC (16) */
    private static final int MSG3P2_MIN = 1 + 2 + 1 + RI_MIN + MAC_SIZE;
    /** Maximum message 3 part 2 size (6000 bytes, less than full buffer) */
    private static final int MSG3P2_MAX = 6000;

    /** Set of states that are part of NTCP 2 protocol processing */
    private static final Set<State> STATES_NTCP2 =
        EnumSet.of(State.IB_NTCP2_INIT, State.IB_NTCP2_GOT_X, State.IB_NTCP2_GOT_MSG1, State.IB_NTCP2_GOT_PADDING,
                   State.IB_NTCP2_SENT_Y, State.IB_NTCP2_GOT_RI, State.IB_NTCP2_READ_RANDOM);

    private BanLogger _banLogger;

    public InboundEstablishState(RouterContext ctx, NTCPTransport transport, NTCPConnection con) {
        super(ctx, transport, con);
        _state = State.IB_INIT;
        _sz_aliceIdent_tsA_padding_aliceSig = new ByteArrayOutputStream(512);
        _prevEncrypted = SimpleByteCache.acquire(AES_SIZE);
        _curEncrypted = SimpleByteCache.acquire(AES_SIZE);
        _payloadTmp = null; // Will be acquired when needed
        _banLogger = new BanLogger();
    }

    /**
     * Extract IP address and port from RouterInfo for logging to sessionbans.txt.
     * Returns IP:PORT format for IPv4 or [IPv6]:PORT format for IPv6.
     *
     * @param router the RouterInfo to extract from
     * @return IP:PORT string or empty string if not available
     */
    private String getRouterIPPort(RouterInfo router) {
        if (router == null) { return ""; }
        try {
            // Try getCompatibleIP first - returns IP for our supported protocols
            byte[] ip = CommSystemFacadeImpl.getCompatibleIP(router);
            if (ip != null) {
                int port = 0;
                for (RouterAddress addr : router.getAddresses()) {
                    if (addr != null && addr.getIP() != null && java.util.Arrays.equals(addr.getIP(), ip)) {
                        port = addr.getPort();
                        break;
                    }
                }
                return formatIPPort(ip, port);
            }
            // Fallback to first available
            for (RouterAddress addr : router.getAddresses()) {
                if (addr != null && addr.getHost() != null) {
                    String ipAddr = addr.getHost();
                    int port = addr.getPort();
                    if (port > 0) {
                        if (ipAddr.contains(":") && !ipAddr.startsWith("[")) {
                            return "[" + ipAddr + "]:" + port;
                        } else {
                            return ipAddr + ":" + port;
                        }
                    } else {
                        return ipAddr;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore extraction errors
        }
        return "";
    }

    /**
     * Format IP byte array to string with port.
     */
    private String formatIPPort(byte[] ip, int port) {
        if (ip.length == 4) {
            return (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff) + ":" + port;
        } else if (ip.length == 16) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < 16; i += 2) {
                if (i > 0) sb.append(":");
                sb.append(String.format("%x", (ip[i] << 8 | ip[i + 1] & 0xff)));
            }
            sb.append("]").append(":").append(port);
            return sb.toString();
        }
        return "";
    }

    /**
     * Entry point for receiving data on an inbound NTCP connection.
     * Parses the contents of the buffer as part of the handshake.
     *
     * <p>Delegates to receiveInbound() for actual processing.
     * All data is copied out of the buffer as Reader.processRead()
     * will return it to the pool.
     *
     * <p>If there are additional data in the buffer after the handshake is complete,
     * this EstablishState is responsible for passing it to NTCPConnection.
     *
     * @param src the ByteBuffer containing received data; caller must not modify position
     */
    @Override
    public synchronized void receive(ByteBuffer src) {
        super.receive(src);
        if (!src.hasRemaining()) {return;} // nothing to receive
        receiveInbound(src);
    }

    /**
     * Get the NTCP version
     * @return 2-5 or 0 if unknown
     * @since 0.9.35
     */
    public int getVersion() {
        return _version;
    }

    /**
     * Receives bytes as part of an inbound connection (Bob's perspective).
     *
     * <p>This method dispatches to the appropriate handler based on state:
     * <ul>
     *   <li>{@link State#IB_INIT}: Waits for sufficient data to determine NTCP version</li>
     *   <li>{@link #STATES_NTCP2}: Delegates to receiveInboundNTCP2()</li>
     * </ul>
     *
     * <p>For NTCP 2, this method receives messages 1 and 3 from Alice,
     * and sends messages 2 and 4 in response.
     *
     * <p>All data must be copied out of the buffer as Reader.processRead()
     * will return it to the pool.
     *
     * @param src the ByteBuffer containing received data; data is copied out
     */
    private void receiveInbound(ByteBuffer src) {
        // first 32 bytes

        if (_state == State.IB_INIT && src.hasRemaining()) {
            int remaining = src.remaining();
            if (remaining + _received < KEY_SIZE) {
                // Less than 32 total received, so we defer the X decode.
                // Buffer in _X.
                // Stay in the IB_INIT state, and wait for more data.
                src.get(_X, _received, remaining);
                _received += remaining;
                if (_log.shouldWarn()) {
                    _log.warn("[NTCP] Insufficient data for handshake -> Received " + _received + " bytes, need " + MSG1_SIZE + "\n* " + this);
                }
                return;
            }
            changeState(State.IB_NTCP2_INIT);
            decodeInboundNTCP2X(src);
        }

        // remainder of message except padding
        if (_state == State.IB_NTCP2_GOT_X && src.hasRemaining()) {
            int remaining = src.remaining();
            int extralen = 0;
            switch (_version) {
                case 2:
                    break;
                case 3:
                    extralen = MAC_SIZE + EncType.MLKEM512_X25519_INT.getPubkeyLen();
                    break;
                case 4:
                    extralen = MAC_SIZE + EncType.MLKEM768_X25519_INT.getPubkeyLen();
                    break;
                case 5:
                    extralen = MAC_SIZE + EncType.MLKEM1024_X25519_INT.getPubkeyLen();
                    break;
                default:
                    throw new IllegalArgumentException("Bad version " + _version);
            }
            int sz = MSG1_SIZE + extralen;
            if (remaining + _received < sz) {
                // Less than full message 1 received, so we defer the handling.
                // Buffer in _X.
                // Stay in the IB_NTCP2_GOT_X state, and wait for more data.
                src.get(_X, _received, remaining);
                _received += remaining;
                if (_log.shouldWarn())
                    _log.warn("Short buffer -> Received " + _received + " bytes, remaining " + remaining + "\n* " + this);
                return;
            }
        }

        // full message and optional padding
        if (STATES_NTCP2.contains(_state)) {
            receiveInboundNTCP2(src);
            return; // releaseBufs() will return the unused DH
}
     }

    /**
     * Decodes the first 32 bytes (X) from the inbound NTCP2 message 1.
     * Performs replay check, decryption, zero-key check, and MSB-based version detection.
     *
     * @since 0.9.69 split out from below
     */
    private synchronized void decodeInboundNTCP2X(ByteBuffer src) {
        if (_state == State.IB_NTCP2_INIT && src.hasRemaining()) {
            int toGet = Math.min(src.remaining(), KEY_SIZE - _received);
            src.get(_X, _received, toGet);
            _received += toGet;
            changeState(State.IB_NTCP2_GOT_X);
            if (!_transport.isHXHIValid(_X)) {
                _context.statManager().addRateData("ntcp.replayHXxorBIH", 1);
                fail("Replay msg 1, eX = " + Base64.encode(_X, 0, KEY_SIZE));
                return;
            }

            Hash h = _context.routerHash();
            SessionKey bobHash = new SessionKey(h.getData());
            System.arraycopy(_X, KEY_SIZE - IV_SIZE, _prevEncrypted, 0, IV_SIZE);
            _context.aes().decrypt(_X, 0, _X, 0, bobHash, _transport.getNTCP2StaticIV(), KEY_SIZE);
            if (DataHelper.eqCT(_X, 0, ZEROKEY, 0, KEY_SIZE)) {
                fail("Bad msg 1, X = 0");
                return;
            }
            if ((_X[KEY_SIZE - 1] & 0x80) != 0) {
                if (NTCPTransport.PQ_INT_VERSION != 0) {
                    _version = NTCPTransport.PQ_INT_VERSION;
                    _X[KEY_SIZE - 1] &= (byte) 0x7f;
                } else {
                    _padlen1 = _context.random().nextInt(PADDING1_FAIL_MAX) - src.remaining();
                    if (_padlen1 > 0) {
                        if (_log.shouldWarn())
                            _log.warn("Bad PK msg 1, X = " + Base64.encode(_X, 0, KEY_SIZE) + " with " + src.remaining() +
                                      " more bytes, waiting for " + _padlen1 + " more bytes");
                        changeState(State.IB_NTCP2_READ_RANDOM);
                    } else {
                        fail("Bad PK msg 1, X = " + Base64.encode(_X, 0, KEY_SIZE) + " remaining = " + src.remaining());
                    }
                    _transport.getPumper().blockIP(_con.getRemoteIP());
                    return;
                }
            }
            _con.setVersion(_version);
        }
    }

    /**
     * Validates an inbound peer's identity and eligibility for connection.
     *
     * <p>Performs the following checks after receiving Alice's RouterIdentity in message 3:
     * <ul>
     *   <li>Banlist checks (permanent, hostile, temporary)</li>
     *   <li>IP address recording for banlisted peers</li>
     *   <li>Clock skew validation and adjustment</li>
     *   <li>Clock update (desperate one-time adjustment)</li>
     * </ul>
     *
     * <p>Side effects:
     * <ul>
     *   <li>Sets _msg3p2FailReason when returning false</li>
     *   <li>May blocklist the peer's IP address</li>
     *   <li>May update the router's clock offset</li>
     * </ul>
     *
     * @param aliceHash the hash of Alice's RouterIdentity to validate
     * @return true if the peer is valid and connection should proceed;
     *         false if validation failed (caller should return immediately)
     * @since 0.9.36 pulled out of verifyInbound()
     */
    private boolean verifyInbound(Hash aliceHash) {
        // get inet-addr
        byte[] ip = _con.getRemoteIP();
        if (_context.banlist().isBanlistedForever(aliceHash)) {
            // Already banned - don't log warning (just drop connection silently)
            if (ip != null) {_context.blocklist().add(ip);}
            if (getVersion() < 2) {
                fail("Banlisting incompatible Router [" + aliceHash.toBase64().substring(0,6) + "] -> No NTCP2 support");
            }
            _msg3p2FailReason = NTCPConnection.REASON_BANNED;
            return false;
        } else if (_context.banlist().isBanlistedHostile(aliceHash) || _context.banlist().isBanlisted(aliceHash)) {
            _context.commSystem().mayDisconnect(aliceHash);
            // Already banned - don't log info (just skip validation silently)
            return false;
        }

        if (ip != null) {_transport.setIP(aliceHash, ip);}
        if (_log.shouldDebug()) {_log.debug(prefix() + "verification successful for " + _con);}

        // Adjust skew calculation now that we know RTT
        // rtt from receiving #1 to receiving #3
        long rtt = _context.clock().now() - _con.getCreated();
        _peerSkew -= ((rtt / 2) + 500) / 1000;
        long diff = 1000*Math.abs(_peerSkew);
        boolean skewOK = diff < Router.CLOCK_FUDGE_FACTOR;
        if (skewOK && !_context.clock().getUpdatedSuccessfully()) {
            /*
             * Adjust the clock one time in desperation
             * This isn't very likely, outbound will do it first
             * Don't adjust to large skews inbound.
             * We are Bob, she is Alice, adjust to match Alice
             */
            _context.clock().setOffset(1000 * (0 - _peerSkew), true);
            _peerSkew = 0;
            if (diff != 0) {
                _log.logAlways(Log.WARN, "NTP failure, NTCP adjusted clock by " + DataHelper.formatDuration(diff) +
                                         " -> Source Router [" + aliceHash.toBase64().substring(0,6) + "]");
            }
        } else if (!skewOK) {
            // Only banlist if we know what time it is
            int port = _con.getRemotePort();
            String ipPort = ip != null ? formatIPPort(ip, port) : "UNKNOWN";
            _banLogger.logBan(aliceHash, ipPort, "Excessive clock skew: " + DataHelper.formatDuration(diff), 0);
            _context.banlist().banlistRouter(DataHelper.formatDuration(diff), aliceHash,
                                             "" + _x("Excessive clock skew ({0})"));
            _transport.setLastBadSkew(_peerSkew);
            if (_log.shouldWarn()) {
                _log.warn("Excessive clock skew (" + diff + "ms) from [" + aliceHash.toBase64().substring(0,6) + "]");
            }
            _msg3p2FailReason = NTCPConnection.REASON_SKEW;
            return false;
        } else if (_log.shouldDebug()) {
            _log.debug(prefix() + "Clock skew (" + diff + "ms) from [" + aliceHash.toBase64().substring(0,6) + "]");
        }
        return true;
    }

    /**
     * Validates the network ID from a peer's RouterInfo.
     *
     * <p>NTCP 2 only. This is called when storing the RouterInfo in the network
     * database fails, to validate the network identifier before rejecting the connection.
     *
     * <p>Side effects when returning false:
     * <ul>
     *   <li>Sets _msg3p2FailReason to REASON_BANNED</li>
     *   <li>Blocklists the peer's IP address</li>
     *   <li>Marks the router as unreachable</li>
     * </ul>
     *
     * @param alice the RouterInfo received from Alice
     * @return true if network IDs match; false if they don't
     * @since 0.9.38
     */
    private boolean verifyInboundNetworkID(RouterInfo alice) {
        int aliceID = alice.getNetworkId();
        boolean rv = aliceID == _context.router().getNetworkID();
        if (!rv) {
            Hash aliceHash = alice.getHash();
            if (_log.shouldWarn()) {
                _log.warn("[NTCP] Dropping Inbound connection for " + aliceID + " [" + aliceHash + "] -> Wrong network identifier");
            }
            // So next time we will not accept the con from this IP, rather than doing the whole handshake
            byte[] ip = _con.getRemoteIP();
            if (ip != null) {_context.blocklist().add(ip);}
            _transport.markUnreachable(aliceHash);
            _msg3p2FailReason = NTCPConnection.REASON_BANNED;
        }
        return rv;
    }

    /**
     *  NTCP2 only. State must be one of IB_NTCP2_*.
     *  Decoded X must be in _X.
     *  Remaining part of msg1 must be in _X or src.
     *  Padding if any is still in src and will be read here.
     *
     *  Side effect: Sets state to IB_NTCP2_GOT_MSG1, IB_NTCP2_GOT_PADDING, or a failure state.
     *
     *  we are Bob, so receive these bytes as part of an inbound connection
     *  This method receives messages 1 and 3, and sends message 2.
     *
     *  All data must be copied out of the buffer as Reader.processRead()
     *  will return it to the pool.
     *
     *  @since 0.9.36
     */
    private synchronized void receiveInboundNTCP2(ByteBuffer src) {
        if (_state == State.IB_NTCP2_GOT_X && src.hasRemaining()) {
            int extralen = 0;
            NoiseInit.PatternID pattern;
            try {
                switch (_version) {
                    case 2:
                        pattern = NoiseInit.PatternID.XK;
                        break;
                    case 3:
                        pattern = NoiseInit.PatternID.XKHFS_512;
                        extralen = MAC_SIZE + EncType.MLKEM512_X25519_INT.getPubkeyLen();
                        break;
                    case 4:
                        pattern = NoiseInit.PatternID.XKHFS_768;
                        extralen = MAC_SIZE + EncType.MLKEM768_X25519_INT.getPubkeyLen();
                        break;
                    case 5:
                        pattern = NoiseInit.PatternID.XKHFS_1024;
                        extralen = MAC_SIZE + EncType.MLKEM1024_X25519_INT.getPubkeyLen();
                        break;
                    default:
                        throw new IllegalArgumentException("Bad version " + _version);
                }
                _handshakeState = new HandshakeState(pattern, HandshakeState.RESPONDER, _transport.getXDHFactory());
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException("bad proto", gse);
            }
            // use _X for the buffer
            int toGet = Math.min(src.remaining(), MSG1_SIZE + extralen - _received);
            src.get(_X, _received, toGet);
            changeState(State.IB_NTCP2_GOT_MSG1);
            _received = 0;
            _handshakeState.getLocalKeyPair().setKeys(_transport.getNTCP2StaticPrivkey(), 0,
                                                      _transport.getNTCP2StaticPubkey(), 0);
            byte options[] = new byte[OPTIONS1_SIZE];
            try {
                _handshakeState.start();
                if (_log.shouldDebug())
                    _log.debug("After start: " + _handshakeState.toString());
                _handshakeState.readMessage(_X, 0, MSG1_SIZE + extralen, options, 0);
            } catch (GeneralSecurityException gse) {
                 String gseMsg = gse.getMessage();
                 // Read a random number of bytes, store wanted in _padlen1
                 _padlen1 = _context.random().nextInt(PADDING1_FAIL_MAX) - src.remaining();
                 if (_padlen1 > 0) {
                      // Delayed fail for probing resistance - need more bytes before failure
                     if (_log.shouldDebug()) {
                         _log.debug("[NTCP] BAD Establishment handshake message #1 debug: X=" +
                                   Base64.encode(_X, 0, 8) + ", " + src.remaining() + " bytes received, " +
                                   _padlen1 + " bytes waiting");
                     } else if (_log.shouldWarn()) {
                     String ip;
                     try {
                          ip = _con.getRemoteIP() != null ? InetAddress.getByAddress(_con.getRemoteIP()).getHostAddress() : "unknown";
                      } catch (UnknownHostException uhe) {
                          ip = "unknown";
                      }
                      _log.warn("[NTCP] BAD NTCP2 handshake #1 from " + ip + ", waiting for " + _padlen1 + " more bytes -> Probable probe");
                      // Track probe attempts for repeat offender detection
                      _context.banlist().badPacket(ip, null);
                     }
                     changeState(State.IB_NTCP2_READ_RANDOM);
                  } else {
                      // Got all we need, fail now
                       String ip;
                       try {
                           ip = _con.getRemoteIP() != null ? InetAddress.getByAddress(_con.getRemoteIP()).getHostAddress() : "unknown";
                       } catch (UnknownHostException uhe) {
                           ip = "unknown";
                       }
                       _log.warn("[NTCP] BAD NTCP2 handshake #1 from " + ip + ", all bytes received but handshake failed -> Invalid encryption");
                       _transport.getPumper().trackInvalidEncryption(_con.getRemoteIP(), null);
                      fail("\n* BAD Establishment handshake message #1: X = " + Base64.encode(_X, 0, KEY_SIZE) + " remaining = " + src.remaining(), gse);
                  }
                _transport.getPumper().blockIP(_con.getRemoteIP());
                return;
            } catch (RuntimeException re) {
                fail("\n* BAD Establishment handshake message #1: X = " + Base64.encode(_X, 0, KEY_SIZE), re);
                return;
            }
            if (_log.shouldDebug()) {
                _log.debug("After Establishment handshake message #1: " + _handshakeState.toString());
            }
            int v = options[1] & 0xff;
            if (v != NTCPTransport.NTCP2_INT_VERSION) {
                fail("BAD version: " + v);
                return;
            }
            // network ID cross-check, proposal 147, as of 0.9.42
            v = options[0] & 0xff;
            if (v != 0 && v != _context.router().getNetworkID()) {
                byte[] ip = _con.getRemoteIP();
                if (ip != null) {
                    if (_log.shouldWarn()) {
                        _log.warn("[NTCP] Dropping Inbound connection (Wrong network identifier): " + Addresses.toString(ip));
                    }
                    _context.blocklist().add(ip);
                }
                fail("BAD network id: " + v);
                return;
            }
            _padlen1 = (int) DataHelper.fromLong(options, 2, 2);
            if (_padlen1 > _X.length) {
                // we reuse _X to read the padding, length set in EstablishBase
                // limits are specified in the PQ spec
                fail("Padlen too large: " + _padlen1);
                return;
            }
            _msg3p2len = (int) DataHelper.fromLong(options, 4, 2);
            long tsA = DataHelper.fromLong(options, 8, 4);
            long now = _context.clock().now();
            // Will be adjusted for RTT in verifyInbound()
            _peerSkew = (now - (tsA * 1000) + 500) / 1000;
            if (_peerSkew > MAX_SKEW || _peerSkew < 0 - MAX_SKEW) {
                long diff = 1000*Math.abs(_peerSkew);
                _context.statManager().addRateData("ntcp.invalidInboundSkew", diff);
                _transport.setLastBadSkew(_peerSkew);
                if (_log.shouldWarn())
                    _log.warn("Clock Skew: " + _peerSkew + "ms on " + this);
                /*
                 * Do them a favor, keep going with msg 2 so they get our timestamp.
                 * They should disconnect after that.
                 *
                 * If they do send a msg 3, verifyInbound() will catch it and will send a
                 * destroy with code 7 and ban the peer for a while.
                 */
            }
            if (_msg3p2len < MSG3P2_MIN || _msg3p2len > MSG3P2_MAX) {
                fail("BAD Establishment handshake message #3 (part 2) -> Length: " + _msg3p2len + " bytes)");
                return;
            }
            if (_padlen1 <= 0) {
                // No padding specified, go straight to sending msg 2
                changeState(State.IB_NTCP2_GOT_PADDING);
                prepareOutbound2(); // Write msg 2
                return;
            }
            // We don't enforce max _padlen1 here
            // if it is more than our buffer size we will fail below.
        }

        // Delayed fail for probing resistance
        if (_state == State.IB_NTCP2_READ_RANDOM) {
            if (!src.hasRemaining()) {return;}
            _received += src.remaining(); // Read more bytes before failing
            if (_received < _padlen1) {
                if (_log.shouldWarn()) {
                    _log.warn("[NTCP] BAD Establishment handshake message #1: Received " + src.remaining() +
                              " more bytes, waiting for " + (_padlen1 - _received) + " more bytes");
                }
            } else {
                fail("BAD Establishment handshake message #1: Failure with " + src.remaining() + " more bytes remaining");
            }
            return;
        }

        if (_state == State.IB_NTCP2_GOT_MSG1 && src.hasRemaining()) {
            // Skip this if _padlen1 == 0; use _X for the buffer
            int toGet = Math.min(src.remaining(), _padlen1 - _received);
            src.get(_X, _received, toGet);
            _received += toGet;
            if (_received < _padlen1) {return;}
            changeState(State.IB_NTCP2_GOT_PADDING);
            _handshakeState.mixHash(_X, 0, _padlen1);
            if (_log.shouldDebug()) {
                _log.debug("After mixhash padding " + _padlen1 + " Establishment handshake message #1: " + _handshakeState.toString());
            }
            _received = 0;
            if (src.hasRemaining()) {
                // Inbound conn can never have extra data after msg 1
                fail("Extra data after Establishment handshake message #1: " + src.remaining());
            } else {prepareOutbound2();} // write msg 2
            return;
        }

        if (_state == State.IB_NTCP2_SENT_Y && src.hasRemaining()) {
            if (!src.hasRemaining()) {return;}
            int msg3tot = MSG3P1_SIZE + _msg3p2len;
            if (_msg3tmp == null) {_msg3tmp = _dataReadBufs.acquire();}
            byte[] tmp = _msg3tmp.getData();
            if (msg3tot > tmp.length) {
                fail("msg 3 too large: " + msg3tot);
                return;
            }
            int toGet = Math.min(src.remaining(), msg3tot - _received);
            src.get(tmp, _received, toGet);
            _received += toGet;
            if (_received < msg3tot) {return;}
            changeState(State.IB_NTCP2_GOT_RI);
            _received = 0;
            if (_payloadTmp == null) {_payloadTmp = _dataReadBufs.acquire();}
            byte[] payload = _payloadTmp.getData();
            try {_handshakeState.readMessage(tmp, 0, msg3tot, payload, 0);}
            catch (GeneralSecurityException gse) {
                // TODO delayed failure per spec, as in NTCPConnection.delayedClose()
                fail("BAD Establishment handshake message #3, part 1 is:\n" + net.i2p.util.HexDump.dump(tmp, 0, MSG3P1_SIZE), gse);
                return;
            } catch (RuntimeException re) {
                fail("BAD Establishment handshake message #3", re);
                return;
            }
            if (_log.shouldDebug()) {
                _log.debug("After Establishment handshake message #3: " + _handshakeState.toString());
            }
            try {
                // calls callbacks below
                NTCP2Payload.processPayload(_context, this, payload, 0, _msg3p2len - MAC_SIZE, true);
            } catch (IOException ioe) {
                if (_log.shouldInfo()) {
                    _log.info("BAD Establishment handshake message #3 payload -> " + ioe.getMessage());
                }
                // probably payload frame/block problems
                // setDataPhase() will send termination
                if (_msg3p2FailReason < 0) {_msg3p2FailReason = NTCPConnection.REASON_FRAMING;}
            } catch (DataFormatException dfe) {
                if (_log.shouldInfo()) {
                    _log.info("BAD Establishment handshake message #3 payload -> " + dfe.getMessage());
                }
                // Probably RI signature failure - setDataPhase() will send termination
                if (_msg3p2FailReason < 0) {
                    _msg3p2FailReason = NTCPConnection.REASON_SIGFAIL;
                    // Buggy/forked and very persistent i2pd
                    // So next time we will not accept the con from this IP, rather than doing the whole handshake
                    byte[] ip = _con.getRemoteIP();
                    if (ip != null) {_context.blocklist().add(ip);}
                }
                _context.statManager().addRateData("ntcp.invalidInboundSignature", 1);
            } catch (I2NPMessageException ime) {
                // shouldn't happen, no I2NP msgs in msg3p2
                if (_log.shouldInfo()) {
                    _log.info("BAD Establishment handshake message #3 payload -> " + ime.getMessage());
                }
                // setDataPhase() will send termination
                if (_msg3p2FailReason < 0) {_msg3p2FailReason = 0;}
            } finally {
                // Keep _payloadTmp for reuse - it will be released in releaseBufs()
            }

            // pass buffer for processing of "extra" data
            setDataPhase(src);
        }
        // TODO check for remaining data and log/throw
    }

    /**
     * Constructs and queues NTCP 2 message 2 for sending to Alice.
     *
     * <p>Message 2 contains:
     * <ul>
     *   <li>Bob's Noise public key (Y), 32 bytes</li>
     *   <li>Options: padding length (2 bytes), timestamp (4 bytes)</li>
     *   <li>Padding (variable length, 0-64 bytes)</li>
     *   <li>AES-CBC MAC (16 bytes)</li>
     * </ul>
     *
     * <p>The IV for CBC encryption must be in _prevEncrypted (extracted from
     * the last 16 bytes of Alice's X in message 1).
     *
     * <p>After sending, transitions to IB_NTCP2_SENT_Y and waits for message 3.
     *
     * @since 0.9.36
     */
    private synchronized void prepareOutbound2() {
        // create msg 2 payload
        int padlen2 = _context.random().nextInt(PADDING2_MAX);
        int len = MSG2_SIZE + padlen2;
        switch (_version) {
            case 2:
                break;
            case 3:
                len += MAC_SIZE + EncType.MLKEM512_X25519_CT.getPubkeyLen();
                break;
            case 4:
                len += MAC_SIZE + EncType.MLKEM768_X25519_CT.getPubkeyLen();
                break;
            case 5:
                len += MAC_SIZE + EncType.MLKEM1024_X25519_CT.getPubkeyLen();
                break;
            default:
                throw new IllegalArgumentException("Bad version " + _version);
        }
        byte[] tmp = new byte[len];
        // write options directly to tmp with offset so it doesn't get clobbered
        int off = len - (OPTIONS2_SIZE + MAC_SIZE + padlen2);
        DataHelper.toLong(tmp, off + 2, 2, padlen2);
        long now = (_context.clock().now() + 500) / 1000;
        DataHelper.toLong(tmp, off + 8, 4, now);
        try {
            // encrypt in-place
            _handshakeState.writeMessage(tmp, 0, tmp, off, OPTIONS2_SIZE);
        } catch (GeneralSecurityException gse) {
            // buffer length error
            boolean gseNotNull = gse.getMessage() != null && !gse.getMessage().equals("null");
            if (!_log.shouldWarn()) {
                _log.error("[NTCP] BAD Outbound Establishment handshake message #2 -> " + gse.getMessage());
            }
            fail("BAD Establishment handshake message #2 out", gse);
            return;
        } catch (RuntimeException re) {
            if (!_log.shouldWarn()) {
                _log.error("[NTCP] BAD Outbound Establishment handshake message #2 -> " + re.getMessage());
            }
            fail("BAD Establishment handshake message #2 out", re);
            return;
        }
        if (_log.shouldDebug()) {
            _log.debug("After Establishment handshake message #2: " + _handshakeState.toString());
        }
        Hash h = _context.routerHash();
        SessionKey bobHash = new SessionKey(h.getData());
        _context.aes().encrypt(tmp, 0, tmp, 0, bobHash, _prevEncrypted, KEY_SIZE);
        if (padlen2 > 0) {
            _context.random().nextBytes(tmp, len - padlen2, padlen2);
            _handshakeState.mixHash(tmp, len - padlen2, padlen2);
            if (_log.shouldDebug()) {
                _log.debug("After mixhash padding " + padlen2 + " Establishment handshake message #2: " + _handshakeState.toString());
            }
        }

        changeState(State.IB_NTCP2_SENT_Y);
        _con.wantsWrite(tmp); // Send it all at once
    }

    /**
     * Performs key derivation for the NTCP 2 data phase and transitions connection state.
     *
     * <p>This method is called after successfully receiving and validating message 3.
     * It performs the following:
     * <ul>
     *   <li>Splits the Noise handshake to derive ChaChaPoly cipher states</li>
     *   <li>Generates SipHash keys for message authentication</li>
     *   <li>Either finishes the connection or sends termination</li>
     *   <li>Processes any "extra" data in the buffer for the data phase</li>
     *   <li>Zeros out sensitive key material</li>
     * </ul>
     *
     * <p>On success (_msg3p2FailReason &lt; 0):
     * <ul>
     *   <li>Calls con.finishInboundEstablishment() with keys and states</li>
     *   <li>Transitions to VERIFIED state</li>
     * </ul>
     *
     * <p>On failure (_msg3p2FailReason &gt;= 0):
     * <ul>
     *   <li>Calls con.failInboundEstablishment() with termination reason</li>
     *   <li>Transitions to CORRUPT state</li>
     * </ul>
     *
     * <p>Note: If you don't call this method, call fail() directly.
     *
     * @param buf ByteBuffer possibly containing "extra" data for immediate processing
     *            in the data phase; typically present for inbound connections
     * @since 0.9.36
     */
    private synchronized void setDataPhase(ByteBuffer buf) {
        // Data phase ChaChaPoly keys
        CipherStatePair ckp = _handshakeState.split();
        CipherState rcvr = ckp.getReceiver();
        CipherState sender = ckp.getSender();

        // Data phase SipHash keys
        byte[][] sipkeys = generateSipHashKeys(_context, _handshakeState);
        byte[] sip_ab = sipkeys[0];
        byte[] sip_ba = sipkeys[1];

        if (_msg3p2FailReason >= 0) {
            if (_log.shouldInfo()) {
                _log.warn("[NTCP] Establishment handshake message #3 (part 2) failure -> " + parseReason(_msg3p2FailReason) + "\n* For: " + this);
            } else if (_log.shouldWarn() && !parseReason(_msg3p2FailReason).contains("banned")) {
                _log.warn("[NTCP] Establishment handshake message #3 (part 2) failure -> " + parseReason(_msg3p2FailReason) + "\n* For: " + this);
            }
            _con.failInboundEstablishment(sender, sip_ba, _msg3p2FailReason);
            changeState(State.CORRUPT);
        } else {
            if (_log.shouldDebug()) {
                _log.debug("Finished Establishment for " + this +
                          "\n* Generated SipHash key for A -> B: " + Base64.encode(sip_ab) +
                          "\n* Generated SipHash key for B -> A: " + Base64.encode(sip_ba));
            }
            // skew in seconds
            _con.finishInboundEstablishment(sender, rcvr, sip_ba, sip_ab, _peerSkew, _hisPadding);
            changeState(State.VERIFIED);
            if (buf.hasRemaining()) {
                // process "extra" data
                // This is very likely for inbound, as data should come right after message 3
                if (_log.shouldInfo()) {
                    _log.info("Extra data (" + buf.remaining() + " bytes) on " + this);
                }
                 _con.recvEncryptedI2NP(buf);
            }
        }
        // zero out everything
        releaseBufs(true);
        _handshakeState.destroy();
        Arrays.fill(sip_ab, (byte) 0);
        Arrays.fill(sip_ba, (byte) 0);
    }

    //// PayloadCallbacks

    /**
     * Validates Alice's RouterInfo and extracts the static key for comparison.
     *
     * <p>This is a PayloadCallback method invoked during message 3 processing.
     *
     * <p>Validations performed:
     * <ul>
     *   <li>Ensures RouterInfo contains an NTCP2-capable address</li>
     *   <li>Extracts and validates the "s" (static key) option</li>
     *   <li>Compares static key against the Noise handshake public key</li>
     *   <li>Verifies the peer's identity via verifyInbound()</li>
     *   <li>Checks IP address consistency (RI-published vs. actual connection)</li>
     *   <li>Validates router version, capabilities, and network ID</li>
     *   <li>Stores RouterInfo in the network database</li>
     * </ul>
     *
     * <p>Side effects:
     * <ul>
     *   <li>Sets _msg3p2FailReason on validation failure</li>
     *   <li>May banlist the peer hash and/or blocklist the IP</li>
     *   <li>Stores RouterInfo in netdb</li>
     *   <li>Sets _aliceIdent and calls con.setRemotePeer()</li>
     * </ul>
     *
     * @param ri Alice's RouterInfo from message 3
     * @param isHandshake always true; indicates this is during handshake processing
     * @param flood true if the RouterInfo should be flooded to the network
     * @throws DataFormatException if validation fails (bad sig, no static key,
     *                              key mismatch, IP mismatch, banned, etc.)
     * @since 0.9.36
     */
    @Override
    public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException {
        // Validate Alice static key
        // find address with matching version
        List<RouterAddress> addrs = ri.getTargetAddresses(NTCPTransport.STYLE, NTCPTransport.STYLE2);
        if (addrs.isEmpty()) {
            _msg3p2FailReason = NTCPConnection.REASON_S_MISMATCH;
            throw new DataFormatException("No NTCP address in RouterInfo: " + ri);
        }
        String s = null;
        String mismatchMessage = null;
        byte[] realIP = _con.getRemoteIP();
        for (RouterAddress addr : addrs) {
            String v = addr.getOption("v");
            if (v == null ||
                (!v.equals(NTCPTransport.NTCP2_VERSION) && !v.startsWith(NTCPTransport.NTCP2_VERSION_ALT))) {
                 continue;
            }
            if (s == null) {s = addr.getOption("s");}
            if (realIP != null) {
                byte[] infoIP = addr.getIP();
                if (infoIP != null && infoIP.length == realIP.length) {
                    if (infoIP.length == 16) {
                        if ((((int) infoIP[0]) & 0xfe) == 0x02) {continue;} // ygg
                        if (DataHelper.eqCT(realIP, 0, infoIP, 0, 8)) {continue;}
                    } else if (DataHelper.eqCT(realIP, 0, infoIP, 0, realIP.length)) {continue;}
                    // We will ban and throw below after checking s
                    mismatchMessage = "IP address mismatch -> Actual IP: " + Addresses.toString(realIP) + "; RI publishes: ";
                }
            }
        }
        if (s == null) {
            _msg3p2FailReason = NTCPConnection.REASON_S_MISMATCH;
            throw new DataFormatException("No s in RouterInfo: " + ri);
        }
        byte[] sb = Base64.decode(s);
        if (sb == null || sb.length != KEY_SIZE) {
            _msg3p2FailReason = NTCPConnection.REASON_S_MISMATCH;
            throw new DataFormatException("BAD s in RouterInfo: " + ri);
        }
        byte[] nb = new byte[32];
        synchronized(this) {
            _handshakeState.getRemotePublicKey().getPublicKey(nb, 0); // Compare to the _handshakeState
        }
        if (!DataHelper.eqCT(sb, 0, nb, 0, KEY_SIZE)) {
            _msg3p2FailReason = NTCPConnection.REASON_S_MISMATCH;
            throw new DataFormatException("s mismatch in RouterInfo: " + ri);
        }
        _aliceIdent = ri.getIdentity();
        Hash h = _aliceIdent.calculateHash();
        // this sets the reason
        boolean ok = verifyInbound(h);
        if (!ok) {throw new DataFormatException("NTCP2 verifyInbound() fail");}

        boolean isBanned = _context.banlist().isBanlisted(h) ||
                           _context.banlist().isBanlistedHostile(h) ||
                           _context.banlist().isBanlistedForever(h);

        // s is verified, we may now ban the hash
        if (mismatchMessage != null) {
            byte[] ip = _con.getRemoteIP();
            int port = _con.getRemotePort();
            String ipPort = ip != null ? formatIPPort(ip, port) : "UNKNOWN";
            _banLogger.logBan(h, ipPort, "Invalid NTCP address", 4*60*60*1000);
            _context.banlist().banlistRouter(h, "Invalid NTCP address",
                                             null, null, _context.clock().now() + 4*60*60*1000);
            _context.commSystem().forceDisconnect(h, "Invalid NTCP address");
            if (_log.shouldWarn() && !isBanned) {
                _log.warn("[NTCP] Banning for 4h and disconnecting from Router [" + h.toBase64().substring(0,6) + "]" +
                          " -> Invalid address");
            }
            _msg3p2FailReason = NTCPConnection.REASON_BANNED;
            throw new DataFormatException(mismatchMessage + ri);
        }

        String cap = ri.getCapabilities();
        String bw = ri.getBandwidthTier();
        String ipPort = getRouterIPPort(ri);
        boolean reachable = cap != null && cap.indexOf(Router.CAPABILITY_REACHABLE) >= 0;
        boolean unreachable = cap != null && cap.indexOf(Router.CAPABILITY_UNREACHABLE) >= 0;
        boolean isSlow = (cap != null && !cap.isEmpty()) && bw.equals("K") ||
                          bw.equals("L") || bw.equals("M") || bw.equals("N");
        String version = ri.getVersion();
        boolean isOld = VersionComparator.comp(version, "0.9.67") < 0;
        boolean isInvalidVersion = VersionComparator.comp(version, "2.5.0") >= 0;
        long now = _context.clock().now();
        String reason = "";

        if (isInvalidVersion) {
            reason = "Invalid Router version (" + version + " / " + bw +
                      (unreachable ? "U" : reachable ? "R" : "") + ")";
            _banLogger.logBan(h, ipPort, reason, now + 24*60*60*1000);
            _context.banlist().banlistRouter(h, reason, null, null, now + 24*60*60*1000);
            _msg3p2FailReason = NTCPConnection.REASON_BANNED;
            if (_log.shouldWarn() && !isBanned)
                _log.warn("[NTCP] Banning for 24h and disconnecting from Router [" + h.toBase64().substring(0,6) + "]" +
                          " -> Invalid version " + version + " / " + bw + (unreachable ? "U" : ""));
            _context.commSystem().forceDisconnect(h, "Invalid version (" + version + ")");
            throw new DataFormatException("Invalid Router version " + version + ": " + h);
        }

        if (!reachable && isSlow && isOld) {
            reason = "Old and slow (" + version + " / " + bw + "U)";
            _banLogger.logBan(h, ipPort, reason, now + 60*60*1000);
            _context.banlist().banlistRouter(h, reason, null, null, now + 60*60*1000);
            _msg3p2FailReason = NTCPConnection.REASON_BANNED;
            if (_log.shouldInfo() && !isBanned)
                _log.info("[NTCP] Banning for 1h and disconnecting from Router [" + h.toBase64().substring(0,6) + "]" +
                          " -> " + version + " / " + bw + (unreachable ? "U" : ""));
            _context.commSystem().forceDisconnect(h, "Old and slow (" + version + ")");
            throw new DataFormatException("Old and slow: " + h);
        }

        if (reachable && unreachable) {
            reason = "Invalid published capabilities (RU)";
            _banLogger.logBan(h, ipPort, reason, now + 24*60*60*1000);
            _context.banlist().banlistRouter(h, reason, null, null, now + 24*60*60*1000);
            _msg3p2FailReason = NTCPConnection.REASON_BANNED;
            if (_log.shouldWarn() && !isBanned) {
                _log.warn("[NTCP] Banning for 24h and disconnecting from Router [" + h.toBase64().substring(0,6) + "]" +
                          " -> Publishing both R and U caps");
            }
            _context.commSystem().forceDisconnect(h, "Invalid published capabilities (RU)");
            throw new DataFormatException("Invalid caps (RU): " + h);
        }

        try {
            RouterInfo old = _context.netDb().store(h, ri);
            if (flood && !ri.equals(old)) {
                FloodfillNetworkDatabaseFacade fndf = (FloodfillNetworkDatabaseFacade) _context.netDb();
                if (fndf.floodConditional(ri)) {
                    if (_log.shouldDebug()) {
                        _log.debug("Flooded RouterInfo [" + h.toBase64().substring(0,6) + "]");
                    }
                } else {
                    if (_log.shouldInfo()) {
                        _log.info("Flood request declined for RouterInfo [" + h.toBase64().substring(0,6) + "]");
                    }
                }
            }
        } catch (IllegalArgumentException iae) {
            ok = verifyInboundNetworkID(ri);
            if (!ok) {throw new DataFormatException("NTCP2 network ID mismatch");}
            reason = iae.getMessage();
            if (reason != null && reason.contains("Invalid NetDbStore attempt - ")) {
                reason = reason.substring(reason.indexOf("Invalid NetDbStore attempt - ") + 27);
            } else {
                reason = "Unknown";
            }
            if (_log.shouldWarn()) {
                _log.warn("[NTCP] RouterInfo store failed for [" + h.toBase64().substring(0,6) + "]: " + reason);
            }
            if (_msg3p2FailReason <= 0) {_msg3p2FailReason = NTCPConnection.REASON_MSG3;}
            throw new DataFormatException("RouterInfo rejected: " + reason + " - " + ri, iae);
        }
        _con.setRemotePeer(_aliceIdent);
    }

    /**
     * Receives and validates Alice's option preferences from message 3.
     *
     * <p>Parses the options byte array into an NTCP2Options object containing
     * negotiated parameters like congestion control and flags.
     *
     * @param options the options byte array from Alice
     * @param isHandshake always true; indicates this is during handshake processing
     * @since 0.9.36
     */
    @Override
    public synchronized void gotOptions(byte[] options, boolean isHandshake) {
        NTCP2Options hisPadding = NTCP2Options.fromByteArray(options);
        if (hisPadding == null) {
            if (_log.shouldWarn()) {
                _log.warn("[NTCP] Received options length " + options.length + " on: " + this);
            }
            return;
        }
        _hisPadding = hisPadding;
    }

    /**
     * Receives padding information from message 3.
     *
     * <p>NTCP 2 padding is handled differently - the actual padding data is
     * not separately tracked in this implementation.
     *
     * @param paddingLength the length of padding in the frame
     * @param frameLength the total length of the frame including padding
     * @since 0.9.36
     */
    @Override
    public void gotPadding(int paddingLength, int frameLength) {}

    // These payload types are illegal during the NTCP 2 handshake
    // and will never be received. Stub implementations are required
    // by the PayloadCallback interface.

    /**
     * Illegal during handshake - termination is only valid after connection is established.
     *
     * @param reason the termination reason code
     * @param lastReceived the number of bytes received when termination occurred
     * @since 0.9.36
     */
    @Override
    public void gotTermination(int reason, long lastReceived) {}
    /**
     * Illegal during handshake - unknown payload types should not appear.
     *
     * @param type the unknown payload type
     * @param len the payload length
     * @since 0.9.36
     */
    public void gotUnknown(int type, int len) {}
    /**
     * Illegal during handshake - date/time is only in the options, not a separate payload.
     *
     * @param time the timestamp
     * @since 0.9.36
     */
    public void gotDateTime(long time) {}
    /**
     * Illegal during handshake - I2NP messages are only in the data phase.
     *
     * @param msg the I2NP message
     * @since 0.9.36
     */
    public void gotI2NP(I2NPMessage msg) {}

    /**
     * Handles handshake failure by transitioning to CORRUPT state and cleaning up.
     *
     * <p>Overrides the base implementation to additionally destroy the Noise
     * handshake state for security.
     *
     * @param reason the failure reason string
     * @param e the exception that caused the failure, or null
     * @param bySkew true if failure was due to clock skew (suppresses stat collection)
     * @since 0.9.16
     */
    @Override
    protected synchronized void fail(String reason, Exception e, boolean bySkew) {
        super.fail(reason, e, bySkew);
        if (_handshakeState != null) {
            if (_log.shouldDebug()) {
                _log.warn("State at Handshake failure: " + _handshakeState.toString());
            }
            _handshakeState.destroy();
        }
    }

    /**
     * Releases buffers and resources held by this state machine.
     *
     * <p>Must be called exactly once when the handshake completes (success or failure).
     * Caller must hold synchronization.
     *
     * <p>Actions:
     * <ul>
     *   <li>Releases _curEncrypted buffer if verification failed (passed to NTCPConnection on success)</li>
     *   <li>Zeros out _X buffer for security</li>
     *   <li>Releases _X and _msg3tmp buffers back to caches</li>
     * </ul>
     *
     * @param isVerified true if handshake succeeded; false if it failed
     * @since 0.9.16
     */
    @Override
    protected void releaseBufs(boolean isVerified) {
        if (_released) {return;}
        _released = true;
        super.releaseBufs(isVerified);
        // Do not release _curEncrypted if verified, it is passed to
        // NTCPConnection to use as the IV
        if (!isVerified) {SimpleByteCache.release(_curEncrypted);}
        Arrays.fill(_X, (byte) 0);
        SimpleByteCache.release(_X);
        if (_msg3tmp != null) {
            _dataReadBufs.release(_msg3tmp, false);
            _msg3tmp = null;
        }
        if (_payloadTmp != null) {
            _dataReadBufs.release(_payloadTmp, false);
            _payloadTmp = null;
        }
    }

    private class Disconnector implements SimpleTimer.TimedEvent {
        private final Hash h;
        public Disconnector(Hash h) { this.h = h; }
        public void timeReached() {_context.commSystem().forceDisconnect(h, "Handshake timeout");}
    }

    /**
     * Converts a termination reason code to a human-readable string.
     *
     * <p>Reason codes are defined in NTCPConnection and used in termination
     * messages to indicate why a connection failed.
     *
     * @param reasonCode the numeric reason code from NTCPConnection
     * @return a descriptive string for the reason code
     */
    public static String parseReason(int reasonCode) {
        switch (reasonCode) {
            case 0: return "Unspecified";
            case 1: return "Termination";
            case 2: return "Timeout";
            case 3: return "Shutdown";
            case 4: return "AEAD error";
            case 5: return "Options error";
            case 6: return "Signature type error";
            case 7: return "Excessive clock skew";
            case 8: return "Padding error";
            case 9: return "Framing error";
            case 10: return "Payload error";
            case 11: return "Message #1 error";
            case 12: return "Message #2 error";
            case 13: return "Message #3 error";
            case 14: return "Frame Timeout";
            case 15: return "Signature error";
            case 16: return "S Mismatch";
            case 17: return "Router is banned";
            case 18: return "Token error";
            case 19: return "Limit reached";
            case 20: return "Incompatible Version";
            case 21: return "BAD Netid";
            case 22: return "Replaced connection";
            default: return "Unknown error";
        }
    }

}
