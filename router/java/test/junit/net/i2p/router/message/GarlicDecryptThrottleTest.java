package net.i2p.router.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;
import org.junit.Test;

/**
 * The window summary behind the garlic decrypt-failure line:
 * {@link GarlicMessageReceiver#decryptFailure}. It replaced a count-based
 * throttle that emitted one line per 50 failures, which on a loaded router is
 * one line every few seconds - 5,327 of them in 4.75 hours, 56% of the whole
 * log - and printed a lifetime total whose delta against the previous line was
 * always exactly the throttle interval, so the number said nothing about the
 * rate.
 * <p>
 * What is pinned here is that the summary is time-based, that it counts only
 * its own window, and that the window length it reports is the real one.
 *
 * @since 0.9.72+
 */
public class GarlicDecryptThrottleTest {

    private static final long TEN_MIN = 10 * 60 * 1000L;

    /**
     * The counter and the window are static and shared, so reset them per test.
     *
     * @throws Exception if one of the two static fields cannot be read reflectively
     */
    @Before
    public void setUp() throws Exception {
        counter().set(0);
        windowStart().set(0L);
    }

    /**
     * The receiver's static failure counter, reached reflectively.
     *
     * @return the counter the receiver increments on a decrypt failure
     * @throws Exception if the receiver declares no such field or it cannot be read
     */
    private static AtomicInteger counter() throws Exception {
        return (AtomicInteger) field("_decryptFailCount");
    }

    /**
     * The receiver's static window start time, reached reflectively.
     *
     * @return the instant the current reporting window began, in ms since the epoch
     * @throws Exception if the receiver declares no such field or it cannot be read
     */
    private static AtomicLong windowStart() throws Exception {
        return (AtomicLong) field("_decryptWindowStart");
    }

    /**
     * One static field of the receiver, by name.
     *
     * @param name the field's name
     * @return the field's current value
     * @throws Exception if the receiver declares no such field or it cannot be read
     */
    private static Object field(String name) throws Exception {
        Field f = GarlicMessageReceiver.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    /**
     * Nothing is reported until the window runs out.
     *
     * @throws Exception if the window field cannot be read reflectively
     */
    @Test
    public void testSilentInsideTheWindow() throws Exception {
        windowStart().set(0L);
        for (int i = 0; i < 5000; i++) {
            assertNull(GarlicMessageReceiver.decryptFailure(i));
        }
    }

    /**
     * At the boundary, one line, counting only the failures in that window.
     *
     * @throws Exception if the counter or window field cannot be read reflectively
     */
    @Test
    public void testSummaryCountsItsOwnWindow() throws Exception {
        windowStart().set(0L);
        for (int i = 1; i < 500; i++) {
            assertNull(GarlicMessageReceiver.decryptFailure(i));
        }
        String s = GarlicMessageReceiver.decryptFailure(TEN_MIN);
        assertEquals("Failed to decrypt 500 GarlicMessages in 10m", s);
    }

    /**
     * The count resets, so the next window does not report the previous one too.
     *
     * @throws Exception if the counter or window field cannot be read reflectively
     */
    @Test
    public void testCountResetsAfterTheSummary() throws Exception {
        windowStart().set(0L);
        for (int i = 1; i < TEN_MIN; i++) {
            GarlicMessageReceiver.decryptFailure(i);
        }
        assertNotNull(GarlicMessageReceiver.decryptFailure(TEN_MIN));
        assertEquals(0, counter().get());
        for (int i = 1; i < 40; i++) {
            assertNull(GarlicMessageReceiver.decryptFailure(TEN_MIN + i));
        }
        String s = GarlicMessageReceiver.decryptFailure(2 * TEN_MIN);
        assertEquals("Failed to decrypt 40 GarlicMessages in 10m", s);
    }

    /**
     * One failure in a window reads as one message, not one messages.
     *
     * @throws Exception if the window field cannot be read reflectively
     */
    @Test
    public void testSingular() throws Exception {
        windowStart().set(0L);
        String s = GarlicMessageReceiver.decryptFailure(TEN_MIN);
        assertEquals("Failed to decrypt 1 GarlicMessage in 10m", s);
    }

    /**
     * A quiet router reports the window it actually covered, so a lone failure
     * after an hour of silence does not claim a 10m window.
     *
     * @throws Exception if the window field cannot be read reflectively
     */
    @Test
    public void testIdleWindowReportsItsRealLength() throws Exception {
        windowStart().set(0L);
        String s = GarlicMessageReceiver.decryptFailure(75 * 60 * 1000L);
        assertEquals("Failed to decrypt 1 GarlicMessage in 75m", s);
    }

    /**
     * Only one thread wins the window, so a burst cannot emit a line each.
     *
     * @throws Exception if the window field cannot be read reflectively, or a
     *                   thread is interrupted while it is joined
     */
    @Test
    public void testOneSummaryPerWindowUnderContention() throws Exception {
        windowStart().set(0L);
        final int threads = 8;
        final String[] won = new String[threads];
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int id = i;
            ts[i] = new Thread(new Runnable() {
                public void run() {
                    for (int n = 0; n < 200; n++) {
                        GarlicMessageReceiver.decryptFailure(n);
                    }
                    won[id] = GarlicMessageReceiver.decryptFailure(TEN_MIN);
                }
            });
        }
        for (Thread t : ts) {t.start();}
        for (Thread t : ts) {t.join();}
        int summaries = 0;
        String line = null;
        for (String s : won) {
            if (s != null) {
                summaries++;
                line = s;
            }
        }
        assertEquals(1, summaries);
        assertTrue(line, line.matches("Failed to decrypt \\d+ GarlicMessages in 10m"));
    }
}
