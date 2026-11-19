package net.i2p.router.transport.ntcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.Arrays;

import net.i2p.crypto.KeyPair;
import net.i2p.data.SessionKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.router.transport.crypto.X25519KeyFactory;
import net.i2p.util.RandomSource;

import org.junit.Test;

/**
 * Every NTCP2 message 1 rejection waits for the same random budget of further
 * bytes before it tears the connection down.
 *
 * <p>A prober that sends one bad prefix and watches the connection learns more
 * from when it closes than from what it sent. The Noise read failure already
 * answered by draining a random number of extra bytes first; the replay check
 * and the zero-key check, which run before it, answered by closing the instant
 * the prefix was read. That gap says "this is an I2P2 NTCP2 listener", and it
 * says which of the three checks the prefix tripped. These tests pin that all
 * three now stop at the same state and fail at the same point in the drain.
 *
 * <p>No socket and no transport: the connection comes from the private
 * constructor, the transport is a mock, and the context's random source is
 * replaced so the drain budget is a known number.
 *
 * @since 0.9.72+
 */
public class NTCP2Msg1ProbeDrainTest {

    /**
     * A fixture group for the message 1 drain; it holds no state of its own.
     *
     * @since 0.9.72+
     */
    public NTCP2Msg1ProbeDrainTest() {}

    /** The drain budget the fixed random source always asks for (100 bytes). */
    private static final int BUDGET = 100;

    /** The static IV the state decrypts X with, stubbed onto the transport. */
    private static final byte[] IV = filled((byte) 0x33, OutboundNTCP2State.IV_SIZE);

    /**
     * A replayed X - one the transport's replay filter has seen - is rejected, but
     * the connection stays open in the drain state instead of going straight to
     * CORRUPT. At HEAD this path called fail() directly.
     *
     * @throws Exception if the harness cannot reach the private constructor
     */
    @Test
    public void replayedPrefixWaitsForBytes() throws Exception {
        RouterContext ctx = fixedRandomContext();
        NTCPTransport transport = transport(ctx, false);
        InboundEstablishState est = newState(ctx, transport);
        est.receive(buffer(xFor(ctx, transport, nonZeroPlain())));
        assertEquals("a replayed X went straight to the terminal state",
                     EstablishBase.State.IB_NTCP2_READ_RANDOM, est._state);
    }

    /**
     * An X that decrypts to all zeros is rejected the same way. At HEAD this path
     * called fail() directly as well.
     *
     * @throws Exception if the harness cannot reach the private constructor
     */
    @Test
    public void zeroKeyPrefixWaitsForBytes() throws Exception {
        RouterContext ctx = fixedRandomContext();
        NTCPTransport transport = transport(ctx, true);
        InboundEstablishState est = newState(ctx, transport);
        est.receive(buffer(xFor(ctx, transport, OutboundNTCP2State.ZEROKEY)));
        assertEquals("an all-zero X went straight to the terminal state",
                     EstablishBase.State.IB_NTCP2_READ_RANDOM, est._state);
    }

    /**
     * A prefix that passes both cheap checks and then fails the Noise read is the
     * path that already drained; it is pinned here so the comparison the other two
     * are measured against cannot move.
     *
     * @throws Exception if the harness cannot reach the private constructor
     */
    @Test
    public void noiseReadFailureWaitsForBytes() throws Exception {
        RouterContext ctx = fixedRandomContext();
        NTCPTransport transport = transport(ctx, true);
        InboundEstablishState est = newState(ctx, transport);
        byte[] msg1 = new byte[OutboundNTCP2State.msg1Size(2)];
        System.arraycopy(xFor(ctx, transport, nonZeroPlain()), 0, msg1, 0, OutboundNTCP2State.KEY_SIZE);
        Arrays.fill(msg1, OutboundNTCP2State.KEY_SIZE, msg1.length, (byte) 0x7f);
        est.receive(buffer(msg1));
        assertEquals("a failed Noise read left the drain state",
                     EstablishBase.State.IB_NTCP2_READ_RANDOM, est._state);
    }

    /**
     * The drain holds the connection until the budget arrives, however it is
     * fragmented, and fails on the byte that completes it.
     *
     * @throws Exception if the harness cannot reach the private constructor
     */
    @Test
    public void drainFailsOnlyOnceTheBudgetArrives() throws Exception {
        RouterContext ctx = fixedRandomContext();
        NTCPTransport transport = transport(ctx, false);
        InboundEstablishState est = newState(ctx, transport);
        est.receive(buffer(xFor(ctx, transport, nonZeroPlain())));
        est.receive(buffer(new byte[40]));
        assertEquals("the drain failed before the budget arrived",
                     EstablishBase.State.IB_NTCP2_READ_RANDOM, est._state);
        est.receive(buffer(new byte[BUDGET - 40 - 1]));
        assertEquals("the drain failed one byte early",
                     EstablishBase.State.IB_NTCP2_READ_RANDOM, est._state);
        est.receive(buffer(new byte[1]));
        assertEquals("the drain never failed once the budget arrived",
                     EstablishBase.State.CORRUPT, est._state);
        assertNotNull("the failure reason was lost", est.getFailReason());
    }

    /**
     * Bytes already sitting in the buffer count toward the budget, so a prober that
     * coalesces its prefix with a flood of junk is not owed another full budget on
     * top of what it just sent.
     *
     * @throws Exception if the harness cannot reach the private constructor
     */
    @Test
    public void coalescedBytesCountTowardTheBudget() throws Exception {
        RouterContext ctx = fixedRandomContext();
        NTCPTransport transport = transport(ctx, false);
        InboundEstablishState est = newState(ctx, transport);
        byte[] msg = new byte[OutboundNTCP2State.KEY_SIZE + BUDGET];
        System.arraycopy(xFor(ctx, transport, nonZeroPlain()), 0, msg, 0, OutboundNTCP2State.KEY_SIZE);
        est.receive(buffer(msg));
        assertEquals("the coalesced bytes were not counted toward the budget",
                     EstablishBase.State.CORRUPT, est._state);
    }

    /**
     * A context whose random source always answers with the drain budget, so the
     * tests can say exactly how many bytes the drain must wait for.
     *
     * @return an isolated router context with a fixed random source
     */
    private static RouterContext fixedRandomContext() {
        RouterContext ctx = spy(RouterTestHelper.newContext());
        assertNotNull("no test context", ctx);
        doReturn(new FixedBudgetRandom(ctx)).when(ctx).random();
        return ctx;
    }

    /**
     * A random source that always asks for the same drain budget, so the tests can
     * say exactly how many bytes the drain must wait for. Everything else, the key
     * generation included, comes from the real SecureRandom behind it.
     *
     * @since 0.9.72+
     */
    private static final class FixedBudgetRandom extends RandomSource {

        /**
         * @param context the context this source is bound to
         */
        FixedBudgetRandom(RouterContext context) {
            super(context);
        }

        /**
         * @param n the exclusive upper bound the caller expects
         * @return {@link #BUDGET}, whatever the bound
         */
        @Override
        public int nextInt(int n) {
            return BUDGET;
        }
    }

    /**
     * A transport that reports the static keys and IV the handshake needs, and
     * whose replay filter either accepts or rejects the prefix under test.
     *
     * @param ctx the context to build the key factory with
     * @param seen false to make the prefix look like a replay
     * @return a transport stubbed for one inbound handshake
     */
    private static NTCPTransport transport(RouterContext ctx, boolean seen) {
        NTCPTransport transport = mock(NTCPTransport.class, RETURNS_DEEP_STUBS);
        when(transport.isHXHIValid(any(byte[].class))).thenReturn(seen);
        when(transport.getNTCP2StaticIV()).thenReturn(IV);
        X25519KeyFactory factory = new X25519KeyFactory(ctx);
        KeyPair pair = factory.getKeys();
        when(transport.getXDHFactory()).thenReturn(factory);
        when(transport.getNTCP2StaticPrivkey()).thenReturn(pair.getPrivate().getData());
        when(transport.getNTCP2StaticPubkey()).thenReturn(pair.getPublic().getData());
        return transport;
    }

    /**
     * A connection with no socket and no address, and the inbound establish state
     * under test attached to it.
     *
     * @param ctx the context to build it with
     * @param transport the transport stub to hand it
     * @return an inbound establish state in its initial state
     * @throws Exception if the private constructor cannot be reached
     */
    private static InboundEstablishState newState(RouterContext ctx, NTCPTransport transport) throws Exception {
        Constructor<NTCPConnection> ctor = NTCPConnection.class.getDeclaredConstructor(
                RouterContext.class, NTCPTransport.class, RouterAddress.class, boolean.class);
        ctor.setAccessible(true);
        NTCPConnection con = ctor.newInstance(ctx, transport, new RouterAddress(), true);
        return new InboundEstablishState(ctx, transport, con);
    }

    /**
     * A plaintext X that is not all zeros and does not ask for a PQ handshake, so
     * the version detection leaves the version at 2.
     *
     * @return 32 bytes of X plaintext that pass the cheap checks
     */
    private static byte[] nonZeroPlain() {
        byte[] plain = filled((byte) 0x5a, OutboundNTCP2State.KEY_SIZE);
        plain[OutboundNTCP2State.KEY_SIZE - 1] = 0;
        return plain;
    }

    /**
     * The 32 bytes to send as X so that the state decrypts it back to plain.
     *
     * @param ctx the context whose router hash is the X key
     * @param transport the transport whose static IV is the X IV
     * @param plain the X plaintext the state should recover
     * @return the encrypted prefix
     */
    private static byte[] xFor(RouterContext ctx, NTCPTransport transport, byte[] plain) {
        byte[] x = plain.clone();
        SessionKey key = new SessionKey(ctx.routerHash().getData());
        ctx.aes().encrypt(x, 0, x, 0, key, transport.getNTCP2StaticIV(), x.length);
        return x;
    }

    /**
     * @param data the bytes a reader would have handed the connection
     * @return a buffer holding exactly those bytes, ready to read
     */
    private static ByteBuffer buffer(byte[] data) {
        return ByteBuffer.wrap(data);
    }

    /**
     * @param value the byte to fill with
     * @param length how many bytes
     * @return the filled array
     */
    private static byte[] filled(byte value, int length) {
        byte[] data = new byte[length];
        Arrays.fill(data, value);
        return data;
    }
}
