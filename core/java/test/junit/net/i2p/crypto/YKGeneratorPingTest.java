package net.i2p.crypto;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.math.BigInteger;

import org.junit.Test;

import net.i2p.I2PAppContext;

/**
 * Waking the YK precalc thread when the pool runs dry (D-6). A miss on the
 * encryption path interrupts the precalc thread so it refills now instead of
 * when its check timer next fires - but under a sustained burst every message
 * misses, and each one used to take the monitor that start() and shutdown()
 * hold and then interrupt the thread, pulling it out of its sleep and straight
 * back into it. One wake-up per idle period is enough to get it moving.
 */
public class YKGeneratorPingTest {

    /**
     * Three drains in a row are one wake-up, not three.
     *
     * @throws Exception on reflective access to the precalc thread field
     */
    @Test
    public void testABurstOfMissesWakesThePrecalcThreadOnce() throws Exception {
        YKGenerator gen = new YKGenerator(I2PAppContext.getGlobalContext());
        PingCounter counter = new PingCounter();
        setPrecalcThread(gen, counter);
        for (int i = 0; i < 3; i++) {
            BigInteger[] yk = gen.getNextYK();
            assertNotNull("a drained pool must still produce a pair", yk);
            assertEquals(2, yk.length);
        }
        assertEquals("a drained pool interrupted the precalc thread once per message",
                     1, counter._pings);
    }

    /**
     * Once the precalc runner has gone back to sleep, the next drain gets a
     * wake-up of its own - the claim is not a one-shot for the life of the
     * generator.
     *
     * @throws Exception on reflective access to the precalc thread field
     */
    @Test
    public void testTheWakeUpIsGoodAgainAfterTheRunnerIdles() throws Exception {
        YKGenerator gen = new YKGenerator(I2PAppContext.getGlobalContext());
        PingCounter counter = new PingCounter();
        setPrecalcThread(gen, counter);
        gen.getNextYK();
        gen.getNextYK();
        assertEquals(1, counter._pings);
        gen.releasePing();
        gen.getNextYK();
        assertEquals("the next drain never got a wake-up", 2, counter._pings);
    }

    /**
     * The claim is held until it is released, so concurrent misses cannot all
     * decide they are the one that wakes the thread.
     */
    @Test
    public void testTheClaimIsHeldUntilReleased() {
        YKGenerator gen = new YKGenerator(I2PAppContext.getGlobalContext());
        assertTrue(gen.claimPing());
        assertFalse("a second miss claimed the same wake-up", gen.claimPing());
        gen.releasePing();
        assertTrue(gen.claimPing());
    }

    /**
     * A claim taken by a miss that arrived during the previous shutdown must not
     * be handed to the new run, or that run's first drain gets no wake-up at all.
     */
    @Test
    public void testARestartClearsTheWakeUpClaim() {
        YKGenerator gen = new YKGenerator(I2PAppContext.getGlobalContext());
        assertTrue(gen.claimPing());
        gen.start();
        try {
            assertTrue("start() left the previous run's claim set, swallowing the first wake-up",
                       gen.claimPing());
        } finally {
            gen.shutdown();
        }
    }

    /**
     * @param gen the generator whose precalc thread reference to replace
     * @param t the thread to install in its place
     * @throws Exception if the private precalc thread field cannot be found
     *                   or set
     */
    private static void setPrecalcThread(YKGenerator gen, Thread t) throws Exception {
        Field field = YKGenerator.class.getDeclaredField("_precalcThread");
        field.setAccessible(true);
        field.set(gen, t);
    }

    /**
     * A thread that counts the wake-ups instead of receiving them. Never
     * started, so the count is the only thing the interrupt does.
     */
    private static class PingCounter extends Thread {
        int _pings;

        @Override
        public void interrupt() {
            _pings++;
        }
    }
}
