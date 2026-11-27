package net.i2p.router.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.SessionId;
import net.i2p.router.RouterContext;
import net.i2p.stat.StatManager;

/**
 * Tests for the per-client I2CP write cap, and for the fact that the tuner's
 * change reaches a connection that is already running.
 *
 * <p>The cap used to be the capacity of the queue, read once in the
 * constructor, so a client connected before the tuner moved the value kept the
 * old limit for the whole session - and a torrent client keeps one I2CP
 * connection for days. Meanwhile {@code getQueueSize()} reported the tuner's
 * number, so the tuner saw its change take effect, kept stepping, and the
 * running client kept overflowing at the old depth until the router closed the
 * connection. The cap is now compared against the queue's depth on every
 * offer, and the queue is built at the largest supported size so raising the
 * cap needs no rebuild.
 *
 * <p>No socket, session or writer thread is involved: one runner stand-in and
 * the offers.
 */
public class ClientWriterQueueCapTest {

    /** Queue size as the router found it, restored after every case. */
    private int _savedQueueSize;

    /**
     * A context whose stat manager is a mock, so the overflow counter can be
     * observed without a router.
     */
    private RouterContext _ctx;

    /** The mock stat manager the context hands out. */
    private StatManager _stats;

    /**
     * Remember the tuner's queue size so the case cannot leak it, and build the
     * context and stat manager every case uses.
     */
    @Before
    public void setUp() {
        _savedQueueSize = ClientWriterRunner.getQueueSize();
        _stats = mock(StatManager.class);
        _ctx = mock(RouterContext.class);
        when(_ctx.statManager()).thenReturn(_stats);
    }

    /**
     * Put the tuner's queue size back, since it is shared across the suite.
     */
    @After
    public void tearDown() {
        ClientWriterRunner.setQueueSize(_savedQueueSize);
    }

    /**
     * A writer for a connection established now, at the cap in force now.
     *
     * @return a runner over a stand-in connection
     */
    private ClientWriterRunner newWriter() {
        return new ClientWriterRunner(_ctx, mock(ClientConnectionRunner.class));
    }

    /**
     * Queue one message, and report whether the cap took it.
     *
     * @param writer the writer to offer to
     * @param seq the message's sequence number, for the failure message
     * @return true if the message was queued
     */
    private static boolean offer(ClientWriterRunner writer, int seq) {
        try {
            writer.addMessage(new Msg(seq));
            return true;
        } catch (I2CPMessageException e) {
            return false;
        }
    }

    /**
     * The cap is enforced at the cap, not one message off it, and the refusal
     * is counted - the counter is what the tuner reads to decide whether to
     * raise the cap, and what tells an operator a client is not draining.
     */
    @Test
    public void capRefusesExactlyPastTheLimit() {
        ClientWriterRunner.setQueueSize(64);
        ClientWriterRunner writer = newWriter();
        for (int i = 0; i < 64; i++) {
            if (!offer(writer, i)) {
                fail("message " + i + " refused with room for 64");
            }
        }
        verify(_stats, never()).addRateData(eq("client.writerQueueFull"), eq(1L));
        if (offer(writer, 64)) {
            fail("a message was accepted past the cap in force");
        }
        verify(_stats).addRateData(eq("client.writerQueueFull"), eq(1L));
    }

    /**
     * Raising the cap reaches a connection that is already running. This is the
     * tuner's whole purpose: a client that has been overflowing gets room
     * without being disconnected and reconnecting.
     */
    @Test
    public void raisingTheCapReachesARunningConnection() {
        ClientWriterRunner.setQueueSize(ClientWriterRunner.MIN_QUEUE_SIZE);
        ClientWriterRunner writer = newWriter();
        for (int i = 0; i < ClientWriterRunner.MIN_QUEUE_SIZE; i++) {
            if (!offer(writer, i)) {
                fail("message " + i + " refused at the minimum cap");
            }
        }
        if (offer(writer, ClientWriterRunner.MIN_QUEUE_SIZE)) {
            fail("a message was accepted past the cap in force");
        }
        ClientWriterRunner.setQueueSize(ClientWriterRunner.MIN_QUEUE_SIZE * 2);
        if (!offer(writer, ClientWriterRunner.MIN_QUEUE_SIZE)) {
            fail("the running connection kept the cap it was built with");
        }
    }

    /**
     * Lowering the cap reaches a running connection too, so the tuner easing
     * back under memory pressure actually takes effect. A queue deeper than the
     * new cap refuses until the writer drains it.
     */
    @Test
    public void loweringTheCapReachesARunningConnection() {
        ClientWriterRunner.setQueueSize(ClientWriterRunner.MAX_QUEUE_SIZE);
        ClientWriterRunner writer = newWriter();
        for (int i = 0; i < 100; i++) {
            if (!offer(writer, i)) {
                fail("message " + i + " refused with room for " + ClientWriterRunner.MAX_QUEUE_SIZE);
            }
        }
        ClientWriterRunner.setQueueSize(ClientWriterRunner.MIN_QUEUE_SIZE);
        if (offer(writer, 100)) {
            fail("a queue deeper than the new cap kept accepting");
        }
    }

    /**
     * The hard capacity behind the cap stays at the largest supported queue, so
     * one stalled client cannot grow its write queue without bound however far
     * the tuner is allowed to raise the cap.
     */
    @Test
    public void capacityIsBoundedAtTheLargestSupportedQueue() {
        ClientWriterRunner.setQueueSize(ClientWriterRunner.MAX_QUEUE_SIZE);
        ClientWriterRunner writer = newWriter();
        for (int i = 0; i < ClientWriterRunner.MAX_QUEUE_SIZE; i++) {
            if (!offer(writer, i)) {
                fail("message " + i + " refused at the largest supported queue");
            }
        }
        if (offer(writer, ClientWriterRunner.MAX_QUEUE_SIZE)) {
            fail("the write queue grew past its hard capacity");
        }
    }

    /**
     * A tuner value outside the supported range is clamped, so a bad form
     * submission cannot leave the queue at zero or at an unbounded depth.
     */
    @Test
    public void theCapIsClampedToTheSupportedRange() {
        ClientWriterRunner.setQueueSize(0);
        assertEquals(ClientWriterRunner.MIN_QUEUE_SIZE, ClientWriterRunner.getQueueSize());
        ClientWriterRunner.setQueueSize(Integer.MAX_VALUE);
        assertEquals(ClientWriterRunner.MAX_QUEUE_SIZE, ClientWriterRunner.getQueueSize());
        ClientWriterRunner.setQueueSize(-1);
        assertEquals(ClientWriterRunner.MIN_QUEUE_SIZE, ClientWriterRunner.getQueueSize());
    }

    /**
     * An I2CP message the writer only counts and hands on, never parses.
     */
    private static class Msg implements I2CPMessage {
        private final int _seq;

        /**
         * Hold the sequence number so a failure names the message.
         *
         * @param seq which message this is
         */
        Msg(int seq) {
            _seq = seq;
        }

        public void readMessage(InputStream in, int size, int type) {}

        public void readMessage(InputStream in) throws I2CPMessageException, IOException {}

        public void writeMessage(OutputStream out) throws I2CPMessageException, IOException {}

        public int getType() {
            return 1;
        }

        public SessionId sessionId() {
            return null;
        }

        @Override
        public String toString() {
            return "Msg " + _seq;
        }
    }
}
