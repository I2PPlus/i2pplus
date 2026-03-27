package net.i2p.router.crypto.ratchet;

import static org.junit.Assert.*;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 *  Tests for RatchetPayload block serialization and deserialization.
 */
public class RatchetPayloadBlockTest {

    @Test
    public void testDateTimeBlockRoundTrip() throws Exception {
        long now = System.currentTimeMillis();
        RatchetPayload.DateTimeBlock block = new RatchetPayload.DateTimeBlock(now);

        assertEquals(4, block.getDataLength());
        assertEquals(RatchetPayload.BLOCK_HEADER_SIZE + 4, block.getTotalLength());

        byte[] buf = new byte[block.getTotalLength()];
        block.write(buf, 0);

        // Parse back
        TestCallback cb = new TestCallback();
        RatchetPayload.processPayload(I2PAppContext.getGlobalContext(), cb, buf, 0, buf.length, false);
        // DateTime is truncated to seconds, so compare at second precision
        assertEquals(now / 1000, cb.dateTime / 1000);
    }

    @Test
    public void testPaddingBlock() throws Exception {
        RatchetPayload.PaddingBlock block = new RatchetPayload.PaddingBlock(10);
        assertEquals(10, block.getDataLength());
        assertEquals(RatchetPayload.BLOCK_HEADER_SIZE + 10, block.getTotalLength());

        byte[] buf = new byte[block.getTotalLength()];
        block.write(buf, 0);

        TestCallback cb = new TestCallback();
        RatchetPayload.processPayload(I2PAppContext.getGlobalContext(), cb, buf, 0, buf.length, false);
        assertEquals(10, cb.paddingLength);
    }

    @Test
    public void testOptionsBlockRoundTrip() throws Exception {
        byte[] opts = new byte[] {0x01, 0x02, 0x03, 0x04};
        RatchetPayload.OptionsBlock block = new RatchetPayload.OptionsBlock(opts);

        assertEquals(4, block.getDataLength());

        byte[] buf = new byte[block.getTotalLength()];
        block.write(buf, 0);

        TestCallback cb = new TestCallback();
        RatchetPayload.processPayload(I2PAppContext.getGlobalContext(), cb, buf, 0, buf.length, false);
        assertArrayEquals(opts, cb.options);
    }

    @Test
    public void testTerminationBlockRoundTrip() throws Exception {
        int reason = 42;
        RatchetPayload.TerminationBlock block = new RatchetPayload.TerminationBlock(reason);

        assertEquals(1, block.getDataLength());

        byte[] buf = new byte[block.getTotalLength()];
        block.write(buf, 0);

        TestCallback cb = new TestCallback();
        RatchetPayload.processPayload(I2PAppContext.getGlobalContext(), cb, buf, 0, buf.length, false);
        assertEquals(reason, cb.terminationReason);
    }

    @Test
    public void testPNBlockRoundTrip() throws Exception {
        int pn = 12345;
        RatchetPayload.PNBlock block = new RatchetPayload.PNBlock(pn);

        assertEquals(2, block.getDataLength());

        byte[] buf = new byte[block.getTotalLength()];
        block.write(buf, 0);

        TestCallback cb = new TestCallback();
        RatchetPayload.processPayload(I2PAppContext.getGlobalContext(), cb, buf, 0, buf.length, false);
        assertEquals(pn, cb.pn);
    }

    @Test
    public void testAckBlockRoundTrip() throws Exception {
        int keyID = 7;
        int n = 3;
        RatchetPayload.AckBlock block = new RatchetPayload.AckBlock(keyID, n);

        assertEquals(4, block.getDataLength());

        byte[] buf = new byte[block.getTotalLength()];
        block.write(buf, 0);

        TestCallback cb = new TestCallback();
        RatchetPayload.processPayload(I2PAppContext.getGlobalContext(), cb, buf, 0, buf.length, false);
        assertEquals(keyID, cb.ackId);
        assertEquals(n, cb.ackN);
    }

    @Test
    public void testAckBlockMultipleRoundTrip() throws Exception {
        List<Integer> acks = new ArrayList<>();
        acks.add((5 << 16) | 2);
        acks.add((7 << 16) | 4);
        RatchetPayload.AckBlock block = new RatchetPayload.AckBlock(acks);

        assertEquals(8, block.getDataLength());

        byte[] buf = new byte[block.getTotalLength()];
        block.write(buf, 0);

        TestCallback cb = new TestCallback();
        RatchetPayload.processPayload(I2PAppContext.getGlobalContext(), cb, buf, 0, buf.length, false);
        // Callback stores last ack processed (overwrites per call)
        assertEquals(7, cb.ackId);
        assertEquals(4, cb.ackN);
    }

    @Test
    public void testAckRequestBlockRoundTrip() throws Exception {
        RatchetPayload.AckRequestBlock block = new RatchetPayload.AckRequestBlock();

        assertEquals(1, block.getDataLength());

        byte[] buf = new byte[block.getTotalLength()];
        block.write(buf, 0);

        TestCallback cb = new TestCallback();
        RatchetPayload.processPayload(I2PAppContext.getGlobalContext(), cb, buf, 0, buf.length, false);
        assertTrue(cb.gotAckRequest);
    }

    @Test
    public void testNextKeyBlockNoKeyRoundTrip() throws Exception {
        NextSessionKey nsk = new NextSessionKey(42, false, true);
        RatchetPayload.NextKeyBlock block = new RatchetPayload.NextKeyBlock(nsk);

        assertEquals(3, block.getDataLength());

        byte[] buf = new byte[block.getTotalLength()];
        block.write(buf, 0);

        TestCallback cb = new TestCallback();
        RatchetPayload.processPayload(I2PAppContext.getGlobalContext(), cb, buf, 0, buf.length, false);
        assertNotNull(cb.nextKey);
        assertEquals(42, cb.nextKey.getID());
        assertFalse(cb.nextKey.isReverse());
        assertTrue(cb.nextKey.isRequest());
        assertNull(cb.nextKey.getData());
    }

    @Test
    public void testMultipleBlocksInPayload() throws Exception {
        List<RatchetPayload.Block> blocks = new ArrayList<>();
        blocks.add(new RatchetPayload.DateTimeBlock(System.currentTimeMillis()));
        blocks.add(new RatchetPayload.PaddingBlock(5));

        byte[] buf = new byte[512];
        int end = RatchetPayload.writePayload(buf, 0, blocks);
        assertTrue(end > 0);

        TestCallback cb = new TestCallback();
        int num = RatchetPayload.processPayload(I2PAppContext.getGlobalContext(), cb, buf, 0, end, false);
        assertEquals(2, num);
    }

    @Test(expected = java.io.IOException.class)
    public void testEmptyHandshakeThrows() throws Exception {
        TestCallback cb = new TestCallback();
        RatchetPayload.processPayload(I2PAppContext.getGlobalContext(), cb, new byte[0], 0, 0, true);
    }

    @Test(expected = java.io.IOException.class)
    public void testBadDateTimeLengthThrows() throws Exception {
        // Craft a block with wrong length for DATETIME
        byte[] buf = new byte[10];
        buf[0] = 0; // BLOCK_DATETIME type
        DataHelper.toLong(buf, 1, 2, 3); // wrong length (should be 4)
        buf[3] = 0;
        buf[4] = 0;
        buf[5] = 0;
        TestCallback cb = new TestCallback();
        RatchetPayload.processPayload(I2PAppContext.getGlobalContext(), cb, buf, 0, 6, false);
    }

    /**
     * Simple callback implementation for testing block parsing.
     */
    private static class TestCallback implements RatchetPayload.PayloadCallback {
        long dateTime;
        byte[] options;
        int terminationReason = -1;
        int pn = -1;
        NextSessionKey nextKey;
        int ackId = -1, ackN = -1;
        boolean gotAckRequest;
        int paddingLength = -1;

        public void gotDateTime(long time) {
            dateTime = time;
        }

        public void gotGarlic(net.i2p.data.i2np.GarlicClove clove) {}

        public void gotOptions(byte[] options, boolean isHandshake) {
            this.options = options;
        }

        public void gotTermination(int reason) {
            terminationReason = reason;
        }

        public void gotPN(int pn) {
            this.pn = pn;
        }

        public void gotNextKey(NextSessionKey nextKey) {
            this.nextKey = nextKey;
        }

        public void gotAck(int id, int n) {
            ackId = id;
            ackN = n;
        }

        public void gotAckRequest() {
            gotAckRequest = true;
        }

        public void gotPadding(int paddingLength, int frameLength) {
            this.paddingLength = paddingLength;
        }

        public void gotUnknown(int type, int len) {}
    }
}
