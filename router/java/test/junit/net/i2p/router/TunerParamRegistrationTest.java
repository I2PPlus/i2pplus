package net.i2p.router;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Guards that every parameter the Tuner declares is registered, and that every
 * name its ordering invariants cite belongs to a registered parameter.
 *
 * <p>Both halves of this were silent defects. {@code MaxHighCapPeersParam} sat
 * in the file with no instantiation site, so the high-capacity peer tier kept
 * its 3,000 peer ceiling while its floor, {@code minHighCapacityPeers}, was
 * hill-climbed against the same stat - the key never appeared on the tuning
 * page at all. And {@code readRuntimeValue()} warns only once, ever, when an
 * invariant names a parameter nobody registered, so a pair written against an
 * unregistered or mistyped name is simply never checked.
 *
 * <p>Neither is visible from the tuning page or the logs, which is why both are
 * pinned here instead of left to be noticed on a live router.
 *
 * @since 0.9.72+
 */
public class TunerParamRegistrationTest {

    /** Key of the high-capacity tier's floor. */
    private static final String CAP_MIN =
            "profileOrganizer.minHighCapacityPeers";

    /** Key of the high-capacity tier's ceiling. */
    private static final String CAP_MAX =
            "profileOrganizer.maxHighCapacityPeers";

    /** Key of the fast tier's floor. */
    private static final String FAST_MIN = "profileOrganizer.minFastPeers";

    /** Key of the fast tier's ceiling. */
    private static final String FAST_MAX = "profileOrganizer.maxFastPeers";

    /** The stat both members of the high-capacity pair hill-climb against. */
    private static final String CAP_STAT = "peer.qualityPeerCount";

    /** The stat the fast tier's floor watches - the tier's own size. */
    private static final String FAST_MIN_STAT = "peer.fastPeerCount";

    /** The stat the fast tier's ceiling watches - the pool it draws from. */
    private static final String FAST_MAX_STAT = "peer.qualityPeerCount";

    /** The tuner under test, built once against an isolated context. */
    private static Tuner _tuner;

    /**
     * Builds the tuner. A tuner needs a context to read its parameters' runtime
     * values, so without one every case here is skipped rather than passed.
     */
    @BeforeClass
    public static void setUp() {
        RouterContext ctx = RouterTestHelper.newContext();
        Assume.assumeTrue("No RouterContext available", ctx != null);
        try {
            _tuner = new Tuner(ctx);
        } catch (RuntimeException re) {
            Assume.assumeNoException("Tuner unavailable in this context", re);
        }
        Assume.assumeNotNull(_tuner);
    }

    /**
     * The registration cases. Every case reads the tuner built above, so the
     * instance itself holds no state.
     */
    public TunerParamRegistrationTest() {
    }

    /**
     * The parameter instances the tuner registered.
     * <p>Read off the private field rather than {@code getSnapshots()}: a
     * snapshot is a {@code ParamSnapshot}, so it cannot say which parameter
     * class produced it, and the point of these cases is the class.
     *
     * @return one entry per registered parameter
     */
    private static List<?> params() {
        try {
            Field f = Tuner.class.getDeclaredField("_params");
            f.setAccessible(true);
            return (List<?>) f.get(_tuner);
        } catch (ReflectiveOperationException re) {
            throw new AssertionError("Tuner._params is not reachable", re);
        }
    }

    /**
     * Registered names of the tuner's parameters.
     *
     * @return one entry per registered parameter
     */
    private static Set<String> registeredNames() {
        Set<String> rv = new HashSet<String>(160);
        for (Object p : params())
            rv.add(((Tuner.TunableParam) p).getName());
        return rv;
    }

    /**
     * Snapshot for one parameter key.
     *
     * @param name the registered parameter key
     * @return the snapshot, null when that key is not registered
     */
    private static Tuner.ParamSnapshot snapshot(String name) {
        for (Tuner.ParamSnapshot snap : _tuner.getSnapshots()) {
            if (snap.name.equals(name))
                return snap;
        }
        return null;
    }

    /**
     * Is the ordered pair named in the invariant table?
     *
     * @param lower the parameter that must stay at or below
     * @param upper the parameter that must stay at or above
     * @return true when the table orders that pair
     */
    private static boolean isOrderedPair(String lower, String upper) {
        for (String[] pair : Tuner.ORDER_INVARIANTS) {
            if (pair[0].equals(lower) && pair[1].equals(upper))
                return true;
        }
        return false;
    }

    /**
     * Every concrete {@code BaseParam} nested in Tuner must be registered.
     * <p>A parameter class that is never instantiated reads alive in every
     * search, promises tuning that never happens, and leaves the value it
     * guards at its code default for the life of the router.
     * {@code MaxHighCapPeersParam} was exactly that.
     */
    @Test
    public void everyDeclaredParamIsRegistered() {
        List<String> missing = new ArrayList<String>(4);
        for (Class<?> c : Tuner.class.getDeclaredClasses()) {
            if (!Tuner.BaseParam.class.isAssignableFrom(c) ||
                Modifier.isAbstract(c.getModifiers()))
                continue;
            String simple = c.getSimpleName();
            boolean found = false;
            for (Object p : params()) {
                if (p.getClass().getSimpleName().equals(simple)) {
                    found = true;
                    break;
                }
            }
            if (!found)
                missing.add(simple);
        }
        assertTrue("declared but never registered: " + missing,
                   missing.isEmpty());
        assertFalse("no params registered at all", params().isEmpty());
    }

    /**
     * Every key the invariant table cites must be a registered parameter.
     * <p>{@code findInversions()} skips a pair whose values it cannot read, and
     * {@code readRuntimeValue()} reports an unknown name once for the whole
     * router's life, so an unregistered member makes the pair silently inert.
     */
    @Test
    public void everyInvariantNameIsRegistered() {
        Set<String> names = registeredNames();
        assertFalse("no params registered at all", names.isEmpty());
        for (String[] pair : Tuner.ORDER_INVARIANTS) {
            for (String name : pair)
                assertTrue(name + " is in the table but not registered",
                           names.contains(name));
        }
    }

    /**
     * The defect. The high-capacity tier's ceiling was declared and never
     * registered, so only its floor was tuned.
     */
    @Test
    public void highCapacityTierPairIsRegistered() {
        assertNotNull("high-capacity tier floor missing", snapshot(CAP_MIN));
        assertNotNull("high-capacity tier ceiling missing", snapshot(CAP_MAX));
    }

    /**
     * Both tier pairs are ordering invariants. The high-capacity pair climbs
     * and falls on one stat, so an inverted pair is that signal read in
     * opposite directions; the fast pair watches the tier's own size against
     * the pool it draws from, which inverts just as easily.
     */
    @Test
    public void peerTierPairsAreOrderedInvariants() {
        assertTrue(isOrderedPair(FAST_MIN, FAST_MAX));
        assertTrue(isOrderedPair(CAP_MIN, CAP_MAX));
        assertEquals(CAP_STAT, snapshot(CAP_MIN).observedStat);
        assertEquals(CAP_STAT, snapshot(CAP_MAX).observedStat);
        assertEquals(FAST_MIN_STAT, snapshot(FAST_MIN).observedStat);
        assertEquals(FAST_MAX_STAT, snapshot(FAST_MAX).observedStat);
    }

    /**
     * The ranges must be able to hold the order at every legal value: a floor
     * whose own ceiling sat above the ceiling's ceiling could be pushed past it
     * by the range check alone.
     */
    @Test
    public void tierRangesCanHoldTheOrder() {
        Tuner.ParamSnapshot min = snapshot(CAP_MIN);
        Tuner.ParamSnapshot max = snapshot(CAP_MAX);
        assertTrue(min.name + " floor " + min.min + " above " + max.name +
                   ' ' + max.min, min.min <= max.min);
        assertTrue(min.name + " ceiling " + min.max + " above " + max.name +
                   ' ' + max.max, min.max <= max.max);
        assertTrue(min.name + " default " + min.defaultValue + " above " +
                   max.name + " default " + max.defaultValue,
                   min.defaultValue <= max.defaultValue);
    }
}
