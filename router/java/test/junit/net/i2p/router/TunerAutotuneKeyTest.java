package net.i2p.router;

import static org.junit.Assert.*;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests the autotune.config property keys a param precomputes at construction.
 *
 * <p>{@code BaseParam.refreshRanges()} and {@code refreshDefault()} run on every
 * tuning cycle — including the 5s fast cycle for transport worker params — so
 * their five keys ("<i>name</i>.min", ".max", ".step", ".value", ".default") are
 * built once in the constructor instead of concatenated on every pass. The name
 * is final, so a key can never need rebuilding; only the value behind it can.
 *
 * <p>The risk in that change is a key that stops matching the string the old
 * concatenation produced, which would silently stop a param picking up its
 * persisted range. These tests drive real keys through a real {@link
 * Tuner.AutotuneConfig} and assert they still land on the param fields and on
 * the persisted property.
 *
 * @since 0.9.71+
 */
public class TunerAutotuneKeyTest {

    private static RouterContext _ctx;

    /** Unique per test case, so a stale autotune.config cannot leak in. */
    private String _param;
    private Tuner.AutotuneConfig _autotune;

    /** Minimal param: the keys are what is under test, not the tuning logic. */
    private static class ProbeParam extends Tuner.BaseParam {
        ProbeParam(String name, RouterContext ctx, Tuner.AutotuneConfig autotune) {
            super(name, "Test key probe", "test", 1, 100, 5, name + ".stat", ctx, autotune, 50);
        }

        @Override
        protected void applyValue(int value) { }
        @Override
        protected int getRuntimeValue() { return 50; }
        @Override
        protected double getObservedStat(RouterContext ctx) { return 0; }
        @Override
        protected int computeTarget(double observed) { return 50; }
    }

    @BeforeClass
    public static void setUpClass() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    @Before
    public void setUp() {
        _param = "i2p.tunnel.testKeyProbe." + System.nanoTime();
        _autotune = new Tuner.AutotuneConfig(_ctx);
    }

    @Test
    public void refreshRangesReadsTheMinMaxAndStepKeys() {
        _autotune.setProperty(_param + ".min", "5");
        _autotune.setProperty(_param + ".max", "80");
        _autotune.setProperty(_param + ".step", "7");
        ProbeParam param = new ProbeParam(_param, _ctx, _autotune);

        param.refreshRanges(_ctx);
        assertEquals(5, param.getMin());
        assertEquals(80, param.getMax());
        assertEquals(7, param.getStep());
    }

    @Test
    public void refreshRangesFallsBackToConstructorDefaults() {
        ProbeParam param = new ProbeParam(_param, _ctx, _autotune);

        param.refreshRanges(_ctx);
        assertEquals(1, param.getMin());
        assertEquals(100, param.getMax());
        assertEquals(5, param.getStep());
    }

    @Test
    public void refreshRangesClampsPersistedValueThroughTheValueKey() {
        // The default has to exist first: with no "<name>.default" the
        // constructor seeds "<name>.value" from the factory default.
        _autotune.setProperty(_param + ".default", "50");
        _autotune.setProperty(_param + ".min", "5");
        _autotune.setProperty(_param + ".max", "80");
        _autotune.setProperty(_param + ".value", "200");
        ProbeParam param = new ProbeParam(_param, _ctx, _autotune);

        param.refreshRanges(_ctx);
        // The clamp has to be written back under "<name>.value" for the next
        // cycle and for the console to read.
        assertEquals("80", _autotune.getProperty(_param + ".value"));
    }

    @Test
    public void constructorPersistsTheDefaultUnderTheDefaultKey() {
        ProbeParam param = new ProbeParam(_param, _ctx, _autotune);
        // A param that could not find or write "<name>.default" would rewrite it
        // on every construction instead of converging on the factory default.
        assertEquals("50", _autotune.getProperty(_param + ".default"));
        assertEquals(50, param.getDefaultValue());

        param.refreshDefault(_ctx);
        assertEquals(50, param.getDefaultValue());
    }

    @Test
    public void rangesAreReReadOnEveryRefreshNotCached() {
        _autotune.setProperty(_param + ".min", "5");
        ProbeParam param = new ProbeParam(_param, _ctx, _autotune);
        param.refreshRanges(_ctx);
        assertEquals(5, param.getMin());

        // The console edits autotune.config while the router runs; the next
        // cycle has to see the new floor, so only the key is fixed, not the
        // value behind it.
        _autotune.setProperty(_param + ".min", "11");
        param.refreshRanges(_ctx);
        assertEquals(11, param.getMin());
    }
}
