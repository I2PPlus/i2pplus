package net.i2p.router.transport.udp;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.router.RouterInfo;

import org.junit.Test;

/**
 * Tests the block-parsing logic split out of SSU2Payload.processPayload().
 * Each per-type parse* helper, plus the frame order/overflow guards, is
 * exercised directly where the framing data is simple to construct.
 */
public class SSU2PayloadTest {

    private static final I2PAppContext CTX = new I2PAppContext();

    /**
     * Records every PayloadCallback invocation as a string of the form
     * "method:arg1:arg2:..." so a test can assert the exact callback sequence.
     */
    private static final class Recorder implements SSU2Payload.PayloadCallback {
        final List<String> events = new ArrayList<String>();

        void reset() { events.clear(); }

        public void gotDateTime(long time) throws DataFormatException { events.add("datetime:" + time); }
        public void gotI2NP(I2NPMessage msg) throws I2NPMessageException { events.add("i2np"); }
        public void gotFragment(byte[] data, int off, int len, long id, int frag, boolean isLast)
                throws DataFormatException {
            events.add("fragment:" + id + ":" + frag + ":" + isLast + ":" + hx(copy(data, off, len)));
        }
        public void gotACK(long ackThru, int acks, byte[] ranges) {
            events.add("ack:" + ackThru + ":" + acks + ":" + (ranges == null ? null : hx(ranges)));
        }
        public void gotOptions(byte[] options, boolean isHandshake) throws DataFormatException {
            events.add("options:" + isHandshake + ":" + hx(options));
        }
        public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) throws DataFormatException {
            events.add("ri:" + isHandshake + ":" + flood + ":" + ri.getPublished());
        }
        public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped,
                                  int frag, int totalFrags) {
            events.add("rifrag:" + isHandshake + ":" + flood + ":" + isGzipped + ":" + frag + ":" + totalFrags + ":" + hx(data));
        }
        public void gotAddress(byte[] ip, int port) { events.add("address:" + port + ":" + hx(ip)); }
        public void gotRelayTagRequest() { events.add("relaytagreq"); }
        public void gotRelayTag(long tag) { events.add("relaytag:" + tag); }
        public void gotRelayRequest(byte[] data) { events.add("relayreq:" + hx(data)); }
        public void gotRelayResponse(int status, byte[] data) { events.add("relayresp:" + status + ":" + hx(data)); }
        public void gotRelayIntro(Hash aliceHash, byte[] data) {
            events.add("relayintro:" + (aliceHash == null ? null : aliceHash.toBase64()) + ":" + hx(data));
        }
        public void gotPeerTest(int msg, int status, Hash h, byte[] data) {
            events.add("peertest:" + msg + ":" + status + ":" + (h == null ? null : h.toBase64()) + ":" + hx(data));
        }
        public void gotToken(long token, long expires) { events.add("token:" + token + ":" + expires); }
        public void gotTermination(int reason, long lastReceived) { events.add("termination:" + reason + ":" + lastReceived); }
        public void gotPathChallenge(RemoteHostId from, byte[] data) { events.add("pathchallenge:" + hx(data)); }
        public void gotPathResponse(RemoteHostId from, byte[] data) { events.add("pathresponse:" + hx(data)); }
    }

    /** one block: [type][2-byte length][data] */
    private static byte[] blk(int type, byte[] data) {
        byte[] b = new byte[3 + data.length];
        b[0] = (byte) type;
        b[1] = (byte) (data.length >> 8);
        b[2] = (byte) data.length;
        System.arraycopy(data, 0, b, 3, data.length);
        return b;
    }

    /** concatenate blocks into one payload frame */
    private static byte[] frame(byte[]... blocks) {
        int n = 0;
        for (byte[] b : blocks)
            n += b.length;
        byte[] f = new byte[n];
        int off = 0;
        for (byte[] b : blocks) {
            System.arraycopy(b, 0, f, off, b.length);
            off += b.length;
        }
        return f;
    }

    /** big-endian unsigned value of nBytes bytes */
    private static byte[] be(int nBytes, long v) {
        byte[] b = new byte[nBytes];
        for (int i = nBytes - 1; i >= 0; i--) {
            b[i] = (byte) (v & 0xff);
            v >>= 8;
        }
        return b;
    }

    private static byte[] copy(byte[] src, int off, int len) {
        byte[] d = new byte[len];
        System.arraycopy(src, off, d, 0, len);
        return d;
    }

    private static String hx(byte[] b) {
        if (b == null)
            return "null";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            sb.append(Character.forDigit((b[i] >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b[i] & 0xf, 16));
        }
        return sb.toString();
    }

    private static void process(Recorder r, byte[] f, boolean handshake) throws Exception {
        SSU2Payload.processPayload(CTX, r, f, 0, f.length, handshake, null);
    }

    // ---------- order guards ----------

    @Test
    public void testOrderGuardsAfterPadding() {
        try {
            SSU2Payload.checkBlockOrder(SSU2Payload.BLOCK_DATETIME, true, false, false, 1);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("after padding"));
        }
    }

    @Test
    public void testOrderGuardsAfterTermination() {
        try {
            SSU2Payload.checkBlockOrder(SSU2Payload.BLOCK_OPTIONS, false, true, false, 1);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("after termination"));
        }
        // padding after termination is legal
        try {
            SSU2Payload.checkBlockOrder(SSU2Payload.BLOCK_PADDING, false, true, false, 1);
        } catch (IOException ioe) {
            fail("padding after termination must be legal, got: " + ioe.getMessage());
        }
    }

    @Test
    public void testOrderGuardsHandshakeFirstIsDatetime() {
        try {
            SSU2Payload.checkBlockOrder(SSU2Payload.BLOCK_OPTIONS, false, false, true, 0);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Illegal first block"));
        }
        // DATETIME as the first handshake block is legal
        try {
            SSU2Payload.checkBlockOrder(SSU2Payload.BLOCK_DATETIME, false, false, true, 0);
        } catch (IOException ioe) {
            fail("DATETIME first must be legal, got: " + ioe.getMessage());
        }
    }

    @Test
    public void testHandshakeEmptyFrame() throws Exception {
        Recorder r = new Recorder();
        try {
            process(r, new byte[0], true);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("No blocks"));
        }
        // non-handshake empty frame is fine
        r.reset();
        assertEquals(0, SSU2Payload.processPayload(CTX, r, new byte[0], 0, 0, false, null));
        assertTrue(r.events.isEmpty());
    }

    // ---------- frame overflow guard ----------

    @Test
    public void testFrameOverflowExactFitIsOk() throws Exception {
        byte[] f = blk(SSU2Payload.BLOCK_DATETIME, be(4, 1));
        SSU2Payload.checkFrameOverflow(f, 0, f.length, 3, 4, 0, SSU2Payload.BLOCK_DATETIME);
    }

    @Test
    public void testFrameOverflowRunsOver() {
        byte[] f = new byte[7]; // DATETIME type + len claims 32, but only 4 data bytes present
        f[0] = (byte) SSU2Payload.BLOCK_DATETIME;
        f[1] = 0;
        f[2] = 32;
        try {
            SSU2Payload.checkFrameOverflow(f, 0, f.length, 3, 32, 0, SSU2Payload.BLOCK_DATETIME);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("runs over frame of size " + f.length));
        }
    }

    @Test
    public void testProcessPayloadFrameOverflow() throws Exception {
        byte[] f = new byte[7];
        f[0] = (byte) SSU2Payload.BLOCK_DATETIME;
        f[1] = 0;
        f[2] = 32;
        Recorder r = new Recorder();
        try {
            process(r, f, false);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("runs over frame"));
        }
    }

    // ---------- per-type parsers ----------

    @Test
    public void testParseDateTime() throws Exception {
        Recorder r = new Recorder();
        byte[] f = blk(SSU2Payload.BLOCK_DATETIME, be(4, 1)); // 1 second = 1000 ms
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, true, null));
        assertEquals("datetime:1000", r.events.get(0));
        // bad length
        try {
            SSU2Payload.parseDateTime(r, f, 3, 3);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Bad length"));
        }
    }

    @Test
    public void testParseOptions() throws Exception {
        Recorder r = new Recorder();
        byte[] f = blk(SSU2Payload.BLOCK_OPTIONS, new byte[] {1, 2, 3});
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("options:false:010203", r.events.get(0));
        // handshake-style options frame
        byte[] hs = frame(blk(SSU2Payload.BLOCK_DATETIME, be(4, 1)), blk(SSU2Payload.BLOCK_OPTIONS, new byte[] {9}));
        r.reset();
        assertEquals(2, SSU2Payload.processPayload(CTX, r, hs, 0, hs.length, true, null));
        assertTrue(r.events.get(1), r.events.get(1).equals("options:true:09"));
    }

    @Test
    public void testParseAddressIPv4() throws Exception {
        Recorder r = new Recorder();
        byte[] ip = new byte[] {10, 0, 0, 1};
        byte[] data = new byte[6];
        System.arraycopy(be(2, 1234), 0, data, 0, 2);
        System.arraycopy(ip, 0, data, 2, 4);
        byte[] f = blk(SSU2Payload.BLOCK_ADDRESS, data);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("address:1234:0a000001", r.events.get(0));
    }

    @Test
    public void testParseAddressIPv6() throws Exception {
        Recorder r = new Recorder();
        byte[] ip = new byte[16];
        for (int i = 0; i < ip.length; i++)
            ip[i] = (byte) (i + 1);
        byte[] data = new byte[18];
        System.arraycopy(be(2, 4321), 0, data, 0, 2);
        System.arraycopy(ip, 0, data, 2, 16);
        byte[] f = blk(SSU2Payload.BLOCK_ADDRESS, data);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("address:4321:0102030405060708090a0b0c0d0e0f10", r.events.get(0));
    }

    @Test
    public void testParseAddressBadLength() throws Exception {
        Recorder r = new Recorder();
        byte[] f = blk(SSU2Payload.BLOCK_ADDRESS, new byte[7]);
        try {
            process(r, f, false);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Bad length for Address"));
        }
    }

    @Test
    public void testParseRelayTag() throws Exception {
        Recorder r = new Recorder();
        byte[] f = blk(SSU2Payload.BLOCK_RELAYTAG, be(4, 123456789L));
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("relaytag:123456789", r.events.get(0));
        try {
            SSU2Payload.parseRelayTag(r, f, 3, 3);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Bad length for RELAYTAG"));
        }
    }

    @Test
    public void testParseRelayTagRequest() throws Exception {
        Recorder r = new Recorder();
        byte[] f = blk(SSU2Payload.BLOCK_RELAYTAGREQ, new byte[0]);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("relaytagreq", r.events.get(0));
    }

    @Test
    public void testParseNewToken() throws Exception {
        Recorder r = new Recorder();
        byte[] data = new byte[12];
        System.arraycopy(be(4, 200), 0, data, 0, 4);
        System.arraycopy(be(8, 0x1122334455667788L), 0, data, 4, 8);
        byte[] f = blk(SSU2Payload.BLOCK_NEWTOKEN, data);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("token:1234605616436508552:200000", r.events.get(0));
        try {
            SSU2Payload.parseNewToken(r, f, 3, 11);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Bad length for NEWTOKEN"));
        }
    }

    @Test
    public void testParseTermination() throws Exception {
        Recorder r = new Recorder();
        byte[] data = new byte[9];
        System.arraycopy(be(8, 0x0A0B0C0D0E0F1011L), 0, data, 0, 8);
        data[8] = 3;
        byte[] f = blk(SSU2Payload.BLOCK_TERMINATION, data);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("termination:3:723685415333072913", r.events.get(0));
        try {
            SSU2Payload.parseTermination(r, f, 3, 8);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Bad length for TERMINATION"));
        }
    }

    @Test
    public void testParseFirstFragment() throws Exception {
        Recorder r = new Recorder();
        byte[] body = new byte[] {0x41, 0x42, 0x43, 0x44, 0x45, 0x46};
        byte[] data = new byte[1 + 4 + body.length];
        data[0] = 0;
        System.arraycopy(be(4, 77), 0, data, 1, 4);
        System.arraycopy(body, 0, data, 5, body.length);
        byte[] f = blk(SSU2Payload.BLOCK_FIRSTFRAG, data);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("fragment:77:0:false:" + hx(data), r.events.get(0));
        // too short
        try {
            SSU2Payload.parseFirstFragment(r, f, 3, 9, false);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Bad length for FIRSTFRAG"));
        }
        // illegal in handshake
        try {
            process(r, frame(blk(SSU2Payload.BLOCK_DATETIME, be(4, 1)), f), true);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Illegal block in handshake"));
        }
    }

    @Test
    public void testParseFollowonFragment() throws Exception {
        Recorder r = new Recorder();
        byte[] body = new byte[] {0x51, 0x52, 0x53, 0x54, 0x55, 0x56};
        byte[] data = new byte[1 + 4 + body.length];
        data[0] = (byte) ((3 << 1) | 1); // frag 3, isLast
        System.arraycopy(be(4, 88), 0, data, 1, 4);
        System.arraycopy(body, 0, data, 5, body.length);
        byte[] f = blk(SSU2Payload.BLOCK_FOLLOWONFRAG, data);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("fragment:88:3:true:" + hx(body), r.events.get(0));
        // frag 0 is invalid even though isLast=true
        byte[] zeroFrag = data.clone();
        zeroFrag[0] = 1;
        byte[] f2 = blk(SSU2Payload.BLOCK_FOLLOWONFRAG, zeroFrag);
        try {
            process(r, f2, false);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("0 frag"));
        }
        // too short
        try {
            SSU2Payload.parseFollowonFragment(r, f, 3, 5, false);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Bad length for FOLLOWON"));
        }
    }

    @Test
    public void testParseACK() throws Exception {
        Recorder r = new Recorder();
        byte[] ranges = new byte[] {0x10, 0x11};
        byte[] data = new byte[4 + 1 + ranges.length];
        System.arraycopy(be(4, 0x01020304L), 0, data, 0, 4);
        data[4] = 2;
        System.arraycopy(ranges, 0, data, 5, ranges.length);
        byte[] f = blk(SSU2Payload.BLOCK_ACK, data);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("ack:16909060:2:1011", r.events.get(0));
        // no ranges -> null
        byte[] noRanges = new byte[5];
        System.arraycopy(be(4, 7), 0, noRanges, 0, 4);
        noRanges[4] = 0;
        byte[] f2 = blk(SSU2Payload.BLOCK_ACK, noRanges);
        r.reset();
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f2, 0, f2.length, false, null));
        assertEquals("ack:7:0:null", r.events.get(0));
        // even length is invalid
        byte[] even = new byte[8];
        byte[] f3 = blk(SSU2Payload.BLOCK_ACK, even);
        try {
            process(r, f3, false);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Bad length for ACK"));
        }
    }

    @Test
    public void testParseRelayRequest() throws Exception {
        Recorder r = new Recorder();
        byte[] data = new byte[60];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte) (i + 1);
        byte[] blockData = new byte[1 + data.length]; // skip-flag byte
        System.arraycopy(data, 0, blockData, 1, data.length);
        byte[] f = blk(SSU2Payload.BLOCK_RELAYREQ, blockData);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("relayreq:" + hx(data), r.events.get(0));
        // too short
        try {
            SSU2Payload.parseRelayRequest(r, f, 3, 60, false);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Bad length for RELAYREQ"));
        }
        // illegal in handshake
        try {
            process(r, frame(blk(SSU2Payload.BLOCK_DATETIME, be(4, 1)), f), true);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Illegal block in handshake"));
        }
    }

    @Test
    public void testParseRelayResponse() throws Exception {
        Recorder r = new Recorder();
        byte[] sig = new byte[50];
        for (int i = 0; i < sig.length; i++)
            sig[i] = (byte) (i + 1);
        byte[] blockData = new byte[2 + sig.length];
        blockData[0] = 0; // flag
        blockData[1] = 5; // status
        System.arraycopy(sig, 0, blockData, 2, sig.length);
        byte[] f = blk(SSU2Payload.BLOCK_RELAYRESP, blockData);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("relayresp:5:" + hx(sig), r.events.get(0));
        try {
            SSU2Payload.parseRelayResponse(r, f, 3, 51, false);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Bad length for RELAYRESP"));
        }
    }

    @Test
    public void testParseRelayIntro() throws Exception {
        Recorder r = new Recorder();
        byte[] hash = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < hash.length; i++)
            hash[i] = (byte) (i + 1);
        byte[] sig = new byte[60];
        for (int i = 0; i < sig.length; i++)
            sig[i] = (byte) (0x40 + i);
        byte[] blockData = new byte[1 + hash.length + sig.length];
        blockData[0] = 0; // flag
        System.arraycopy(hash, 0, blockData, 1, hash.length);
        System.arraycopy(sig, 0, blockData, 1 + hash.length, sig.length);
        byte[] f = blk(SSU2Payload.BLOCK_RELAYINTRO, blockData);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        Hash expected = Hash.create(hash);
        assertEquals("relayintro:" + expected.toBase64() + ":" + hx(sig), r.events.get(0));
        try {
            SSU2Payload.parseRelayIntro(r, f, 3, 92, false);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Bad length for RELAYINTRO"));
        }
    }

    @Test
    public void testParsePeerTest() throws Exception {
        Recorder r = new Recorder();
        // msg 1: no hash
        byte[] data16 = new byte[16];
        for (int i = 0; i < data16.length; i++)
            data16[i] = (byte) (i + 1);
        byte[] blockData = new byte[3 + data16.length];
        blockData[0] = 1; // msg
        blockData[1] = 0; // status
        blockData[2] = 0; // flag
        System.arraycopy(data16, 0, blockData, 3, data16.length);
        byte[] f = blk(SSU2Payload.BLOCK_PEERTEST, blockData);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("peertest:1:0:null:" + hx(data16), r.events.get(0));

        // msg 2: with Alice hash
        byte[] hash = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < hash.length; i++)
            hash[i] = (byte) (i + 1);
        byte[] data14 = new byte[14];
        for (int i = 0; i < data14.length; i++)
            data14[i] = (byte) (0x20 + i);
        byte[] blockData2 = new byte[3 + hash.length + data14.length];
        blockData2[0] = 2;
        blockData2[1] = 1;
        blockData2[2] = 0;
        System.arraycopy(hash, 0, blockData2, 3, hash.length);
        System.arraycopy(data14, 0, blockData2, 3 + hash.length, data14.length);
        byte[] f2 = blk(SSU2Payload.BLOCK_PEERTEST, blockData2);
        r.reset();
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f2, 0, f2.length, false, null));
        Hash expected = Hash.create(hash);
        assertEquals("peertest:2:1:" + expected.toBase64() + ":" + hx(data14), r.events.get(0));

        // msg 0 and 8 are invalid
        byte[] bad = blockData.clone();
        bad[0] = 0;
        byte[] f3 = blk(SSU2Payload.BLOCK_PEERTEST, bad);
        try {
            process(r, f3, false);
            fail("expected DataFormatException");
        } catch (DataFormatException dfe) {
            assertTrue(dfe.getMessage(), dfe.getMessage().contains("Bad PEERTEST number"));
        }
        bad[0] = 8;
        byte[] f4 = blk(SSU2Payload.BLOCK_PEERTEST, bad);
        try {
            process(r, f4, false);
            fail("expected DataFormatException");
        } catch (DataFormatException dfe) {
            assertTrue(dfe.getMessage(), dfe.getMessage().contains("Bad PEERTEST number"));
        }
    }

    @Test
    public void testParsePathChallengeAndResponse() throws Exception {
        Recorder r = new Recorder();
        byte[] ch = new byte[] {0x0a, 0x0b, 0x0c};
        byte[] f = blk(SSU2Payload.BLOCK_PATHCHALLENGE, ch);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("pathchallenge:0a0b0c", r.events.get(0));
        byte[] resp = new byte[] {0x1a, 0x1b};
        byte[] f2 = blk(SSU2Payload.BLOCK_PATHRESP, resp);
        r.reset();
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f2, 0, f2.length, false, null));
        assertEquals("pathresponse:1a1b", r.events.get(0));
        // illegal in handshake
        try {
            process(r, frame(blk(SSU2Payload.BLOCK_DATETIME, be(4, 1)), f), true);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Illegal block in handshake"));
        }
    }

    // ---------- RouterInfo ----------

    @Test
    public void testParseRouterInfoFragment() throws Exception {
        Recorder r = new Recorder();
        byte[] piece = new byte[10];
        for (int i = 0; i < piece.length; i++)
            piece[i] = (byte) (i + 1);
        byte[] blockData = new byte[2 + piece.length];
        blockData[0] = 0x02; // gz
        blockData[1] = (byte) ((2 << 4) | 3); // fnum 2, ftot 3
        System.arraycopy(piece, 0, blockData, 2, piece.length);
        byte[] f = blk(SSU2Payload.BLOCK_ROUTERINFO, blockData);
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertEquals("rifrag:false:false:true:2:3:" + hx(piece), r.events.get(0));
    }

    @Test
    public void testParseRouterInfoBadFragmentCount() throws Exception {
        Recorder r = new Recorder();
        byte[] blockData = new byte[] {0, 0}; // fnum 0, ftot 0
        byte[] f = blk(SSU2Payload.BLOCK_ROUTERINFO, blockData);
        try {
            process(r, f, false);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Bad fragment count"));
        }
    }

    @Test
    public void testParseRouterInfoGarbageRejects() throws Exception {
        // single unfragmented RouterInfo that is not parseable and does not verify;
        // 300 bytes of noise leaves enough trailing data that the recover path
        // attempts a signature check over them and fails, rethrowing the parse error
        byte[] garbage = new byte[300];
        for (int i = 0; i < garbage.length; i++)
            garbage[i] = 0x05;
        byte[] blockData = new byte[2 + garbage.length];
        blockData[0] = 0;
        blockData[1] = 1; // fnum 0, ftot 1
        System.arraycopy(garbage, 0, blockData, 2, garbage.length);
        byte[] f = blk(SSU2Payload.BLOCK_ROUTERINFO, blockData);
        Recorder r = new Recorder();
        try {
            process(r, f, false);
            fail("expected IOException or DataFormatException");
        } catch (IOException ioe) {
            // EOF mid-routerinfo surfaces as an IOException
        } catch (DataFormatException dfe) {
            // recover path rethrows the original parse failure
        }
    }

    @Test
    public void testParseRouterInfoTooBigRejects() throws Exception {
        // decompressed size over the RouterInfo cap fails deterministically
        byte[] oversized = new byte[RouterInfo.MAX_UNCOMPRESSED_SIZE + 1];
        byte[] blockData = new byte[2 + oversized.length];
        blockData[1] = 1; // fnum 0, ftot 1
        System.arraycopy(oversized, 0, blockData, 2, oversized.length);
        byte[] f = blk(SSU2Payload.BLOCK_ROUTERINFO, blockData);
        Recorder r = new Recorder();
        try {
            process(r, f, false);
            fail("expected DataFormatException");
        } catch (DataFormatException dfe) {
            assertTrue(dfe.getMessage(), dfe.getMessage().contains("RouterInfo too big"));
        }
    }

    // ---------- multi-block frames ----------

    @Test
    public void testScanOrderDatetimeOptionsPadding() throws Exception {
        Recorder r = new Recorder();
        byte[] f = frame(blk(SSU2Payload.BLOCK_DATETIME, be(4, 1)),
                         blk(SSU2Payload.BLOCK_OPTIONS, new byte[] {1, 2, 3}),
                         blk(SSU2Payload.BLOCK_PADDING, new byte[0]));
        assertEquals(3, SSU2Payload.processPayload(CTX, r, f, 0, f.length, true, null));
        assertEquals("datetime:1000", r.events.get(0));
        assertEquals("options:true:010203", r.events.get(1));
    }

    @Test
    public void testBlockAfterPaddingRejected() throws Exception {
        Recorder r = new Recorder();
        byte[] f = frame(blk(SSU2Payload.BLOCK_DATETIME, be(4, 1)),
                         blk(SSU2Payload.BLOCK_PADDING, new byte[0]),
                         blk(SSU2Payload.BLOCK_OPTIONS, new byte[] {1}));
        try {
            process(r, f, false);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("after padding"));
        }
    }

    @Test
    public void testTerminationThenPaddingIsLegal() throws Exception {
        Recorder r = new Recorder();
        byte[] term = new byte[9];
        System.arraycopy(be(8, 0x0A0B0C0D0E0F1011L), 0, term, 0, 8);
        term[8] = 2;
        byte[] f = frame(blk(SSU2Payload.BLOCK_DATETIME, be(4, 1)),
                         blk(SSU2Payload.BLOCK_TERMINATION, term),
                         blk(SSU2Payload.BLOCK_PADDING, new byte[0]));
        assertEquals(3, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertTrue(r.events.get(1), r.events.get(1).equals("termination:2:723685415333072913"));
    }

    @Test
    public void testBlockAfterTerminationRejected() throws Exception {
        Recorder r = new Recorder();
        byte[] term = new byte[9];
        term[8] = 2;
        byte[] f = frame(blk(SSU2Payload.BLOCK_DATETIME, be(4, 1)),
                         blk(SSU2Payload.BLOCK_TERMINATION, term),
                         blk(SSU2Payload.BLOCK_OPTIONS, new byte[] {1}));
        try {
            process(r, f, false);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("after termination"));
        }
    }

    @Test
    public void testUnknownBlockSkipped() throws Exception {
        Recorder r = new Recorder();
        byte[] f = blk(0x7a, new byte[] {1, 2, 3, 4});
        assertEquals(1, SSU2Payload.processPayload(CTX, r, f, 0, f.length, false, null));
        assertTrue(r.events.isEmpty());
    }

    @Test
    public void testGarbageFirstBlockNotDatetimeInHandshake() throws Exception {
        Recorder r = new Recorder();
        byte[] f = blk(SSU2Payload.BLOCK_OPTIONS, new byte[] {1});
        try {
            process(r, f, true);
            fail("expected IOException");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage(), ioe.getMessage().contains("Illegal first block"));
        }
    }
}
