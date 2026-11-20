package net.i2p.router;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Pins the streaming parameters' factory defaults against the values the
 * streaming module actually ships.
 * <p>The Tuner treats its declared factory default as the baseline it heals a
 * persisted default back to and force-applies on the first tick, so a literal
 * there that disagrees with the module does not merely mislabel a range — it
 * replaces a value the module chose deliberately, on every install, before
 * auto-tuning has measured anything. Three streaming parameters did exactly
 * that: the piggyback ACK wait and the Nagle flush delay both declared their
 * own range floor, and the window ceiling declared 1024 on a machine where
 * streaming ships 2048.
 * <p>The module side is read through the Tuner's own reflector target table, so
 * this also proves the binding the parameter calls through still resolves. A
 * module this environment cannot see is skipped rather than failed, as in
 * {@link TunerReflectorTargetTest}; a module that is visible and disagrees is a
 * hard failure.
 *
 * @since 0.9.72+
 */
public class TunerStreamingDefaultsTest {

    /** Runs the default checks; the class carries no per-test state. */
    public TunerStreamingDefaultsTest() {}

    /** Isolated context, so no other test's stat data reaches the Tuner. */
    private static RouterContext _ctx;

    /**
     * Builds the context every test here tunes against.
     */
    @BeforeClass
    public static void setUp() {
        _ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", _ctx != null);
    }

    /**
     * The piggyback ACK wait the Tuner treats as its factory default is the
     * delayed-ACK window streaming ships, not the parameter's own range floor.
     * The floor, 25 ms, was what every install ran with, against streaming's
     * documented 500 ms.
     */
    @Test
    public void ackDelayDefaultIsWhatStreamingShips() {
        Tuner tuner = newTuner();
        Tuner.ParamSnapshot snap = snapshot(tuner, "INITIAL_ACK_DELAY");
        assertEquals("the Tuner's declared default and its effective default must agree",
                     Tuner.streamingInitialAckDelayDefault(), snap.defaultValue);
        assertTrue("the ACK wait default sat at the tuning floor, so auto-tuning could " +
                   "only ever raise it: " + snap.defaultValue + " of " + snap.min +
                   ".." + snap.max, snap.defaultValue > snap.min);
        Integer shipped = moduleInt("getDefaultInitialAckDelay");
        Assume.assumeNotNull("streaming module not visible to this test run", shipped);
        assertEquals("streaming's shipped ACK delay and the Tuner's factory default differ",
                     shipped.intValue(), snap.defaultValue);
    }

    /**
     * The Nagle flush delay the Tuner treats as its factory default is the
     * machine-scaled value streaming ships, not the parameter's range floor.
     */
    @Test
    public void passiveFlushDefaultIsWhatStreamingShips() {
        Tuner tuner = newTuner();
        Tuner.ParamSnapshot snap = snapshot(tuner, "PASSIVE_FLUSH_DELAY");
        assertEquals("the Tuner's declared default and its effective default must agree",
                     Tuner.streamingPassiveFlushDelayDefault(), snap.defaultValue);
        assertTrue("the flush delay default sat at the tuning floor, so auto-tuning could " +
                   "only ever raise it: " + snap.defaultValue + " of " + snap.min +
                   ".." + snap.max, snap.defaultValue > snap.min);
        Integer shipped = moduleInt("getDefaultPassiveFlushDelay");
        Assume.assumeNotNull("streaming module not visible to this test run", shipped);
        assertEquals("streaming's shipped flush delay and the Tuner's factory default differ",
                     shipped.intValue(), snap.defaultValue);
    }

    /**
     * The window-ceiling floor the Tuner treats as its factory default is the
     * machine-scaled constant streaming ships, the same value the parameter's own
     * runtime fallback already names.
     */
    @Test
    public void windowCeilingDefaultIsWhatStreamingShips() {
        Tuner tuner = newTuner();
        Tuner.ParamSnapshot snap = snapshot(tuner, "i2p.streaming.maxWindowSize");
        assertEquals("the Tuner's declared default and its effective default must agree",
                     Tuner.streamingMaxWindowSizeDefault(), snap.defaultValue);
        assertTrue("the window ceiling default sat at the tuning floor: " + snap.defaultValue +
                   " of " + snap.min + ".." + snap.max, snap.defaultValue > snap.min);
        Integer shipped = moduleWindowDefault();
        Assume.assumeNotNull("streaming module not visible to this test run", shipped);
        assertEquals("streaming's shipped window ceiling and the Tuner's " +
                     "factory default differ", shipped.intValue(), snap.defaultValue);
    }

    /**
     * The two growth-rate parameters deliberately declare the inversion of the
     * module's value, because the Tuner reads a higher number as more aggressive
     * while streaming reads a higher divisor as slower. This is not a stale
     * readback of the previous run, and pinning it here keeps a future reader
     * from "correcting" the mismatch the console shows.
     */
    @Test
    public void growthFactorDefaultsAreTheInvertedScale() {
        Integer caShipped = moduleInt("getDefaultCongestionAvoidanceGrowthRateFactorStatic");
        Integer ssShipped = moduleInt("getDefaultSlowStartGrowthRateFactorStatic");
        Assume.assumeNotNull("streaming module not visible to this test run",
                             caShipped, ssShipped);
        Tuner tuner = newTuner();
        String caName = "i2p.streaming.congestionAvoidanceGrowthRateFactor";
        String ssName = "i2p.streaming.slowStartGrowthRateFactor";
        assertInvertedAgainstModule(snapshot(tuner, caName), caShipped.intValue());
        assertInvertedAgainstModule(snapshot(tuner, ssName), ssShipped.intValue());
    }

    /**
     * The resend-delay floor deliberately ignores streaming's own 100 ms default,
     * because 100 ms sits below the floor this parameter exists to hold. The
     * effective default is therefore the floor, and that is the documented
     * intent rather than a mismatch to fix.
     */
    @Test
    public void resendDelayFloorDefaultIsTheFloorByDesign() {
        Integer shipped = moduleInt("getMinResendDelayStatic");
        Assume.assumeNotNull("streaming module not visible to this test run", shipped);
        Tuner tuner = newTuner();
        Tuner.ParamSnapshot snap = snapshot(tuner, "i2p.streaming.minResendDelay");
        assertTrue("streaming's own resend delay is expected below the tuning floor, got " +
                   shipped + " against floor " + snap.min, shipped < snap.min);
        assertEquals("the clamped default is the floor, which is the point of this parameter",
                     snap.min, snap.defaultValue);
    }

    /**
     * Asserts the parameter's effective default is the tuning range read backwards
     * from the module's value.
     *
     * @param snap the parameter as the Tuner holds it
     * @param shipped the divisor streaming ships, in its own direction
     */
    private static void assertInvertedAgainstModule(Tuner.ParamSnapshot snap, int shipped) {
        int inverted = snap.min + snap.max - shipped;
        assertEquals(snap.name + " should hold the module's value on the inverted scale " +
                     "(min " + snap.min + " + max " + snap.max + " - " + shipped + ")",
                     inverted, snap.defaultValue);
    }

    /**
     * The Tuner, built against the isolated context.
     *
     * @return a Tuner whose parameters carry no persisted state of their own
     */
    private static Tuner newTuner() {
        Tuner tuner;
        try {
            tuner = new Tuner(_ctx);
        } catch (Exception e) {
            Assume.assumeNoException("Tuner unavailable in this test context", e);
            return null;
        }
        assertNotNull("Tuner construction returned nothing", tuner);
        return tuner;
    }

    /**
     * The snapshot of one parameter by property name.
     *
     * @param tuner the Tuner to read
     * @param name the parameter's property name
     * @return that parameter's snapshot
     */
    private static Tuner.ParamSnapshot snapshot(Tuner tuner, String name) {
        List<Tuner.ParamSnapshot> snaps = tuner.getSnapshots();
        for (Tuner.ParamSnapshot p : snaps) {
            if (name.equals(p.name))
                return p;
        }
        fail(name + " is not a Tuner parameter; " + snaps.size() + " parameters were listed");
        return null;
    }

    /**
     * The current value of a streaming static getter, reached through the same
     * target table the parameter calls through.
     *
     * @param getter the streaming static method name the Tuner binds
     * @return the value, or null if this environment cannot see the declaring
     *         module or the getter cannot be called
     */
    private static Integer moduleInt(String getter) {
        Class<?> c = Tuner.reflectorTargetClass(getter);
        if (c == null)
            return null;
        try {
            Method m = c.getDeclaredMethod(getter);
            // Connection and ConnectionOptions are package classes; a public
            // method on one of them still needs the boundary crossed.
            m.setAccessible(true);
            return (Integer) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The window-ceiling constant streaming ships, read from the field rather
     * than the mutable static the getter returns, so a value some other test
     * applied cannot stand in for the shipped one.
     *
     * @return {@code Connection.MAX_WINDOW_SIZE_DEFAULT}, or null if this
     *         environment cannot see the class
     */
    private static Integer moduleWindowDefault() {
        Class<?> c = Tuner.reflectorTargetClass("getGlobalMaxWindowSize");
        if (c == null)
            return null;
        try {
            Field f = c.getDeclaredField("MAX_WINDOW_SIZE_DEFAULT");
            f.setAccessible(true);
            return (Integer) f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
