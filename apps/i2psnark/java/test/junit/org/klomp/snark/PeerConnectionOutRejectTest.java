package org.klomp.snark;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.data.ByteArray;
import org.junit.Test;

/**
 * Tests for the REJECTs PeerConnectionOut writes when it drops queued pieces, and for where
 * those writes happen. A control message that finds the send queue full displaces the pieces
 * queued ahead of it, and a peer that announced the BEP 6 fast option must be told each one was
 * dropped. The displaced pieces are the only case in which a thread other than the sender
 * thread writes to the peer's socket, and the write can park for as long as the peer's receive
 * window stays shut, so it must not happen under the send-queue monitor: the reader thread
 * queueing a cancel, the checker flipping our choke state and the sender thread itself all
 * need that monitor, and the sender thread is the one that would open the window again.
 *
 * <p>The connections here are built without starting the sender thread, so nothing drains the
 * queue and the only bytes that reach the stream are the rejects under test.
 *
 * @since 0.9.72+
 */
public class PeerConnectionOutRejectTest {

    /**
     * Wire length of a REJECT: the four-byte length prefix, the message id, and the piece,
     * offset and length fields.
     */
    private static final int REJECT_BYTES = 17;

    /** The BEP 6 fast option bit, from Peer's private OPTION_FAST. */
    private static final long OPTION_FAST = 0x4L;

    /** Block length of each queued piece, small so a full queue costs nothing. */
    private static final int BLOCK_LEN = 16;

    /** How long a fixture waits for a thread to make progress before calling it stuck. */
    private static final long WAIT_MS = 3000;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     *
     * @since 0.9.72+
     */
    public PeerConnectionOutRejectTest() {}

    /**
     * A socketless peer on the shared two-piece test torrent, either having announced the fast
     * option or not, which decides whether dropped pieces are rejected.
     *
     * @param fast whether this peer announced the BEP 6 fast option
     * @return a peer with no socket, on the shared test torrent
     * @throws Throwable on torrent parse, peer id construction or field access failure
     */
    private static Peer peerFor(boolean fast) throws Throwable {
        MetaInfo mi =
                new MetaInfo(
                        new java.io.ByteArrayInputStream(
                                PeerStateTest.buildTorrentBytes(new byte[40])));
        PeerID pid = new PeerID(new byte[32], (I2PSnarkUtil) null);
        Peer peer = new Peer(pid, new byte[20], mi.getInfoHash(), mi);
        // the option word only ever arrives in the handshake, which needs a socket, so it is
        // written directly
        Field options = Peer.class.getDeclaredField("options");
        options.setAccessible(true);
        options.setLong(peer, fast ? OPTION_FAST : 0L);
        return peer;
    }

    /**
     * Fills the send queue with piece messages, each for a distinct offset of one piece, so a
     * purge has a known set of blocks to reject.
     *
     * @param out the connection whose queue is to be filled
     */
    private static void fillQueue(PeerConnectionOut out) {
        for (int i = 0; i < PeerConnectionOut.MAX_QUEUE_SIZE; i++) {
            out.sendPiece(0, i * BLOCK_LEN, BLOCK_LEN,
                          (piece, begin, length) -> new ByteArray(new byte[length]));
        }
    }

    /**
     * Reads the REJECTs that reached a stream as piece/offset/length triples, checking each
     * record's own framing on the way.
     *
     * @param bytes the bytes written to the stream
     * @return one triple per complete REJECT record, in the order they were written
     */
    private static int[][] rejectsOf(byte[] bytes) {
        int count = bytes.length / REJECT_BYTES;
        int[][] rv = new int[count][3];
        for (int i = 0; i < count; i++) {
            int at = i * REJECT_BYTES;
            assertEquals("record " + i + " length prefix", 13, readInt(bytes, at));
            assertEquals("record " + i + " message id", Message.REJECT, bytes[at + 4]);
            for (int f = 0; f < 3; f++) {
                rv[i][f] = readInt(bytes, at + 5 + f * 4);
            }
        }
        return rv;
    }

    /**
     * A big-endian int out of a byte array.
     *
     * @param bytes the array to read
     * @param at the offset of the int
     * @return the value at that offset
     */
    private static int readInt(byte[] bytes, int at) {
        return ((bytes[at] & 0xff) << 24) | ((bytes[at + 1] & 0xff) << 16)
               | ((bytes[at + 2] & 0xff) << 8) | (bytes[at + 3] & 0xff);
    }

    /**
     * An output stream that can park in the middle of a write, standing in for a peer whose
     * receive window is shut.
     */
    private static class ParkingStream extends OutputStream {

        /** Counted down from inside the parked write, once, when the park begins. */
        final CountDownLatch entered = new CountDownLatch(1);

        /** The parked write waits here until the test lets it finish. */
        final CountDownLatch release = new CountDownLatch(1);

        /** True to park in the next write. */
        volatile boolean park;

        /** Everything written, parked writes included, in order. */
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();

        /**
         * Parks if asked to, then records the byte.
         *
         * @param b the byte to write
         * @throws IOException if the wait for the test is interrupted
         */
        @Override
        public void write(int b) throws IOException {
            parkIfNeeded();
            sink.write(b);
        }

        /**
         * Parks if asked to, then records the bytes.
         *
         * @param buf the bytes to write
         * @param off where in buf they start
         * @param len how many to write
         * @throws IOException if the wait for the test is interrupted
         */
        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            parkIfNeeded();
            sink.write(buf, off, len);
        }

        /**
         * Blocks the calling thread in a write until the test releases it.
         *
         * @throws IOException if the wait is interrupted, which the test does not do
         */
        private void parkIfNeeded() throws IOException {
            if (!park) return;
            entered.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IOException("the test never released the write");
                }
            } catch (InterruptedException ie) {
                throw new IOException(ie);
            }
        }
    }

    /**
     * The displaced pieces are rejected one per block, with the piece, offset and length the
     * peer had asked for, so it can ask someone else rather than wait for a timeout.
     *
     * @throws Throwable if the test torrent or the peer fixture cannot be built
     */
    @Test(timeout = 30000)
    public void testDisplacedPiecesAreRejectedOnePerBlock() throws Throwable {
        Peer peer = peerFor(true);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PeerConnectionOut out = new PeerConnectionOut(peer, new DataOutputStream(sink));
        try {
            fillQueue(out);
            // a control message with no room left displaces every queued piece
            out.sendHave(1);
            int[][] rejects = rejectsOf(sink.toByteArray());
            assertEquals(PeerConnectionOut.MAX_QUEUE_SIZE, rejects.length);
            for (int i = 0; i < rejects.length; i++) {
                assertEquals(0, rejects[i][0]);
                assertEquals(i * BLOCK_LEN, rejects[i][1]);
                assertEquals(BLOCK_LEN, rejects[i][2]);
            }
        } finally {
            out.disconnect();
        }
    }

    /**
     * A peer that never announced the fast option is not sent a REJECT, the house behaviour for
     * a peer that has no REJECT in its protocol vocabulary, so the stream stays empty.
     *
     * @throws Throwable if the test torrent or the peer fixture cannot be built
     */
    @Test(timeout = 30000)
    public void testNoRejectToAPeerWithoutFast() throws Throwable {
        Peer peer = peerFor(false);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PeerConnectionOut out = new PeerConnectionOut(peer, new DataOutputStream(sink));
        try {
            fillQueue(out);
            out.sendHave(1);
            assertEquals(0, sink.size());
        } finally {
            out.disconnect();
        }
    }

    /**
     * The reject write does not hold the send-queue monitor. With the write parked mid-message,
     * another thread must still be able to queue a message; if the write happens under the
     * monitor, that thread waits for the peer's window to reopen instead, and the window only
     * reopens when the sender thread - which needs the same monitor - runs.
     *
     * @throws Throwable if the test torrent or the peer fixture cannot be built, or a fixture
     *                   thread is interrupted while being joined
     */
    @Test(timeout = 30000)
    public void testRejectWriteDoesNotHoldTheSendQueueMonitor() throws Throwable {
        Peer peer = peerFor(true);
        ParkingStream stream = new ParkingStream();
        PeerConnectionOut out = new PeerConnectionOut(peer, new DataOutputStream(stream));
        Thread displayer = null;
        Thread queuer = null;
        try {
            fillQueue(out);
            stream.park = true;
            displayer = new Thread(() -> out.sendHave(1), "reject-writer");
            displayer.start();
            assertTrue("the reject write never started",
                       stream.entered.await(10, TimeUnit.SECONDS));

            AtomicBoolean queued = new AtomicBoolean();
            queuer = new Thread(() -> {
                out.sendChoke(true);
                queued.set(true);
            }, "monitor-user");
            queuer.start();
            queuer.join(WAIT_MS);
            assertTrue("the parked reject write held the send-queue monitor",
                       queued.get());

            stream.park = false;
            stream.release.countDown();
            displayer.join(10000);
            assertFalse("the reject write never finished", displayer.isAlive());
            // one parked write, then the rest of the rejects went out behind it
            assertEquals(PeerConnectionOut.MAX_QUEUE_SIZE,
                         rejectsOf(stream.sink.toByteArray()).length);
        } finally {
            stream.park = false;
            stream.release.countDown();
            if (displayer != null) displayer.join(WAIT_MS);
            if (queuer != null) queuer.join(WAIT_MS);
            out.disconnect();
        }
    }
}
