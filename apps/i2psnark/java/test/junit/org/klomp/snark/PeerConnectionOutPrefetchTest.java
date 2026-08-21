package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.i2p.data.ByteArray;

import org.junit.Test;

/**
 * Tests for PeerConnectionOut prefetch wiring: sendPiece() kicks an off-thread load, purge paths
 * release prefetched buffers, and the per-connection depth cap holds.
 *
 * @since 0.9.71+
 */
public class PeerConnectionOutPrefetchTest {

    private static final int PART_LEN = 1024;

    /** Peer with no socket, mirroring PeerStateTest's construction. */
    private static Peer peerFor() throws Exception {
        MetaInfo mi =
                new MetaInfo(
                        new java.io.ByteArrayInputStream(
                                PeerStateTest.buildTorrentBytes(new byte[40])));
        PeerID pid = new PeerID(new byte[32], (I2PSnarkUtil) null);
        return new Peer(pid, new byte[20], mi.getInfoHash(), mi);
    }

    private static PeerConnectionOut outFor(Peer peer) {
        return new PeerConnectionOut(peer, new DataOutputStream(new ByteArrayOutputStream()));
    }

    /** Waits until the condition holds or the deadline passes. */
    private static boolean waitFor(WaitCondition c, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (c.check()) return true;
            Thread.sleep(20);
        }
        return c.check();
    }

    private interface WaitCondition {
        boolean check();
    }

    @Test(timeout = 20000)
    public void testSendPiecePrefetchesOffThread() throws Exception {
        Peer peer = peerFor();
        PeerConnectionOut out = outFor(peer);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> loaderThread = new AtomicReference<>();
        DataLoader loader =
                (piece, begin, length) -> {
                    loaderThread.set(Thread.currentThread());
                    started.countDown();
                    return new ByteArray(new byte[length]);
                };

        assertFalse(out.hasPendingPiece());
        out.sendPiece(0, 0, PART_LEN, loader);

        assertTrue("prefetch load did not run", started.await(10, TimeUnit.SECONDS));
        assertNotNull(loaderThread.get());
        assertNotSame("load must run off the enqueueing thread",
                Thread.currentThread(), loaderThread.get());
        assertTrue(out.hasPendingPiece());

        Message head = out.headPiece();
        assertNotNull(head);
        assertTrue("head piece should become ready via prefetch",
                waitFor(() -> head.isDataReady(), 5000));

        // cleanup so shared executor isn't left with latches held
        out.disconnect();
        assertNull(out.headPiece());
    }

    @Test(timeout = 20000)
    public void testDisconnectDiscardsPendingPiece() throws Exception {
        Peer peer = peerFor();
        PeerConnectionOut out = outFor(peer);
        CountDownLatch gate = new CountDownLatch(1);
        DataLoader blocking =
                (piece, begin, length) -> {
                    try {
                        gate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return new ByteArray(new byte[length]);
                };

        out.sendPiece(1, 0, PART_LEN, blocking);
        Message head = out.headPiece();
        assertNotNull(head);

        out.disconnect();
        assertTrue(head.isDiscarded());
        assertFalse(out.hasPendingPiece());

        // let any blocked prefetch finish; its late result must not resurrect the buffer
        gate.countDown();
        assertTrue(waitFor(() -> out.prefetchInFlight() == 0, 5000));
        assertFalse(head.isDataReady());
    }

    @Test(timeout = 20000)
    public void testDepthCapBoundsInflightPrefetches() throws Exception {
        Peer peer = peerFor();
        PeerConnectionOut out = outFor(peer);
        int cap = PeerConnectionOut.MAX_PREFETCH_INFLIGHT;
        CountDownLatch gate = new CountDownLatch(1);
        DataLoader blocking =
                (piece, begin, length) -> {
                    try {
                        gate.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return new ByteArray(new byte[length]);
                };

        // enqueue more pieces than the cap; extras must stay lazy
        for (int i = 0; i < cap + 3; i++) {
            out.sendPiece(i, 0, PART_LEN, blocking);
        }

        assertTrue(waitFor(() -> out.prefetchInFlight() >= Math.min(cap, 1), 5000));
        int observed = out.prefetchInFlight();
        assertTrue("inflight " + observed + " exceeds cap " + cap, observed <= cap);

        gate.countDown();
        assertTrue(waitFor(() -> out.prefetchInFlight() == 0, 10000));
        out.disconnect();
    }
}
