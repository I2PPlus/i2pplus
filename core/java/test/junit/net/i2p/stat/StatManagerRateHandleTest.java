package net.i2p.stat;

import net.i2p.I2PAppContext;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the cached-handle API, {@link StatManager#getOrCreateRateStat} and
 * {@link StatManager#getOrCreateFrequencyStat}.
 *
 * <p>The reason the handle exists: {@link StatManager#addRateData(String, long)} costs
 * a string-keyed map lookup plus one monitor per period on every call, and the
 * per-packet, per-fragment and per-job paths call it thousands of times a second.
 * A caller that resolves the stat once and then holds the {@link RateStat} pays
 * neither. That is only a legitimate optimisation if the handle is
 * <em>interchangeable</em> with the string path, so that is what these check:
 * same instance, same counters, whichever way the data arrives.
 *
 * <p>Also pinned here because it is easy to break while optimising: creation is
 * idempotent, a concurrent first use yields one shared stat rather than one per
 * thread, and a stat that this manager is not collecting is reported as absent
 * rather than quietly created behind the {@code stat.full} setting.
 *
 * <p>Uses the same mocked-context arrangement as
 * {@link StatManagerCoalesceHeartbeatTest}: {@code LogManager.getLog()} is final and
 * cannot be stubbed, so a real one is built against a temporary directory.
 *
 * @since 0.9.71+
 */
public class StatManagerRateHandleTest {

    private static final String NAME = "test.handle";
    private static final String FREQ = "test.handleFreq";
    private static final String DESC = "d";
    private static final String GROUP = "g";
    private static final long[] PERIODS = { 60_000L, 10 * 60_000L, 24 * 60 * 60_000L };

    private static final int THREADS = 4;
    private static final int PER_THREAD = 5_000;
    private static final long TIMEOUT_MS = 10_000;

    private I2PAppContext _ctx;
    private StatManager _sm;
    private File _tmpDir;

    @Before
    public void setUp() throws IOException {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"), "i2p-stat-handle-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());
        _ctx = mock(I2PAppContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString()))
                .thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        LogManager lm = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(lm);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1_700_000_000_000L);
        when(_ctx.clock()).thenReturn(clock);
        _sm = new StatManager(_ctx);
    }

    @After
    public void tearDown() {
        _sm.shutdown();
        File[] files = _tmpDir.listFiles();
        if (files != null)
            for (File f : files)
                f.delete();
        _tmpDir.delete();
    }

    /**
     * The handle must be the very instance the string path resolves to, or a
     * caller that cached it would be writing into an orphaned object.
     */
    @Test
    public void handleIsTheSameInstanceTheStringPathResolves() {
        RateStat handle = _sm.getOrCreateRateStat(NAME, DESC, GROUP, PERIODS);
        assertNotNull(handle);
        assertSame("the handle must be the stat the name resolves to",
                   handle, _sm.getRate(NAME));
        assertTrue(_sm.isRate(NAME));
        // Idempotent: a second resolve returns the same object, it does not replace it.
        assertSame(handle, _sm.getOrCreateRateStat(NAME, DESC, GROUP, PERIODS));
        assertSame(handle, _sm.getRate(NAME));
    }

    /**
     * Interchangeability in both directions: data arriving through the string path
     * shows up in the handle, and data arriving through the handle shows up in a
     * later string-path read.
     */
    @Test
    public void handleAndStringPathShareTheSameCounters() {
        RateStat handle = _sm.getOrCreateRateStat(NAME, DESC, GROUP, PERIODS);
        Rate one = handle.getRate(PERIODS[0]);
        _sm.addRateData(NAME, 7L);
        assertEquals("one call is one event", 1L, handle.getLifetimeEventCount());
        assertEquals(7.0d, one.getLifetimeTotalValue(), 0.0d);
        handle.addData(5L);
        assertEquals(2L, handle.getLifetimeEventCount());
        assertEquals(12.0d, one.getLifetimeTotalValue(), 0.0d);
        // and the duration variant
        _sm.addRateData(NAME, 3L, 11L);
        assertEquals(3L, handle.getLifetimeEventCount());
        assertEquals(15.0d, one.getLifetimeTotalValue(), 0.0d);
        assertEquals(11L, one.getLifetimeTotalEventTime());
    }

    /** Same interchangeability for the frequency side of the API. */
    @Test
    public void frequencyHandleAndStringPathShareTheSameCounters() {
        FrequencyStat handle = _sm.getOrCreateFrequencyStat(FREQ, DESC, GROUP, PERIODS);
        assertNotNull(handle);
        assertSame(handle, _sm.getFrequency(FREQ));
        _sm.updateFrequency(FREQ);
        _sm.updateFrequency(FREQ);
        assertEquals(2L, handle.getEventCount());
        assertSame(handle, _sm.getOrCreateFrequencyStat(FREQ, DESC, GROUP, PERIODS));
    }

    /**
     * A concurrent first use must produce one stat, not one per thread. Every
     * thread's data has to end up in the same place or counts vanish at startup.
     */
    @Test
    public void concurrentFirstUseYieldsOneStat() throws Exception {
        final RateStat[] resolved = new RateStat[THREADS];
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < THREADS; t++) {
            final int id = t;
            new Thread(() -> {
                try {
                    start.await();
                    resolved[id] = _sm.getOrCreateRateStat(NAME, DESC, GROUP, PERIODS);
                    for (int i = 0; i < PER_THREAD; i++)
                        resolved[id].addData(1L);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }, "handle-" + t).start();
        }
        start.countDown();
        assertTrue(done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        if (failure.get() != null)
            throw new AssertionError(failure.get());

        RateStat first = resolved[0];
        assertNotNull(first);
        for (RateStat rs : resolved)
            assertSame("every thread must resolve the same stat", first, rs);
        assertEquals("exactly one stat for the name", 1, countRates(NAME));
        assertEquals("no update may be lost", (long) THREADS * PER_THREAD,
                     first.getLifetimeEventCount());
        assertEquals((double) THREADS * PER_THREAD,
                     first.getRate(PERIODS[0]).getLifetimeTotalValue(), 0.0d);
    }

    /**
     * The handle must not create a stat the {@code stat.full} setting says to skip:
     * doing so would let a hot caller resurrect stats that are meant not to exist,
     * and would silently undo the memory saving the setting buys.
     */
    @Test
    public void uncollectedStatIsReportedAbsentRatherThanCreated() {
        RateStat existing = _sm.getOrCreateRateStat("test.already", DESC, GROUP, PERIODS);
        assertNotNull(existing);

        when(_ctx.isRouterContext()).thenReturn(true);
        when(_ctx.getBooleanProperty(StatManager.PROP_STAT_FULL)).thenReturn(false);
        assertTrue(_sm.ignoreStat(NAME));
        assertNull("must not create a stat that is not being collected",
                   _sm.getOrCreateRateStat(NAME, DESC, GROUP, PERIODS));
        assertFalse(_sm.isRate(NAME));

        // An existing stat is still resolvable, so a handle cached before the
        // setting changed keeps working rather than being silently dropped.
        assertSame(existing, _sm.getOrCreateRateStat("test.already", DESC, GROUP, PERIODS));
        existing.addData(2L);
        assertEquals(1L, existing.getLifetimeEventCount());
    }

    /**
     * A removed stat takes its handle out of the table, so a cached handle must be
     * treated as invalid afterwards rather than silently writing to a detached stat.
     */
    @Test
    public void removeRateStatDetachesTheHandle() {
        RateStat handle = _sm.getOrCreateRateStat(NAME, DESC, GROUP, PERIODS);
        assertSame(handle, _sm.getRate(NAME));
        _sm.removeRateStat(NAME);
        assertNull(_sm.getRate(NAME));
        assertFalse(_sm.isRate(NAME));
        // The old handle is inert rather than fatal: it still counts, for nobody.
        handle.addData(1L);
        assertEquals(1L, handle.getLifetimeEventCount());
        // Re-resolving the name must build a fresh stat, not hand back the detached one.
        RateStat fresh = _sm.getOrCreateRateStat(NAME, DESC, GROUP, PERIODS);
        assertNotNull(fresh);
        assertNotSame(handle, fresh);
        assertEquals("the detached handle must not carry into the new stat", 0L,
                     fresh.getLifetimeEventCount());
    }

    /** Same detach semantics for the frequency side. */
    @Test
    public void removeFrequencyStatDetachesTheHandle() {
        FrequencyStat handle = _sm.getOrCreateFrequencyStat(FREQ, DESC, GROUP, PERIODS);
        assertSame(handle, _sm.getFrequency(FREQ));
        _sm.removeFrequencyStat(FREQ);
        assertNull(_sm.getFrequency(FREQ));
        assertFalse(_sm.isFrequency(FREQ));
    }

    /**
     * {@code createRequiredRateStat} was two map operations and one wasted stat
     * allocation when a concurrent caller won the race; it must still be idempotent
     * under that race and must not replace a stat that already exists.
     */
    @Test
    public void createRequiredRateStatIsIdempotent() {
        _sm.createRequiredRateStat(NAME, DESC, GROUP, PERIODS);
        RateStat first = _sm.getRate(NAME);
        _sm.createRequiredRateStat(NAME, "a different description", GROUP, new long[] { 1L });
        assertSame("an existing stat must not be replaced", first, _sm.getRate(NAME));
        assertEquals(DESC, first.getDescription());
        assertEquals(PERIODS.length, first.getPeriods().length);
        assertEquals(1, countRates(NAME));
    }

    /** And the frequency equivalent. */
    @Test
    public void createRequiredFrequencyStatIsIdempotent() {
        _sm.createRequiredFrequencyStat(FREQ, DESC, GROUP, PERIODS);
        FrequencyStat first = _sm.getFrequency(FREQ);
        _sm.createRequiredFrequencyStat(FREQ, "different", "other", new long[] { 1L });
        assertSame(first, _sm.getFrequency(FREQ));
        assertEquals(DESC, first.getDescription());
    }

    /** A stat with no periods is still rejected, and creation stays atomic. */
    @Test
    public void emptyPeriodsStillRejectedAndNothingIsLeftBehind() {
        try {
            _sm.getOrCreateRateStat("test.empty", DESC, GROUP, new long[0]);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
        assertFalse("a failed creation must not leave an entry in the table",
                    _sm.isRate("test.empty"));
    }

    /** Counts how many rate stats the table holds under one name. */
    private int countRates(String name) {
        int count = 0;
        for (String n : _sm.getRateNames())
            if (name.equals(n))
                count++;
        return count;
    }
}
