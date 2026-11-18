package net.i2p.router.tunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.RouterContext;
import net.i2p.stat.RateStat;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests that a fragment which cannot be stored leaves no reassembly slot behind.
 *
 * <p>Both parsers used to insert a {@link FragmentedMessage} into the reassembly
 * map before they knew the fragment was usable, and they only armed the 45 second
 * removal when the fragment was accepted. A fragment declaring an impossible
 * size - zero, or more bytes than are left in the message - therefore cost an
 * entry no timer would ever take out again, and the map is capped at 128, so 128
 * such fragments close defragmentation at that tunnel endpoint permanently, at a
 * cost to the sender of one small fragment each. The parsers now check the size
 * against what the message actually holds before the entry exists, and the
 * removal is armed before the fragment is stored, so every entry in the map has
 * an event that will take it out again.
 *
 * <p>No gateway and no preprocessor: the buffers are built in the form the
 * endpoint expects, { IV, H(payload+IV)[0:3], padding terminator, fragments },
 * with a checksum that verifies, so what is under test is the parser and not the
 * verifier in front of it.
 *
 * @since 0.9.72+
 */
public class FragmentHandlerValidationTest {

    /** Where the fragments begin: the IV, the checksum and the padding terminator. */
    private static final int FRAGMENTS = HopProcessor.IV_LENGTH + 4 + 1;

    /** The delivery instruction's tunnel id, fixed so the delivery can be checked. */
    private static final long TUNNEL_ID = 42;

    /** The delivery instruction's target router. */
    private static final Hash TARGET = target();

    private static RouterContext _context;

    /**
     * A bare context is enough: the handler needs the log, the digest pool, the
     * stat manager and the timer, all of which a RouterContext builds itself.
     */
    @BeforeClass
    public static void globalSetUp() {
        _context = new RouterContext(null);
        TunnelDispatcher.registerFragmentStats(_context.statManager());
    }

    /**
     * A fixture group for the fragment parsers; it holds no state.
     *
     * @since 0.9.72+
     */
    public FragmentHandlerValidationTest() {}

    /**
     * An initial fragment that declares no payload at all is refused, and it
     * never reaches the reassembly map.
     */
    @Test
    public void testAZeroSizeInitialFragmentLeavesNoSlot() {
        FragmentHandler handler = new FragmentHandler(_context, new RecordingReceiver(), false);
        byte[] buf = packet(initial(17, 0, new byte[8]));
        assertFalse(handler.receiveTunnelMessage(buf, 0, buf.length));
        assertEquals(0, handler.getPendingCount());
    }

    /**
     * An initial fragment declaring more than the message holds is refused too.
     * The declared size is what the reassembly would size itself to, so a peer
     * that sends 128 of these used to own the map.
     */
    @Test
    public void testAnOversizedInitialFragmentLeavesNoSlot() {
        FragmentHandler handler = new FragmentHandler(_context, new RecordingReceiver(), false);
        byte[] buf = packet(initial(18, 4096, new byte[8]));
        assertFalse(handler.receiveTunnelMessage(buf, 0, buf.length));
        assertEquals(0, handler.getPendingCount());
    }

    /**
     * A follow-on fragment numbered 0 is not a follow-on fragment at all - that
     * number belongs to the initial one - so it is refused without opening a slot
     * for the message it claims to continue.
     */
    @Test
    public void testAZeroFragmentNumberLeavesNoSlot() {
        FragmentHandler handler = new FragmentHandler(_context, new RecordingReceiver(), false);
        byte[] buf = packet(subsequent(19, 0, true, 8, new byte[8]));
        assertFalse(handler.receiveTunnelMessage(buf, 0, buf.length));
        assertEquals(0, handler.getPendingCount());
    }

    /**
     * A follow-on fragment whose header is cut short by the end of the message is
     * refused before any of its fields are read: here the size field is not in
     * the message at all.
     */
    @Test
    public void testATruncatedFollowOnHeaderLeavesNoSlot() {
        FragmentHandler handler = new FragmentHandler(_context, new RecordingReceiver(), false);
        byte[] buf = packet(Arrays.copyOf(subsequent(20, 1, true, 8, new byte[8]), 5));
        assertFalse(handler.receiveTunnelMessage(buf, 0, buf.length));
        assertEquals(0, handler.getPendingCount());
    }

    /**
     * Refusals do not close the map: 320 of them, twice the cap, and a real
     * two-fragment message still reassembles and is delivered, with the
     * map-full counter never incremented.
     */
    @Test
    public void testRefusalsNeverFillTheMap() {
        RecordingReceiver receiver = new RecordingReceiver();
        FragmentHandler handler = new FragmentHandler(_context, receiver, false);
        for (int i = 0; i < 320; i++) {
            byte[] buf = packet(initial(1000 + i, 0, new byte[8]));
            boolean ok = handler.receiveTunnelMessage(buf, 0, buf.length);
            assertFalse("refusal " + i + " was accepted", ok);
        }
        assertEquals(0, handler.getPendingCount());
        RateStat full = _context.statManager().getRate("tunnel.fragmentMapFull");
        assertNotNull(full);
        assertEquals(0, full.getLifetimeEventCount());

        byte[] body = i2np("hello");
        byte[] head = Arrays.copyOfRange(body, 0, 10);
        byte[] tail = Arrays.copyOfRange(body, 10, body.length);
        byte[] buf = packet(initial(99, head.length, head),
                            subsequent(99, 1, true, tail.length, tail));
        assertTrue("a valid message was refused after the refusals",
                   handler.receiveTunnelMessage(buf, 0, buf.length));
        assertEquals(1, receiver._delivered);
        assertEquals(0, handler.getPendingCount());
    }

    /**
     * A fragment that gets past the parser and is then rejected by the
     * reassembly - here one over the 996 byte fragment ceiling the message class
     * enforces - still has its slot taken out again, because the removal is
     * armed before the fragment is stored. An entry with no event behind it is
     * permanent.
     */
    @Test
    public void testARejectedFragmentStillGetsAnExpireEvent() {
        FragmentHandler handler = new FragmentHandler(_context, new RecordingReceiver(), false);
        int size = 1000;
        byte[] buf = packet(initial(55, size, new byte[size]));
        assertFalse(handler.receiveTunnelMessage(buf, 0, buf.length));
        assertEquals(1, handler.getPendingCount());
        FragmentedMessage msg = handler.getOrCreate(55, 55);
        assertNotNull(msg);
        assertNotNull("the slot has nothing that will ever remove it", msg.getExpireEvent());
    }

    /**
     * A two-fragment message reassembles, is delivered to the receiver with the
     * delivery instructions of the initial fragment, and leaves the map empty
     * with its removal cancelled.
     */
    @Test
    public void testTwoFragmentsReassembleAndAreDelivered() {
        RecordingReceiver receiver = new RecordingReceiver();
        FragmentHandler handler = new FragmentHandler(_context, receiver, false);
        byte[] body = i2np("hello");
        byte[] head = Arrays.copyOfRange(body, 0, 10);
        byte[] tail = Arrays.copyOfRange(body, 10, body.length);
        byte[] buf = packet(initial(7, head.length, head),
                            subsequent(7, 1, true, tail.length, tail));
        assertTrue(handler.receiveTunnelMessage(buf, 0, buf.length));
        assertEquals(1, receiver._delivered);
        assertEquals(TARGET, receiver._router);
        assertEquals(new TunnelId(TUNNEL_ID), receiver._tunnel);
        assertEquals(0, handler.getPendingCount());
    }

    /**
     * A buffer in the form the endpoint verifies: IV, the first four bytes of
     * H(payload+IV), the padding terminator, then the fragments.
     *
     * @param fragments the fragment encodings, in order
     * @return a buffer holding them, with a checksum that verifies
     */
    private static byte[] packet(byte[]... fragments) {
        int total = 0;
        for (byte[] f : fragments)
            total += f.length;
        byte[] buf = new byte[FRAGMENTS + total];
        _context.random().nextBytes(buf);
        buf[HopProcessor.IV_LENGTH + 4] = 0; // no padding
        int off = FRAGMENTS;
        for (byte[] f : fragments) {
            System.arraycopy(f, 0, buf, off, f.length);
            off += f.length;
        }
        MessageDigest digest = _context.sha().acquire();
        try {
            digest.update(buf, FRAGMENTS, total);
            digest.update(buf, 0, HopProcessor.IV_LENGTH);
            System.arraycopy(digest.digest(), 0, buf, HopProcessor.IV_LENGTH, 4);
        } finally {
            _context.sha().release(digest);
        }
        return buf;
    }

    /**
     * An initial fragment: the delivery instruction for a tunnel, the target
     * router, the message id, the declared size, then the payload.
     *
     * @param msgId the fragmented message id
     * @param size the size to declare, which may not match the payload
     * @param payload the fragment payload
     * @return the encoded fragment
     */
    private static byte[] initial(long msgId, int size, byte[] payload) {
        byte[] b = new byte[11 + Hash.HASH_LENGTH + payload.length];
        b[0] = (byte) (FragmentHandler.TYPE_TUNNEL << 5 | FragmentHandler.MASK_FRAGMENTED);
        DataHelper.toLong(b, 1, 4, TUNNEL_ID);
        System.arraycopy(TARGET.getData(), 0, b, 5, Hash.HASH_LENGTH);
        DataHelper.toLong(b, 5 + Hash.HASH_LENGTH, 4, msgId);
        DataHelper.toLong(b, 9 + Hash.HASH_LENGTH, 2, size);
        System.arraycopy(payload, 0, b, 11 + Hash.HASH_LENGTH, payload.length);
        return b;
    }

    /**
     * A follow-on fragment: its number and the last flag, the message id, the
     * declared size, then the payload.
     *
     * @param msgId the fragmented message id
     * @param num sequence number within the message, as it goes on the wire
     * @param last whether this is the message's final fragment
     * @param size the size to declare, which may not match the payload
     * @param payload the fragment payload
     * @return the encoded fragment
     */
    private static byte[] subsequent(long msgId, int num, boolean last, int size, byte[] payload) {
        byte[] b = new byte[7 + payload.length];
        b[0] = (byte) (FragmentHandler.MASK_IS_SUBSEQUENT | (num << 1) | (last ? 1 : 0));
        DataHelper.toLong(b, 1, 4, msgId);
        DataHelper.toLong(b, 5, 2, size);
        System.arraycopy(payload, 0, b, 7, payload.length);
        return b;
    }

    /**
     * An I2NP message body the outbound endpoint can parse: type, id, expiration,
     * body length, checksum, then the body.
     *
     * @param body the message body to carry
     * @return the encoded message
     */
    private static byte[] i2np(String body) {
        byte[] b = new byte[16 + body.length()];
        b[0] = 0; // PING, a type the endpoint does not need to know
        DataHelper.toLong(b, 1, 4, 7);
        DataHelper.toLong(b, 5, 8, _context.clock().now());
        DataHelper.toLong(b, 13, 2, body.length());
        b[15] = 0;
        System.arraycopy(body.getBytes(StandardCharsets.US_ASCII), 0, b, 16, body.length());
        return b;
    }

    /**
     * A fixed target router for the delivery instructions, so the delivery can be
     * compared against it.
     *
     * @return a hash of 20 known bytes
     */
    private static Hash target() {
        byte[] b = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) i;
        return new Hash(b);
    }

    /**
     * Counts what the endpoint hands on, and keeps the delivery instructions it
     * came with.
     */
    private static class RecordingReceiver implements FragmentHandler.DefragmentedReceiver {

        /** Messages handed on so far. */
        private int _delivered;
        /** The target router of the last message delivered. */
        private Hash _router;
        /** The target tunnel of the last message delivered. */
        private TunnelId _tunnel;

        /**
         * Records one delivery.
         *
         * @param msg the reassembled I2NP message
         * @param toRouter where we are told to send the message, null means locally
         * @param toTunnel where we are told to send the message, null means locally
         */
        @Override
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel) {
            _delivered++;
            _router = toRouter;
            _tunnel = toTunnel;
        }
    }
}
