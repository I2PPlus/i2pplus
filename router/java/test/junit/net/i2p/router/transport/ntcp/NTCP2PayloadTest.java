package net.i2p.router.transport.ntcp;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.i2p.crypto.KeyGenerator;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.util.OrderedProperties;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *  Tests for NTCP2 payload generation and parsing (NTCP2Payload),
 *  and for the per-writer-thread block pooling (NTCPConnection.BlockPool).
 */
public class NTCP2PayloadTest {

    private static RouterContext _ctx;

    @BeforeClass
    public static void checkContext() {
        _ctx = RouterTestHelper.getContext();
    }

    /**
     *  A DataMessage with the given payload and unique id.
     */
    private DataMessage createMessage(byte[] data, long uniqueId) {
        DataMessage msg = new DataMessage(_ctx);
        msg.setData(data);
        msg.setUniqueId(uniqueId);
        return msg;
    }

    /**
     *  Write the blocks and parse them back, recording the callbacks.
     */
    private Recorder writeAndParse(NTCP2Payload.Block... blocks) throws IOException, DataFormatException, I2NPMessageException {
        return writeAndParse(false, blocks);
    }

    /**
     *  Write the blocks and parse them back, recording the callbacks.
     *
     *  @param isHandshake true for the NTCP2 message 3 part 2 handshake frame
     */
    private Recorder writeAndParse(boolean isHandshake, NTCP2Payload.Block... blocks) throws IOException, DataFormatException, I2NPMessageException {
        List<NTCP2Payload.Block> list = new ArrayList<>(blocks.length);
        for (NTCP2Payload.Block b : blocks)
            list.add(b);
        byte[] out = new byte[4096];
        int written = NTCP2Payload.writePayload(out, 0, list);
        Recorder rec = new Recorder();
        NTCP2Payload.processPayload(_ctx, rec, out, 0, written, isHandshake);
        return rec;
    }

    /**
     *  Records every PayloadCallback invocation.
     */
    private static class Recorder implements NTCP2Payload.PayloadCallback {
        private final List<I2NPMessage> i2np = new ArrayList<>(4);
        private long dateTime;
        private boolean gotDateTime;
        private byte[] options;
        private RouterInfo ri;
        private boolean riHandshake;
        private boolean riFlood;
        private int terminationReason;
        private long terminationLast;
        private int paddingLen;
        private int frameLen;
        private int unknownType;
        private int unknownLen;

        public void gotDateTime(long time) {
            dateTime = time;
            gotDateTime = true;
        }

        public void gotI2NP(I2NPMessage msg) {
            i2np.add(msg);
        }

        public void gotOptions(byte[] options, boolean isHandshake) {
            this.options = options;
        }

        public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) {
            this.ri = ri;
            riHandshake = isHandshake;
            riFlood = flood;
        }

        public void gotTermination(int reason, long lastReceived) {
            terminationReason = reason;
            terminationLast = lastReceived;
        }

        public void gotPadding(int paddingLength, int frameLength) {
            paddingLen = paddingLength;
            frameLen = frameLength;
        }

        public void gotUnknown(int type, int len) {
            unknownType = type;
            unknownLen = len;
        }
    }

    /**
     *  A small RouterInfo with one address, valid for serialization.
     *  Compact enough that the flood flag survives the 3 KB limit
     *  imposed by the NTCP2 payload parser.
     */
    private static RouterInfo createRouterInfo() throws DataFormatException {
        RouterInfo info = new RouterInfo();
        RouterIdentity ident = new RouterIdentity();
        ident.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
        Object[] obj = KeyGenerator.getInstance().generatePKIKeypair();
        ident.setPublicKey((PublicKey) obj[0]);
        obj = KeyGenerator.getInstance().generateSigningKeypair();
        ident.setSigningPublicKey((SigningPublicKey) obj[0]);
        SigningPrivateKey signingPrivKey = (SigningPrivateKey) obj[1];
        info.setIdentity(ident);
        OrderedProperties options = new OrderedProperties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        Set<RouterAddress> addrs = new HashSet<>(1);
        addrs.add(new RouterAddress("NTCP2", options, 42));
        info.setAddresses(addrs);
        info.setPublished(System.currentTimeMillis());
        info.sign(signingPrivKey);
        return info;
    }

    /**
     *  A byte array filled with a deterministic pattern.
     */
    private static byte[] fill(int size, byte start) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++)
            data[i] = (byte) (start + i);
        return data;
    }

    @Test
    public void testI2NPBlockRoundTrip() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        byte[] data = fill(512, (byte) 10);
        DataMessage msg = createMessage(data, 42);
        NTCP2Payload.I2NPBlock block = new NTCP2Payload.I2NPBlock(msg);
        assertEquals("data length", msg.getMessageSize() - 7, block.getDataLength());
        assertEquals("total length", msg.getMessageSize() - 4, block.getTotalLength());
        Recorder rec = writeAndParse(block);
        assertEquals(1, rec.i2np.size());
        DataMessage parsed = (DataMessage) rec.i2np.get(0);
        assertArrayEquals(data, parsed.getData());
        assertEquals(msg.getUniqueId(), parsed.getUniqueId());
        // expiration is rounded up to seconds on the wire, and the parser
        // rounds back up to milliseconds
        assertEquals(((msg.getMessageExpiration() + 500) / 1000) * 1000 + 500, parsed.getMessageExpiration());
    }

    @Test
    public void testPaddingBlockRoundTrip() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        NTCP2Payload.PaddingBlock block = new NTCP2Payload.PaddingBlock(100);
        assertEquals(100, block.getDataLength());
        assertEquals(103, block.getTotalLength());
        List<NTCP2Payload.Block> list = new ArrayList<>(1);
        list.add(block);
        byte[] out = new byte[256];
        int written = NTCP2Payload.writePayload(out, 0, list);
        assertEquals(103, written);
        // block header: type 254, then two-byte length
        assertEquals(254, out[0] & 0xff);
        assertEquals(100, DataHelper.fromLong(out, 1, 2));
        // zero-filled data
        for (int i = 3; i < written; i++)
            assertEquals(0, out[i]);
        Recorder rec = new Recorder();
        NTCP2Payload.processPayload(_ctx, rec, out, 0, written, false);
        assertEquals(100, rec.paddingLen);
        assertEquals(written, rec.frameLen);
    }

    @Test
    public void testDateTimeBlockRoundTrip() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        long before = _ctx.clock().now();
        NTCP2Payload.DateTimeBlock block = new NTCP2Payload.DateTimeBlock(_ctx);
        long after = _ctx.clock().now();
        assertEquals(4, block.getDataLength());
        Recorder rec = writeAndParse(block);
        assertTrue("missing datetime", rec.gotDateTime);
        assertTrue("datetime " + rec.dateTime + " before " + before, rec.dateTime >= (before + 500) / 1000 * 1000);
        assertTrue("datetime " + rec.dateTime + " after " + after, rec.dateTime <= (after + 500) / 1000 * 1000 + 1000);
    }

    @Test
    public void testRIBlockRoundTrip() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        RouterInfo ri = createRouterInfo();
        NTCP2Payload.RIBlock block = new NTCP2Payload.RIBlock(ri, true);
        // the parser only honors the flood flag for router infos under 3 KB
        assertTrue("RI too large for flood flag", block.getDataLength() < 3072);
        Recorder rec = writeAndParse(block);
        assertNotNull("missing routerinfo", rec.ri);
        assertArrayEquals(ri.toByteArray(), rec.ri.toByteArray());
        assertTrue("flood flag", rec.riFlood);
        assertFalse("handshake flag", rec.riHandshake);
    }

    @Test
    public void testTerminationBlockRoundTrip() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        NTCP2Payload.TerminationBlock block = new NTCP2Payload.TerminationBlock(3, 12345678L);
        assertEquals(9, block.getDataLength());
        List<NTCP2Payload.Block> list = new ArrayList<>(1);
        list.add(block);
        byte[] out = new byte[64];
        int written = NTCP2Payload.writePayload(out, 0, list);
        assertEquals(4, out[0] & 0xff);
        Recorder rec = new Recorder();
        NTCP2Payload.processPayload(_ctx, rec, out, 0, written, false);
        assertEquals(3, rec.terminationReason);
        assertEquals(12345678L, rec.terminationLast);
    }

    @Test
    public void testOptionsBlockRoundTrip() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        byte[] opts = new byte[] {1, 2, 3, 4};
        NTCP2Payload.OptionsBlock block = new NTCP2Payload.OptionsBlock(opts);
        Recorder rec = writeAndParse(block);
        assertNotNull("missing options", rec.options);
        assertArrayEquals(opts, rec.options);
    }

    @Test
    public void testMultiBlockFrame() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        byte[] data = fill(128, (byte) 1);
        DataMessage msg = createMessage(data, 7);
        RouterInfo ri = createRouterInfo();
        Recorder rec = writeAndParse(new NTCP2Payload.I2NPBlock(msg),
                                     new NTCP2Payload.DateTimeBlock(_ctx),
                                     new NTCP2Payload.RIBlock(ri, false),
                                     new NTCP2Payload.PaddingBlock(50));
        assertEquals(1, rec.i2np.size());
        assertArrayEquals(data, ((DataMessage) rec.i2np.get(0)).getData());
        assertTrue("missing datetime", rec.gotDateTime);
        assertNotNull("missing routerinfo", rec.ri);
        assertFalse("flood flag", rec.riFlood);
        assertEquals(50, rec.paddingLen);
    }

    @Test
    public void testTerminationThenPaddingFrame() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        Recorder rec = writeAndParse(new NTCP2Payload.TerminationBlock(1, 99),
                                     new NTCP2Payload.PaddingBlock(16));
        assertEquals(1, rec.terminationReason);
        assertEquals(99, rec.terminationLast);
        assertEquals(16, rec.paddingLen);
    }

    @Test
    public void testHandshakeFrame() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        RouterInfo ri = createRouterInfo();
        Recorder rec = writeAndParse(true, new NTCP2Payload.RIBlock(ri, false),
                                           new NTCP2Payload.PaddingBlock(16));
        assertNotNull("missing routerinfo", rec.ri);
        assertTrue("handshake flag", rec.riHandshake);
        assertEquals(16, rec.paddingLen);
    }

    @Test
    public void testI2NPBlockReuse() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        byte[] data1 = fill(256, (byte) 20);
        byte[] data2 = fill(64, (byte) 30);
        DataMessage m1 = createMessage(data1, 1);
        DataMessage m2 = createMessage(data2, 2);
        NTCP2Payload.I2NPBlock block = new NTCP2Payload.I2NPBlock(m1);
        assertEquals(m1.getMessageSize() - 7, block.getDataLength());
        Recorder r1 = writeAndParse(block);
        assertEquals(1, r1.i2np.size());
        assertArrayEquals(data1, ((DataMessage) r1.i2np.get(0)).getData());
        // reuse the same block for a different message
        block.setMessage(m2);
        assertEquals("data length after reuse", m2.getMessageSize() - 7, block.getDataLength());
        Recorder r2 = writeAndParse(block);
        assertEquals(1, r2.i2np.size());
        assertArrayEquals("no stale bytes from previous message", data2, ((DataMessage) r2.i2np.get(0)).getData());
        assertEquals(2, ((DataMessage) r2.i2np.get(0)).getUniqueId());
    }

    @Test
    public void testPaddingBlockReuse() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        NTCP2Payload.PaddingBlock block = new NTCP2Payload.PaddingBlock(10);
        assertEquals(10, block.getDataLength());
        // reuse the same block with a new size
        block.setSize(100);
        assertEquals("data length after reuse", 100, block.getDataLength());
        List<NTCP2Payload.Block> list = new ArrayList<>(1);
        list.add(block);
        byte[] out = new byte[256];
        int written = NTCP2Payload.writePayload(out, 0, list);
        assertEquals(103, written);
        assertEquals(100, DataHelper.fromLong(out, 1, 2));
        Recorder rec = new Recorder();
        NTCP2Payload.processPayload(_ctx, rec, out, 0, written, false);
        assertEquals(100, rec.paddingLen);
    }

    @Test
    public void testBlockPoolReusesInstances() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        NTCPConnection.BlockPool pool = new NTCPConnection.BlockPool();
        DataMessage m1 = createMessage(fill(32, (byte) 40), 1);
        DataMessage m2 = createMessage(fill(48, (byte) 41), 2);
        NTCP2Payload.I2NPBlock b1 = pool.acquireI2NP(m1);
        List<NTCP2Payload.Block> list = new ArrayList<>(2);
        list.add(b1);
        pool.release(list);
        NTCP2Payload.I2NPBlock b2 = pool.acquireI2NP(m2);
        assertSame("I2NP block not reused", b1, b2);
        assertEquals("message not reset on reuse", m2.getMessageSize() - 7, b2.getDataLength());
        NTCP2Payload.PaddingBlock p1 = pool.acquirePadding(11);
        list.clear();
        list.add(p1);
        pool.release(list);
        NTCP2Payload.PaddingBlock p2 = pool.acquirePadding(22);
        assertSame("padding block not reused", p1, p2);
        assertEquals(22, p2.getDataLength());
        // non-pooled block types are dropped on release, not added to the pool
        list.clear();
        list.add(new NTCP2Payload.TerminationBlock(1, 2));
        pool.release(list);
        NTCP2Payload.PaddingBlock p3 = pool.acquirePadding(33);
        assertNotSame("termination block leaked into padding pool", p1, p3);
    }

    @Test
    public void testBlockPoolListReuse() {
        NTCPConnection.BlockPool pool = new NTCPConnection.BlockPool();
        List<NTCP2Payload.Block> list = pool.blocks;
        assertNotNull(list);
        list.add(new NTCP2Payload.PaddingBlock(1));
        list.add(new NTCP2Payload.PaddingBlock(2));
        assertEquals(2, list.size());
        list.clear();
        assertEquals(0, list.size());
        assertSame("block list not reused", list, pool.blocks);
    }

    @Test
    public void testBlockRunsOverFrame() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        byte[] payload = new byte[5];
        payload[0] = 3;
        DataHelper.toLong(payload, 1, 2, 80);
        try {
            Recorder rec = new Recorder();
            NTCP2Payload.processPayload(_ctx, rec, payload, 0, payload.length, false);
            fail("no exception thrown");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("runs over frame"));
        }
    }

    @Test
    public void testIllegalBlockAfterPadding() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        byte[] payload = new byte[] {(byte) 254, 0, 1, 0, 3, 0, 1, 0};
        try {
            Recorder rec = new Recorder();
            NTCP2Payload.processPayload(_ctx, rec, payload, 0, payload.length, false);
            fail("no exception thrown");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Illegal block after padding"));
        }
    }

    @Test
    public void testBadDateTimeLength() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        byte[] payload = new byte[] {0, 0, 5, 0, 0, 0, 0, 0};
        try {
            Recorder rec = new Recorder();
            NTCP2Payload.processPayload(_ctx, rec, payload, 0, payload.length, false);
            fail("no exception thrown");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Bad length for DATETIME"));
        }
    }

    @Test
    public void testI2NPTooShort() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        byte[] payload = new byte[] {3, 0, 4, 0, 0, 0, 0};
        try {
            Recorder rec = new Recorder();
            NTCP2Payload.processPayload(_ctx, rec, payload, 0, payload.length, false);
            fail("no exception thrown");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("I2NP block too short"));
        }
    }

    @Test
    public void testTerminationTooShort() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        byte[] payload = new byte[] {4, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0};
        try {
            Recorder rec = new Recorder();
            NTCP2Payload.processPayload(_ctx, rec, payload, 0, payload.length, false);
            fail("no exception thrown");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Bad length for TERMINATION"));
        }
    }

    @Test
    public void testUnknownBlockType() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        byte[] payload = new byte[] {99, 0, 1, 0};
        Recorder rec = new Recorder();
        NTCP2Payload.processPayload(_ctx, rec, payload, 0, payload.length, false);
        assertEquals(99, rec.unknownType);
        assertEquals(1, rec.unknownLen);
    }

    @Test
    public void testHandshakeFirstBlockNotRI() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        byte[] payload = new byte[] {3, 0, 4, 0, 0, 0, 0};
        try {
            Recorder rec = new Recorder();
            NTCP2Payload.processPayload(_ctx, rec, payload, 0, payload.length, true);
            fail("no exception thrown");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Illegal first block in handshake"));
        }
    }

    @Test
    public void testEmptyHandshakeFrame() throws Exception {
        Assume.assumeTrue("No RouterContext available", _ctx != null);
        byte[] payload = new byte[0];
        try {
            Recorder rec = new Recorder();
            NTCP2Payload.processPayload(_ctx, rec, payload, 0, 0, true);
            fail("no exception thrown");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("No blocks in handshake"));
        }
    }
}