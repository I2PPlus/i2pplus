package net.i2p.client.streaming.impl;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.SendMessageOptions;
import net.i2p.data.ByteArray;
import net.i2p.data.Destination;
import net.i2p.stat.RateConstants;
import net.i2p.util.SimpleTimer2;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * An outbound packet's payload comes from a shared ByteCache, and
 * releasePayload() hands it back as soon as the packet is ACKed or cancelled. A
 * retransmit that checks writeReleased() and then copies the payload outside
 * that release's critical section can be overtaken: the buffer goes back to the
 * pool, someone else acquires it, and writes a different payload into it while
 * the copy is still running (1.1). The peer then receives a corrupted packet -
 * the connection recovers, but the bytes sent were garbage.
 *
 * The check and the serialize have to be one critical section on the packet's
 * monitor, and releasePayload() has to take that same monitor.
 */
public class PacketPayloadReleaseRaceTest {

    /** A payload long enough that a copy of it is worth racing. */
    private static final byte[] PATTERN = pattern();

    /**
     * releasePayload() must not slip in while the send path holds the packet's
     * monitor to serialize it.
     *
     * @throws Exception if the wait for the thread that releases the payload is
     *                   interrupted
     */
    @Test
    public void testReleaseWaitsForTheSerialize() throws Exception {
        final PacketLocal pl = packetWithPayload();
        final CountDownLatch released = new CountDownLatch(1);
        Thread t = new Thread() {
            @Override
            public void run() {
                pl.releasePayload();
                released.countDown();
            }
        };
        synchronized (pl) {
            t.start();
            assertFalse("releasePayload() got in under the packet's monitor",
                        released.await(200, TimeUnit.MILLISECONDS));
            assertNotNull("the payload was freed under our feet", pl.getPayload());
        }
        assertTrue(released.await(5000, TimeUnit.MILLISECONDS));
        assertNull(pl.getPayload());
    }

    /**
     * The buffer does go back to the pool, so the exclusion above is not
     * accidental - the next acquire() finds it again.
     */
    @Test
    public void testReleaseReturnsTheBufferToThePool() {
        PacketLocal pl = packetWithPayload();
        ByteArray ba = pl.getPayload();
        pl.releasePayload();
        assertNull(pl.getPayload());
        assertSame(ba, PacketLocal.getPayloadCache().acquire());
        PacketLocal.getPayloadCache().release(ba);
    }

    /**
     * ACK the packet while its payload is mid-copy, then hand the freed buffer
     * to a new owner that fills it with something else. The ACK has to wait for
     * the copy, and what goes out has to be the payload the packet was built
     * with.
     *
     * @throws Exception if a latch await or a thread join in the race is interrupted
     */
    @Test
    public void testAckMidCopyCannotCorruptTheRetransmit() throws Exception {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        createTelemetryRates(ctx);
        SimpleTimer2 timer = new SimpleTimer2(ctx);
        PacketQueue queue = new PacketQueue(ctx, timer);
        final AtomicReference<String> bad = new AtomicReference<String>();
        try {
            I2PSession session = mock(I2PSession.class);
            when(session.sendMessage(any(Destination.class), any(byte[].class), anyInt(), anyInt(),
                                      anyInt(), anyInt(), anyInt(), any(SendMessageOptions.class)))
                .thenAnswer(new Answer<Boolean>() {
                    @Override
                    public Boolean answer(InvocationOnMock inv) {
                        checkSentPayload(inv.getArgument(1), inv.getArgument(3), bad);
                        return Boolean.TRUE;
                    }
                });
            final GatedPacketLocal pl = new GatedPacketLocal(ctx, session);
            ByteArray ba = PacketLocal.getPayloadCache().acquire();
            System.arraycopy(PATTERN, 0, ba.getData(), 0, PATTERN.length);
            ba.setValid(PATTERN.length);
            pl.setPayload(ba);

            final CountDownLatch ackDone = new CountDownLatch(1);
            Thread acker = new Thread() {
                @Override
                public void run() {
                    pl.ackReceived();
                    ByteArray reuse = PacketLocal.getPayloadCache().acquire();
                    Arrays.fill(reuse.getData(), (byte) 0xEE);
                    PacketLocal.getPayloadCache().release(reuse);
                    ackDone.countDown();
                }
            };
            Thread sender = new Thread() {
                @Override
                public void run() {
                    try {
                        queue.enqueue(pl);
                    } catch (RuntimeException re) {
                        bad.compareAndSet(null, "enqueue threw " + re);
                    }
                }
            };
            sender.start();
            assertTrue("the send never reached the payload copy",
                       pl._inSerialize.await(5, TimeUnit.SECONDS));
            acker.start();
            assertFalse("the ACK freed and recycled the payload mid-copy",
                        ackDone.await(250, TimeUnit.MILLISECONDS));
            pl._gate.countDown();
            sender.join(5000);
            acker.join(5000);
            assertFalse("the send never finished", sender.isAlive());
            assertFalse("the ack never finished", acker.isAlive());
            assertNull(bad.get(), bad.get());
        } finally {
            queue.close();
            timer.stop();
        }
    }

    /**
     * A packet that stops inside the payload copy, so the ACK can be aimed at
     * the middle of it instead of hoping to land there.
     */
    private static class GatedPacketLocal extends PacketLocal {
        final CountDownLatch _inSerialize = new CountDownLatch(1);
        final CountDownLatch _gate = new CountDownLatch(1);

        GatedPacketLocal(I2PAppContext ctx, I2PSession session) {
            super(ctx, new Destination(), session);
        }

        @Override
        public int writePacket(byte[] buffer, int offset) {
            _inSerialize.countDown();
            try {
                _gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return super.writePacket(buffer, offset);
        }
    }

    /**
     * The payload is the last thing a plain packet serializes, so it occupies
     * the tail of what was handed to the session. Compare that tail against the
     * pattern the packet was built with.
     *
     * @param buf the buffer the session was given
     * @param size how many bytes of it were written
     * @param bad set to a description of the first mismatch seen
     */
    private static void checkSentPayload(byte[] buf, int size, AtomicReference<String> bad) {
        if (size < PATTERN.length) {
            bad.compareAndSet(null, "the packet went out with no payload at all (" + size + " bytes)");
            return;
        }
        int from = size - PATTERN.length;
        for (int k = 0; k < PATTERN.length; k++) {
            if (buf[from + k] != PATTERN[k]) {
                bad.compareAndSet(null, "retransmitted payload byte " + k + " is " + buf[from + k] +
                                        ", expected " + PATTERN[k]);
                return;
            }
        }
    }

    /**
     * enqueue() records send sizes every TELEMETRY_SAMPLE_PERIOD sends; create
     * the rates so the test does not have to log about missing ones.
     *
     * @param ctx the context whose stat manager records the streaming rates
     */
    private static void createTelemetryRates(I2PAppContext ctx) {
        long[] periods = new long[] {RateConstants.ONE_MINUTE, RateConstants.ONE_HOUR};
        String[] names = new String[] {"stream.con.sendMessageSize", "stream.con.sendDuplicateSize",
                                       "stream.con.sendMessageTime"};
        for (String name : names) {
            if (ctx.statManager().getRate(name) == null) {
                ctx.statManager().createRateStat(name, "probe", "Stream", periods);
            }
        }
    }

    /**
     * @return a payload where byte i is (byte) i, so any overwrite is visible
     */
    private static byte[] pattern() {
        byte[] b = new byte[Packet.MAX_PAYLOAD_SIZE];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    /**
     * A packet holding one pooled payload buffer.
     *
     * @return a PacketLocal with a MAX_PAYLOAD_SIZE payload set
     */
    private static PacketLocal packetWithPayload() {
        PacketLocal pl = new PacketLocal(I2PAppContext.getGlobalContext(), new Destination(),
                                         (I2PSession) null);
        ByteArray ba = PacketLocal.getPayloadCache().acquire();
        Arrays.fill(ba.getData(), (byte) 0x41);
        ba.setValid(ba.getData().length);
        pl.setPayload(ba);
        return pl;
    }
}
