package net.i2p.router.transport.udp;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.Signature;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageImpl;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.util.Log;

/**
 * SSU2 Payload generation and parsing utilities.
 *
 *  @since 0.9.54
 */
class SSU2Payload {

    /** SSU2 block type: ACK */
    static final int BLOCK_ACK = 12;
    /** SSU2 block type: ADDRESS */
    static final int BLOCK_ADDRESS = 13;
    /** SSU2 block type: DATETIME (must be the first block of a handshake) */
    static final int BLOCK_DATETIME = 0;
    /** SSU2 block type: FIRSTFRAG */
    static final int BLOCK_FIRSTFRAG = 4;
    /** SSU2 block type: FOLLOWONFRAG */
    static final int BLOCK_FOLLOWONFRAG = 5;
    /** SSU2 block type: I2NP */
    static final int BLOCK_I2NP = 3;
    /** SSU2 block type: NEWTOKEN */
    static final int BLOCK_NEWTOKEN = 17;
    /** SSU2 block type: OPTIONS */
    static final int BLOCK_OPTIONS = 1;
    /** SSU2 block type: PADDING (must end the block list) */
    static final int BLOCK_PADDING = 254;
    /** SSU2 block type: PATHCHALLENGE */
    static final int BLOCK_PATHCHALLENGE = 18;
    /** SSU2 block type: PATHRESP */
    static final int BLOCK_PATHRESP = 19;
    /** SSU2 block type: PEERTEST */
    static final int BLOCK_PEERTEST = 10;
    /** SSU2 block type: RELAYINTRO */
    static final int BLOCK_RELAYINTRO = 9;
    /** SSU2 block type: RELAYREQ */
    static final int BLOCK_RELAYREQ = 7;
    /** SSU2 block type: RELAYRESP */
    static final int BLOCK_RELAYRESP = 8;
    /** SSU2 block type: RELAYTAG */
    static final int BLOCK_RELAYTAG = 16;
    /** SSU2 block type: RELAYTAGREQ */
    static final int BLOCK_RELAYTAGREQ = 15;
    /** SSU2 block type: ROUTERINFO */
    static final int BLOCK_ROUTERINFO = 2;
    /**
     * BLOCK_HEADER_SIZE.
     */
    public static final int BLOCK_HEADER_SIZE = 3;
    /**
     * BLOCK_TERMINATION.
     */
    public static final int BLOCK_TERMINATION = 6;

    /**
     *  For all callbacks, recommend throwing exceptions only from the handshake.
     *  Exceptions will get thrown out of processPayload() and prevent
     *  processing of succeeding blocks.
     */
    public interface PayloadCallback {
        /**
         * Receive a date/time synchronization block.
         * @param time time in milliseconds since epoch
         */
        public void gotDateTime(long time) throws DataFormatException;

        /**
         * Receive an I2NP message block.
         * @param msg the parsed I2NP message
         */
        public void gotI2NP(I2NPMessage msg) throws I2NPMessageException;

        /**
         * Receive a fragment of a fragmented message.
         * Data must be copied out in this method.
         * Data starts at the 9 byte header for fragment 0.
         *
         * @param data buffer containing fragment data
         * @param off offset in data
         * @param len length of data to copy
         * @param messageID unique message identifier
         * @param frag fragment number (0-based)
         * @param isLast whether this is the last fragment
         */
        public void gotFragment(byte[] data, int off, int len, long messageID, int frag, boolean isLast) throws DataFormatException;

        /**
         * Receive an acknowledgment block.
         * @param ackThru highest contiguous sequence number acknowledged
         * @param acks number of additional acknowledgments beyond ackThru
         * @param ranges null if none
         */
        public void gotACK(long ackThru, int acks, byte[] ranges);

        /**
         * Receive a session options block.
         * @param options the option data
         * @param isHandshake true only for message 3 part 2
         */
        public void gotOptions(byte[] options, boolean isHandshake) throws DataFormatException;

        /**
         * Receive a complete RouterInfo block.
         * @param ri will already be validated
         * @param isHandshake true only for message 3 part 2
         * @param flood true if this is a floodfill router
         */
        public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException;

        /**
         * Receive a fragment of a RouterInfo block.
         * @param data is first gzipped and then fragmented
         * @param isHandshake true only for message 3 part 2
         * @param flood true if this is a floodfill router
         * @param isGzipped true if the data is gzipped
         * @param frag fragment number (0-based)
         * @param totalFrags total number of fragments
         */
        public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags);

        /**
         * Receive an address block.
         * @param ip IP address bytes
         * @param port port number
         */
        public void gotAddress(byte[] ip, int port);

        /**
         * Receive a relay tag request.
         */
        public void gotRelayTagRequest();

        /**
         * Receive a relay tag.
         * @param tag the relay tag
         */
        public void gotRelayTag(long tag);

        /**
         * Receive a relay request.
         * @param data excludes flag, includes signature
         */
        public void gotRelayRequest(byte[] data);

        /**
         * Receive a relay response.
         * @param status 0 = accept, 1-255 = reject
         * @param data excludes flag, includes signature
         */
        public void gotRelayResponse(int status, byte[] data);

        /**
         * Receive a relay introduction.
         * @param aliceHash hash of the introducer
         * @param data excludes flag, includes signature
         */
        public void gotRelayIntro(Hash aliceHash, byte[] data);

        /**
         * Receive a peer test block.
         * @param msg 1-7
         * @param status 0 = accept, 1-255 = reject
         * @param h Alice or Charlie hash for msg 2 and 4, null for msg 1, 3, 5-7
         * @param data excludes flag, includes signature
         */
        public void gotPeerTest(int msg, int status, Hash h, byte[] data);

        /**
         * Receive a new session token.
         * @param token the token value
         * @param expires token expiration time in milliseconds
         */
        public void gotToken(long token, long expires);

        /**
         * Receive a session termination block.
         * @param reason termination reason code
         * @param lastReceived in theory could wrap around to negative, but very unlikely
         */
        public void gotTermination(int reason, long lastReceived);

        /**
         * Receive a path challenge block.
         * @param from null if unknown
         * @since 0.9.55
         */
        public void gotPathChallenge(RemoteHostId from, byte[] data);

        /**
         * Receive a path response block.
         * @param from null if unknown
         * @since 0.9.55
         */
        public void gotPathResponse(RemoteHostId from, byte[] data);
    }

    /**
     *  Incoming payload. Calls the callback for each received block.
     *
     *  @param isHandshake true for Token Req, Retry, Sess Req, Sess Created; false for Sess Confirmed
     *  @param from for path challenge/response only, may be null
     *  @return number of blocks processed
     *  @throws IOException on major errors
     *  @throws DataFormatException on parsing of individual blocks
     *  @throws I2NPMessageException on parsing of I2NP block
     */
    public static int processPayload(I2PAppContext ctx, PayloadCallback cb,
                                     byte[] payload, int off, int length, boolean isHandshake, RemoteHostId from)
                                     throws IOException, DataFormatException, I2NPMessageException {
        int blocks = 0;
        boolean gotPadding = false;
        boolean gotTermination = false;
        int i = off;
        final int end = off + length;
        while (i < end) {
            int type = payload[i++] & 0xff;
            checkBlockOrder(type, gotPadding, gotTermination, isHandshake, blocks);
            int len = (int) DataHelper.fromLong(payload, i, 2);
            i += 2;
            checkFrameOverflow(payload, off, length, i, len, blocks, type);
            switch (type) {
                case BLOCK_DATETIME: parseDateTime(cb, payload, i, len); break;
                case BLOCK_OPTIONS: parseOptions(cb, payload, i, len, isHandshake); break;
                case BLOCK_ROUTERINFO: parseRouterInfo(ctx, cb, payload, i, len, isHandshake); break;
                case BLOCK_I2NP: parseI2NP(ctx, cb, payload, i, len, isHandshake); break;
                case BLOCK_FIRSTFRAG: parseFirstFragment(cb, payload, i, len, isHandshake); break;
                case BLOCK_FOLLOWONFRAG: parseFollowonFragment(cb, payload, i, len, isHandshake); break;
                case BLOCK_ACK: parseACK(cb, payload, i, len, isHandshake); break;
                case BLOCK_ADDRESS: parseAddress(cb, payload, i, len); break;
                case BLOCK_RELAYTAGREQ: cb.gotRelayTagRequest(); break;
                case BLOCK_RELAYTAG: parseRelayTag(cb, payload, i, len); break;
                case BLOCK_RELAYREQ: parseRelayRequest(cb, payload, i, len, isHandshake); break;
                case BLOCK_RELAYRESP: parseRelayResponse(cb, payload, i, len, isHandshake); break;
                case BLOCK_RELAYINTRO: parseRelayIntro(cb, payload, i, len, isHandshake); break;
                case BLOCK_PEERTEST: parsePeerTest(cb, payload, i, len, isHandshake); break;
                case BLOCK_NEWTOKEN: parseNewToken(cb, payload, i, len); break;
                case BLOCK_TERMINATION: parseTermination(cb, payload, i, len); gotTermination = true; break;
                case BLOCK_PATHCHALLENGE: parsePathChallenge(cb, payload, i, len, isHandshake, from); break;
                case BLOCK_PATHRESP: parsePathResponse(cb, payload, i, len, isHandshake, from); break;
                case BLOCK_PADDING: gotPadding = true; break;
                default: parseUnknown(ctx, cb, type, len); break;
            }
            // don't modify i inside switch
            i += len;
            blocks++;
        }
        checkHandshakeNotEmpty(isHandshake, blocks);
        return blocks;
    }

    /**
     *  Order-rule guard for the incoming-block scan.
     *  Padding must terminate the block list; only padding may follow a
     *  termination block; a handshake must start with a DATETIME block.
     *  Each violation is a protocol error, thrown as an IOException.
     *
     *  @param type the current block type byte
     *  @param gotPadding true if a BLOCK_PADDING preceded this block
     *  @param gotTermination true if a BLOCK_TERMINATION preceded this block
     *  @param isHandshake true if this is a handshake frame, where the first block must be DATETIME
     *  @param blocks the number of blocks processed before this one
     *  @throws IOException on any ordering violation
     *  @since 0.9.71+
     */
    static void checkBlockOrder(int type, boolean gotPadding, boolean gotTermination,
                                boolean isHandshake, int blocks) throws IOException {
        if (gotPadding)
            throw new IOException("Illegal block after padding: " + type);
        if (gotTermination && type != BLOCK_PADDING)
            throw new IOException("Illegal block after termination: " + type);
        if (isHandshake && blocks == 0 && type != BLOCK_DATETIME)
            throw new IOException("Illegal first block in handshake: " + type);
    }

    /**
     *  Guard that a handshake frame carries at least one block.
     *
     *  @param isHandshake true if this is a handshake frame
     *  @param blocks the total number of blocks processed in the frame
     *  @throws IOException if the frame is a handshake with no blocks
     *  @since 0.9.71+
     */
    static void checkHandshakeNotEmpty(boolean isHandshake, int blocks) throws IOException {
        if (isHandshake && blocks == 0)
            throw new IOException("No blocks in handshake");
    }

    /**
     *  Guard that a block's declared length fits inside the frame.
     *  Offsets in the error message are byte positions relative to the frame.
     *
     *  @param payload the whole frame
     *  @param off start of the payload in the array
     *  @param length frame size in bytes
     *  @param dataOff offset of the block's 2-byte length field
     *  @param len the declared block length
     *  @param blocks the zero-based index of this block for the error message
     *  @param type the block type byte for the error message
     *  @throws IOException if the block runs past the end of the frame
     *  @since 0.9.71+
     */
    static void checkFrameOverflow(byte[] payload, int off, int length, int dataOff, int len,
                                   int blocks, int type) throws IOException {
        if (dataOff + len > off + length)
            throw new IOException("Block " + blocks + " type " + type + " length " + len +
                                  " at offset " + (dataOff - 3 - off) + " runs over frame of size " + length +
                                  '\n' + net.i2p.util.HexDump.dump(payload, off, length));
    }

    /**
     *  Parse a DATETIME block of exactly 4 bytes.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be 4
     *  @throws IOException on bad length
     *  @throws DataFormatException if the callback rejects the timestamp
     *  @since 0.9.71+
     */
    static void parseDateTime(PayloadCallback cb, byte[] payload, int i, int len) throws IOException, DataFormatException {
        if (len != 4)
            throw new IOException("Bad length for DATETIME: " + len);
        long time = DataHelper.fromLong(payload, i, 4) * 1000;
        cb.gotDateTime(time);
    }

    /**
     *  Parse an OPTIONS block, copying the option bytes for the callback.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length
     *  @param isHandshake passed through to the callback
     *  @throws DataFormatException if the callback rejects the options
     *  @since 0.9.71+
     */
    static void parseOptions(PayloadCallback cb, byte[] payload, int i, int len, boolean isHandshake) throws DataFormatException {
        byte[] options = new byte[len];
        System.arraycopy(payload, i, options, 0, len);
        cb.gotOptions(options, isHandshake);
    }

    /**
     *  Parse a ROUTERINFO block: one- or two-byte header, then either a single
     *  (possibly compressed) RouterInfo or a fragment piece.
     *
     *  @param ctx context for decompression and logging
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be &gt; 1
     *  @param isHandshake passed through to the callback
     *  @throws IOException on framing errors
     *  @throws DataFormatException if the RouterInfo is too large or fails verification
     *  @since 0.9.71+
     */
    static void parseRouterInfo(I2PAppContext ctx, PayloadCallback cb, byte[] payload, int i, int len, boolean isHandshake)
                                throws IOException, DataFormatException {
        int flag = payload[i] & 0xff;
        boolean flood = (flag & 0x01) != 0;
        boolean gz = (flag & 0x02) != 0;
        int frag = payload[i + 1] & 0xff;
        int fnum = frag >> 4;
        int ftot = frag & 0x0f;
        if (ftot == 0)
            throw new IOException("Bad fragment count for ROUTERINFO: " + ftot);
        if (fnum == 0 && ftot == 1) {
            ByteArrayInputStream bais;
            if (gz) {
                byte[] decompressed = DataHelper.decompress(payload, i + 2, len - 2);
                if (decompressed.length > RouterInfo.MAX_UNCOMPRESSED_SIZE)
                    throw new DataFormatException("RouterInfo too big: " + decompressed.length);
                bais = new ByteArrayInputStream(decompressed);
            } else {
                if (len - 2 > RouterInfo.MAX_UNCOMPRESSED_SIZE)
                    throw new DataFormatException("RouterInfo too big: " + (len - 2));
                bais = new ByteArrayInputStream(payload, i + 2, len - 2);
            }
            if (bais.available() >= 3*1024)
                flood = false;
            RouterInfo alice = parseRouterInfoSingle(ctx, bais);
            cb.gotRI(alice, isHandshake, flood);
        } else {
            byte[] data = new byte[len - 2];
            System.arraycopy(payload, i + 2, data, 0, len - 2);
            cb.gotRIFragment(data, isHandshake, flood, gz, fnum, ftot);
        }
    }

    /**
     *  Read a complete RouterInfo from the stream, falling back to an
     *  alternate signature verification when the strict parse fails.
     *
     *  @param ctx context for logging
     *  @param bais the RI bytes, positioned at the start
     *  @return the parsed RouterInfo (possibly a partially filled-in one, signalled by {@link RouterInfo#setPublished(long)} of -1)
     *  @throws IOException on any I/O while reading the stream
     *  @throws DataFormatException on a genuinely malformed RI that fails the alternate verification
     *  @since 0.9.71+
     */
    static RouterInfo parseRouterInfoSingle(I2PAppContext ctx, ByteArrayInputStream bais) throws IOException, DataFormatException {
        RouterInfo alice = new RouterInfo();
        try {alice.readBytes(bais, true);}
        catch (DataFormatException dfe) {
            alice = recoverRouterInfo(ctx, bais, dfe);
        }
        return alice;
    }

    /**
     *  Alternate verification of a RouterInfo whose strict parse failed: if the
     *  signed prefix is valid, return a partially filled-in RI (published = -1)
     *  so the session layer can still act on the identity.
     *
     *  @param ctx context for logging
     *  @param bais the RI bytes; position is reset for re-reading identity and signature
     *  @param dfe the original parse failure
     *  @return a partially filled-in RouterInfo when the signature verifies
     *  @throws IOException on any I/O while re-reading the stream
     *  @throws DataFormatException the original parse failure when verification fails
     *  @since 0.9.71+
     */
    static RouterInfo recoverRouterInfo(I2PAppContext ctx, ByteArrayInputStream bais, DataFormatException dfe)
                                        throws IOException, DataFormatException {
        bais.reset();
        RouterIdentity ident = new RouterIdentity();
        ident.readBytes(bais);
        SigningPublicKey pub = ident.getSigningPublicKey();
        SigType st = pub.getType();
        if (st == null) {throw dfe;}
        bais.reset();
        byte[] data = new byte[bais.available() - st.getSigLen()];
        bais.read(data);
        Signature sig = new Signature(st);
        sig.readBytes(bais);
        if (DSAEngine.getInstance().verifySignature(sig, data, pub)) {
            Log log = ctx.logManager().getLog(SSU2Payload.class);
            if (log.shouldDebug()) {log.warn("Error reading RouterInfo", dfe);}
            else if (log.shouldWarn()) {log.warn("Error reading RouterInfo -> " + dfe.getMessage());}
            // partially filled-in RI, -1 is signal to IES2.gotRI()
            RouterInfo alice = new RouterInfo();
            alice.setIdentity(ident);
            alice.setPublished(-1);
            return alice;
        } else {throw dfe;} // bad sig, just throw dfe
    }

    /**
     *  Parse an I2NP block (data blocks only, never in handshakes).
     *  @param ctx context for I2NP parsing
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be &ge; 9
     *  @param isHandshake if true the block is illegal
     *  @throws IOException if the block appears in a handshake or is too short
     *  @throws I2NPMessageException if the embedded I2NP message is malformed
     *  @since 0.9.71+
     */
    static void parseI2NP(I2PAppContext ctx, PayloadCallback cb, byte[] payload, int i, int len, boolean isHandshake)
                          throws IOException, I2NPMessageException {
        if (isHandshake)
            throw new IOException("Illegal block in handshake: " + BLOCK_I2NP);
        if (len < 9)
            throw new IOException("I2NP block too short: " + len);
        I2NPMessage msg = I2NPMessageImpl.fromRawByteArrayNTCP2(ctx, payload, i, len, null);
        cb.gotI2NP(msg);
    }

    /**
     *  Parse the first fragment of a fragmented message (data blocks only).
     *  The 1-byte flag and 4-byte message ID precede the fragment data; the
     *  callback receives the raw payload slice.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be &gt; 9
     *  @param isHandshake if true the block is illegal
     *  @throws IOException if the block appears in a handshake or is too short
     *  @throws DataFormatException if the callback rejects the fragment
     *  @since 0.9.71+
     */
    static void parseFirstFragment(PayloadCallback cb, byte[] payload, int i, int len, boolean isHandshake)
                                   throws IOException, DataFormatException {
        if (isHandshake)
            throw new IOException("Illegal block in handshake: " + BLOCK_FIRSTFRAG);
        if (len <= 9)
            throw new IOException("Bad length for FIRSTFRAG: " + len);
        long id = DataHelper.fromLong(payload, i + 1, 4);
        cb.gotFragment(payload, i, len, id, 0, false);
    }

    /**
     *  Parse a follow-on fragment block (data blocks only).
     *  The low bit of the flag byte marks the last fragment; the remaining bits
     *  carry the fragment number.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be &gt; 5
     *  @param isHandshake if true the block is illegal
     *  @throws IOException if the block appears in a handshake, is too short, or has fragment number 0
     *  @throws DataFormatException if the callback rejects the fragment
     *  @since 0.9.71+
     */
    static void parseFollowonFragment(PayloadCallback cb, byte[] payload, int i, int len, boolean isHandshake)
                                      throws IOException, DataFormatException {
        if (isHandshake)
            throw new IOException("Illegal block in handshake: " + BLOCK_FOLLOWONFRAG);
        if (len <= 5)
            throw new IOException("Bad length for FOLLOWON: " + len);
        int frag = (payload[i] & 0xff) >> 1;
        if (frag == 0)
            throw new IOException("0 frag for FOLLOWON");
        boolean isLast = (payload[i] & 0x01) != 0;
        long id = DataHelper.fromLong(payload, i + 1, 4);
        cb.gotFragment(payload, i + 5, len - 5, id, frag, isLast);
    }

    /**
     *  Parse an ACK block (data blocks only).
     *  Odd block length: 4-byte ack ID, 1-byte fragment count, then the ACK
     *  range bitmap (possibly zero length).
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be &ge; 5 and odd
     *  @param isHandshake if true the block is illegal
     *  @throws IOException if the block appears in a handshake or has a bad length
     *  @since 0.9.71+
     */
    static void parseACK(PayloadCallback cb, byte[] payload, int i, int len, boolean isHandshake) throws IOException {
        if (isHandshake)
            throw new IOException("Illegal block in handshake: " + BLOCK_ACK);
        if (len < 5 || (len & 1) != 1)
            throw new IOException("Bad length for ACK: " + len);
        long ack = DataHelper.fromLong(payload, i, 4);
        int acnt = payload[i + 4] & 0xff;
        int rcnt = len - 5;
        byte[] ranges;
        if (rcnt > 0) {
            ranges = new byte[rcnt];
            System.arraycopy(payload, i + 5, ranges, 0, rcnt);
        } else {
            ranges = null;
        }
        cb.gotACK(ack, acnt, ranges);
    }

    /**
     *  Parse an ADDRESS block of 4 bytes IPv4 or 16 bytes IPv6.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be 6 or 18
     *  @throws IOException on bad length
     *  @since 0.9.71+
     */
    static void parseAddress(PayloadCallback cb, byte[] payload, int i, int len) throws IOException {
        if (len != 6 && len != 18)
            throw new IOException("Bad length for Address: " + len);
        int port = (int) DataHelper.fromLong(payload, i, 2);
        byte[] ip = new byte[len - 2];
        System.arraycopy(payload, i + 2, ip, 0, len - 2);
        cb.gotAddress(ip, port);
    }

    /**
     *  Parse a RELAYTAG block of at least 4 bytes.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be &ge; 4
     *  @throws IOException on bad length
     *  @since 0.9.71+
     */
    static void parseRelayTag(PayloadCallback cb, byte[] payload, int i, int len) throws IOException {
        if (len < 4)
            throw new IOException("Bad length for RELAYTAG: " + len);
        long tag = DataHelper.fromLong(payload, i, 4);
        cb.gotRelayTag(tag);
    }

    /**
     *  Parse a RELAYREQ block (data blocks only): 1 flag byte then the relay
     *  request (IPv4 address + DSA signature).
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be &ge; 61
     *  @param isHandshake if true the block is illegal
     *  @throws IOException if the block appears in a handshake or is too short
     *  @since 0.9.71+
     */
    static void parseRelayRequest(PayloadCallback cb, byte[] payload, int i, int len, boolean isHandshake)
                                  throws IOException {
        if (isHandshake)
            throw new IOException("Illegal block in handshake: " + BLOCK_RELAYREQ);
        if (len < 61) // 21 byte data w/ IPv4 + 40 byte DSA sig
            throw new IOException("Bad length for RELAYREQ: " + len);
        byte[] data = new byte[len - 1]; // skip flag
        System.arraycopy(payload, i + 1, data, 0, len - 1);
        cb.gotRelayRequest(data);
    }

    /**
     *  Parse a RELAYRESP block (data blocks only): 1 flag byte, 1 response
     *  code byte, and the remaining response data.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be &ge; 52
     *  @param isHandshake if true the block is illegal
     *  @throws IOException if the block appears in a handshake or is too short
     *  @since 0.9.71+
     */
    static void parseRelayResponse(PayloadCallback cb, byte[] payload, int i, int len, boolean isHandshake)
                                   throws IOException {
        if (isHandshake)
            throw new IOException("Illegal block in handshake: " + BLOCK_RELAYRESP);
        if (len < 52) // 12 byte data w/o IP or token + 40 byte DSA sig
            throw new IOException("Bad length for RELAYRESP: " + len);
        int resp = payload[i + 1] & 0xff; // skip flag
        byte[] data = new byte[len - 2];
        System.arraycopy(payload, i + 2, data, 0, len - 2);
        cb.gotRelayResponse(resp, data);
    }

    /**
     *  Parse a RELAYINTRO block (data blocks only): 1 flag byte, the 32-byte
     *  introducer hash, then the intro data.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be &ge; 93
     *  @param isHandshake if true the block is illegal
     *  @throws IOException if the block appears in a handshake or is too short
     *  @since 0.9.71+
     */
    static void parseRelayIntro(PayloadCallback cb, byte[] payload, int i, int len, boolean isHandshake)
                                throws IOException {
        if (isHandshake)
            throw new IOException("Illegal block in handshake: " + BLOCK_RELAYINTRO);
        if (len < 93) // 32 byte hash + 21 byte data w/ IPv4 + 40 byte DSA sig
            throw new IOException("Bad length for RELAYINTRO: " + len);
        Hash h = Hash.create(payload, i + 1); // skip flag
        byte[] data = new byte[len - (1 + Hash.HASH_LENGTH)]; // skip flag
        System.arraycopy(payload, i + 1 + Hash.HASH_LENGTH, data, 0, data.length);
        cb.gotRelayIntro(h, data);
    }

    /**
     *  Parse a PEERTEST block (data blocks only): message number, response
     *  code, an optional 32-byte hash for questions 2/4, and the data payload.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be &ge; 19
     *  @param isHandshake if true the block is illegal
     *  @throws IOException if the block appears in a handshake or is too short
     *  @throws DataFormatException if the message number is out of the 1-7 range
     *  @since 0.9.71+
     */
    static void parsePeerTest(PayloadCallback cb, byte[] payload, int i, int len, boolean isHandshake)
                              throws IOException, DataFormatException {
        if (isHandshake)
            throw new IOException("Illegal block in handshake: " + BLOCK_PEERTEST);
        if (len < 19) // 19 byte data w/ IPv4 (hash and sig optional)
            throw new IOException("Bad length for PEERTEST: " + len);
        int mnum = payload[i] & 0xff;
        if (mnum == 0 || mnum > 7)
            throw new DataFormatException("Bad PEERTEST number: " + mnum);
        int resp = payload[i + 1] & 0xff;
        int o = i + 3; // skip flag
        int datalen;
        Hash h;
        if (mnum == 2 || mnum == 4) {
            h = Hash.create(payload, o);
            datalen = len - (3 + Hash.HASH_LENGTH);
            o += Hash.HASH_LENGTH;
        } else {
            datalen = len - 3;
            h = null;
        }
        byte[] data = new byte[datalen];
        System.arraycopy(payload, o, data, 0, datalen);
        cb.gotPeerTest(mnum, resp, h, data);
    }

    /**
     *  Parse a NEWTOKEN block of at least 12 bytes: 4-byte expiry, 8-byte token.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be &ge; 12
     *  @throws IOException on bad length
     *  @since 0.9.71+
     */
    static void parseNewToken(PayloadCallback cb, byte[] payload, int i, int len) throws IOException {
        if (len < 12)
            throw new IOException("Bad length for NEWTOKEN: " + len);
        long exp = DataHelper.fromLong(payload, i, 4) * 1000;
        long token = DataHelper.fromLong8(payload, i + 4);
        cb.gotToken(token, exp);
    }

    /**
     *  Parse a TERMINATION block of at least 9 bytes: 8-byte receive time, 1
     *  byte reason code. The caller must flag the frame as terminated.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length, must be &ge; 9
     *  @throws IOException on bad length
     *  @since 0.9.71+
     */
    static void parseTermination(PayloadCallback cb, byte[] payload, int i, int len) throws IOException {
        if (len < 9)
            throw new IOException("Bad length for TERMINATION: " + len);
        long last = DataHelper.fromLong8(payload, i);
        int rsn = payload[i + 8] & 0xff;
        cb.gotTermination(rsn, last);
    }

    /**
     *  Parse a PATHCHALLENGE block (data blocks only), passing the challenge
     *  array up with the originating address.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length
     *  @param isHandshake if true the block is illegal
     *  @param from the originating address, may be null
     *  @throws IOException if the block appears in a handshake
     *  @since 0.9.71+
     */
    static void parsePathChallenge(PayloadCallback cb, byte[] payload, int i, int len, boolean isHandshake, RemoteHostId from)
                                   throws IOException {
        if (isHandshake)
            throw new IOException("Illegal block in handshake: " + BLOCK_PATHCHALLENGE);
        byte[] cdata = new byte[len];
        System.arraycopy(payload, i, cdata, 0, len);
        cb.gotPathChallenge(from, cdata);
    }

    /**
     *  Parse a PATHRESP block (data blocks only), passing the response array
     *  up with the originating address.
     *  @param cb callback
     *  @param payload the whole frame
     *  @param i offset of the block data
     *  @param len declared block length
     *  @param isHandshake if true the block is illegal
     *  @param from the originating address, may be null
     *  @throws IOException if the block appears in a handshake
     *  @since 0.9.71+
     */
    static void parsePathResponse(PayloadCallback cb, byte[] payload, int i, int len, boolean isHandshake, RemoteHostId from)
                                  throws IOException {
        if (isHandshake)
            throw new IOException("Illegal block in handshake: " + BLOCK_PATHRESP);
        byte[] rdata = new byte[len];
        System.arraycopy(payload, i, rdata, 0, len);
        cb.gotPathResponse(from, rdata);
    }

    /**
     *  Unknown block type: warn once per occurrence and skip the block.
     *  @param ctx context for logging
     *  @param cb callback, included in the message for context
     *  @param type the unknown block type byte
     *  @param len the declared block length
     *  @since 0.9.71+
     */
    static void parseUnknown(I2PAppContext ctx, PayloadCallback cb, int type, int len) {
        Log log = ctx.logManager().getLog(SSU2Payload.class);
        if (log.shouldWarn())
            log.warn("[SSU] Received UNKNOWN block: Type: " + type + "; Length: " + len + " bytes on " + cb);
    }

    /**
     *  @param payload writes to it starting at off
     *  @return the new offset
     */
    public static int writePayload(byte[] payload, int off, List<Block> blocks) {
        for (Block block : blocks) {
            off = block.write(payload, off);
        }
        return off;
    }

    /**
     *  Base class for blocks to be transmitted.
     *  Not used for receive; we use callbacks instead.
     */
    public abstract static class Block {
        private final int type;

        /**
         * Create a new block of the given type.
         * @param ttype the block type identifier
         */
        public Block(int ttype) {
            type = ttype;
        }

        /**
         * The block type.
         *
         * @return block type
         * @since 0.9.55
         */
        public int getType() { return type; }

        /**
         * Write the block to the target array, returning the new offset.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int write(byte[] tgt, int off) {
            tgt[off++] = (byte) type;
            // we do it this way so we don't call getDataLength(),
            // which may be inefficient
            // off is where the length goes
            int rv = writeData(tgt, off + 2);
            int blockSize = rv - (off + 2);
            if (blockSize < 0) {
                // Handle edge case where data is empty
                blockSize = 0;
                rv = off + 2;
            }
            DataHelper.toLong(tgt, off, 2, blockSize);
            return rv;
        }

        /**
         * Return the total size of the block, including the 3 byte header.
         * @return the size of the block, including the 3 byte header (type and size)
         */
        public int getTotalLength() {
            return BLOCK_HEADER_SIZE + getDataLength();
        }

        /**
         * Return the size of the block data, excluding the 3 byte header.
         * @return the size of the block, NOT including the 3 byte header (type and size)
         */
        public abstract int getDataLength();

        /**
         * Write the block data to the target array, returning the new offset.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public abstract int writeData(byte[] tgt, int off);

        /**
         * String representation of this block.
         */
        @Override
        public String toString() {
            return "Payload block type " + type + " length " + getDataLength();
        }
    }

    /**
     * Block containing router information data.
     * Used to exchange router information during session establishment.
     */
    public static class RIBlock extends Block {
        private final byte[] data;
        private final int doff;
        private final int dlen;
        private final boolean f;
        private final boolean gz;
        private final int fr;
        private final int frt;

        /**
         * Create a complete (non-fragmented) RouterInfo block.
         * @param ridata the raw RouterInfo data
         * @param flood true if this is a floodfill router
         * @param gzipped true if data is gzipped
         */
        public RIBlock(byte[] ridata, boolean flood, boolean gzipped) {
            this(ridata, 0, ridata.length, flood, gzipped, 0, 1);
        }

        /**
         * Create a fragment of a RouterInfo block.
         * @param ridata the raw RouterInfo data
         * @param off offset in the data
         * @param len length of the fragment
         * @param flood true if this is a floodfill router
         * @param gzipped true if data is gzipped
         * @param frag fragment number (0-based)
         * @param total total number of fragments
         */
        public RIBlock(byte[] ridata, int off, int len, boolean flood, boolean gzipped, int frag, int total) {
            super(BLOCK_ROUTERINFO);
            data = ridata;
            doff = off;
            dlen = len;
            f = flood;
            gz = gzipped;
            fr = frag;
            frt = total;
        }

        /**
         * Return the data length including the 2 byte header.
         * @return the data length
         */
        public int getDataLength() {
            return 2 + data.length;
        }

        /**
         * Write the RouterInfo block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            byte b = (byte) (f ? 1 : 0);
            if (gz)
                b |= 0x02;
            tgt[off++] = b;    // flag
            b = (byte) ((fr << 4) | frt);
            tgt[off++] = b;    // frag
            System.arraycopy(data, doff, tgt, off, dlen);
            return off + dlen;
        }
    }

    /**
     * Block containing I2NP message data.
     * Used to transport I2NP messages over SSU2.
     */
    public static class I2NPBlock extends Block {
        private OutboundMessageState m;

        /**
         * Create an I2NP message block.
         * @param msg the outbound message state containing the I2NP message
         */
        public I2NPBlock(OutboundMessageState msg) {
            super(BLOCK_I2NP);
            m = msg;
        }

        /**
         * Repurpose this block for a new message. Only for pooling.
         * @param msg the outbound message state
         * @since 0.9.71+
         */
        void setMessage(OutboundMessageState msg) {
            m = msg;
        }

        /**
         * Return the full I2NP message size (9 byte header vs. 16).
         * @return the message size
         */
        public int getDataLength() {
            // 9 byte header vs. 16
            return m.getMessageSize();
        }

        /**
         * Write the I2NP block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            return off + m.writeFragment(tgt, off, 0);
        }
    }

    /**
     *  Same format as I2NPBlock
     */
    public static class FirstFragBlock extends Block {
        private OutboundMessageState m;

        /**
         * Create a first fragment block.
         * @param msg the outbound message state
         */
        public FirstFragBlock(OutboundMessageState msg) {
            super(BLOCK_FIRSTFRAG);
            m = msg;
        }

        /**
         * Repurpose this block for a new message. Only for pooling.
         * @param msg the outbound message state
         * @since 0.9.71+
         */
        void setMessage(OutboundMessageState msg) {
            m = msg;
        }

        /**
         * Return the fragment size including the 9 byte header.
         * @return the fragment data length
         */
        public int getDataLength() {
            // 9 byte header vs. 5
            return m.fragmentSize(0); // + 4;
        }

        /**
         * Write the first fragment block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            return off + m.writeFragment(tgt, off, 0);
        }
    }

    /**
     *  Follow-on fragment block for SSU2 payload.
     */
    public static class FollowFragBlock extends Block {
        private OutboundMessageState m;
        private int f;

        /**
         * Create a follow-on fragment block.
         * @param msg the outbound message state
         * @param frag the fragment number (must be &gt; 0)
         */
        public FollowFragBlock(OutboundMessageState msg, int frag) {
            super(BLOCK_FOLLOWONFRAG);
            if (frag <= 0)
                throw new IllegalArgumentException();
            m = msg;
            f = frag;
        }

        /**
         * Repurpose this block for a new fragment. Only for pooling.
         * @param msg the outbound message state
         * @param frag the fragment number (must be &gt; 0)
         * @since 0.9.71+
         */
        void setMessage(OutboundMessageState msg, int frag) {
            if (frag <= 0)
                throw new IllegalArgumentException();
            m = msg;
            f = frag;
        }

        /**
         * Return the fragment data length including the 5 byte header.
         * @return the fragment data length
         */
        public int getDataLength() {
            return m.fragmentSize(f) + 5;
        }

        /**
         * Write the follow-on fragment block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            byte b = (byte) (f << 1);
            if (f == m.getFragmentCount() - 1)
                b |= (byte) 0x01;
            tgt[off++] = b;
            DataHelper.toLong(tgt, off, 4, m.getMessageId());
            off += 4;
            return off + m.writeFragment(tgt, off, f);
        }
    }

    /**
     * Block containing padding data.
     * Used to pad messages to required sizes.
     */
    public static class PaddingBlock extends Block {
        private final int sz;
        private final I2PAppContext ctx;

        /**
         * Create a padding block with zero-filled data.
         * @param size the padding size
         */
        public PaddingBlock(int size) {
            this(null, size);
        }

        /**
         * Create a padding block with random data.
         * @param context the I2P context for random data generation
         * @param size the padding size
         */
        public PaddingBlock(I2PAppContext context, int size) {
            super(BLOCK_PADDING);
            sz = size;
            ctx = context;
        }

        /**
         * Return the padding size.
         * @return the padding size
         */
        public int getDataLength() {
            return sz;
        }

        /**
         * Write the padding block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            if (ctx != null)
                ctx.random().nextBytes(tgt, off, sz);
            else
                Arrays.fill(tgt, off, off + sz, (byte) 0);
            return off + sz;
        }
    }

    /**
     * Block containing the current date and time.
     * Used for time synchronization between peers.
     */
    public static class DateTimeBlock extends Block {
        private final long now;

        /**
         * Create a date/time block with the current clock time.
         * @param ctx the I2P context for reading the clock
         */
        public DateTimeBlock(I2PAppContext ctx) {
            super(BLOCK_DATETIME);
            now = ctx.clock().now();
        }

        /**
         * Return the 4 byte date/time data length.
         * @return the data length
         */
        public int getDataLength() {
            return 4;
        }

        /**
         * Write the date/time block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 4, (now + 500) / 1000);
            return off + 4;
        }
    }

    /**
     * Block containing session options.
     * Used to negotiate session parameters.
     */
    public static class OptionsBlock extends Block {
        private final byte[] opts;

        /**
         * Create an options block.
         * @param options the option data
         */
        public OptionsBlock(byte[] options) {
            super(BLOCK_OPTIONS);
            opts = options;
        }

        /**
         * Return the options data length.
         * @return the options data length
         */
        public int getDataLength() {
            return opts.length;
        }

        /**
         * Write the options block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            System.arraycopy(opts, 0, tgt, off, opts.length);
            return off + opts.length;
        }
    }

    /**
     * Block containing session termination data.
     * Used to signal session termination.
     */
    public static class TerminationBlock extends Block {
        private final byte rsn;
        private final long rcvd;

        /**
         * Create a session termination block.
         * @param reason termination reason code (0-255)
         * @param lastReceived highest sequence number received
         */
        public TerminationBlock(int reason, long lastReceived) {
            super(BLOCK_TERMINATION);
            rsn = (byte) reason;
            rcvd = lastReceived;
        }

        /**
         * Return the 9 byte termination data length.
         * @return the data length
         */
        public int getDataLength() {
            return 9;
        }

        /**
         * Write the termination block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong8(tgt, off, rcvd);
            tgt[off + 8] = rsn;
            return off + 9;
        }
    }

    /**
     * Block containing acknowledgment data.
     * Used to acknowledge receipt of packets.
     */
    public static class AckBlock extends Block {
        private final long t;
        private final int a;
        private final byte[] r;
        private final int rc;

        /**
         * Create an acknowledgment block.
         * @param thru highest contiguous sequence number
         * @param acnt number of additional acks (max 255)
         * @param ranges nack/ack/nack/ack ranges
         * @param rangeCount number of range pairs (ranges length / 2)
         */
        public AckBlock(long thru, int acnt, byte[] ranges, int rangeCount) {
            super(BLOCK_ACK);
            if (acnt > 255)
                throw new IllegalArgumentException();
            t = thru;
            a = acnt;
            r = ranges;
            rc = rangeCount;
        }

        /**
         * Return the ACK data length (5 byte header + 2 bytes per range).
         * @return the data length
         */
        public int getDataLength() {
            return 5 + (rc * 2);
        }

        /**
         * Write the ACK block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 4, t);
            off += 4;
            tgt[off++] = (byte) a;
            System.arraycopy(r, 0, tgt, off, rc * 2);
            return off + (rc * 2);
        }

        /**
         * String representation of this ACK block.
         */
        @Override
        public String toString() {
            return SSU2Bitfield.toString(t, a, r, rc);
        }
    }

    /**
     * Block containing IP address and port information.
     * Used to exchange transport addresses.
     */
    public static class AddressBlock extends Block {
        private final byte[] i;
        private final int p;

        /**
         * Create an address block.
         * @param ip the IP address bytes (IPv4 = 4 bytes, IPv6 = 16 bytes)
         * @param port the port number
         */
        public AddressBlock(byte[] ip, int port) {
            super(BLOCK_ADDRESS);
            i = ip;
            p = port;
        }

        /**
         * Return the address data length (2 byte port + IP bytes).
         * @return the data length
         */
        public int getDataLength() {
            return 2 + i.length;
        }

        /**
         * Write the address block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 2, p);
            off += 2;
            System.arraycopy(i, 0, tgt, off, i.length);
            return off + i.length;
        }
    }

    /**
     * Block containing relay tag request data.
     * Used to request relay tags for session establishment.
     */
    public static class RelayTagRequestBlock extends Block {

        /**
         * Create a relay tag request block (no data payload).
         */
        public RelayTagRequestBlock() {
            super(BLOCK_RELAYTAGREQ);
        }

        /**
         * Return zero (no data payload).
         * @return the data length
         */
        public int getDataLength() {
            return 0;
        }

        /**
         * Write the relay tag request block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            return off;
        }
    }

    /**
     * Block containing relay tag data.
     * Used to identify relay sessions.
     */
    public static class RelayTagBlock extends Block {
        private final long t;

        /**
         * Create a relay tag block.
         * @param tag the relay tag value
         */
        public RelayTagBlock(long tag) {
            super(BLOCK_RELAYTAG);
            t = tag;
        }

        /**
         * Return the 4 byte relay tag data length.
         * @return the data length
         */
        public int getDataLength() {
            return 4;
        }

        /**
         * Write the relay tag block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 4, t);
            return off + 4;
        }
    }

    /**
     * Block containing relay request data.
     * Used to request relay services from a peer.
     */
    public static class RelayRequestBlock extends Block {
        private final byte[] d;

        /**
         * Create a relay request block.
         * @param data the relay request data (excludes flag, includes signature)
         */
        public RelayRequestBlock(byte[] data) {
            super(BLOCK_RELAYREQ);
            d = data;
        }

        /**
         * Return the relay request data length.
         * @return the data length
         */
        public int getDataLength() {
            return d.length;
        }

        /**
         * Write the relay request block data to the target array.
         * @param tgt target array to write to
         * @param off offset in the target array
         * @return the new offset after writing
         */
        public int writeData(byte[] tgt, int off) {
            System.arraycopy(d, 0, tgt, off, d.length);
            return off + d.length;
        }
    }

    /**
     * Block containing relay response data.
     * Used to respond to relay requests.
     */
    public static class RelayResponseBlock extends Block {
        private final byte[] d;

        /**
         * RelayResponseBlock.
         */
        public RelayResponseBlock(byte[] data) {
            super(BLOCK_RELAYRESP);
            d = data;
        }

        /**
         * The data length of this block.
         *
         * @return the data length
         */
        public int getDataLength() {
            return d.length;
        }

        /**
         * Write this block's data to the target array.
         */
        public int writeData(byte[] tgt, int off) {
            System.arraycopy(d, 0, tgt, off, d.length);
            return off + d.length;
        }
    }

    /**
     * Block containing relay introduction data.
     * Used to introduce peers for relayed connections.
     */
    public static class RelayIntroBlock extends Block {
        private final byte[] d;

        /**
         * RelayIntroBlock.
         */
        public RelayIntroBlock(byte[] data) {
            super(BLOCK_RELAYINTRO);
            d = data;
        }

        /**
         * The data length of this block.
         *
         * @return the data length
         */
        public int getDataLength() {
            return d.length;
        }

        /**
         * Write this block's data to the target array.
         */
        public int writeData(byte[] tgt, int off) {
            System.arraycopy(d, 0, tgt, off, d.length);
            return off + d.length;
        }
    }

    /**
     * Block containing peer test data.
     * Used for network reachability testing.
     */
    public static class PeerTestBlock extends Block {
        private final int n;
        private final int c;
        private final Hash h;
        private final byte[] d;

        /**
         *  @param hash may be null
         */
        public PeerTestBlock(int msgNum, int code, Hash hash, byte[] data) {
            super(BLOCK_PEERTEST);
            n = msgNum;
            c = code;
            h = hash;
            d = data;
        }

        /**
         * The data length of this block.
         *
         * @return the data length
         */
        public int getDataLength() {
            int rv = 3 + d.length;
            if (h != null)
                rv += Hash.HASH_LENGTH;
            return rv;
        }

        /**
         * Write this block's data to the target array.
         */
        public int writeData(byte[] tgt, int off) {
            tgt[off++] = (byte) n;
            tgt[off++] = (byte) c;
            tgt[off++] = 0;  // flag
            if (h != null) {
                System.arraycopy(h.getData(), 0, tgt, off, Hash.HASH_LENGTH);
                off += Hash.HASH_LENGTH;
            }
            System.arraycopy(d, 0, tgt, off, d.length);
            return off + d.length;
        }
    }

    /**
     * Block containing a new session token.
     * Used to provide tokens for future session establishment.
     */
    public static class NewTokenBlock extends Block {
        private final EstablishmentManager.Token tok;

        /**
         * NewTokenBlock.
         */
        public NewTokenBlock(EstablishmentManager.Token token) {
            super(BLOCK_NEWTOKEN);
            tok = token;
        }

        /**
         * The data length of this block.
         *
         * @return the data length
         */
        public int getDataLength() {
            return 12;
        }

        /**
         * Write this block's data to the target array.
         */
        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 4, tok.getExpiration() / 1000);
            off += 4;
            DataHelper.toLong8(tgt, off, tok.getToken());
            return off + 8;
        }
    }

    /**
     *  Path challenge block for SSU2 payload.
     *  @since 0.9.55
     */
    public static class PathChallengeBlock extends Block {
        private final byte[] d;

        /**
         * PathChallengeBlock.
         */
        public PathChallengeBlock(byte[] data) {
            super(BLOCK_PATHCHALLENGE);
            d = data;
        }

        /**
         * The data length of this block.
         *
         * @return the data length
         */
        public int getDataLength() {
            return d.length;
        }

        /**
         * Write this block's data to the target array.
         */
        public int writeData(byte[] tgt, int off) {
            System.arraycopy(d, 0, tgt, off, d.length);
            return off + d.length;
        }
    }

    /**
     *  Path response block for SSU2 payload.
     *  @since 0.9.55
     */
    public static class PathResponseBlock extends Block {
        private final byte[] d;

        /**
         * PathResponseBlock.
         */
        public PathResponseBlock(byte[] data) {
            super(BLOCK_PATHRESP);
            d = data;
        }

        /**
         * The data length of this block.
         *
         * @return the data length
         */
        public int getDataLength() {
            return d.length;
        }

        /**
         * Write this block's data to the target array.
         */
        public int writeData(byte[] tgt, int off) {
            System.arraycopy(d, 0, tgt, off, d.length);
            return off + d.length;
        }
    }
}
