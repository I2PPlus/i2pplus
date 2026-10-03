package net.i2p.stat;

import net.i2p.I2PAppContext;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

import java.io.File;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the coalesce-sweep heartbeat, {@link StatManager#getCoalesceSweepAgeMs} and
 * {@link StatManager#hasCoalesced}.
 *
 * <p>The gap this closes: nothing inside the stats package can detect its own
 * non-execution. When the shared coalesce timer saturates, the coalesce task is simply
 * queued behind other work and never runs, so no rate throws, no counter moves, and
 * every graph listener starves together. Observed live as
 * {@code SimpleTimer active 2/2, 3001 events queued, 0 tasks completed} while
 * {@code Rate coalesce failed} stayed at zero - an empty error log was the only
 * symptom available to the operator.
 *
 * @since 0.9.71+
 */
public class StatManagerCoalesceHeartbeatTest {

    private static final long T0 = 1_700_000_000_000L;

    private I2PAppContext _ctx;
    private Clock _clock;
    private StatManager _sm;
    private File _tmpDir;

    @Before
    public void setUp() throws IOException {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-coalesce-hb-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());
        _ctx = mock(I2PAppContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString()))
                .thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        // LogManager.getLog() is final, so it cannot be stubbed: a real one is needed.
        // Built before the when() call because its constructor calls back into the
        // context, and doing that inside when() registers nested stubbing.
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);
        _clock = mock(Clock.class);
        when(_clock.now()).thenReturn(T0);
        when(_ctx.clock()).thenReturn(_clock);
        _sm = new StatManager(_ctx);
    }

    @After
    public void tearDown() {
        File[] files = _tmpDir.listFiles();
        if (files != null) {
            for (File f : files)
                f.delete();
        }
        _tmpDir.delete();
    }

    @Test
    public void beforeAnySweepTheAgeIsUnknown() {
        assertFalse(_sm.hasCoalesced());
        assertEquals("an absent heartbeat must be distinguishable from a fresh one",
                     -1L, _sm.getCoalesceSweepAgeMs(T0));
    }

    @Test
    public void noFailuresBeforeAnySweep() {
        assertEquals(0L, _sm.getCoalesceFailures());
    }

    @Test
    public void anEmptyTableStillPublishesAHeartbeatOnSweep() {
        _sm.coalesceStats();
        assertTrue("a completed sweep must be observable even with no stats registered",
                   _sm.hasCoalesced());
        assertEquals(0L, _sm.getCoalesceSweepAgeMs(T0));
    }

    @Test
    public void sweepCountAccumulates() {
        _sm.coalesceStats();
        _sm.coalesceStats();
        _sm.coalesceStats();
        assertTrue(_sm.hasCoalesced());
        assertEquals(0L, _sm.getCoalesceSweepAgeMs(T0));
    }

    @Test
    public void ageGrowsWithTheClock() {
        _sm.coalesceStats();
        when(_clock.now()).thenReturn(T0 + 90_000L);
        assertEquals(90_000L, _sm.getCoalesceSweepAgeMs(T0 + 90_000L));
    }

    /** A backwards clock step must not make a stale heartbeat look fresh. */
    @Test
    public void aNegativeDeltaClampsToZero() {
        _sm.coalesceStats();
        when(_clock.now()).thenReturn(T0 - 5_000L);
        assertEquals(0L, _sm.getCoalesceSweepAgeMs(T0 - 5_000L));
    }

    @Test
    public void hasCoalescedStaysTrueOnceSeen() {
        assertFalse(_sm.hasCoalesced());
        _sm.coalesceStats();
        assertTrue(_sm.hasCoalesced());
        // Never resets: a later wedge must not be able to claim "never ran".
        for (int i = 0; i < 3; i++)
            _sm.coalesceStats();
        assertTrue(_sm.hasCoalesced());
    }

    @Test
    public void isolatingFailuresDoesNotStopTheHeartbeat() {
        // The point of the isolation: even with failures recorded, later sweeps must
        // still publish a heartbeat, or the watchdog would report a false wedge.
        _sm.coalesceStats();
        when(_clock.now()).thenReturn(T0 + 60_000L);
        _sm.coalesceStats();
        assertTrue(_sm.hasCoalesced());
        assertEquals(0L, _sm.getCoalesceSweepAgeMs(T0 + 60_000L));
    }
}