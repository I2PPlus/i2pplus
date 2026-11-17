package net.i2p.router.tunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterTestHelper;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests that the pump threads keep pumping across a queue resize.
 * <p>
 * The Tuner grows the pumper queue at run time, and resizeQueue() does it by
 * building a new queue and pointing the field at it. A thread parked on the old
 * queue is never woken by a gateway offered to the new one, so after a single
 * resize nothing pumped anything at all: the router's own client and exploratory
 * traffic and every transit inbound gateway it hosted just queued up until the
 * new queue was full, and each further request cost its caller the offer
 * timeout. The resized capacity is also persisted in autotune.config, so the
 * next session was stranded too.
 *
 * @since 0.9.72+
 */
public class PumperResizeWakeupTest {

    /**
     * Capacity to resize to. Deliberately not one of the memory-scaled defaults
     * (256, 512 or 1024), so resizeQueue() never treats it as a no-op.
     */
    private static final int RESIZED_CAPACITY = 384;

    /** Gateways queued at once, well under any queue capacity. */
    private static final int GATEWAYS = 8;

    /** How long to wait for a gateway that should be pumped. */
    private static final int PUMP_WAIT_MS = 3000;

    /** Context the pumpers and gateways are built on. */
    private static RouterContext _context;

    /** Builds the isolated context, skipping the class if none can be created. */
    @BeforeClass
    public static void setUpClass() {
        _context = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _context != null);
    }

    /** Holds no state of its own beyond the context built for the class. */
    public PumperResizeWakeupTest() {}

    /**
     * A pumper with its threads started and parked, plus one gateway pumped
     * through it to prove the threads are working before anything is resized.
     *
     * @return the pumper, with its threads waiting on the queue it was built with
     * @throws InterruptedException if the warm-up pump did not happen in time
     */
    private TunnelGatewayPumper idlePumper() throws InterruptedException {
        TunnelGatewayPumper pumper = new TunnelGatewayPumper(_context);
        CountingGateway warmup = new CountingGateway(_context);
        pumper.wantsPumping(warmup);
        assertTrue("the pump threads did not pump the warm-up gateway",
                   waitForPumps(warmup, 1, PUMP_WAIT_MS));
        return pumper;
    }

    /**
     * A gateway queued after the queue was replaced is pumped anyway. This is
     * the regression: the threads stayed on the old queue, so nothing pumped it
     * until the router restarted.
     *
     * @throws InterruptedException if the wait for the pump was interrupted
     */
    @Test
    public void gatewayQueuedAfterAResizeIsPumped() throws InterruptedException {
        TunnelGatewayPumper pumper = idlePumper();
        try {
            pumper.resizeQueue(RESIZED_CAPACITY);
            CountingGateway gw = new CountingGateway(_context);
            pumper.wantsPumping(gw);
            assertTrue("no gateway pumped after the queue was replaced",
                       waitForPumps(gw, 1, PUMP_WAIT_MS));
        } finally {
            pumper.stopPumping();
        }
    }

    /**
     * A gateway already queued when the queue is replaced is moved into the new
     * one and pumped, rather than dropped with the old one.
     *
     * @throws InterruptedException if the wait for the pump was interrupted
     */
    @Test
    public void gatewayQueuedBeforeAResizeIsPumped() throws InterruptedException {
        TunnelGatewayPumper pumper = idlePumper();
        try {
            CountingGateway gw = new CountingGateway(_context);
            pumper.wantsPumping(gw);
            pumper.resizeQueue(RESIZED_CAPACITY);
            assertTrue("the gateway waiting at resize time was dropped",
                       waitForPumps(gw, 1, PUMP_WAIT_MS));
        } finally {
            pumper.stopPumping();
        }
    }

    /**
     * Resizing moves every gateway waiting in the old queue into the new one, so
     * nothing that was queued when the Tuner resized is dropped along with the
     * queue it was waiting in.
     *
     * @throws InterruptedException if the wait for the pumps was interrupted
     */
    @Test
    public void resizeKeepsEveryQueuedGateway() throws InterruptedException {
        TunnelGatewayPumper pumper = idlePumper();
        try {
            List<CountingGateway> gws = new ArrayList<>(GATEWAYS);
            for (int i = 0; i < GATEWAYS; i++) {
                CountingGateway gw = new CountingGateway(_context);
                gws.add(gw);
                pumper.wantsPumping(gw);
            }
            pumper.resizeQueue(RESIZED_CAPACITY);
            int pumped = 0;
            for (CountingGateway gw : gws) {
                if (waitForPumps(gw, 1, PUMP_WAIT_MS)) {
                    pumped++;
                }
            }
            assertEquals("gateways lost by the resize", GATEWAYS, pumped);
        } finally {
            pumper.stopPumping();
        }
    }

    /**
     * Waits for a gateway to be pumped the wanted number of times.
     *
     * @param gw the gateway being watched
     * @param wanted pumps to wait for
     * @param timeoutMs how long to wait, in milliseconds
     * @return true if it was pumped often enough in time
     * @throws InterruptedException if the wait was interrupted
     */
    private static boolean waitForPumps(CountingGateway gw, int wanted, int timeoutMs)
            throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (gw._pumps.get() < wanted && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        return gw._pumps.get() >= wanted;
    }

    /**
     * Gateway that only counts the times it is pumped, so the test never needs a
     * tunnel, a preprocessor or a sender.
     */
    private static class CountingGateway extends PumpedTunnelGateway {

        /** Times pump() has been called. */
        private final AtomicInteger _pumps = new AtomicInteger();

        /**
         * Builds an inert gateway on the given context.
         *
         * @param ctx the router context the gateway's queues are built against
         */
        CountingGateway(RouterContext ctx) {
            super(ctx, null, null, new NullReceiver(), null);
        }

        @Override
        public boolean pump(List<PendingGatewayMessage> queueBuf) {
            _pumps.incrementAndGet();
            return false;
        }
    }

    /**
     * Receiver the inert gateway is built with, discarding anything it is given.
     */
    private static class NullReceiver implements TunnelGateway.Receiver {

        @Override
        public long receiveEncrypted(byte[] encrypted) {
            return -1;
        }

        @Override
        public Hash getSendTo() {
            return null;
        }
    }
}
