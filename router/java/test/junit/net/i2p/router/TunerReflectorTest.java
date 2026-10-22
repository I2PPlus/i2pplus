package net.i2p.router;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import org.junit.Assume;
import org.junit.Test;

/**
 * Regression tests for the reflective calls the auto-tuner makes into modules it
 * cannot compile against.
 *
 * <p>The trap is the combination the streaming module happens to use: a
 * package-private class, in a package the tuner is not in, carrying public static
 * members. {@link Class#getMethod} finds such a member and {@code invoke} then
 * refuses it with IllegalAccessException, so the call site took its fallback
 * branch and the param tuned nothing while reporting a value that looked live.
 * Every lookup goes through {@link Tuner#findStaticMethod}, which resolves
 * non-public members and makes them accessible; these tests pin that down.
 *
 * <p>The fixtures in net.i2p.router.reflector are package-private on purpose: a
 * test in this package cannot name them, so the call has to be made the way the
 * tuner makes it - by class name, across a package boundary.
 */
public class TunerReflectorTest {

    /** Package-private class with public and package-private statics. */
    private static final String HIDDEN = "net.i2p.router.reflector.HiddenTarget";

    /** Subclass inheriting a package-private static it never redeclares. */
    private static final String DERIVED = "net.i2p.router.reflector.DerivedTarget";

    /** Public class with a package-private static - the other half of the trap. */
    public static class VisibleClass {
        private static int _value = 11;
        static int getValue() {return _value;}
        static void setValue(int v) {_value = v;}
    }

    /** Non-public class with long and double statics, for the wider getter types. */
    static class WideTarget {
        private static long _long = 5L;
        private static double _double = 2.5d;
        public static long getLong() {return _long;}
        public static void setLong(long v) {_long = v;}
        public static double getDouble() {return _double;}
        public static void setDouble(double v) {_double = v;}
    }

    /**
     * A public static on a non-public class across a package boundary must still be
     * reachable - the exact shape of streaming's ConnectionOptions, and the case
     * that silently tuned nothing before findStaticMethod().
     */
    @Test
    public void reachesPublicStaticOnPackagePrivateClass() throws Exception {
        Class<?> c = Class.forName(HIDDEN);
        int before = Tuner.callStaticInt(c, "getValue", -1);
        assertTrue("the getter must read the live value, not the fallback", before > 0);
        try {
            Tuner.callStaticSetInt(c, "setValue", before + 5);
            assertEquals(before + 5, Tuner.callStaticInt(c, "getValue", -1));
        } finally {
            Tuner.callStaticSetInt(c, "setValue", before);
        }
    }

    /** A package-private member is invisible to getMethod, so it needs getDeclaredMethod. */
    @Test
    public void reachesPackagePrivateMemberOnPackagePrivateClass() throws Exception {
        Class<?> c = Class.forName(HIDDEN);
        int before = Tuner.callStaticInt(c, "getHiddenValue", -1);
        assertTrue("the package-private getter must resolve", before > 0);
        try {
            Tuner.callStaticSetInt(c, "setHiddenValue", before + 3);
            assertEquals(before + 3, Tuner.callStaticInt(c, "getHiddenValue", -1));
        } finally {
            Tuner.callStaticSetInt(c, "setHiddenValue", before);
        }
    }

    /** A non-public static on a public class must be reachable too. */
    @Test
    public void reachesPackagePrivateStaticOnPublicClass() {
        int before = Tuner.callStaticInt(VisibleClass.class, "getValue", -1);
        try {
            Tuner.callStaticSetInt(VisibleClass.class, "setValue", before + 2);
            assertEquals(before + 2, Tuner.callStaticInt(VisibleClass.class, "getValue", -1));
        } finally {
            Tuner.callStaticSetInt(VisibleClass.class, "setValue", before);
        }
    }

    /**
     * The lookup walks superclasses, since not every target declares its member -
     * and an inherited package-private member is one getMethod never finds.
     */
    @Test
    public void walksSuperclasses() throws Exception {
        Class<?> c = Class.forName(DERIVED);
        Method m = Tuner.findStaticMethod(c, "inherited");
        assertEquals(3, m.invoke(null));
        assertEquals(3, Tuner.callStaticInt(c, "inherited", -1));
    }

    /** A genuinely missing method yields the fallback and never throws. */
    @Test
    public void missingMethodYieldsFallback() throws Exception {
        Class<?> c = Class.forName(HIDDEN);
        int before = Tuner.callStaticInt(c, "getValue", -1);
        assertEquals(-1, Tuner.callStaticInt(c, "noSuchGetter", -1));
        Tuner.callStaticSetInt(c, "noSuchSetter", before + 1);
        assertEquals("a failed setter must not have moved the value",
                     before, Tuner.callStaticInt(c, "getValue", -1));
    }

    /** A class that is not loaded yet yields the fallback, for the retry-next-cycle path. */
    @Test
    public void nullClassYieldsFallback() {
        assertEquals(-1, Tuner.callStaticInt(null, "getValue", -1));
        assertEquals(-1L, Tuner.callStaticLong(null, "getValue", -1L));
        assertEquals(-1.0d, Tuner.callStaticDouble(null, "getValue", -1.0d), 0.0d);
        Tuner.callStaticSetInt(null, "setValue", 1);
        Tuner.callStaticSetLong(null, "setValue", 1L);
    }

    /**
     * A null class handed to the lookup directly is a programming error, and it has
     * to read as one: NoSuchMethodException, not the NullPointerException the
     * failure message would otherwise produce by dereferencing the argument.
     */
    @Test
    public void nullClassYieldsNoSuchMethodNotNpe() {
        try {
            Tuner.findStaticMethod(null, "getValue");
            fail("a null class must not resolve to a method");
        } catch (NoSuchMethodException e) {
            assertTrue("the message must name the method that was sought: " + e.getMessage(),
                       e.getMessage().contains("getValue"));
        }
    }

    /** Long and double getters reach the same non-public shapes as the int ones. */
    @Test
    public void reachesLongAndDoubleMembers() {
        Tuner.callStaticSetLong(WideTarget.class, "setLong", 17L);
        assertEquals(17L, Tuner.callStaticLong(WideTarget.class, "getLong", -1L));
        assertEquals(2.5d, Tuner.callStaticDouble(WideTarget.class, "getDouble", -1.0d), 0.0d);
    }

    /**
     * A wrong parameter type is a missing method, not a call: the streaming module
     * has setters differing only by signature (setMinPacingRate takes a long,
     * setMinPacingRateKBps an int), so resolving must refuse the near-miss rather
     * than invoke something adjacent. Asserted on the lookup, so the failure
     * reporter is not involved.
     */
    @Test
    public void wrongSignatureIsNotResolved() {
        try {
            Tuner.findStaticMethod(WideTarget.class, "setDouble", int.class);
            fail("an int-argument setDouble does not exist and must not resolve");
        } catch (NoSuchMethodException e) {
            assertEquals(WideTarget.class.getName() + ".setDouble", e.getMessage());
        }
    }

    /**
     * A reflective failure is reported once per class and method, and never again.
     * The dedup is the behaviour that keeps a permanent failure visible without
     * emitting it every cycle, so it is pinned here: it must fire on the first
     * failure, stay quiet on repeats, and keep separate keys for separate methods,
     * classes and the unresolved-class case.
     */
    @Test
    public void failureIsReportedOncePerMethod() {
        assertTrue("the first failure must be reported",
                   Tuner.shouldReportReflectorFailure(WideTarget.class, "firstReportMethod"));
        assertFalse("a repeat must not be reported again",
                    Tuner.shouldReportReflectorFailure(WideTarget.class, "firstReportMethod"));
        assertFalse("still quiet on a third attempt",
                    Tuner.shouldReportReflectorFailure(WideTarget.class, "firstReportMethod"));
        assertTrue("a different method is its own failure",
                   Tuner.shouldReportReflectorFailure(WideTarget.class, "secondReportMethod"));
        assertTrue("the same method on another class is its own failure",
                   Tuner.shouldReportReflectorFailure(VisibleClass.class, "firstReportMethod"));
        assertTrue("an unresolved class still gets its one report",
                   Tuner.shouldReportReflectorFailure(null, "unloadedClassMethod"));
        assertFalse("and it too is reported only once",
                    Tuner.shouldReportReflectorFailure(null, "unloadedClassMethod"));
    }

    /** The dedup key names the class and method, so the log line stays greppable. */
    @Test
    public void failureKeyNamesClassAndMethod() {
        assertEquals(VisibleClass.class.getName() + "#getValue",
                     Tuner.reflectorFailureKey(VisibleClass.class, "getValue"));
        assertEquals("an unresolved class has no name to lead with", "getValue",
                     Tuner.reflectorFailureKey(null, "getValue"));
    }

    /**
     * The real target that started this: ConnectionOptions is a package-private
     * class in a module the router cannot compile against, and every streaming
     * param backed by it reported a fallback instead of a live reading.
     *
     * <p>Skipped when the streaming classes are not on the test classpath - the
     * router suite runs against the mini-streaming jar, which does not carry them.
     * The fixture cases above cover the same access boundary either way.
     */
    @Test
    public void reachesStreamingConnectionOptions() {
        Class<?> c;
        try {
            c = Class.forName("net.i2p.client.streaming.impl.ConnectionOptions");
        } catch (ClassNotFoundException e) {
            Assume.assumeNoException("streaming is not on this test classpath", e);
            return;
        }
        int before = Tuner.callStaticInt(c, "getMaxRTOStatic", -1);
        assertTrue("the getter must read the live value, not the fallback", before > 0);
        try {
            Tuner.callStaticSetInt(c, "setMaxRTO", before - 1000);
            assertEquals(before - 1000, Tuner.callStaticInt(c, "getMaxRTOStatic", -1));
        } finally {
            Tuner.callStaticSetInt(c, "setMaxRTO", before);
        }
    }
}
