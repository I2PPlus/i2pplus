package net.i2p.router.transport.udp;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.HKDF;
import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

/**
 *  SSU2 Utils and constants
 *
 *  @since 0.9.54
 */
final class SSU2Util {
    /**
     *  SSU2 protocol version number
     */
    public static final int PROTOCOL_VERSION = 2;

    // lengths
    /** 32 bytes, X25519 public key length */
    public static final int KEY_LEN = EncType.ECIES_X25519.getPubkeyLen();
    /** 16 bytes, MAC tag length */
    public static final int MAC_LEN = 16;
    /** 12 bytes, ChaCha20 nonce length */
    public static final int CHACHA_IV_LEN = 12;
    /** 32 bytes, introductory key length */
    public static final int INTRO_KEY_LEN = 32;
    /** 16 bytes, short (data) header size */
    public static final int SHORT_HEADER_SIZE = 16;
    /** 32 bytes, long (handshake) header size */
    public static final int LONG_HEADER_SIZE = 32;
    /** 64 bytes, combined long header and key */
    public static final int SESSION_HEADER_SIZE = LONG_HEADER_SIZE + KEY_LEN;

    // header fields
    /** Offset of destination connection ID in header */
    public static final int DEST_CONN_ID_OFFSET = 0;
    /** Offset of packet number in header */
    public static final int PKT_NUM_OFFSET = 8;
    /** Length of packet number field in bytes */
    public static final int PKT_NUM_LEN = 4;
    /** Offset of message type in header */
    public static final int TYPE_OFFSET = 12;
    /** Offset of protocol version in header */
    public static final int VERSION_OFFSET = 13;
    /** Offset of short header flags */
    public static final int SHORT_HEADER_FLAGS_OFFSET = 13;
    /** Length of short header flags in bytes */
    public static final int SHORT_HEADER_FLAGS_LEN = 3;
    /** Offset of network ID in header */
    public static final int NETID_OFFSET = 14;
    /** Offset of long header flags */
    public static final int LONG_HEADER_FLAGS_OFFSET = 15;
    /** Offset of source connection ID in long header */
    public static final int SRC_CONN_ID_OFFSET = 16;
    /** Offset of token in long header */
    public static final int TOKEN_OFFSET = 24;

    // header protection
    /** Length of header protection sample in bytes */
    public static final int HEADER_PROT_SAMPLE_LEN = 12;
    /** Total header protection sample length for both samples */
    public static final int TOTAL_PROT_SAMPLE_LEN = 2 * HEADER_PROT_SAMPLE_LEN;
    /** Offset of first header protection sample */
    public static final int HEADER_PROT_SAMPLE_1_OFFSET = 2 * HEADER_PROT_SAMPLE_LEN;
    /** Offset of second header protection sample */
    public static final int HEADER_PROT_SAMPLE_2_OFFSET = HEADER_PROT_SAMPLE_LEN;
    /** Length of header protection data in bytes */
    public static final int HEADER_PROT_DATA_LEN = 8;
    /** Offset of first header protection region (dest conn ID) */
    public static final int HEADER_PROT_1_OFFSET = DEST_CONN_ID_OFFSET;
    /** Offset of second header protection region (packet number) */
    public static final int HEADER_PROT_2_OFFSET = PKT_NUM_OFFSET;

    /** Maximum padding for data packets */
    public static final int PADDING_MAX = 32;
    /** Maximum padding for session request */
    public static final int PADDING_MAX_SESSION_REQUEST = 32;
    /** Maximum padding for session created */
    public static final int PADDING_MAX_SESSION_CREATED = 64;

    // data size minimums, not including IP/UDP headers

    /** Short message type (e.g. data) minimum length in bytes (40). */
    public static final int MIN_DATA_LEN = SHORT_HEADER_SIZE + TOTAL_PROT_SAMPLE_LEN;
    /** Long message type minimum length in bytes (56). */
    public static final int MIN_LONG_DATA_LEN = LONG_HEADER_SIZE + TOTAL_PROT_SAMPLE_LEN;
    /** Handshake message type minimum length in bytes (88). */
    public static final int MIN_HANDSHAKE_DATA_LEN = SESSION_HEADER_SIZE + TOTAL_PROT_SAMPLE_LEN;
    /** Token request minimum length in bytes (56). */
    public static final int MIN_TOKEN_REQUEST_LEN = MIN_LONG_DATA_LEN;
    /** Retry message minimum length in bytes (56). */
    public static final int MIN_RETRY_LEN = MIN_LONG_DATA_LEN;
    /** Session request minimum length in bytes (88). */
    public static final int MIN_SESSION_REQUEST_LEN = MIN_HANDSHAKE_DATA_LEN;
    /** Session created minimum length in bytes (88). */
    public static final int MIN_SESSION_CREATED_LEN = MIN_HANDSHAKE_DATA_LEN;

    /** 3 byte block header */
    public static final int FIRST_FRAGMENT_HEADER_SIZE = SSU2Payload.BLOCK_HEADER_SIZE;

    /**
     * 5 for flag and msg number in followon block
     */
    public static final int DATA_FOLLOWON_EXTRA_SIZE = 5;

    /** 3 byte block header + 4 byte msg ID + 1 byte fragment info = 8 */
    public static final int FOLLOWON_FRAGMENT_HEADER_SIZE = SSU2Payload.BLOCK_HEADER_SIZE + DATA_FOLLOWON_EXTRA_SIZE;

    /** 16 byte short header */
    public static final int DATA_HEADER_SIZE = SHORT_HEADER_SIZE;

    /**
     *  The message types, 0-11, as bytes.
     *  Message type flag for session request.
     */
    public static final byte SESSION_REQUEST_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_SESSION_REQUEST;
    /** Message type flag for session created */
    public static final byte SESSION_CREATED_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_SESSION_CREATED;
    /** Message type flag for session confirmed */
    public static final byte SESSION_CONFIRMED_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_SESSION_CONFIRMED;
    /** Message type flag for data */
    public static final byte DATA_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_DATA;
    /** Message type flag for peer test */
    public static final byte PEER_TEST_FLAG_BYTE = UDPPacket.PAYLOAD_TYPE_TEST;
    /** Message type flag for retry */
    public static final byte RETRY_FLAG_BYTE = 9;
    /** Message type flag for token request */
    public static final byte TOKEN_REQUEST_FLAG_BYTE = 10;
    /** Message type flag for hole punch */
    public static final byte HOLE_PUNCH_FLAG_BYTE = 11;

    // HKDF infos
    /** HKDF info string for session created header key derivation */
    public static final String INFO_CREATED =   "SessCreateHeader";
    /** HKDF info string for session confirmed key derivation */
    public static final String INFO_CONFIRMED = "SessionConfirmed";
    /** HKDF info string for data packet key derivation */
    public static final String INFO_DATA =      "HKDFSSU2DataKeys";

    /** Empty byte array, used as HKDF salt */
    public static final byte[] ZEROLEN = new byte[0];
    /** All-zero key of KEY_LEN bytes */
    public static final byte[] ZEROKEY = new byte[KEY_LEN];

    // relay and peer test
    // Signature prologues
    /** Prologue for relay request signatures */
    public static final byte[] RELAY_REQUEST_PROLOGUE = DataHelper.getASCII("RelayRequestData");
    /** Prologue for relay response signatures */
    public static final byte[] RELAY_RESPONSE_PROLOGUE = DataHelper.getASCII("RelayAgreementOK");
    /** Prologue for peer test signatures */
    public static final byte[] PEER_TEST_PROLOGUE = DataHelper.getASCII("PeerTestValidate");

    // test status codes
    /** Peer test accepted */
    public static final int TEST_ACCEPT = 0;
    /** Rejected by Bob: unspecified reason */
    public static final int TEST_REJECT_BOB_UNSPEC = 1;
    /** Rejected by Bob: no Charlie */
    public static final int TEST_REJECT_BOB_NO_CHARLIE = 2;
    /** Rejected by Bob: limit reached */
    public static final int TEST_REJECT_BOB_LIMIT = 3;
    /** Rejected by Bob: signature failure */
    public static final int TEST_REJECT_BOB_SIGFAIL = 4;
    /** Rejected by Bob: address mismatch */
    public static final int TEST_REJECT_BOB_ADDRESS = 5;
    /** Rejected by Charlie: unspecified reason */
    public static final int TEST_REJECT_CHARLIE_UNSPEC = 64;
    /** Rejected by Charlie: address mismatch */
    public static final int TEST_REJECT_CHARLIE_ADDRESS = 65;
    /** Rejected by Charlie: limit reached */
    public static final int TEST_REJECT_CHARLIE_LIMIT = 66;
    /** Rejected by Charlie: signature failure */
    public static final int TEST_REJECT_CHARLIE_SIGFAIL = 67;
    /** Rejected by Charlie: already connected */
    public static final int TEST_REJECT_CHARLIE_CONNECTED = 68;
    /** Rejected by Charlie: banned */
    public static final int TEST_REJECT_CHARLIE_BANNED = 69;
    /** Rejected by Charlie: unknown Alice */
    public static final int TEST_REJECT_CHARLIE_UNKNOWN_ALICE = 70;

    // relay status codes
    /** Relay accepted */
    public static final int RELAY_ACCEPT = 0;
    /** Rejected by Bob: unspecified reason */
    public static final int RELAY_REJECT_BOB_UNSPEC = 1;
    /** Rejected by Bob: Charlie is banned */
    public static final int RELAY_REJECT_BOB_BANNED_CHARLIE = 2;
    /** Rejected by Bob: limit reached */
    public static final int RELAY_REJECT_BOB_LIMIT = 3;
    /** Rejected by Bob: signature failure */
    public static final int RELAY_REJECT_BOB_SIGFAIL = 4;
    /** Rejected by Bob: no tag found */
    public static final int RELAY_REJECT_BOB_NO_TAG = 5;
    /** Rejected by Bob: unknown Alice */
    public static final int RELAY_REJECT_BOB_UNKNOWN_ALICE = 6;
    /** Rejected by Charlie: unspecified reason */
    public static final int RELAY_REJECT_CHARLIE_UNSPEC = 64;
    /** Rejected by Charlie: address mismatch */
    public static final int RELAY_REJECT_CHARLIE_ADDRESS = 65;
    /** Rejected by Charlie: limit reached */
    public static final int RELAY_REJECT_CHARLIE_LIMIT = 66;
    /** Rejected by Charlie: signature failure */
    public static final int RELAY_REJECT_CHARLIE_SIGFAIL = 67;
    /** Rejected by Charlie: already connected */
    public static final int RELAY_REJECT_CHARLIE_CONNECTED = 68;
    /** Rejected by Charlie: banned */
    public static final int RELAY_REJECT_CHARLIE_BANNED = 69;
    /** Rejected by Charlie: unknown Alice */
    public static final int RELAY_REJECT_CHARLIE_UNKNOWN_ALICE = 70;

    // termination reason codes
    /** Unspecified reason */
    public static final int REASON_UNSPEC = 0;
    /** Termination requested by remote */
    public static final int REASON_TERMINATION = 1;
    /** Connection timeout */
    public static final int REASON_TIMEOUT = 2;
    /** Router shutting down */
    public static final int REASON_SHUTDOWN = 3;
    /** AEAD verification failure */
    public static final int REASON_AEAD = 4;
    /** Options parameter mismatch */
    public static final int REASON_OPTIONS = 5;
    /** Signature type error or mismatch */
    public static final int REASON_SIGTYPE = 6;
    /** Clock skew exceeds tolerance */
    public static final int REASON_SKEW = 7;
    /** Invalid padding */
    public static final int REASON_PADDING = 8;
    /** Framing protocol error */
    public static final int REASON_FRAMING = 9;
    /** Payload data error */
    public static final int REASON_PAYLOAD = 10;
    /** Error in session request message */
    public static final int REASON_MSG1 = 11;
    /** Error in session created message */
    public static final int REASON_MSG2 = 12;
    /** Error in session confirmed message */
    public static final int REASON_MSG3 = 13;
    /** Frame acknowledgment timeout */
    public static final int REASON_FRAME_TIMEOUT = 14;
    /** Signature verification failed */
    public static final int REASON_SIGFAIL = 15;
    /** Session key mismatch */
    public static final int REASON_S_MISMATCH = 16;
    /** Peer is banned */
    public static final int REASON_BANNED = 17;
    /** Token validation error */
    public static final int REASON_TOKEN = 18;
    /** Resource limits exceeded */
    public static final int REASON_LIMITS = 19;
    /** Protocol version mismatch */
    public static final int REASON_VERSION = 20;
    /** Network ID mismatch */
    public static final int REASON_NETID = 21;
    /** Session was replaced by a new connection */
    public static final int REASON_REPLACED = 22;

    /**
     *  Convert a termination reason code to a human-readable string
     *
     *  @param code one of the REASON_* constants
     *  @return human-readable description
     */
    public static String terminationCodeToString(int code) {
        switch (code) {
            case REASON_UNSPEC:          return "Unspecified reason";
            case REASON_TERMINATION:     return "Termination requested";
            case REASON_TIMEOUT:         return "Timeout occurred";
            case REASON_SHUTDOWN:        return "Shutdown in progress";
            case REASON_AEAD:            return "AEAD verification failure";
            case REASON_OPTIONS:         return "Options mismatch";
            case REASON_SIGTYPE:         return "Signature type error";
            case REASON_SKEW:            return "Clock skew too large";
            case REASON_PADDING:         return "Padding error";
            case REASON_FRAMING:         return "Framing error";
            case REASON_PAYLOAD:         return "Payload error";
            case REASON_MSG1:            return "Message 1 error";
            case REASON_MSG2:            return "Message 2 error";
            case REASON_MSG3:            return "Message 3 error";
            case REASON_FRAME_TIMEOUT:   return "Frame timeout";
            case REASON_SIGFAIL:         return "Signature verification failed";
            case REASON_S_MISMATCH:      return "Session mismatch";
            case REASON_BANNED:          return "Banned";
            case REASON_TOKEN:           return "Token error";
            case REASON_LIMITS:          return "Resource limits exceeded";
            case REASON_VERSION:         return "Protocol version mismatch";
            case REASON_NETID:           return "Network ID mismatch";
            case REASON_REPLACED:        return "Session replaced";
            default:                     return "Unknown termination reason: " + code;
        }
    }

    private SSU2Util() { /* no-op */ }

    /**
     *  32 byte output, ZEROLEN data
     */
    public static byte[] hkdf(I2PAppContext ctx, byte[] key, String info) {
        HKDF hkdf = new HKDF(ctx);
        byte[] rv = new byte[32];
        hkdf.calculate(key, ZEROLEN, info, rv);
        return rv;
    }

    /**
     *  Make the data for the peer test block
     *
     *  @param h to be included in sig, not included in data
     *  @param h2 may be null, to be included in sig, not included in data
     *  @param role unused
     *  @param ip may be null
     *  @return null on failure
     */
    public static byte[] createPeerTestData(I2PAppContext ctx, Hash h, Hash h2,
                                            PeerTestState.Role role, long nonce, byte[] ip, int port,
                                            SigningPrivateKey spk) {
        int datalen = 12 + (ip != null ? ip.length : 0);
        byte[] data = new byte[datalen + spk.getType().getSigLen()];
        data[0] = 2;  // version
        DataHelper.toLong(data, 1, 4, nonce);
        DataHelper.toLong(data, 5, 4, ctx.clock().now() / 1000);
        int iplen = (ip != null) ? ip.length : 0;
        data[9] = (byte) (ip != null ? iplen + 2 : 0);
        if (ip != null) {
            DataHelper.toLong(data, 10, 2, port);
            System.arraycopy(ip, 0, data, 12, iplen);
        }
        Signature sig = sign(ctx, PEER_TEST_PROLOGUE, h, h2, data, datalen, spk);
        if (sig == null)
            return null;
        byte[] s = sig.getData();
        System.arraycopy(s, 0, data, datalen, s.length);
        return data;
    }

    /**
     *  Make the data for the relay request block
     *
     *  @param h Bob hash to be included in sig, not included in data
     *  @param h2 Charlie hash to be included in sig, not included in data
     *  @param ip non-null
     *  @return null on failure
     *  @since 0.9.55
     */
    public static byte[] createRelayRequestData(I2PAppContext ctx, Hash h, Hash h2,
                                                long nonce, long tag, byte[] ip, int port,
                                                SigningPrivateKey spk) {
        int datalen = 16 + ip.length;
        byte[] data = new byte[datalen];
        DataHelper.toLong(data, 0, 4, nonce);
        DataHelper.toLong(data, 4, 4, tag);
        DataHelper.toLong(data, 8, 4, ctx.clock().now() / 1000);
        data[12] = 2;  // version
        data[13] = (byte) (ip.length + 2);
        DataHelper.toLong(data, 14, 2, port);
        System.arraycopy(ip, 0, data, 16, ip.length);
        Signature sig = sign(ctx, RELAY_REQUEST_PROLOGUE, h, h2, data, datalen, spk);
        if (sig == null)
            return null;
        int len = 1 + datalen + spk.getType().getSigLen();
        byte[] rv = new byte[len];
        System.arraycopy(data, 0, rv, 1, data.length);
        byte[] s = sig.getData();
        System.arraycopy(s, 0, rv, 1 + datalen, s.length);
        return rv;
    }

    /**
     *  Make the data for the relay response block
     *
     *  @param h Bob hash to be included in sig, not included in data
     *  @param ip may be null
     *  @param port the UDP port number if ip is null
     *  @param token if nonzero, append it
     *  @return null on failure
     *  @since 0.9.55
     */
    public static byte[] createRelayResponseData(I2PAppContext ctx, Hash h, int code,
                                                 long nonce, byte[] ip, int port,
                                                 SigningPrivateKey spk, long token) {
        int datalen = 10;
        if (ip != null)
            datalen += 2 + ip.length;
        byte[] data = new byte[datalen];
        DataHelper.toLong(data, 0, 4, nonce);
        DataHelper.toLong(data, 4, 4, ctx.clock().now() / 1000);
        data[8] = 2;  // version
        if (ip != null) {
            data[9] = (byte) (ip.length + 2);
            DataHelper.toLong(data, 10, 2, port);
            System.arraycopy(ip, 0, data, 12, ip.length);
        } else {
            // data[9] = 0;
        }
        Signature sig = sign(ctx, RELAY_RESPONSE_PROLOGUE, h, null, data, datalen, spk);
        if (sig == null)
            return null;
        int len = 2 + datalen + spk.getType().getSigLen();
        if (token != 0)
            len += 8;
        byte[] rv = new byte[len];
        rv[1] = (byte) code;
        System.arraycopy(data, 0, rv, 2, data.length);
        byte[] s = sig.getData();
        System.arraycopy(s, 0, rv, 2 + datalen, s.length);
        if (token != 0)
            DataHelper.toLong8(rv, 2 + datalen + s.length, token);
        return rv;
    }

    /**
     *  Sign the relay or peer test data, using
     *  the prologue and hash as the initial data,
     *  and then the provided data.
     *
     *  @param data if desired, leave room at end for sig
     *  @param datalen the length of the data to be signed
     *  @param h to be included in sig, not included in data
     *  @param h2 may be null, to be included in sig, not included in data
     *  @return null on failure
     */
    public static Signature sign(I2PAppContext ctx, byte[] prologue, Hash h, Hash h2,
                                 byte[] data, int datalen, SigningPrivateKey spk) {
        int len = prologue.length + Hash.HASH_LENGTH + datalen;
        if (h2 != null)
            len += Hash.HASH_LENGTH;
        byte[] buf = new byte[len];
        System.arraycopy(prologue, 0, buf, 0, prologue.length);
        System.arraycopy(h.getData(), 0, buf, prologue.length, Hash.HASH_LENGTH);
        int off = prologue.length + Hash.HASH_LENGTH;
        if (h2 != null) {
            System.arraycopy(h2.getData(), 0, buf, off, Hash.HASH_LENGTH);
            off += Hash.HASH_LENGTH;
        }
        System.arraycopy(data, 0, buf, off, datalen);
        return ctx.dsa().sign(buf, spk);
    }

    /**
     *  Validate the signed relay or peer test data, using
     *  the prologue and hash as the initial data,
     *  and then the provided data which ends with a signature of the specified type.
     *
     *  @param h2 may be null
     *  @param data not including relay response token
     */
    public static boolean validateSig(I2PAppContext ctx, byte[] prologue, Hash h, Hash h2, byte[] data, SigningPublicKey spk) {
        if (h == null) return false;
        SigType type = spk.getType();
        int siglen = type.getSigLen();
        int len = prologue.length + Hash.HASH_LENGTH + data.length - siglen;
        if (h2 != null)
            len += Hash.HASH_LENGTH;
        byte[] buf = new byte[len];
        System.arraycopy(prologue, 0, buf, 0, prologue.length);
        System.arraycopy(h.getData(), 0, buf, prologue.length, Hash.HASH_LENGTH);
        int off = prologue.length + Hash.HASH_LENGTH;
        if (h2 != null) {
            System.arraycopy(h2.getData(), 0, buf, off, Hash.HASH_LENGTH);
            off += Hash.HASH_LENGTH;
        }
        System.arraycopy(data, 0, buf, off, data.length - siglen);
        byte[] bsig = new byte[siglen];
        System.arraycopy(data, data.length - siglen, bsig, 0, siglen);
        Signature sig = new Signature(type, bsig);
        return ctx.dsa().verifySignature(sig, buf, spk);
    }
}
