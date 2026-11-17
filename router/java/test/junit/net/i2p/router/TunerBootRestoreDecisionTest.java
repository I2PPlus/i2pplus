package net.i2p.router;

import static org.junit.Assert.assertEquals;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * What a value left in autotune.config does to the first tuning pass after a
 * restart.
 *
 * <p>{@link Tuner.BaseParam#update()} applies the persisted value before it looks
 * at any measurement, which is right for a limit this router's own load drove:
 * what it settled on last session describes this machine, and starting from it
 * avoids walking the range again. It is wrong for a limit remote peers drove. A
 * transit throttle raised by a flood of transit requests describes the flood, and
 * the flood is not there at boot, so restoring it hands every later session the
 * loosest limit the worst session ever reached, with nothing left to decay it.
 * Those params answer false to {@link Tuner.BaseParam#restorePersistedValue()}
 * and start from the configured value instead.
 *
 * @since 0.9.72+
 */
public class TunerBootRestoreDecisionTest {

    /** Runs the cases; it holds no state of its own. */
    public TunerBootRestoreDecisionTest() {}

    /** Context for the autotune.config the params read. */
    private static RouterContext _ctx;

    /** The value left in autotune.config by the previous session. */
    private static final int PERSISTED = 937;
    /** The value the runtime starts at, from config or code. */
    private static final int CONFIGURED = 100;

    /** Unique per test case, so one case's persisted value cannot leak into another. */
    private String _param;
    private Tuner.AutotuneConfig _autotune;

    /**
     * One context for the autotune.config the params read. The tests are skipped
     * if no context can be built at all.
     */
    @BeforeClass
    public static void setUpClass() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    /** A param name no other case has used, so no other case's persisted value leaks in. */
    @Before
    public void setUp() {
        _param = "i2p.tunnel.bootRestoreProbe." + System.nanoTime();
        _autotune = new Tuner.AutotuneConfig(_ctx);
        // The state a session that has already run leaves behind: the configured
        // value under .default, and the tuned one under .value. A param with no
        // .default key rewrites .value to the default in its own constructor, so
        // both keys have to be there for the persisted value to be readable.
        _autotune.setProperty(_param + ".default", String.valueOf(CONFIGURED));
        _autotune.setProperty(_param + ".value", String.valueOf(PERSISTED));
    }

    /** The standing behaviour: the persisted value is applied on the first pass. */
    @Test
    public void persistedValueIsRestoredOnTheFirstPass() {
        ProbeParam param = new ProbeParam(_param, true, _ctx, _autotune);
        param.update();
        assertEquals(PERSISTED, param.getRuntimeValue());
    }

    /** A param whose measurement peers drive keeps the value it starts with. */
    @Test
    public void peerDrivenParamStartsFromTheConfiguredValue() {
        ProbeParam param = new ProbeParam(_param, false, _ctx, _autotune);
        param.update();
        assertEquals(CONFIGURED, param.getRuntimeValue());
    }

    /**
     * Opting out of the restore is not opting out of tuning: the next pass tunes
     * from where the param actually is.
     */
    @Test
    public void peerDrivenParamStillTunesAfterBoot() {
        ProbeParam param = new ProbeParam(_param, false, _ctx, _autotune);
        param.update();
        param._tune = true;
        param.update();
        assertEquals(CONFIGURED + 10, param.getRuntimeValue());
    }

    /**
     * The persisted value is still in autotune.config after a pass that declined
     * to apply it, so the file is not rewritten behind the operator's back before
     * the param has anything new to report.
     */
    @Test
    public void decliningToRestoreLeavesTheConfigFileAlone() {
        ProbeParam param = new ProbeParam(_param, false, _ctx, _autotune);
        param.update();
        assertEquals(String.valueOf(PERSISTED), _autotune.getProperty(_param + ".value"));
    }

    /**
     * A param that only exists to be tuned: the observed stat is a constant, so
     * the pass reaches {@code computeTarget} instead of stopping at a missing
     * stat, and the target is a fixed move from wherever the value stands.
     */
    private class ProbeParam extends Tuner.BaseParam {

        /** Whether the first pass applies the persisted value. */
        private final boolean _restore;
        /** The value this param holds, standing in for a runtime getter. */
        private int _value = CONFIGURED;
        /** Whether a later pass should move the value at all. */
        private boolean _tune;

        ProbeParam(String name, boolean restore, RouterContext ctx, Tuner.AutotuneConfig autotune) {
            super(name, "Boot restore probe", "test", 10, 1000, 10, name + ".stat", ctx, autotune,
                  CONFIGURED);
            _restore = restore;
        }

        protected void applyValue(int value) { _value = value; }

        protected int getRuntimeValue() { return _value; }

        protected double getObservedStat(RouterContext ctx) { return 99; }

        protected int computeTarget(double observed) {
            return _tune ? _value + 10 : _value;
        }

        protected boolean restorePersistedValue() { return _restore; }
    }

    /** Sanity check on the fixture: the persisted key really is what the param reads. */
    @Test
    public void theFixtureSeesThePersistedValue() {
        ProbeParam param = new ProbeParam(_param, true, _ctx, _autotune);
        assertEquals(PERSISTED, param._initialValue);
    }
}
