package net.i2p.router.crypto.ratchet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageImpl;
import net.i2p.router.RouterContext;

/**
 *
 *  Ratchet payload generation and parsing
 *
 *  @since 0.9.44 adapted from NTCP2Payload
 */
class RatchetPayload {

    public static final int BLOCK_HEADER_SIZE = 3;

    private static final int BLOCK_DATETIME = 0;
    private static final int BLOCK_SESSIONID = 1;
    private static final int BLOCK_TERMINATION = 4;
    private static final int BLOCK_OPTIONS = 5;
    private static final int BLOCK_MSGNUM = 6;
    private static final int BLOCK_NEXTKEY = 7;
    private static final int BLOCK_ACKKEY = 8;
    private static final int BLOCK_REPLYDI = 9;
    private static final int BLOCK_GARLIC = 11;
    private static final int BLOCK_PADDING = 254;

    /**
     *  For all callbacks, recommend throwing exceptions only from the handshake.
     *  Exceptions will get thrown out of processPayload() and prevent
     *  processing of succeeding blocks.
     */
    public interface PayloadCallback {
        public void gotDateTime(long time) throws DataFormatException;

        public void gotGarlic(GarlicClove clove);

        /**
         *  @param isHandshake true only for message 3 part 2
         */
        public void gotOptions(byte[] options, boolean isHandshake) throws DataFormatException;

        /**
         *  @param lastReceived in theory could wrap around to negative, but very unlikely
         */
        public void gotTermination(int reason, long lastReceived);

        /**
         *  @param nextKey the next one
         */
        public void gotNextKey(NextSessionKey nextKey);

        /**
         *  @since 0.9.46
         */
        public void gotAck(int id, int n);

        /**
         *  @since 0.9.46
         */
        public void gotAckRequest(int id, DeliveryInstructions di);

        /**
         *  For stats.
         *  @param paddingLength the number of padding bytes, not including the 3-byte block header
         *  @param frameLength the total size of the frame, including all blocks and block headers
         */
        public void gotPadding(int paddingLength, int frameLength);

        public void gotUnknown(int type, int len);
    }

    /**
     *  Incoming payload. Calls the callback for each received block.
     *
     *  @return number of blocks processed
     *  @throws IOException on major errors
     *  @throws DataFormatException on parsing of individual blocks
     *  @throws I2NPMessageException on parsing of I2NP block
     */
    public static int processPayload(I2PAppContext ctx, PayloadCallback cb,
                                     byte[] payload, int off, int length, boolean isHandshake)
                                     throws IOException, DataFormatException, I2NPMessageException {
        int blocks = 0;
        boolean gotPadding = false;
        boolean gotTermination = false;
        int i = off;
        final int end = off + length;
        while (i < end) {
            int type = payload[i++] & 0xff;
            if (gotPadding)
                throw new IOException("Illegal block after padding: " + type);
            if (gotTermination && type != BLOCK_PADDING)
                throw new IOException("Illegal block after termination: " + type);
            int len = (int) DataHelper.fromLong(payload, i, 2);
            i += 2;
            if (i + len > end) {
                throw new IOException("Block " + blocks + " type " + type + " length " + len +
                                      " at offset " + (i - 3 - off) + " runs over frame of size " + length +
                                      '\n' + net.i2p.util.HexDump.dump(payload, off, length));
            }
            switch (type) {
                // don't modify i inside switch

                case BLOCK_DATETIME:
                    if (len != 4)
                        throw new IOException("Bad length for DATETIME: " + len);
                    long time = DataHelper.fromLong(payload, i, 4) * 1000;
                    cb.gotDateTime(time);
                    break;

                case BLOCK_OPTIONS:
                    byte[] options = new byte[len];
                    System.arraycopy(payload, i, options, 0, len);
                    cb.gotOptions(options, isHandshake);
                    break;

                case BLOCK_GARLIC:
                    GarlicClove clove = new GarlicClove(ctx);
                    clove.readBytesRatchet(payload, i, len);
                    cb.gotGarlic(clove);
                    break;

                case BLOCK_NEXTKEY:
                  {
                    if (len != 34)
                        throw new IOException("Bad length for NEXTKEY: " + len);
                    int id = (int) DataHelper.fromLong(payload, i, 2);
                    byte[] data = new byte[32];
                    System.arraycopy(payload, i + 2, data, 0, 32);
                    NextSessionKey nsk = new NextSessionKey(data, id);
                    cb.gotNextKey(nsk);
                  }
                    break;

                case BLOCK_ACKKEY:
                  {
                    if (len < 4 || (len % 4) != 0)
                        throw new IOException("Bad length for REPLYDI: " + len);
                    for (int j = i; j < i + len; j += 4) {
                        int id = (int) DataHelper.fromLong(payload, j, 2);
                        int n = (int) DataHelper.fromLong(payload, j + 2, 2);
                        cb.gotAck(id, n);
                    }
                  }
                    break;

                case BLOCK_REPLYDI:
                  {
                    if (len < 6)
                        throw new IOException("Bad length for REPLYDI: " + len);
                    int id = (int) DataHelper.fromLong(payload, i, 4);
                    DeliveryInstructions di = new DeliveryInstructions();
                    di.readBytes(payload, i + 5);
                    cb.gotAckRequest(id, di);
                  }
                    break;

                case BLOCK_TERMINATION:
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    if (len < 9)
                        throw new IOException("Bad length for TERMINATION: " + len);
                    long last = fromLong8(payload, i);
                    int rsn = payload[i + 8] & 0xff;
                    cb.gotTermination(rsn, last);
                    gotTermination = true;
                    break;

                case BLOCK_PADDING:
                    gotPadding = true;
                    cb.gotPadding(len, length);
                    break;

                default:
                    if (isHandshake)
                        throw new IOException("Illegal block in handshake: " + type);
                    cb.gotUnknown(type, len);
                    break;

            }
            i += len;
            blocks++;
        }
        if (isHandshake && blocks == 0)
            throw new IOException("No blocks in handshake");
        return blocks;
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
    public static abstract class Block {
        private final int type;

        public Block(int ttype) {
            type = ttype;
        }

        /** @return new offset */
        public int write(byte[] tgt, int off) {
            tgt[off++] = (byte) type;
            // we do it this way so we don't call getDataLength(),
            // which may be inefficient
            // off is where the length goes
            int rv = writeData(tgt, off + 2);
            DataHelper.toLong(tgt, off, 2, rv - (off + 2));
            return rv;
        }

        /**
         *  @return the size of the block, including the 3 byte header (type and size)
         */
        public int getTotalLength() {
            return BLOCK_HEADER_SIZE + getDataLength();
        }

        /**
         *  @return the size of the block, NOT including the 3 byte header (type and size)
         */
        public abstract int getDataLength();

        /** @return new offset */
        public abstract int writeData(byte[] tgt, int off);

        @Override
        public String toString() {
            return "Payload block type " + type + " length " + getDataLength();
        }
    }

    public static class GarlicBlock extends Block {
        private final GarlicClove c;

        public GarlicBlock(GarlicClove clove) {
            super(BLOCK_GARLIC);
            c = clove;
        }

        public int getDataLength() {
            return c.getSizeRatchet();
        }

        public int writeData(byte[] tgt, int off) {
            return c.writeBytesRatchet(tgt, off);
        }
    }

    public static class PaddingBlock extends Block {
        private final int sz;
        private final I2PAppContext ctx;

        /** with zero-filled data */
        public PaddingBlock(int size) {
            this(null, size);
        }

        /** with random data */
        public PaddingBlock(I2PAppContext context, int size) {
            super(BLOCK_PADDING);
            sz = size;
            ctx = context;
        }

        public int getDataLength() {
            return sz;
        }

        public int writeData(byte[] tgt, int off) {
            if (ctx != null)
                ctx.random().nextBytes(tgt, off, sz);
            else
                Arrays.fill(tgt, off, off + sz, (byte) 0);
            return off + sz;
        }
    }

    public static class DateTimeBlock extends Block {
        private final long now;

        public DateTimeBlock(long time) {
            super(BLOCK_DATETIME);
            now = time;
        }

        public int getDataLength() {
            return 4;
        }

        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 4, now / 1000);
            return off + 4;
        }
    }

    public static class OptionsBlock extends Block {
        private final byte[] opts;

        public OptionsBlock(byte[] options) {
            super(BLOCK_OPTIONS);
            opts = options;
        }

        public int getDataLength() {
            return opts.length;
        }

        public int writeData(byte[] tgt, int off) {
            System.arraycopy(opts, 0, tgt, off, opts.length);
            return off + opts.length;
        }
    }

    public static class NextKeyBlock extends Block {
        private final NextSessionKey next;

        public NextKeyBlock(NextSessionKey nextKey) {
            super(BLOCK_NEXTKEY);
            next = nextKey;
        }

        public int getDataLength() {
            return 34;
        }

        public int writeData(byte[] tgt, int off) {
            DataHelper.toLong(tgt, off, 2, next.getID());
            System.arraycopy(next.getData(), 0, tgt, off + 2, 32);
            return off + 34;
        }
    }

    /**
     *  @since 0.9.46
     */
    public static class AckBlock extends Block {
        private final byte[] data;

        public AckBlock(int keyID, int n) {
            super(BLOCK_ACKKEY);
            data = new byte[4];
            DataHelper.toLong(data, 0, 2, keyID);
            DataHelper.toLong(data, 2, 2, n);
        }

        public int getDataLength() {
            return 4;
        }

        public int writeData(byte[] tgt, int off) {
            System.arraycopy(data, 0, tgt, off, data.length);
            return off + data.length;
        }
    }

    /**
     *  @since 0.9.46
     */
    public static class AckRequestBlock extends Block {
        private final byte[] data;

        public AckRequestBlock(int sessionID, DeliveryInstructions di) {
            super(BLOCK_REPLYDI);
            data = new byte[5 + di.getSize()];
            DataHelper.toLong(data, 0, 4, sessionID);
            // flag is zero
            di.writeBytes(data, 5);
        }

        public int getDataLength() {
            return data.length;
        }

        public int writeData(byte[] tgt, int off) {
            System.arraycopy(data, 0, tgt, off, data.length);
            return off + data.length;
        }
    }

    public static class TerminationBlock extends Block {
        private final byte rsn;
        private final long rcvd;

        public TerminationBlock(int reason, long lastReceived) {
            super(BLOCK_TERMINATION);
            rsn = (byte) reason;
            rcvd = lastReceived;
        }

        public int getDataLength() {
            return 9;
        }

        public int writeData(byte[] tgt, int off) {
            toLong8(tgt, off, rcvd);
            tgt[off + 8] = rsn;
            return off + 9;
        }
    }

    /**
     * Big endian.
     * Same as DataHelper.fromLong(src, offset, 8) but allows negative result
     *
     * @throws ArrayIndexOutOfBoundsException
     */
    static long fromLong8(byte src[], int offset) {
        long rv = 0;
        int limit = offset + 8;
        for (int i = offset; i < limit; i++) {
            rv <<= 8;
            rv |= src[i] & 0xFF;
        }
        return rv;
    }
    
    /**
     * Big endian.
     * Same as DataHelper.toLong(target, offset, 8, value) but allows negative value
     *
     * @throws ArrayIndexOutOfBoundsException
     */
    static void toLong8(byte target[], int offset, long value) {
        for (int i = offset + 7; i >= offset; i--) {
            target[i] = (byte) value;
            value >>= 8;
        }
    }
}
