package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.util.Clock;

import org.junit.Test;

/**
 * Tests how a connect cooldown sits out its remaining time.
 * <p>
 * The wait was a single wait(delay) on a monitor that shutdown notifies. Any
 * notify - shutdown's, or the JVM's spurious ones - returned early and let the
 * connect through to a peer we had just failed to reach, which is the exact
 * thing the cooldown exists to prevent. The fix is a deadline loop, and what has
 * to be pinned is both halves: wakeups no longer cut the wait short, and the
 * deadline is fixed at entry so a wakeup cannot make the cooldown longer either.
 * <p>
 * Driven with a clock that advances a fixed step per read, so the number of
 * re-arms is determined by the delay and not by how fast the notifier thread
 * happens to run.
 *
 * @since 0.9.72+
 */
public class ConnectCooldownWaitTest {

    private static final long STEP = 10;

    /**
     * A clock that moves forward step milliseconds per read.
     *
     * @param step how far each read advances the clock, in ms
     * @return a mock clock with deterministic stepping
     */
    private static Clock steppingClock(long step) {
        AtomicLong t = new AtomicLong(0);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenAnswer(inv -> Long.valueOf(t.getAndAdd(step)));
        return clock;
    }

    /**
     * Notifies the lock as fast as it can, standing in for shutdown's notify and
     * for spurious wakeups.
     *
     * @param lock the monitor to keep waking
     * @param stop set to end the loop
     * @return the running thread
     */
    private static Thread notifier(final Object lock, final AtomicBoolean stop) {
        Thread t = new Thread(() -> {
            while (!stop.get()) {
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * A notify storm must not cut the cooldown short. This is the bug: one
     * stray wakeup returned the caller from the wait and the connect went out to
     * a peer that had just refused us.
     */
    @Test
    public void testStrayNotifyDoesNotShortenTheCooldown() {
        Object lock = new Object();
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread noisy = notifier(lock, stop);
        try {
            long waited = ConnectionManager.awaitCooldown(lock, 100, steppingClock(STEP));
            assertTrue("cooldown cut short by wakeups: " + waited, waited >= 100);
            // The deadline is fixed at entry, so the storm cannot inflate it
            // either - one extra step is the clock's own read granularity.
            assertTrue("cooldown overshot: " + waited, waited <= 100 + STEP);
        } finally {
            stop.set(true);
        }
    }

    /**
     * With nothing waking it, the wait still runs the full delay - the loop must
     * not depend on wakeups to make progress.
     * <p>
     * The wall-clock bound is what separates re-arming with what is left from
     * re-arming with the whole delay: the same clock advance would cost twice as
     * much real time. The first wait on a fresh monitor carries JIT and mock
     * setup cost, so it is warmed up before the measured run.
     * <p>
     * The upper bound is twice the nominal 300 ms. Re-arming with the whole
     * delay takes three 300 ms waits, so 600 ms still catches that, while the
     * slack keeps a loaded CI host from failing a correct loop.
     */
    @Test
    public void testNoNotifyStillWaitsTheFullDelay() {
        Object lock = new Object();
        ConnectionManager.awaitCooldown(lock, 10, steppingClock(100));

        long real0 = System.currentTimeMillis();
        long waited = ConnectionManager.awaitCooldown(lock, 300, steppingClock(100));
        long real = System.currentTimeMillis() - real0;

        assertTrue("returned before the delay elapsed: " + waited, waited >= 300);
        assertTrue("did not actually block: " + real + "ms", real >= 280);
        assertTrue("waited longer than the deadline: " + real + "ms", real <= 600);
    }

    /**
     * An interrupt is a deliberate cancellation, so it does end the wait - and
     * the flag is left set for the caller to see.
     *
     * @throws Exception if the main thread's sleep or join is interrupted before the
     *                   waiter finishes, so the test cannot observe the outcome
     */
    @Test
    public void testInterruptEndsTheWait() throws Exception {
        final Object lock = new Object();
        final AtomicLong waited = new AtomicLong(-1);
        final AtomicBoolean flagged = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            waited.set(ConnectionManager.awaitCooldown(lock, 60_000, steppingClock(STEP)));
            flagged.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        Thread.sleep(50);
        waiter.interrupt();
        waiter.join(5000);

        assertFalse("waiter did not finish", waiter.isAlive());
        assertTrue("interrupt did not end the wait: " + waited.get(), waited.get() < 60_000);
        assertTrue("interrupt flag was swallowed", flagged.get());
    }

    /**
     * No cooldown left means no wait at all, and no clock reads either - the
     * short-circuit is what keeps a zero delay from ever reaching wait(), and
     * wait(0) means wait forever.
     */
    @Test
    public void testNonPositiveDelayDoesNotWait() {
        Object lock = new Object();
        Clock clock = mock(Clock.class);

        long t0 = System.currentTimeMillis();
        assertEquals(0, ConnectionManager.awaitCooldown(lock, 0, clock));
        assertEquals(0, ConnectionManager.awaitCooldown(lock, -5, clock));
        verify(clock, never()).now();
        assertTrue("blocked on a zero cooldown: " + (System.currentTimeMillis() - t0) + "ms",
                   System.currentTimeMillis() - t0 < 50);
    }
}
