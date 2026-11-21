package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.router.TunnelInfo;
import net.i2p.stat.RateConstants;
import net.i2p.stat.StatManager;

import org.junit.Test;

/**
 * Tests for how a test round is attributed when the partner leg - the tunnel
 * the round was actually routed through - is not usable: the stat event chosen
 * by {@code TestJob#partnerVerdict(boolean)}, the gateway tag from
 * {@code TestJob#partnerTag(TunnelInfo)}, and the two WARN lines built by
 * {@code TestJob#partnerSuspectLine} and {@code TestJob#partnerChargedLine}.
 *
 * <p>The pair of counters exists because the log used to show only the
 * consequence of a suspect partner (a failure kept or charged) and never the
 * reason, so a pool being executed by its own broken reply path looked exactly
 * like a pool of dead tunnels. The ratio between the two counters is what
 * separates those diagnoses, so both names must be registered, distinct, and
 * emitted from the one classification.
 *
 * <p>The {@code TestJob} members are named in {@code @code} rather than
 * {@code @link}: the javadoc-test classpath does not carry the compiled router
 * classes, so a link here would be an unresolvable reference.
 *
 * @since 0.9.72+
 */
public class TestJobPartnerAttributionTest {

    /**
     * JUnit fixture object; every test here drives a static helper of
     * {@code TestJob}, so the instance carries no state.
     */
    public TestJobPartnerAttributionTest() {
    }

    private static final int BUDGET = 3;

    // ---------------- partnerVerdict ----------------

    /**
     * A round that still has defer budget left is deferred; one that spent it
     * is charged to the tunnel. The two must be different events, or the ratio
     * that separates "reply path is dead" from "tunnels are dead" cannot be read.
     */
    @Test
    public void deferredAndChargedRoundsAreDifferentEvents() {
        assertNotEquals(TestJob.partnerVerdict(false), TestJob.partnerVerdict(true));
        assertEquals("PartnerSuspect", TestJob.partnerVerdict(false));
        assertEquals("PartnerCharged", TestJob.partnerVerdict(true));
    }

    /**
     * A verdict name that is not in the declared set is a stat that will never
     * be registered, and StatManager drops data for names it has never seen.
     */
    @Test
    public void bothVerdictsAreDeclaredEvents() {
        java.util.Set<String> declared = new java.util.HashSet<String>();
        for (String ev : TestJob.DIRECTION_EVENTS) {
            declared.add(ev);
        }
        assertTrue("PartnerSuspect missing from DIRECTION_EVENTS",
                   declared.contains(TestJob.partnerVerdict(false)));
        assertTrue("PartnerCharged missing from DIRECTION_EVENTS",
                   declared.contains(TestJob.partnerVerdict(true)));
    }

    /**
     * Both verdicts must be registered in both directions, or the data written
     * at the failure site is silently dropped and the counters read as "this
     * never happens" instead of "this is broken".
     */
    @Test
    public void bothVerdictsAreRegisteredInBothDirections() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        TestJob.registerDirectionStats(stats, RateConstants.SHORT_TERM_RATES);
        for (boolean inbound : new boolean[] {true, false}) {
            for (boolean spent : new boolean[] {true, false}) {
                String name = TestJob.directionStat(inbound, TestJob.partnerVerdict(spent));
                assertNotNull(name + " is emitted but never registered", stats.getRate(name));
            }
        }
    }

    // ---------------- partnerTag ----------------

    /** A round with no partner at all has no gateway to name. */
    @Test
    public void missingPartnerHasNoGateway() {
        assertEquals("none", TestJob.partnerTag(null));
    }

    /**
     * A real partner is named by its gateway, bracketed, so the line stays
     * greppable and the two partner lines cannot disagree about the form.
     */
    @Test
    public void partnerIsNamedByItsGateway() {
        TunnelInfo partner = mock(TunnelInfo.class);
        byte[] bits = new byte[Hash.HASH_LENGTH];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = (byte) i;
        }
        Hash gw = new Hash(bits);
        when(partner.getGateway()).thenReturn(gw);
        String tag = TestJob.partnerTag(partner);
        assertEquals("[" + gw.toShortBase32() + "]", tag);
        assertTrue("gateway must stay bracketed: " + tag, tag.startsWith("[") && tag.endsWith("]"));
    }

    // ---------------- the two log lines ----------------

    /**
     * The deferral line must carry the budget alongside the count. A bare
     * "Deferrals: 2" cannot be read against a limit the reader does not know,
     * and the whole point of the line is to say how close the round is to being
     * charged anyway.
     */
    @Test
    public void deferralLineCarriesTheDeferralBudget() {
        String line = TestJob.partnerSuspectLine("[abc12]", false, 2, BUDGET);
        assertTrue(line, line.contains("Deferrals: 2 of 3"));
        assertTrue(line, line.contains("Partner gateway: [abc12]"));
    }

    /**
     * A partner borrowed from the exploratory pool is the paired pool saying it
     * has nothing to test with, which is the evidence that should trigger a
     * rebuild there. It must be visible on the line, and absent otherwise.
     */
    @Test
    public void exploratoryPartnerIsReportedAsBorrowed() {
        String borrowed = TestJob.partnerSuspectLine("[abc12]", true, 1, BUDGET);
        String own = TestJob.partnerSuspectLine("[abc12]", false, 1, BUDGET);
        assertTrue(borrowed, borrowed.contains("Partner source: exploratory pool"));
        assertFalse(own, own.contains("exploratory"));
    }

    /**
     * The charged line must say the tunnel was charged and name the budget that
     * ran out; it must not read like a deferral, or the one line that reports a
     * non-excused failure looks like the lines that report excused ones.
     */
    @Test
    public void chargedLineSaysTheTunnelWasCharged() {
        String line = TestJob.partnerChargedLine("[abc12]", BUDGET);
        assertTrue(line, line.contains("Charging the tunnel after 3 rounds"));
        assertTrue(line, line.contains("Partner gateway: [abc12]"));
        assertFalse(line, line.toLowerCase().contains("deferr"));
    }

    /**
     * House log shape: an arrow after the event, {@code key: value} segments
     * rather than {@code key=value}, no trailing whitespace, and every emitted
     * line short enough to read in the console log.
     */
    @Test
    public void bothLinesFollowTheHouseLogShape() {
        String tag = "[" + new Hash(new byte[Hash.HASH_LENGTH]).toShortBase32() + "]";
        String[] lines = {TestJob.partnerSuspectLine(tag, true, 1, BUDGET),
                          TestJob.partnerSuspectLine(tag, false, 2, BUDGET),
                          TestJob.partnerChargedLine(tag, BUDGET)};
        for (String line : lines) {
            assertTrue(line, line.contains(" -> "));
            assertFalse(line, line.contains("="));
            for (String part : line.split("\\n")) {
                assertTrue("line over 100 chars: " + part.length() + ' ' + part,
                           part.length() <= 100);
                assertFalse("trailing whitespace: [" + part + ']',
                            part.endsWith(" ") || part.endsWith("\t"));
            }
        }
    }

    /**
     * A missing partner must still produce a readable line - the round is
     * deferred for it - rather than a hole where the gateway should be.
     */
    @Test
    public void missingPartnerStillNamesTheGatewaySegment() {
        String tag = TestJob.partnerTag(null);
        assertTrue(TestJob.partnerSuspectLine(tag, false, 1, BUDGET)
                   .contains("Partner gateway: none"));
        assertTrue(TestJob.partnerChargedLine(tag, BUDGET)
                   .contains("Partner gateway: none"));
    }
}
