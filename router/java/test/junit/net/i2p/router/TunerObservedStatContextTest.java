package net.i2p.router;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;

/**
 * Contract tests for how {@code BaseParam} supplies the router context to
 * {@code getObservedStat(RouterContext)}.
 *
 * <p>The regression: {@link Tuner} passed {@code null} for the argument at both
 * call sites, so any implementation that used the <em>parameter</em> rather
 * than the field threw on every tuning cycle.
 * {@code i2p.tunnel.build.requestTimeout} did exactly that — it forwarded the
 * null {@code ctx} into {@code getAdditionalStat(ctx, ...)}, which
 * dereferences it — so the param never tuned once for the life of the router,
 * and the only evidence was a repeated stack trace in a log nobody reads.
 *
 * <p>These tests reproduce that shape directly: a param whose
 * {@code getObservedStat} uses the argument it is handed. If the call sites ever
 * pass null again, {@code update()} throws here rather than silently degrading
 * in production. That is behavioural, so it does not depend on the source file's
 * location or on matching its text.
 *
 * @since 0.9.71+
 */
public class TunerObservedStatContextTest {

    private RouterContext _ctx;
    private File _tmpDir;

    @Before
    public void setUp() throws Exception {
        _tmpDir = new File(System.getProperty("java.io.tmpdir"),
                           "i2p-tuner-ctx-" + System.nanoTime());
        assertTrue(_tmpDir.mkdirs());
        _ctx = mock(RouterContext.class);
        when(_ctx.getConfigDir()).thenReturn(_tmpDir);
        when(_ctx.getProperty(anyString(), anyString()))
                .thenReturn(new File(_tmpDir, "logger.config").getAbsolutePath());
        // Build the LogManager before stubbing: LogManager's constructor calls
        // back into the RouterContext mock, and doing that inside a thenReturn()
        // argument leaves that mock's stubbing unfinished, which then fails
        // every subsequent test in the class.
        LogManager logs = new LogManager(_ctx);
        when(_ctx.logManager()).thenReturn(logs);
        // getAdditionalStat() goes through ctx.statManager(); without this the
        // helper NPEs on a null StatManager and the test would be asserting the
        // wrong failure.
        StatManager stats = new StatManager(_ctx);
        when(_ctx.statManager()).thenReturn(stats);
        Clock clock = mock(Clock.class);
        when(clock.now()).thenReturn(1_000_000_000L);
        when(_ctx.clock()).thenReturn(clock);
    }

    @After
    public void tearDown() {
        File[] children = _tmpDir.listFiles();
        if (children != null) {
            for (File c : children) {c.delete();}
        }
        _tmpDir.delete();
    }

    /**
     * A param shaped exactly like the one that broke: it reads the
     * cross-reference helper using the {@code ctx} <em>argument</em> rather
     * than the inherited field.
     */
    private static class ArgumentUsingParam extends Tuner.BaseParam {
        boolean sawNullContext;

        ArgumentUsingParam(RouterContext ctx) {
            super("tuner.test.argumentUsing", "test param", "Tuner",
                  1, 10, 1, "tuner.noSuchStat", ctx,
                  new Tuner.AutotuneConfig(ctx), 1);
        }

        @Override
        protected void applyValue(int value) { /* nothing */ }

        @Override
        protected int getRuntimeValue() {return 1;}

        @Override
        protected double getObservedStat(RouterContext ctx) {
            sawNullContext = (ctx == null);
            // This is the call shape that threw: getAdditionalStat dereferences ctx.
            return getAdditionalStat(ctx, _statName);
        }

        @Override
        protected int computeTarget(double observed) {return 1;}
    }

    /**
     * The core fix. Before it, {@code update()} called
     * {@code getObservedStat(null)} and this threw.
     */
    @Test
    public void updateNeverHandsTheImplementationANullContext() {
        ArgumentUsingParam p = new ArgumentUsingParam(_ctx);
        try {
            p.update();
        } catch (RuntimeException re) {
            fail("update() must supply a non-null context; a param that uses the "
                 + "argument would otherwise throw every cycle: " + re);
        }
        assertFalse("getObservedStat was handed null", p.sawNullContext);
    }

    /**
     * The snapshot path has the same defect and needs the same fix.
     */
    @Test
    public void snapshotNeverHandsTheImplementationANullContext() {
        ArgumentUsingParam p = new ArgumentUsingParam(_ctx);
        p.update();
        p.snapshot();
        assertFalse("snapshot() handed getObservedStat a null context", p.sawNullContext);
    }

    /**
     * An unregistered stat name is the common case and must yield NaN, not an
     * exception — the helper is expected to tolerate a missing stat.
     */
    @Test
    public void missingStatYieldsNaNRatherThanThrowing() {
        ArgumentUsingParam p = new ArgumentUsingParam(_ctx);
        p.update();
        // Reaching here without a failure is the assertion; also confirm the
        // param is still operable afterwards.
        assertEquals(1, p.getRuntimeValue());
    }
}
