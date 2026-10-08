package net.i2p.client.streaming.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *  Rate limiting of the duplicate-CLOSE report.
 *
 *  <p>The duplicate itself is already handled idempotently by the CAS in
 *  {@code notifyCloseSent()}, so the report is a safety net rather than a fault. Measured at
 *  roughly one per second under an ordinary streaming workload, a WARN per occurrence buried
 *  the warnings that mattered — this test pins the limiter that replaces it, and that the
 *  suppressed total stays visible so nothing is silently lost.
 *
 * @since 0.9.71+
 */
public class ConnectionDuplicateCloseLimiterTest {

    private Method shouldLog;
    private AtomicLong suppressed;

    @Before
    public void setUp() throws Exception {
        shouldLog = Connection.class.getDeclaredMethod("shouldLogDuplicateClose", long.class);
        shouldLog.setAccessible(true);
        Field f = Connection.class.getDeclaredField("_duplicateCloseSuppressed");
        f.setAccessible(true);
        suppressed = (AtomicLong) f.get(null);
        resetStatics();
    }

    @After
    public void tearDown() {
        resetStatics();
    }

    private void resetStatics() {
        for (String name : new String[] { "_duplicateCloseSuppressed", "_lastDuplicateCloseLog" }) {
            try {
                Field f = Connection.class.getDeclaredField(name);
                f.setAccessible(true);
                ((AtomicLong) f.get(null)).set(0);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot reset " + name, e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean call(long now) throws Exception {
        return (Boolean) shouldLog.invoke(null, now);
    }

    /** The first occurrence after an interval is always reported. */
    @Test
    public void firstOccurrenceIsReported() throws Exception {
        assertTrue(call(60_000L));
    }

    /** Everything inside the interval is suppressed, not logged. */
    @Test
    public void occurrencesInsideTheIntervalAreSuppressed() throws Exception {
        call(60_000L);
        assertFalse(call(60_001L));
        assertFalse(call(90_000L));
        assertFalse(call(119_999L));
    }

    /** Suppressed occurrences are counted, so the report can say how many it stands for. */
    @Test
    public void suppressedOccurrencesAreCounted() throws Exception {
        call(60_000L);
        int expected = 0;
        for (long t = 60_001L; t < 90_000L; t += 1000L) { call(t); expected++; }
        assertEquals(expected, suppressed.get());
    }

    /** Once the interval passes, the next occurrence reports again. */
    @Test
    public void reportsAgainAfterTheInterval() throws Exception {
        call(60_000L);
        call(61_000L);
        assertTrue("a new interval must produce a report", call(120_001L));
    }

    /**
     *  The steady-state case that motivated this: a busy connection notifies roughly once a
     *  second. That must produce one log line, not sixty.
     */
    @Test
    public void oneNotificationPerSecondYieldsOneReportPerMinute() throws Exception {
        long base = 1_000_000L;
        int reports = 0;
        for (long t = 0; t < 60_000L; t += 1000L) {
            if (call(base + t)) { reports++; }
        }
        assertTrue("expected one or two reports per minute, got " + reports, reports <= 2);
        assertTrue("and at least one", reports >= 1);
    }

    /** Suppression must not swallow the count; a report that stands for 59 events says so. */
    @Test
    public void suppressionCountIsNonZeroAfterABusyInterval() throws Exception {
        call(60_000L);
        for (long t = 60_001L; t < 120_000L; t += 500L) { call(t); }
        assertTrue("a busy interval must record what it stood for", suppressed.get() > 50);
    }
}