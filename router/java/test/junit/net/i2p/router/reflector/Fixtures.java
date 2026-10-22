package net.i2p.router.reflector;

/**
 * Fixture for the tuner's reflective lookups: a package-private class, in a
 * package the tuner does not live in, carrying both public and package-private
 * static members.
 *
 * <p>This is the shape of streaming's {@code ConnectionOptions}. The combination
 * matters: {@link Class#getMethod} reports a missing method for the package-private
 * members, and finds the public one only for {@code invoke} to refuse it with
 * IllegalAccessException, because the declaring class itself is not accessible.
 * Either way the tuner takes its fallback branch and tunes nothing.
 *
 * <p>Package-private on purpose - a test in another package cannot name these
 * classes, which is what makes the fixture reproduce the real call sites.
 *
 * @since 0.9.72+
 */
class HiddenTarget {

    /** Backing store for the static accessors below. */
    private static int _value = 7;

    /**
     * Public getter, reachable only if the call is made accessible.
     *
     * @return the current value
     */
    public static int getValue() {return _value;}

    /**
     * Public setter, reachable only if the call is made accessible.
     *
     * @param v the value to record
     */
    public static void setValue(int v) {_value = v;}

    /**
     * Package-private getter, invisible to a getMethod lookup.
     *
     * @return the current value
     */
    static int getHiddenValue() {return _value;}

    /**
     * Package-private setter, invisible to a getMethod lookup.
     *
     * @param v the value to record
     */
    static void setHiddenValue(int v) {_value = v;}
}

/**
 * Base declaring a package-private static that the subclass only inherits, so a
 * lookup that does not walk superclasses cannot reach it from the subclass.
 *
 * @since 0.9.72+
 */
class BaseTarget {

    /**
     * Inherited member, never redeclared by {@link DerivedTarget}.
     *
     * @return a fixed marker
     */
    static int inherited() {return 3;}
}

/**
 * Subclass that inherits {@link BaseTarget#inherited()} without declaring it.
 *
 * @since 0.9.72+
 */
class DerivedTarget extends BaseTarget {}
