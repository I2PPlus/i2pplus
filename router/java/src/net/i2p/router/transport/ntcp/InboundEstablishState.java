package net.i2p.router.transport.ntcp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.transport.crypto.DHSessionKeyBuilder;
import static net.i2p.router.transport.ntcp.OutboundNTCP2State.*;
import net.i2p.util.ByteArrayStream;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

import net.i2p.util.SystemVersion;

/**
 *
 *  NTCP 2. We are Bob.
 *
 *  @since 0.9.35 pulled out of EstablishState
 */
class InboundEstablishState extends EstablishBase implements NTCP2Payload.PayloadCallback {

    /** current encrypted block we are reading (IB only) or an IV buf used at the end for OB */
    private byte _curEncrypted[];

    private int _aliceIdentSize;
    private RouterIdentity _aliceIdent;

    /** contains the decrypted aliceIndexSize + aliceIdent + tsA + padding + aliceSig */
    private final ByteArrayOutputStream _sz_aliceIdent_tsA_padding_aliceSig;

    /** how long we expect _sz_aliceIdent_tsA_padding_aliceSig to be when its full */
    private int _sz_aliceIdent_tsA_padding_aliceSigSize;

    private boolean _released;

    //// NTCP2 things

    private HandshakeState _handshakeState;
    private int _padlen1;
    private int _msg3p2len;
    private int _msg3p2FailReason = -1;
    private ByteArray _msg3tmp;
    private NTCP2Options _hisPadding;

    // same as I2PTunnelRunner
//    private static final int BUFFER_SIZE = 4*1024;
    private static final int BUFFER_SIZE = 8*1024;
//    private static final int MAX_DATA_READ_BUFS = 32;
    private static final int MAX_DATA_READ_BUFS = 64;
//    private static final int BUFFER_SIZE = SystemVersion.getMaxMemory() < 1024*1024*1024 ? 4*1024 : 5*1024;
//    private static final int MAX_DATA_READ_BUFS = SystemVersion.getMaxMemory() < 1024*1024*1024 ? 32 : 36;
    private static final ByteCache _dataReadBufs = ByteCache.getInstance(MAX_DATA_READ_BUFS, BUFFER_SIZE);

    // 287 - 64 = 223
    private static final int PADDING1_MAX = TOTAL1_MAX - MSG1_SIZE;
    private static final int PADDING1_FAIL_MAX = 128;
    private static final int PADDING2_MAX = 64;
    // DSA RI, no options, no addresses
    private static final int RI_MIN = 387 + 8 + 1 + 1 + 2 + 40;
    private static final int MSG3P2_MIN = 1 + 2 + 1 + RI_MIN + MAC_SIZE;
    // absolute max, let's enforce less
    //private static final int MSG3P2_MAX = BUFFER_SIZE - MSG3P1_SIZE;
    private static final int MSG3P2_MAX = 6000;

    private static final Set<State> STATES_NTCP2 =
        EnumSet.of(State.IB_NTCP2_INIT, State.IB_NTCP2_GOT_X, State.IB_NTCP2_GOT_PADDING,
                   State.IB_NTCP2_SENT_Y, State.IB_NTCP2_GOT_RI, State.IB_NTCP2_READ_RANDOM);


    public InboundEstablishState(RouterContext ctx, NTCPTransport transport, NTCPConnection con) {
        super(ctx, transport, con);
        _state = State.IB_INIT;
        _sz_aliceIdent_tsA_padding_aliceSig = new ByteArrayOutputStream(512);
        _prevEncrypted = SimpleByteCache.acquire(AES_SIZE);
        _curEncrypted = SimpleByteCache.acquire(AES_SIZE);
    }

    /**
     * Parse the contents of the buffer as part of the handshake.
     *
     * All data must be copied out of the buffer as Reader.processRead()
     * will return it to the pool.
     *
     * If there are additional data in the buffer after the handshake is complete,
     * the EstablishState is responsible for passing it to NTCPConnection.
     */
    @Override
    public synchronized void receive(ByteBuffer src) {
        super.receive(src);
        if (!src.hasRemaining())
            return; // nothing to receive
        receiveInbound(src);
    }

    /**
     *  Get the NTCP version
     *  @return 1, 2, or 0 if unknown
     *  @since 0.9.35
     */
    public int getVersion() {
            return 2;
    }

    /**
     *  we are Bob, so receive these bytes as part of an inbound connection
     *  This method receives messages 1 and 3, and sends messages 2 and 4.
     *
     *  All data must be copied out of the buffer as Reader.processRead()
     *  will return it to the pool.
     *
     *  Caller must synch.
     *
     */
    private void receiveInbound(ByteBuffer src) {
        if (STATES_NTCP2.contains(_state)) {
            receiveInboundNTCP2(src);
            return;
        }
        if (_state == State.IB_INIT && src.hasRemaining()) {
            int remaining = src.remaining();

                if (remaining + _received < MSG1_SIZE) {
                    // Less than 64 total received, so we defer the NTCP 1 or 2 decision.
                    // Buffer in _X.
                    // Stay in the IB_INIT state, and wait for more data.
                    src.get(_X, _received, remaining);
                    _received += remaining;
                    if (_log.shouldWarn())
                        _log.warn("Short buffer got " + remaining + " total now " + _received + " on " + this);
                    return;
                }
                //if (remaining + _received < NTCP1_MSG1_SIZE ||
                //    !_transport.isNTCP1Enabled()) {
                    // Less than 288 total received, assume NTCP2
                    // TODO can't change our mind later if we get more than 287
                    _con.setVersion(2);
                    changeState(State.IB_NTCP2_INIT);
                    receiveInboundNTCP2(src);
                    // releaseBufs() will return the unused DH
                    return;
                //}

        }
    }

    /**
     *  Common validation things for both NTCP 1 and 2.
     *  Call after receiving Alice's RouterIdentity (in message 3).
     *  _peerSkew must be set.
     *
     *  Side effect: sets _msg3p2FailReason when returning false
     *
     *  @return success or calls fail() and returns false
     *  @since 0.9.36 pulled out of verifyInbound()
     */
    private boolean verifyInbound(Hash aliceHash) {
        // get inet-addr
        InetAddress addr = this._con.getChannel().socket().getInetAddress();
        byte[] ip = (addr == null) ? null : addr.getAddress();
        if (_context.banlist().isBanlistedForever(aliceHash)) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping inbound connection from permanently banlisted peer [" + aliceHash.toBase64().substring(0,6) + "]");
            // So next time we will not accept the con from this IP,
            // rather than doing the whole handshake
            if(ip != null)
               _context.blocklist().add(ip);
            if (getVersion() < 2)
                fail("Banlisting incompatible router [" + aliceHash.toBase64().substring(0,6) + "] -> no NTCP2 support");
            else if (_log.shouldWarn())
                _log.warn("Peer is banlisted forever [" + aliceHash.toBase64().substring(0,6) + "]");
            _msg3p2FailReason = NTCPConnection.REASON_BANNED;
            return false;
        }
        if(ip != null)
           _transport.setIP(aliceHash, ip);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(prefix() + "verification successful for " + _con);

        long diff = 1000*Math.abs(_peerSkew);
        if (!_context.clock().getUpdatedSuccessfully()) {
            // Adjust the clock one time in desperation
            // This isn't very likely, outbound will do it first
            // We are Bob, she is Alice, adjust to match Alice
            _context.clock().setOffset(1000 * (0 - _peerSkew), true);
            _peerSkew = 0;
            if (diff != 0)
                _log.logAlways(Log.WARN, "NTP failure, NTCP adjusting clock by " + DataHelper.formatDuration(diff));
        } else if (diff >= Router.CLOCK_FUDGE_FACTOR) {
            _context.statManager().addRateData("ntcp.invalidInboundSkew", diff);
            _transport.markReachable(aliceHash, true);
            // Only banlist if we know what time it is
            _context.banlist().banlistRouter(DataHelper.formatDuration(diff),
                                             aliceHash,
                                             " <b>➜</b> " + _x("Excessive clock skew: {0}"));
            _transport.setLastBadSkew(_peerSkew);
            if (getVersion() < 2)
                fail("Clocks too skewed (" + diff + " ms)", null, true);
            else if (_log.shouldWarn())
                _log.warn("Excessive clock skew (" + diff + "ms) from [" + aliceHash.toBase64().substring(0,6) + "]");
            _msg3p2FailReason = NTCPConnection.REASON_SKEW;
            return false;
        } else if (_log.shouldLog(Log.DEBUG)) {
            _log.debug(prefix() + "Clock skew (" + diff + "ms) from [" + aliceHash.toBase64().substring(0,6) + "]");
        }
        return true;
    }

    /**
     *  Validate network ID, NTCP 2 only.
     *  Call if storing in netdb fails.
     *
     *  Side effects: When returning false, sets _msg3p2FailReason
     *
     *  @return success
     *  @since 0.9.38
     */
    private boolean verifyInboundNetworkID(RouterInfo alice) {
        int aliceID = alice.getNetworkId();
        boolean rv = aliceID == _context.router().getNetworkID();
        if (!rv) {
            Hash aliceHash = alice.getHash();
            if (_log.shouldLog(Log.WARN))
                _log.warn("Dropping inbound connection from wrong network: " + aliceID + " [" + aliceHash + "]");
            // So next time we will not accept the con from this IP,
            // rather than doing the whole handshake
            InetAddress addr = _con.getChannel().socket().getInetAddress();
            if (addr != null) {
                byte[] ip = addr.getAddress();
                _context.blocklist().add(ip);
            }
            _transport.markUnreachable(aliceHash);
            _msg3p2FailReason = NTCPConnection.REASON_BANNED;
        }
        return rv;
    }

    //// NTCP2 below here

    /**
     *  NTCP2 only. State must be one of IB_NTCP2_*
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
        if (_state == State.IB_NTCP2_INIT && src.hasRemaining()) {
            // use _X for the buffer
            int toGet = Math.min(src.remaining(), MSG1_SIZE - _received);
            src.get(_X, _received, toGet);
            _received += toGet;
            if (_received < MSG1_SIZE) {
                // Won't get here, now handled in receiveInbound()
                if (_log.shouldWarn())
                    _log.warn("Short buffer got " + toGet + " total now " + _received);
                return;
            }
            changeState(State.IB_NTCP2_GOT_X);
            _received = 0;
            // replay check using encrypted key
            if (!_transport.isHXHIValid(_X)) {
                _context.statManager().addRateData("ntcp.replayHXxorBIH", 1);
                fail("\n* Replay Message 1: eX = " + Base64.encode(_X, 0, KEY_SIZE));
                return;
            }

            Hash h = _context.routerHash();
            SessionKey bobHash = new SessionKey(h.getData());
            // save encrypted data for CBC for msg 2
            System.arraycopy(_X, KEY_SIZE - IV_SIZE, _prevEncrypted, 0, IV_SIZE);
            _context.aes().decrypt(_X, 0, _X, 0, bobHash, _transport.getNTCP2StaticIV(), KEY_SIZE);
            if (DataHelper.eqCT(_X, 0, ZEROKEY, 0, KEY_SIZE)) {
                fail("Bad message #1: X = 0");
                return;
            }
            // fast MSB check for key < 2^255
            if ((_X[KEY_SIZE - 1] & 0x80) != 0) {
                fail("Bad PK message #1");
                return;
            }

            try {
                _handshakeState = new HandshakeState(HandshakeState.PATTERN_ID_XK, HandshakeState.RESPONDER, _transport.getXDHFactory());
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException("bad proto", gse);
            }
            _handshakeState.getLocalKeyPair().setKeys(_transport.getNTCP2StaticPrivkey(), 0,
                                                      _transport.getNTCP2StaticPubkey(), 0);
            byte options[] = new byte[OPTIONS1_SIZE];
            try {
                _handshakeState.start();
                if (_log.shouldDebug())
                    _log.debug("After start: " + _handshakeState.toString());
                _handshakeState.readMessage(_X, 0, MSG1_SIZE, options, 0);
            } catch (GeneralSecurityException gse) {
                // Read a random number of bytes, store wanted in _padlen1
                _padlen1 = _context.random().nextInt(PADDING1_FAIL_MAX) - src.remaining();
                if (_padlen1 > 0) {
                    // delayed fail for probing resistance
                    // need more bytes before failure
                    if (_log.shouldDebug())
                        _log.warn("Bad message #1 \n* X = " + Base64.encode(_X, 0, KEY_SIZE) + " with " + src.remaining() +
                                  " more bytes, waiting for " + _padlen1 + " more bytes", gse);
                    else if (_log.shouldWarn())
                        _log.warn("Bad message #1 \n* X = " + Base64.encode(_X, 0, KEY_SIZE) + " with " + src.remaining() +
                                  " more bytes, waiting for " + _padlen1 + " more bytes \n* General Security Exception: " +  gse.getMessage());
                    changeState(State.IB_NTCP2_READ_RANDOM);
                } else {
                    // got all we need, fail now
                    fail("\n* Bad message #1: X = " + Base64.encode(_X, 0, KEY_SIZE) + " remaining = " + src.remaining(), gse);
                }
                return;
            } catch (RuntimeException re) {
                fail("\n* Bad message #1: X = " + Base64.encode(_X, 0, KEY_SIZE), re);
                return;
            }
            if (_log.shouldDebug())
                _log.debug("After message #1: " + _handshakeState.toString());
            int v = options[1] & 0xff;
            if (v != NTCPTransport.NTCP2_INT_VERSION) {
                fail("Bad version: " + v);
                return;
            }
            // network ID cross-check, proposal 147, as of 0.9.42
            v = options[0] & 0xff;
            if (v != 0 && v != _context.router().getNetworkID()) {
                InetAddress addr = _con.getChannel().socket().getInetAddress();
                if (addr != null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Dropping inbound connection from wrong network: " + addr);
                    byte[] ip = addr.getAddress();
                    // So next time we will not accept the con from this IP
                    _context.blocklist().add(ip);
                }
                fail("Bad NetworkId: " + v);
                return;
            }
            _padlen1 = (int) DataHelper.fromLong(options, 2, 2);
            _msg3p2len = (int) DataHelper.fromLong(options, 4, 2);
            long tsA = DataHelper.fromLong(options, 8, 4);
            long now = _context.clock().now();
            // In NTCP1, timestamp comes in msg 3 so we know the RTT.
            // In NTCP2, it comes in msg 1, so just guess.
            // We could defer this to msg 3 to calculate the RTT?
            long rtt = 250;
            _peerSkew = (now - (tsA * 1000) - (rtt / 2) + 500) / 1000;
            if ((_peerSkew > MAX_SKEW || _peerSkew < 0 - MAX_SKEW) &&
                !_context.clock().getUpdatedSuccessfully()) {
                // If not updated successfully, allow it.
                // This isn't very likely, outbound will do it first
                // See verifyInbound() above.
                fail("Clock Skew: " + _peerSkew, null, true);
                return;
            }
            if (_msg3p2len < MSG3P2_MIN || _msg3p2len > MSG3P2_MAX) {
                fail("Bad msg3p2 (length: " + _msg3p2len + " bytes)");
                return;
            }
            if (_padlen1 <= 0) {
                // No padding specified, go straight to sending msg 2
                changeState(State.IB_NTCP2_GOT_PADDING);
                if (src.hasRemaining()) {
                    // Inbound conn can never have extra data after message #1
                    fail("Extra data after message #1: " + src.remaining());
                } else {
                    // write msg 2
                    prepareOutbound2();
                }
                return;
            }
        }

        // delayed fail for probing resistance
        if (_state == State.IB_NTCP2_READ_RANDOM && src.hasRemaining()) {
            // read more bytes before failing
            _received += src.remaining();
            if (_received < _padlen1) {
                if (_log.shouldWarn())
                    _log.warn("Bad message #1: received " + src.remaining() +
                              " more bytes, waiting for " + (_padlen1 - _received) + " more bytes");
            } else {
                fail("Bad message #1: failing after getting " + src.remaining() + " more bytes");
            }
            return;
        }

        if (_state == State.IB_NTCP2_GOT_X && src.hasRemaining()) {
            // skip this if _padlen1 == 0;
            // use _X for the buffer
            int toGet = Math.min(src.remaining(), _padlen1 - _received);
            src.get(_X, _received, toGet);
            _received += toGet;
            if (_received < _padlen1)
                return;
            changeState(State.IB_NTCP2_GOT_PADDING);
            _handshakeState.mixHash(_X, 0, _padlen1);
            if (_log.shouldDebug())
                _log.debug("After mixhash padding " + _padlen1 + " message #1: " + _handshakeState.toString());
            _received = 0;
            if (src.hasRemaining()) {
                // Inbound conn can never have extra data after msg 1
                fail("Extra data after message #1: " + src.remaining());
            } else {
                // write msg 2
                prepareOutbound2();
            }
            return;
        }

        if (_state == State.IB_NTCP2_SENT_Y && src.hasRemaining()) {
            int msg3tot = MSG3P1_SIZE + _msg3p2len;
            if (_msg3tmp == null)
                _msg3tmp = _dataReadBufs.acquire();
            // use _X for the buffer FIXME too small
            byte[] tmp = _msg3tmp.getData();
            int toGet = Math.min(src.remaining(), msg3tot - _received);
            src.get(tmp, _received, toGet);
            _received += toGet;
            if (_received < msg3tot)
                return;
            changeState(State.IB_NTCP2_GOT_RI);
            _received = 0;
            ByteArray ptmp = _dataReadBufs.acquire();
            byte[] payload = ptmp.getData();
            try {
                _handshakeState.readMessage(tmp, 0, msg3tot, payload, 0);
            } catch (GeneralSecurityException gse) {
                // TODO delayed failure per spec, as in NTCPConnection.delayedClose()
                _dataReadBufs.release(ptmp, false);
                fail("Bad message #3, part 1 is:\n" + net.i2p.util.HexDump.dump(tmp, 0, MSG3P1_SIZE), gse);
                return;
            } catch (RuntimeException re) {
                _dataReadBufs.release(ptmp, false);
                fail("Bad message #3", re);
                return;
            }
            if (_log.shouldDebug())
                _log.debug("After message #3: " + _handshakeState.toString());
            try {
                // calls callbacks below
                NTCP2Payload.processPayload(_context, this, payload, 0, _msg3p2len - MAC_SIZE, true);
            } catch (IOException ioe) {
                if (_log.shouldWarn())
//                    _log.warn("Bad msg 3 payload", ioe);
                    _log.warn("Bad message #3 payload \n* IO Error:" + ioe.getMessage());
                // probably payload frame/block problems
                // setDataPhase() will send termination
                if (_msg3p2FailReason < 0)
                    _msg3p2FailReason = NTCPConnection.REASON_FRAMING;
            } catch (DataFormatException dfe) {
                if (_log.shouldWarn())
//                    _log.warn("Bad msg 3 payload", dfe);
                    _log.warn("Bad message #3 payload \n* Data Format Exception: " + dfe.getMessage());
                // probably RI problems
                // setDataPhase() will send termination
                if (_msg3p2FailReason < 0)
                    _msg3p2FailReason = NTCPConnection.REASON_SIGFAIL;
                _context.statManager().addRateData("ntcp.invalidInboundSignature", 1);
            } catch (I2NPMessageException ime) {
                // shouldn't happen, no I2NP msgs in msg3p2
                if (_log.shouldWarn())
//                    _log.warn("Bad msg 3 payload", ime);
                    _log.warn("Bad message #3 payload \n* I2NP Message Exception: " + ime.getMessage());
                // setDataPhase() will send termination
                if (_msg3p2FailReason < 0)
                    _msg3p2FailReason = 0;
            } finally {
                _dataReadBufs.release(ptmp, false);
            }

            // pass buffer for processing of "extra" data
            setDataPhase(src);
        }
        // TODO check for remaining data and log/throw
    }

    /**
     *  Write the 2nd NTCP2 message.
     *  IV (CBC from msg 1) must be in _prevEncrypted
     *
     *  @since 0.9.36
     */
    private synchronized void prepareOutbound2() {
        // create msg 2 payload
        byte[] options2 = new byte[OPTIONS2_SIZE];
        int padlen2 = _context.random().nextInt(PADDING2_MAX);
        DataHelper.toLong(options2, 2, 2, padlen2);
        long now = _context.clock().now() / 1000;
        DataHelper.toLong(options2, 8, 4, now);
        byte[] tmp = new byte[MSG2_SIZE + padlen2];
        try {
            _handshakeState.writeMessage(tmp, 0, options2, 0, OPTIONS2_SIZE);
        } catch (GeneralSecurityException gse) {
            // buffer length error
            if (!_log.shouldWarn())
//                _log.error("Bad msg 2 out", gse);
                _log.warn("Bad message #2 out\n* General Secruity Exception: " + gse.getMessage());
            fail("Bad msg 2 out", gse);
            return;
        } catch (RuntimeException re) {
            if (!_log.shouldWarn())
//                _log.error("Bad msg 2 out", re);
                _log.error("Bad message #2 out \n* Runtime Exception: " + re.getMessage());
            fail("Bad message #2 out", re);
            return;
        }
        if (_log.shouldDebug())
            _log.debug("After message #2: " + _handshakeState.toString());
        Hash h = _context.routerHash();
        SessionKey bobHash = new SessionKey(h.getData());
        _context.aes().encrypt(tmp, 0, tmp, 0, bobHash, _prevEncrypted, KEY_SIZE);
        if (padlen2 > 0) {
            _context.random().nextBytes(tmp, MSG2_SIZE, padlen2);
            _handshakeState.mixHash(tmp, MSG2_SIZE, padlen2);
            if (_log.shouldDebug())
                _log.debug("After mixhash padding " + padlen2 + " message #2: " + _handshakeState.toString());
        }

        changeState(State.IB_NTCP2_SENT_Y);
        // send it all at once
        _con.wantsWrite(tmp);
    }

    /**
     *  KDF for NTCP2 data phase.
     *
     *  If _msg3p2FailReason is less than zero,
     *  this calls con.finishInboundEstablishment(),
     *  passing over the final keys and states to the con,
     *  and changes the state to VERIFIED.
     *
     *  Otherwise, it calls con.failInboundEstablishment(),
     *  which will send a termination message,
     *  and changes the state to CORRUPT.
     *
     *  If you don't call this, call fail().
     *
     *  @param buf possibly containing "extra" data for data phase
     *  @since 0.9.36
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
            if (_log.shouldWarn())
                _log.warn("Failed msg3p2, code " + _msg3p2FailReason + " for " + this);
            _con.failInboundEstablishment(sender, sip_ba, _msg3p2FailReason);
            changeState(State.CORRUPT);
        } else {
            if (_log.shouldDebug()) {
                _log.debug("Finished establishment for " + this +
                          "\n* Generated SipHash key for A -> B: " + Base64.encode(sip_ab) +
                          "\n* Generated SipHash key for B -> A: " + Base64.encode(sip_ba));
            }
            // skew in seconds
            _con.finishInboundEstablishment(sender, rcvr, sip_ba, sip_ab, _peerSkew, _hisPadding);
            changeState(State.VERIFIED);
            if (buf.hasRemaining()) {
                // process "extra" data
                // This is very likely for inbound, as data should come right after message 3
                if (_log.shouldInfo())
                    _log.info("Extra data (" + buf.remaining() + " bytes) on " + this);
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
     *  Get "s" static key out of RI, compare to what we got in the handshake.
     *  Tell NTCPConnection who it is.
     *
     *  @param isHandshake always true
     *  @throws DataFormatException on bad sig, unknown SigType, no static key,
     *                                 static key mismatch, IP checks in verifyInbound()
     *  @since 0.9.36
     */
    public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException {
        // Validate Alice static key
        // find address with matching version
        List<RouterAddress> addrs = ri.getTargetAddresses(NTCPTransport.STYLE, NTCPTransport.STYLE2);
        if (addrs.isEmpty()) {
            _msg3p2FailReason = NTCPConnection.REASON_S_MISMATCH;
            throw new DataFormatException("no NTCP in RI: " + ri);
        }
        String s = null;
        for (RouterAddress addr : addrs) {
            String v = addr.getOption("v");
            if (v == null ||
                (!v.equals(NTCPTransport.NTCP2_VERSION) && !v.startsWith(NTCPTransport.NTCP2_VERSION_ALT))) {
                 continue;
            }
            s = addr.getOption("s");
            if (s != null)
                break;
        }
        if (s == null) {
            _msg3p2FailReason = NTCPConnection.REASON_S_MISMATCH;
            throw new DataFormatException("no s in RI: " + ri);
        }
        byte[] sb = Base64.decode(s);
        if (sb == null || sb.length != KEY_SIZE) {
            _msg3p2FailReason = NTCPConnection.REASON_S_MISMATCH;
            throw new DataFormatException("bad s in RI: " + ri);
        }
        byte[] nb = new byte[32];
        // compare to the _handshakeState
        _handshakeState.getRemotePublicKey().getPublicKey(nb, 0);
        if (!DataHelper.eqCT(sb, 0, nb, 0, KEY_SIZE)) {
            _msg3p2FailReason = NTCPConnection.REASON_S_MISMATCH;
            throw new DataFormatException("s mismatch in RI: " + ri);
        }
        _aliceIdent = ri.getIdentity();
        Hash h = _aliceIdent.calculateHash();
        // this sets the reason
        boolean ok = verifyInbound(h);
        if (!ok)
            throw new DataFormatException("NTCP2 verifyInbound() fail");
        try {
            RouterInfo old = _context.netDb().store(h, ri);
            if (flood && !ri.equals(old)) {
                FloodfillNetworkDatabaseFacade fndf = (FloodfillNetworkDatabaseFacade) _context.netDb();
                if (fndf.floodConditional(ri)) {
                    if (_log.shouldDebug())
                        _log.debug("Flooded the RI: " + h);
                } else {
                    if (_log.shouldInfo())
                        _log.info("Flood request but we didn't: " + h);
                }
            }
        } catch (IllegalArgumentException iae) {
            // sets _msg3p2FailReason
            ok = verifyInboundNetworkID(ri);
            if (!ok)
                throw new DataFormatException("NTCP2 network ID mismatch");
            // hash collision?
            // expired RI?
            _msg3p2FailReason = NTCPConnection.REASON_MSG3;
            throw new DataFormatException("RI store fail: " + ri, iae);
        }
        _con.setRemotePeer(_aliceIdent);
    }

    /** @since 0.9.36 */
    public void gotOptions(byte[] options, boolean isHandshake) {
        NTCP2Options hisPadding = NTCP2Options.fromByteArray(options);
        if (hisPadding == null) {
            if (_log.shouldWarn())
                _log.warn("Received options length " + options.length + " on: " + this);
            return;
        }
        _hisPadding = hisPadding;
    }

    /** @since 0.9.36 */
    public void gotPadding(int paddingLength, int frameLength) {}

    // Following 4 are illegal in handshake, we will never get them

    /** @since 0.9.36 */
    public void gotTermination(int reason, long lastReceived) {}
    /** @since 0.9.36 */
    public void gotUnknown(int type, int len) {}
    /** @since 0.9.36 */
    public void gotDateTime(long time) {}
    /** @since 0.9.36 */
    public void gotI2NP(I2NPMessage msg) {}

    /**
     *  @since 0.9.16
     */
    @Override
    protected synchronized void fail(String reason, Exception e, boolean bySkew) {
        super.fail(reason, e, bySkew);
        if (_handshakeState != null) {
            if (_log.shouldDebug())
                _log.warn("State at Handshake failure: " + _handshakeState.toString());
            _handshakeState.destroy();
        }
    }

    /**
     *  Only call once. Caller must synch.
     *  @since 0.9.16
     */
    @Override
    protected void releaseBufs(boolean isVerified) {
        if (_released)
            return;
        _released = true;
        super.releaseBufs(isVerified);
        // Do not release _curEncrypted if verified, it is passed to
        // NTCPConnection to use as the IV
        if (!isVerified)
            SimpleByteCache.release(_curEncrypted);
        Arrays.fill(_X, (byte) 0);
        SimpleByteCache.release(_X);
        if (_msg3tmp != null) {
            _dataReadBufs.release(_msg3tmp, false);
            _msg3tmp = null;
        }
    }
}
