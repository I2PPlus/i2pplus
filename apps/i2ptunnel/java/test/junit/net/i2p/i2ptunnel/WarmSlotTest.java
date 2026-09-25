package net.i2p.i2ptunnel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.Hash;
import net.i2p.i2ptunnel.I2PTunnelHTTPClient.WarmSlot;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 *  Unit tests for the request-scoped warm-socket state machine: generation
 *  tagging of attempts, idempotent teardown, wrong-destination discard, and
 *  exactly-once permit release along every exit path.
 */
public class WarmSlotTest {

    private static final Hash DEST_A = new Hash(new byte[32]);
    private static final Hash DEST_B;
    static {
        byte[] d = new byte[32];
        d[31] = 1;
        DEST_B = new Hash(d);
    }
    private static final int PORT = 80;
    private static final int OTHER_PORT = 443;

    /** Permits the slot handed back, in order. */
    private final List<Hash> _released = new ArrayList<Hash>();
    private final WarmSlot _slot = new WarmSlot(h -> _released.add(h));

    // ---------- open / generation ----------

    @Test
    public void testOpenIssuesGenerationAndRejectsBusySlot() {
        assertTrue(_slot.isIdle());
        assertEquals(WarmSlot.State.OPEN, _slot.getState());
        assertEquals(0, _slot.getGeneration());

        long gen = _slot.open(DEST_A, PORT, DEST_A);
        assertEquals(1, gen);
        assertFalse(_slot.isIdle());
        assertEquals(1, _slot.getGeneration());

        // Second open while the attempt is in flight is refused and must not
        // bump the generation.
        assertEquals(-1, _slot.open(DEST_A, PORT, DEST_A));
        assertEquals(1, _slot.getGeneration());
        assertTrue(_released.isEmpty());
    }

    @Test
    public void testOpenRejectedWhenCancelled() {
        _slot.cancel();
        assertEquals(-1, _slot.open(DEST_A, PORT, DEST_A));
        assertTrue(_released.isEmpty());
    }

    @Test
    public void testOpenRejectedWhenFull() {
        long gen = _slot.open(DEST_A, PORT, DEST_A);
        assertTrue(_slot.install(gen, mockSocket(new AtomicInteger())));
        assertEquals(WarmSlot.State.FULL, _slot.getState());
        assertEquals(-1, _slot.open(DEST_A, PORT, DEST_A));
        assertTrue(_released.isEmpty());
    }

    // ---------- install ----------

    @Test
    public void testInstallSucceedsForCurrentGeneration() {
        long gen = _slot.open(DEST_A, PORT, DEST_A);
        assertTrue(_slot.install(gen, mockSocket(new AtomicInteger())));
        assertEquals(WarmSlot.State.FULL, _slot.getState());
        assertFalse(_slot.isIdle());
        assertTrue(_released.isEmpty());
    }

    @Test
    public void testInstallWithoutOpenRejected() {
        assertFalse(_slot.install(1, mockSocket(new AtomicInteger())));
        assertFalse(_slot.install(0, mockSocket(new AtomicInteger())));
        assertEquals(WarmSlot.State.OPEN, _slot.getState());
        assertNull(_slot.take(DEST_A, PORT));
        assertTrue(_released.isEmpty());
    }

    @Test
    public void testLateInstallAfterCancelRejectedAndLeaseReleasedOnce() {
        long gen = _slot.open(DEST_A, PORT, DEST_A);
        _slot.cancel();
        assertEquals(WarmSlot.State.CANCELLED, _slot.getState());

        // The background connect completes after teardown: install must
        // refuse the socket, and the attempt thread releases the lease.
        AtomicInteger closes = new AtomicInteger();
        I2PSocket late = mockSocket(closes);
        assertFalse(_slot.install(gen, late));
        assertEquals(0, closes.get());
        _slot.releaseLease();
        assertEquals(1, _released.size());
        assertEquals(DEST_A, _released.get(0));

        // Every further exit path stays a no-op: no double release, no reuse.
        _slot.releaseLease();
        _slot.cancel();
        assertEquals(1, _released.size());
        assertNull(_slot.take(DEST_A, PORT));
        assertEquals(-1, _slot.open(DEST_A, PORT, DEST_A));
    }

    @Test
    public void testStaleGenerationInstallRejectedAfterFailedAttempt() {
        long stale = _slot.open(DEST_A, PORT, DEST_A);
        _slot.releaseLease(); // attempt failed or was interrupted
        assertTrue(_slot.isIdle());
        assertEquals(1, _released.size());

        long fresh = _slot.open(DEST_A, PORT, DEST_A);
        assertTrue(fresh > stale);

        // A late completion for the stale attempt cannot install over the
        // fresh one, and must not touch the fresh attempt's lease.
        assertFalse(_slot.install(stale, mockSocket(new AtomicInteger())));
        assertEquals(WarmSlot.State.OPEN, _slot.getState());
        // The rejected install released nothing: the stale attempt already did.
        assertEquals(1, _released.size());

        assertTrue(_slot.install(fresh, mockSocket(new AtomicInteger())));
        assertEquals(WarmSlot.State.FULL, _slot.getState());
    }

    // ---------- take ----------

    @Test
    public void testTakeMatchingDestinationReturnsSocketAndReleasesLease() {
        AtomicInteger closes = new AtomicInteger();
        I2PSocket sock = mockSocket(closes);
        long gen = _slot.open(DEST_A, PORT, DEST_A);
        assertTrue(_slot.install(gen, sock));

        I2PSocket taken = _slot.take(DEST_A, PORT);
        assertSame(sock, taken);
        assertEquals(0, closes.get()); // handed over, not closed
        assertEquals(1, _released.size()); // warm permit not double-counted
        assertEquals(WarmSlot.State.OPEN, _slot.getState());
        assertTrue(_slot.isIdle());
        assertNull(_slot.take(DEST_A, PORT)); // nothing installed now

        // Slot is reusable for a fresh attempt after a take.
        assertTrue(_slot.open(DEST_B, OTHER_PORT, DEST_B) > 0);
    }

    @Test
    public void testTakeWrongDestinationDiscardsSocket() {
        AtomicInteger closes = new AtomicInteger();
        long gen = _slot.open(DEST_A, PORT, DEST_A);
        assertTrue(_slot.install(gen, mockSocket(closes)));

        assertNull(_slot.take(DEST_B, PORT));
        assertEquals(1, closes.get());
        assertEquals(1, _released.size());
        assertEquals(WarmSlot.State.OPEN, _slot.getState());

        // Recoverable: a fresh attempt for the right destination installs.
        long fresh = _slot.open(DEST_B, PORT, DEST_B);
        assertTrue(fresh > 0);
        I2PSocket right = mockSocket(closes);
        assertTrue(_slot.install(fresh, right));
        assertSame(right, _slot.take(DEST_B, PORT));
    }

    @Test
    public void testTakeWrongPortDiscardsSocket() {
        AtomicInteger closes = new AtomicInteger();
        long gen = _slot.open(DEST_A, PORT, DEST_A);
        assertTrue(_slot.install(gen, mockSocket(closes)));

        assertNull(_slot.take(DEST_A, OTHER_PORT));
        assertEquals(1, closes.get());
        assertEquals(1, _released.size());
        assertEquals(WarmSlot.State.OPEN, _slot.getState());
    }

    // ---------- release / cancel ----------

    @Test
    public void testFailedAttemptReleasesPermitExactlyOnce() {
        long gen = _slot.open(DEST_A, PORT, DEST_A);
        _slot.releaseLease(); // interrupted connect cleanup
        _slot.releaseLease(); // repeated exit must not double-release
        assertEquals(1, _released.size());
        assertEquals(DEST_A, _released.get(0));
        assertTrue(_slot.isIdle());

        // The slot recovers and tags the next attempt with a new generation.
        long next = _slot.open(DEST_A, PORT, DEST_A);
        assertTrue(next > gen);
        assertTrue(_slot.install(next, mockSocket(new AtomicInteger())));
        assertEquals(WarmSlot.State.FULL, _slot.getState());
    }

    @Test
    public void testReleaseLeaseWhileFullIsNoOp() {
        AtomicInteger closes = new AtomicInteger();
        long gen = _slot.open(DEST_A, PORT, DEST_A);
        assertTrue(_slot.install(gen, mockSocket(closes)));

        // The installed connection owns its permit until taken or cancelled.
        _slot.releaseLease();
        assertTrue(_released.isEmpty());
        assertEquals(WarmSlot.State.FULL, _slot.getState());

        _slot.cancel();
        assertEquals(1, _released.size());
        assertEquals(1, closes.get());
    }

    @Test
    public void testCancelIdempotentAfterInstall() {
        AtomicInteger closes = new AtomicInteger();
        long gen = _slot.open(DEST_A, PORT, DEST_A);
        assertTrue(_slot.install(gen, mockSocket(closes)));

        _slot.cancel();
        _slot.cancel();
        assertEquals(WarmSlot.State.CANCELLED, _slot.getState());
        assertEquals(1, closes.get());
        assertEquals(1, _released.size());
        assertNull(_slot.take(DEST_A, PORT));
        assertEquals(-1, _slot.open(DEST_A, PORT, DEST_A));
    }

    @Test
    public void testCancelWhileIdleReleasesNothing() {
        _slot.cancel();
        _slot.cancel();
        assertEquals(WarmSlot.State.CANCELLED, _slot.getState());
        assertTrue(_released.isEmpty());
        assertEquals(-1, _slot.open(DEST_A, PORT, DEST_A));
        assertNull(_slot.take(DEST_A, PORT));
    }

    @Test
    public void testTakeThenCancelReleasesOnce() {
        AtomicInteger closes = new AtomicInteger();
        long gen = _slot.open(DEST_A, PORT, DEST_A);
        assertTrue(_slot.install(gen, mockSocket(closes)));
        assertNotNull(_slot.take(DEST_A, PORT));
        assertEquals(1, _released.size());

        _slot.cancel();
        assertEquals(1, _released.size()); // nothing left to release
        assertEquals(0, closes.get()); // socket left with the request
    }

    // ---------- helper wiring ----------

    @Test
    public void testCancelWarmSlotIsNullSafeAndIdempotent() {
        I2PTunnelHTTPClient.cancelWarmSlot(null);

        AtomicInteger closes = new AtomicInteger();
        long gen = _slot.open(DEST_A, PORT, DEST_A);
        assertTrue(_slot.install(gen, mockSocket(closes)));
        I2PTunnelHTTPClient.cancelWarmSlot(_slot);
        I2PTunnelHTTPClient.cancelWarmSlot(_slot);
        assertEquals(1, closes.get());
        assertEquals(1, _released.size());
    }

    /**
     *  Mock I2PSocket whose close() increments the supplied counter.
     *
     *  @param closes counter of close() calls
     *  @return the proxy socket
     */
    private static I2PSocket mockSocket(final AtomicInteger closes) {
        return (I2PSocket) Proxy.newProxyInstance(I2PSocket.class.getClassLoader(),
                new Class<?>[]{I2PSocket.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if ("close".equals(m.getName())) {
                            closes.incrementAndGet();
                            return null;
                        }
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) {return Boolean.FALSE;}
                        if (rt == int.class) {return Integer.valueOf(0);}
                        if (rt == long.class) {return Long.valueOf(0);}
                        return null;
                    }
                });
    }
}
