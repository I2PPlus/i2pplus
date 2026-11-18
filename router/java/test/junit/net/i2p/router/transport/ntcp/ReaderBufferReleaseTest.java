package net.i2p.router.transport.ntcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import net.i2p.data.router.RouterAddress;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;
import net.i2p.util.TryCache;
import org.junit.Test;

/**
 * Tests that the reader hands its pooled read buffer back whatever the handshake
 * did with it.
 *
 * <p>The read buffers come from a bounded pool, so one that is not returned is
 * not a leak in the strict sense but pool starvation: the buffer becomes
 * garbage and the next reader allocates another 8 KiB in its place. A peer that
 * keeps failing handshakes therefore turns into a steady stream of allocations
 * and the same number of objects to collect, on the reader threads. The data
 * phase of the same method already returned the buffer on its failure paths;
 * these tests pin that the handshake phase does too, and that it does so exactly
 * once, because the pool has no duplicate detection and a buffer handed back
 * twice is given to two readers at once.
 *
 * <p>No socket and no transport: the connection is built by the private
 * constructor and the establish state is set directly.
 *
 * @since 0.9.72+
 */
public class ReaderBufferReleaseTest {

    /**
     * A fixture group for the reader's buffer handling; it holds no state.
     *
     * @since 0.9.72+
     */
    public ReaderBufferReleaseTest() {}

    /**
     * A handshake that throws - the shared failed state rejects any data with an
     * IllegalStateException - returns the buffer it was reading, exactly once,
     * cleared and ready for the next reader.
     *
     * @throws Exception if the harness cannot reach the code under test
     */
    @Test
    public void testAThrowingHandshakeReturnsTheBuffer() throws Exception {
        assertBufferReturned(EstablishBase.FAILED);
    }

    /**
     * A handshake that throws a NullPointerException - the shape a half-built
     * establish state shows - returns the buffer as well.
     *
     * @throws Exception if the harness cannot reach the code under test
     */
    @Test
    public void testANullPointerHandshakeReturnsTheBuffer() throws Exception {
        EstablishState est = mock(EstablishState.class);
        when(est.isComplete()).thenReturn(false);
        doThrow(new NullPointerException("no read state")).when(est).receive(any(ByteBuffer.class));
        assertBufferReturned(est);
    }

    /**
     * A handshake that completes normally returns the buffer once too - the
     * release moved into the finally must not join the one that was already
     * there, or two readers get the same buffer.
     *
     * @throws Exception if the harness cannot reach the code under test
     */
    @Test
    public void testASuccessfulHandshakeReturnsTheBufferOnce() throws Exception {
        EstablishState est = mock(EstablishState.class);
        when(est.isComplete()).thenReturn(false);
        when(est.isCorrupt()).thenReturn(false);
        assertBufferReturned(est);
    }

    /**
     * The data phase returns the buffer when the decrypt throws, which is the
     * behaviour the handshake phase is being matched to.
     *
     * @throws Exception if the harness cannot reach the code under test
     */
    @Test
    public void testAThrowingDecryptReturnsTheBuffer() throws Exception {
        assertBufferReturned(EstablishBase.VERIFIED);
    }

    /**
     * Drives one read pass over one buffer and asserts the pool holds that same
     * buffer afterwards, and nothing else.
     *
     * @param est the establish state the connection is in
     * @throws Exception if the harness cannot reach the private constructor or
     *                   the private read pass, or if the read pass itself throws
     */
    private static void assertBufferReturned(EstablishState est) throws Exception {
        RouterContext ctx = RouterTestHelper.newContext();
        TryCache<ByteBuffer> cache = bufferCache();
        cache.clear();
        ByteBuffer buf = cache.acquire();
        buf.clear();
        NTCPConnection con = newConnection(ctx);
        setEstablishState(con, est);
        con.recv(buf);
        Reader reader = new Reader(ctx);
        Method processRead = Reader.class.getDeclaredMethod("processRead", NTCPConnection.class);
        processRead.setAccessible(true);
        processRead.invoke(reader, con);
        assertSame("the read buffer was not returned to the pool", buf, cache.acquire());
        assertEquals("the read buffer was returned more than once", 0, cache.size());
        assertEquals("the buffer was not cleared for the next reader", 0, buf.position());
        assertEquals("the buffer was not cleared for the next reader", buf.capacity(), buf.limit());
    }

    /**
     * The reader hands buffers back to this pool, so it is what the assertions
     * measure.
     *
     * @return the shared read-buffer cache
     * @throws Exception if the field cannot be reached
     */
    @SuppressWarnings("unchecked")
    private static TryCache<ByteBuffer> bufferCache() throws Exception {
        Field field = EventPumper.class.getDeclaredField("_bufferCache");
        field.setAccessible(true);
        return (TryCache<ByteBuffer>) field.get(null);
    }

    /**
     * A connection with no socket attached, so only what is queued here is read.
     *
     * @param ctx the context to build it with
     * @return an unestablished inbound connection
     * @throws Exception if the private constructor cannot be reached
     */
    private static NTCPConnection newConnection(RouterContext ctx) throws Exception {
        Constructor<NTCPConnection> ctor = NTCPConnection.class.getDeclaredConstructor(
                RouterContext.class, NTCPTransport.class, RouterAddress.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(ctx, mock(NTCPTransport.class, RETURNS_DEEP_STUBS),
                                new RouterAddress(), true);
    }

    /**
     * Puts the connection into the establish state under test.
     *
     * @param con the connection to change
     * @param est the state to put it in
     * @throws Exception if the field cannot be reached
     */
    private static void setEstablishState(NTCPConnection con, EstablishState est) throws Exception {
        Field field = NTCPConnection.class.getDeclaredField("_establishState");
        field.setAccessible(true);
        field.set(con, est);
    }
}
