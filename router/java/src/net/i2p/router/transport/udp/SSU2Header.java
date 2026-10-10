package net.i2p.router.transport.udp;

import static net.i2p.router.transport.udp.SSU2Util.*;

import java.net.DatagramPacket;
import net.i2p.crypto.ChaCha20;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;

/**
 * Encrypt/decrypt headers
 *
 * @since 0.9.54
 */
final class SSU2Header {

    /** 8 bytes of zeros */
    public static final byte[] HEADER_PROT_DATA = new byte[HEADER_PROT_DATA_LEN];
    /** 12 bytes of zeros */
    public static final byte[] CHACHA_IV_0 = new byte[CHACHA_IV_LEN];

    /**
     * Thread-scoped scratch for the trial decrypts, which run 3-6 times per
     * inbound datagram on the UDP handle threads.
     *
     * Safe because a trial-decrypted Header never escapes the calling method:
     * acceptTrialDecrypt() copies the bytes into the packet, and no caller
     * stores the Header. One scratch per header size, because Header.toString()
     * and getEphemeralKey() infer the header type from the array length, and
     * because PacketHandler may hold a handshake header while falling back to
     * a long-header decrypt of the same packet.
     *
     * @since 0.9.71+
     */
    private static final class Scratch {
        final Header session = new Header(SESSION_HEADER_SIZE);
        final Header longHdr = new Header(LONG_HEADER_SIZE);
        final Header shortHdr = new Header(SHORT_HEADER_SIZE);
        /** reused by decryptDestConnID(), decryptShortHeader(), encryptShortHeader() */
        final byte[] xor = new byte[HEADER_PROT_DATA_LEN];
    }

    private static final ThreadLocal<Scratch> SCRATCH = new ThreadLocal<Scratch>() {
        @Override
        protected Scratch initialValue() {return new Scratch();}
    };

    private SSU2Header() { /* no-op */ }

    /**
     * Session Request and Session Created only. 64 bytes.
     * Packet is unmodified.
     *
     * The returned Header is scratch, valid only until the next trial decrypt
     * on this thread. It must not be retained.
     *
     * @param packet must be 88 bytes min
     * @param key1 the 32-byte ChaCha20 key protecting header bytes 0-7
     * @param key2 the 32-byte ChaCha20 key protecting header bytes 8-15
     * @return 64 byte header, null if data too short
     */
    public static Header trialDecryptHandshakeHeader(UDPPacket packet, byte[] key1, byte[] key2) {
        DatagramPacket pkt = packet.getPacket();
        if (pkt.getLength() < MIN_HANDSHAKE_DATA_LEN)
            return null;
        Scratch scratch = SCRATCH.get();
        Header header = scratch.session;
        decryptHandshakeHeader(pkt, key1, key2, header, scratch);
        return header;
    }

    /**
     * Retry, Token Request, Peer Test only. 32 bytes.
     * Packet is unmodified.
     *
     * The returned Header is scratch, valid only until the next trial decrypt
     * on this thread. It must not be retained.
     *
     * @param packet must be 56 bytes min
     * @param key1 the 32-byte ChaCha20 key protecting header bytes 0-7
     * @param key2 the 32-byte ChaCha20 key protecting header bytes 8-15
     * @return 32 byte header, null if data too short
     */
    public static Header trialDecryptLongHeader(UDPPacket packet, byte[] key1, byte[] key2) {
        DatagramPacket pkt = packet.getPacket();
        if (pkt.getLength() < MIN_LONG_DATA_LEN)
            return null;
        Scratch scratch = SCRATCH.get();
        Header header = scratch.longHdr;
        decryptLongHeader(pkt, key1, key2, header, scratch);
        return header;
    }

    /**
     * Session Confirmed and data phase. 16 bytes.
     * Packet is unmodified.
     *
     * The returned Header is scratch, valid only until the next trial decrypt
     * on this thread. It must not be retained.
     *
     * @param packet must be 40 bytes min
     * @param key1 the 32-byte ChaCha20 key protecting header bytes 0-7
     * @param key2 the 32-byte ChaCha20 key protecting header bytes 8-15
     * @return 16 byte header, null if data too short, must be 40 bytes min
     */
    public static Header trialDecryptShortHeader(UDPPacket packet, byte[] key1, byte[] key2) {
        DatagramPacket pkt = packet.getPacket();
        if (pkt.getLength() < MIN_DATA_LEN)
            return null;
        Scratch scratch = SCRATCH.get();
        Header header = scratch.shortHdr;
        decryptShortHeader(pkt, key1, key2, header, scratch);
        return header;
    }

    /**
     * Decrypt bytes 0-7 in header.
     * Packet is unmodified.
     *
     * @param pkt must be 8 bytes min
     * @param key1 the 32-byte ChaCha20 key protecting header bytes 0-7
     * @return the destination connection ID
     * @throws IndexOutOfBoundsException if too short
     */
    public static long decryptDestConnID(DatagramPacket pkt, byte[] key1) {
        byte[] data = pkt.getData();
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte[] xor = SCRATCH.get().xor;

        ChaCha20.decrypt(key1, data, off + len - HEADER_PROT_SAMPLE_1_OFFSET, HEADER_PROT_DATA, 0, xor, 0, HEADER_PROT_DATA_LEN);
        for (int i = 0; i < HEADER_PROT_DATA_LEN; i++) {
            xor[i] ^= data[i + off + HEADER_PROT_1_OFFSET];
        }
        return DataHelper.fromLong8(xor, 0);
    }

    /**
     * Copy the header back to the packet. Cannot be undone.
     *
     * @param packet the packet the trial-decrypted header came from
     * @param header the scratch Header to copy back over the packet's header
     */
    public static void acceptTrialDecrypt(UDPPacket packet, Header header) {
        DatagramPacket pkt = packet.getPacket();
        int off = pkt.getOffset();
        byte[] data = pkt.getData();
        System.arraycopy(header.data, 0, data, off, header.data.length);
    }


    /**
     * Decrypt bytes 0-63 from pkt to header
     * First 64 bytes
     * Packet is unmodified.
     */
    private static void decryptHandshakeHeader(DatagramPacket pkt, byte[] key1, byte[] key2, Header header, Scratch scratch) {
        byte[] data = pkt.getData();
        int off = pkt.getOffset();
        decryptShortHeader(pkt, key1, key2, header, scratch);
        ChaCha20.decrypt(key2, CHACHA_IV_0, data, off + SHORT_HEADER_SIZE, header.data, SHORT_HEADER_SIZE, KEY_LEN + LONG_HEADER_SIZE - SHORT_HEADER_SIZE);
    }

    /**
     * Decrypt bytes 0-31 from pkt to header.
     * First 32 bytes
     * Packet is unmodified.
     */
    private static void decryptLongHeader(DatagramPacket pkt, byte[] key1, byte[] key2, Header header, Scratch scratch) {
        byte[] data = pkt.getData();
        int off = pkt.getOffset();
        decryptShortHeader(pkt, key1, key2, header, scratch);
        ChaCha20.decrypt(key2, CHACHA_IV_0, data, off + SHORT_HEADER_SIZE, header.data, SHORT_HEADER_SIZE, LONG_HEADER_SIZE - SHORT_HEADER_SIZE);
    }

    /**
     * Decrypt bytes 0-15 to header.
     * Packet is unmodified.
     *
     * First 8 bytes uses key1 and the next-to-last 12 bytes as the IV.
     * Next 8 bytes uses key2 and the last 12 bytes as the IV.
     */
    private static void decryptShortHeader(DatagramPacket pkt, byte[] key1, byte[] key2, Header header, Scratch scratch) {
        byte[] data = pkt.getData();
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte[] xor = scratch.xor;

        ChaCha20.decrypt(key1, data, off + len - HEADER_PROT_SAMPLE_1_OFFSET, HEADER_PROT_DATA, 0, xor, 0, HEADER_PROT_DATA_LEN);
        for (int i = 0; i < HEADER_PROT_DATA_LEN; i++) {
            header.data[i + HEADER_PROT_1_OFFSET] = (byte) (data[i + off + HEADER_PROT_1_OFFSET] ^ xor[i]);
        }

        ChaCha20.decrypt(key2, data, off + len - HEADER_PROT_SAMPLE_2_OFFSET, HEADER_PROT_DATA, 0, xor, 0, HEADER_PROT_DATA_LEN);
        for (int i = 0; i < HEADER_PROT_DATA_LEN; i++) {
            header.data[i + HEADER_PROT_2_OFFSET] = (byte) (data[i + off + HEADER_PROT_2_OFFSET] ^ xor[i]);
        }
    }

    /**
     * A temporary structure returned from trial decrypt,
     * with methods to access the fields.
     */
    public static class Header {
        /**
         * The raw header bytes.
         */
        public final byte[] data;

        /**
         * Create a Header with the given size.
         *
         * @param len the number of raw header bytes to allocate, one of the SHORT_HEADER_SIZE, LONG_HEADER_SIZE or SESSION_HEADER_SIZE values
         */
        public Header(int len) { data = new byte[len]; }

        /**
         * Available in all header types.
         *
         * @return the destination connection ID, 0 if the header is all zeros
         */
        public long getDestConnID() { return DataHelper.fromLong8(data, 0); }
        /**
         * Available in all header types.
         *
         * @return the packet number, for an I2P header sent before data phase it
         *     may be 0
         */
        public long getPacketNumber() { return DataHelper.fromLong(data, PKT_NUM_OFFSET, PKT_NUM_LEN); }
        /**
         * Available in all header types.
         *
         * @return the message type byte
         */
        public int getType() { return data[TYPE_OFFSET] & 0xff; }

        /**
         * Short header flags byte, present only in short headers.
         *
         * @return the short header flags byte
         */
        public int getShortHeaderFlags() { return (int) DataHelper.fromLong(data, SHORT_HEADER_FLAGS_OFFSET, SHORT_HEADER_FLAGS_LEN); }

        /**
         * SSU2 protocol version, present only in long headers.
         *
         * @return the protocol version byte
         */
        public int getVersion() { return data[VERSION_OFFSET] & 0xff; }
        /**
         * Network ID, present only in long headers.
         *
         * @return the network ID byte
         */
        public int getNetID() { return data[NETID_OFFSET] & 0xff; }
        /**
         * Long header flags byte, present only in long headers.
         *
         * @return the long header flags byte
         */
        public int getHandshakeHeaderFlags() { return data[LONG_HEADER_FLAGS_OFFSET] & 0xff; }
        /**
         * Source connection ID, present only in long headers.
         *
         * @return the source connection ID, 0 if absent
         */
        public long getSrcConnID() { return DataHelper.fromLong8(data, SRC_CONN_ID_OFFSET); }
        /**
         * Token, present only in long headers.
         *
         * @return the 8-byte token as a long, 0 if absent
         */
        public long getToken() { return DataHelper.fromLong8(data, TOKEN_OFFSET); }

        /**
         * X25519 ephemeral public key, present only in handshake headers.
         *
         * @return a newly allocated copy of the 32-byte ephemeral public key
         */
        public byte[] getEphemeralKey() {
            byte[] rv = new byte[KEY_LEN];
            System.arraycopy(data, LONG_HEADER_SIZE, rv, 0, KEY_LEN);
            return rv;
        }

        /**
         * String representation of this header.
         */
        @Override
        public String toString() {
            if (data.length >= SESSION_HEADER_SIZE) {
                return "Handshake header: DestID: " + getDestConnID() + "; Packet [#" + getPacketNumber() + "]; Type: " + getType() +
                       "; SourceID: " + getSrcConnID() + "\n* Token: " + getToken() + "; Key: " + Base64.encode(getEphemeralKey());
            }
            if (data.length >= LONG_HEADER_SIZE) {
                return "Long header: DestID: " + getDestConnID() + "; Packet [#" + getPacketNumber() + "]; Type: " + getType() +
                       "; SourceID: " + getSrcConnID() + "\n* Token: " + getToken();
            }
            return "Short header: DestID: " + getDestConnID() + "; Packet [#" + getPacketNumber() + "]; Type: " + getType() +
                   "; Flags: " + getShortHeaderFlags();
        }
    }

    ////////// Encryption ///////////

    /**
     * First 64 bytes
     *
     * @param packet the outbound packet whose header bytes are encrypted in place
     * @param key1 the 32-byte ChaCha20 key protecting header bytes 0-7
     * @param key2 the 32-byte ChaCha20 key protecting header bytes 8-63
     */
    public static void encryptHandshakeHeader(UDPPacket packet, byte[] key1, byte[] key2) {
        DatagramPacket pkt = packet.getPacket();
        byte[] data = pkt.getData();
        int off = pkt.getOffset();
        encryptShortHeader(packet, key1, key2);
        ChaCha20.encrypt(key2, CHACHA_IV_0, data, off + SHORT_HEADER_SIZE, data, off + SHORT_HEADER_SIZE, KEY_LEN + LONG_HEADER_SIZE - SHORT_HEADER_SIZE);
    }

    /**
     * First 32 bytes
     *
     * @param packet the outbound packet whose header bytes are encrypted in place
     * @param key1 the 32-byte ChaCha20 key protecting header bytes 0-7
     * @param key2 the 32-byte ChaCha20 key protecting header bytes 8-31
     */
    public static void encryptLongHeader(UDPPacket packet, byte[] key1, byte[] key2) {
        DatagramPacket pkt = packet.getPacket();
        byte[] data = pkt.getData();
        int off = pkt.getOffset();
        encryptShortHeader(packet, key1, key2);
        ChaCha20.encrypt(key2, CHACHA_IV_0, data, off + SHORT_HEADER_SIZE, data, off + SHORT_HEADER_SIZE, LONG_HEADER_SIZE - SHORT_HEADER_SIZE);
    }

    /**
     * First 16 bytes.
     *
     * First 8 bytes uses key1 and the next-to-last 12 bytes as the IV.
     * Next 8 bytes uses key2 and the last 12 bytes as the IV.
     *
     * @param packet the outbound packet whose header bytes are encrypted in place
     * @param key1 the 32-byte ChaCha20 key protecting header bytes 0-7
     * @param key2 the 32-byte ChaCha20 key protecting header bytes 8-15
     */
    public static void encryptShortHeader(UDPPacket packet, byte[] key1, byte[] key2) {
        DatagramPacket pkt = packet.getPacket();
        byte[] data = pkt.getData();
        int off = pkt.getOffset();
        int len = pkt.getLength();
        byte[] xor = SCRATCH.get().xor;

        ChaCha20.encrypt(key1, data, off + len - HEADER_PROT_SAMPLE_1_OFFSET, HEADER_PROT_DATA, 0, xor, 0, HEADER_PROT_DATA_LEN);
        for (int i = 0; i < HEADER_PROT_DATA_LEN; i++) {
            data[i + off + HEADER_PROT_1_OFFSET] ^= xor[i];
        }

        ChaCha20.encrypt(key2, data, off + len - HEADER_PROT_SAMPLE_2_OFFSET, HEADER_PROT_DATA, 0, xor, 0, HEADER_PROT_DATA_LEN);
        for (int i = 0; i < HEADER_PROT_DATA_LEN; i++) {
            data[i + off + HEADER_PROT_2_OFFSET] ^= xor[i];
        }
    }


}
