package net.i2p.addressbook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * The decisions the host checker makes from its configuration and the size of
 * the address book: whether the periodic check runs at all, which proxy the
 * HEAD fallback goes through, and how many names one cycle probes.
 *
 * <p>The periodic check is off unless the operator asks for it, because it
 * contacts every destination in the router's address book on the router's own
 * initiative, and the HEAD fallback leaves from the browsing destination of the
 * proxy it is sent through. What is probed is bounded so a subscription feed
 * delivering a large book cannot make one cycle outlive the interval between
 * cycles.
 *
 * @since 0.9.72+
 */
public class HostCheckerProbeDecisionTest {

    /** Runs the decisions; it holds no state. */
    public HostCheckerProbeDecisionTest() {}

    /** The plain affirmative turns the periodic check on. */
    @Test
    public void trueEnablesTheCheck() {
        assertTrue(HostChecker.parseEnabled("true"));
    }

    /** The affirmative is read without regard to case, and its common aliases work. */
    @Test
    public void otherAffirmativesEnableTheCheck() {
        assertTrue(HostChecker.parseEnabled("TRUE"));
        assertTrue(HostChecker.parseEnabled("True"));
        assertTrue(HostChecker.parseEnabled("yes"));
        assertTrue(HostChecker.parseEnabled("YES"));
        assertTrue(HostChecker.parseEnabled("1"));
    }

    /**
     * Anything that is not an affirmative leaves the check off, including a
     * misspelling - a typo in the value must not start a cycle of probes nobody
     * asked for.
     */
    @Test
    public void anythingElseLeavesTheCheckOff() {
        assertFalse(HostChecker.parseEnabled("false"));
        assertFalse(HostChecker.parseEnabled(""));
        assertFalse(HostChecker.parseEnabled("no"));
        assertFalse(HostChecker.parseEnabled("0"));
        assertFalse(HostChecker.parseEnabled("ture"));
        assertFalse(HostChecker.parseEnabled("enabled"));
        assertFalse(HostChecker.parseEnabled(null));
    }

    /** A port in range is used as given, including the two ends of the range. */
    @Test
    public void aPortInRangeIsUsed() {
        assertEquals(4447, HostChecker.parseProxyPort("4447", 4444));
        assertEquals(1, HostChecker.parseProxyPort("1", 4444));
        assertEquals(65535, HostChecker.parseProxyPort("65535", 4444));
    }

    /**
     * A value a socket cannot be opened on keeps the port already in effect: a
     * port 0 or a negative port would fail the HEAD for every host in the book.
     */
    @Test
    public void anUnusablePortKeepsTheCurrentOne() {
        assertEquals(4444, HostChecker.parseProxyPort("0", 4444));
        assertEquals(4444, HostChecker.parseProxyPort("-1", 4444));
        assertEquals(4444, HostChecker.parseProxyPort("65536", 4444));
        assertEquals(4444, HostChecker.parseProxyPort("4444a", 4444));
        assertEquals(4444, HostChecker.parseProxyPort("", 4444));
    }

    /** A book at or under the limit is probed in full. */
    @Test
    public void aSmallBookIsProbedInFull() {
        List<String> names = Arrays.asList("a.i2p", "b.i2p", "c.i2p");
        assertEquals(names, HostChecker.probeSet(names, 3));
        assertEquals(names, HostChecker.probeSet(names, 4));
        List<String> none = Collections.emptyList();
        assertEquals(none, HostChecker.probeSet(none, 3));
    }

    /** A book over the limit is cut to the limit, at the front of the shuffled list. */
    @Test
    public void aLargeBookIsCutToTheLimit() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            names.add("host" + i + ".i2p");
        }
        List<String> probed = HostChecker.probeSet(names, 200);
        assertEquals(200, probed.size());
        assertEquals("host0.i2p", probed.get(0));
        assertEquals("host199.i2p", probed.get(199));
    }

    /**
     * The cut is a prefix, which is what makes a large book sampled rather than
     * truncated: the names are shuffled before the cut, so a different subset is
     * probed each cycle.
     */
    @Test
    public void theCutIsAPrefixOfTheShuffledNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            names.add("host" + i + ".i2p");
        }
        Collections.shuffle(names);
        List<String> probed = HostChecker.probeSet(names, 200);
        assertEquals(names.subList(0, 200), probed);
    }

    /** A limit of zero probes nothing, and a negative limit cannot probe a negative count. */
    @Test
    public void aLimitOfZeroProbesNothing() {
        List<String> names = Arrays.asList("a.i2p", "b.i2p");
        assertEquals(Collections.<String> emptyList(), HostChecker.probeSet(names, 0));
        assertEquals(Collections.<String> emptyList(), HostChecker.probeSet(names, -1));
    }

    /**
     * The limit keeps the worst case - every probed host silent, each holding a
     * slot for the ping timeout plus the HEAD fallback - inside the default
     * interval between cycles at the default concurrency.
     */
    @Test
    public void theWorstCaseCycleFitsInsideTheDefaultInterval() {
        int concurrent = 16;
        long perHostMs = 210_000L;
        long intervalMs = 4 * 60 * 60 * 1000L;
        long batches = (HostChecker.MAX_HOSTS_PER_CYCLE + concurrent - 1) / concurrent;
        long worstMs = batches * perHostMs;
        assertTrue(worstMs + "ms for " + HostChecker.MAX_HOSTS_PER_CYCLE + " names at " + concurrent +
                   " at once is inside the " + intervalMs + "ms interval", worstMs < intervalMs);
    }
}
