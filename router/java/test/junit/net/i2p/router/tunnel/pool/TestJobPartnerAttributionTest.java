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
 * Tests how a test round is attributed when the partner leg - the tunnel
 * the round was actually routed through - is not usable: whether the round is
 * charged to the tunnel at all ({@code TestJob#chargePartnerRound}), the stat
 * event chosen ({@code TestJob#partnerVerdict}), the gateway tag from
 * {@code TestJob#partnerTag(TunnelInfo)}, and the three WARN lines built by
 * {@code TestJob#partnerSuspectLine}, {@code TestJob#partnerShortLine} and
 * {@code TestJob#partnerChargedLine}.
 *
 * <p>The counters exist because the log used to show only the consequence of a
 * suspect partner (a failure kept or charged) and never the reason, so a pool
 * being executed by its own broken reply path looked exactly like a pool of
 * dead tunnels. The ratio between them is what separates those diagnoses, so
 * every name must be registered, distinct, and emitted from the one
 * classification - and a charge must not be taken from a paired pool that was
 * itself too short to supply a fair partner, which is how one degraded half
 * used to execute the other.
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

    /** The paired-pool floor under test, read from production so it cannot drift. */
    private static final int MIN_FLOOR = TestJob.MIN_PAIRED_POOL_TUNNELS;

    // ---------------- chargePartnerRound ----------------

    /**
     * A charge needs both halves: the defer budget spent, and a paired pool
     * that was fit to supply a fair partner. Either one alone leaves the round
     * excused - charging a tunnel because its reply path was short of tunnels
     * is what shrinks the pool that had to recover.
     */
    @Test
    public void chargeNeedsSpentBudgetAndAFitPairedPool() {
        assertFalse("budget still has rounds left", TestJob.chargePartnerRound(false, false));
        assertFalse("budget left and paired pool short", TestJob.chargePartnerRound(false, true));
        assertFalse("paired pool too short to have supplied a fair partner",
                    TestJob.chargePartnerRound(true, true));
        assertTrue(TestJob.chargePartnerRound(true, false));
    }

    /**
     * The paired-pool floor is 2 usable tunnels. One tunnel is not a reply
     * path, it is the one tunnel the pool is trying to replace.
     */
    @Test
    public void pairedPoolFloorIsTwoTunnels() {
        assertEquals(2, TestJob.MIN_PAIRED_POOL_TUNNELS);
    }

    /**
     * The decision must actually be taken by the helper at the failure site,
     * off the paired pool's own count. A budget-only comparison is what the
     * escape used to be: it read "this tunnel has deferred long enough" and
     * charged it, while the pool that had to supply the partner was itself down
     * to one tunnel. Asserting the wiring, not just the helper, is what keeps
     * the two from being split apart again.
     *
     * @throws Exception if the TestJob source cannot be read back from disk
     */
    @Test
    public void theFailureSiteAsksThePairedPoolBeforeCharging() throws Exception {
        java.io.File src = new java.io.File(
            "router/java/src/net/i2p/router/tunnel/pool/TestJob.java");
        if (!src.isFile()) {src = new java.io.File("src/net/i2p/router/tunnel/pool/TestJob.java");}
        org.junit.Assume.assumeTrue("source not available", src.isFile());
        String body = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
        assertTrue("the charge must go through chargePartnerRound",
                   body.contains("chargePartnerRound(_partnerDeferrals >= MAX_PARTNER_DEFERRALS,"));
        assertTrue("the paired pool's usable count must reach the decision",
                   body.contains("pairedTunnels < MIN_PAIRED_POOL_TUNNELS"));
        assertTrue("the paired pool must be asked, not assumed",
                   body.contains("paired.countUsableTunnels()"));
    }

    // ---------------- partnerVerdict ----------------

    /**
     * The three partner outcomes must be three different events, or the ratio
     * that separates "reply path is dead" from "tunnels are dead" cannot be
     * read off the stats.
     */
    @Test
    public void theThreePartnerOutcomesAreDifferentEvents() {
        assertEquals("PartnerSuspect", TestJob.partnerVerdict(false, false));
        assertEquals("PartnerPairedShort", TestJob.partnerVerdict(false, true));
        assertEquals("PartnerCharged", TestJob.partnerVerdict(true, false));
        assertNotEquals(TestJob.partnerVerdict(false, true), TestJob.partnerVerdict(true, false));
    }

    /**
     * A charge is never reported as a short-paired-pool defer. The pair cannot
     * occur together in production - a charge requires a fit paired pool - but
     * if it ever did, the charge must be the event written, or a condemned
     * tunnel would be counted as excused.
     */
    @Test
    public void aChargeIsNeverTheShortPairedEvent() {
        assertEquals("PartnerCharged", TestJob.partnerVerdict(true, true));
    }

    /**
     * Every verdict name must be in the declared set: a name that is not
     * declared is a stat that will never be registered, and StatManager drops
     * data for names it has never seen.
     */
    @Test
    public void everyVerdictIsADeclaredEvent() {
        java.util.Set<String> declared = new java.util.HashSet<String>();
        for (String ev : TestJob.DIRECTION_EVENTS) {
            declared.add(ev);
        }
        for (boolean charge : new boolean[] {true, false}) {
            for (boolean shortPaired : new boolean[] {true, false}) {
                String ev = TestJob.partnerVerdict(charge, shortPaired);
                assertTrue(ev + " missing from DIRECTION_EVENTS", declared.contains(ev));
            }
        }
    }

    /**
     * Every verdict must be registered in both directions, or the data written
     * at the failure site is silently dropped and the counters read as "this
     * never happens" instead of "this is broken".
     */
    @Test
    public void everyVerdictIsRegisteredInBothDirections() {
        StatManager stats = new StatManager(I2PAppContext.getGlobalContext());
        TestJob.registerDirectionStats(stats, RateConstants.SHORT_TERM_RATES);
        for (boolean inbound : new boolean[] {true, false}) {
            for (boolean charge : new boolean[] {true, false}) {
                for (boolean shortPaired : new boolean[] {true, false}) {
                    String ev = TestJob.partnerVerdict(charge, shortPaired);
                    String name = TestJob.directionStat(inbound, ev);
                    assertNotNull(name + " is emitted but never registered", stats.getRate(name));
                }
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

    // ---------------- the three log lines ----------------

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
     * The withheld-charge line must name both numbers that made the charge
     * unfair - what the paired pool had and what it needs - and say the charge
     * was withheld. A line that only says "deferred" cannot be told apart from
     * an ordinary budget defer, and the whole point of the line is to show the
     * feedback loop: a pool losing tunnels to a reply path that has almost
     * nothing to reply through.
     */
    @Test
    public void withheldChargeLineNamesThePairedPoolShortfall() {
        String line = TestJob.partnerShortLine("[abc12]", 1, MIN_FLOOR, 5);
        assertTrue(line, line.contains("withholding the charge"));
        assertTrue(line, line.contains("Paired pool tunnels: 1"));
        assertTrue(line, line.contains("Minimum: 2"));
        assertTrue(line, line.contains("Rounds deferred: 5"));
        assertTrue(line, line.contains("Partner gateway: [abc12]"));
        assertFalse("must not read as a charge: " + line, line.contains("Charging"));
    }

    /**
     * House log shape: an arrow after the event, {@code key: value} segments
     * rather than {@code key=value}, no trailing whitespace, and every emitted
     * line short enough to read in the console log.
     */
    @Test
    public void everyLineFollowsTheHouseLogShape() {
        String tag = "[" + new Hash(new byte[Hash.HASH_LENGTH]).toShortBase32() + "]";
        String[] lines = {TestJob.partnerSuspectLine(tag, true, 1, BUDGET),
                          TestJob.partnerSuspectLine(tag, false, 2, BUDGET),
                          TestJob.partnerChargedLine(tag, BUDGET),
                          TestJob.partnerShortLine(tag, 0, MIN_FLOOR, 7)};
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
        assertTrue(TestJob.partnerShortLine(tag, 0, MIN_FLOOR, 4)
                   .contains("Partner gateway: none"));
    }
}
