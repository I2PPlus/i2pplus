package net.i2p.i2ptunnel.access;

import static org.junit.Assert.*;

import java.io.File;
import net.i2p.data.Hash;
import org.junit.Test;

/**
 * Tests how the attempt history kept for a destination is sized.
 *
 * <p>A destination's counters are tested by two rules, not one: the admission rule
 * that decides whether the next connection is allowed, and every recorder's rule,
 * which the filter runs over the same attempts looking for destinations to write to
 * its breach file. The history has to hold the attempts the wider of the two asks
 * for. Sized from the admission rule alone, a recorder that wants more attempts than
 * are retained can never breach, and the recorder file - which is usually fed back in
 * as a throttle list - stays empty without saying why.
 *
 * <p>The retention is still capped, so a rule written with an absurd count cannot
 * allocate a buffer per destination; that rule is never breached, and the definition
 * says so once when it is read.
 *
 * @since 0.9.72+
 */
public class RecorderRetentionTest {

    /** Fewest timestamps a counter retains, mirrored from the counter's own floor. */
    private static final int MIN_RETAINED = 64;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one; the trackers under test are built inside each method.
     *
     * @since 0.9.72+
     */
    public RecorderRetentionTest() {}

    /**
     * A recorder rule wider than the admission rule still breaches: the history
     * follows the recorder, not the rule that owns the counter.
     */
    @Test
    public void testWiderRecorderRuleStillBreaches() {
        Threshold admission = new Threshold(15, 5);
        DestTracker tracker = new DestTracker(Hash.FAKE_HASH, admission, 100);
        long now = 100000;
        for (int i = 0; i < 120; i++) {
            tracker.recordAccess(now + i);
        }
        assertTrue("the admission rule of 15 in 5s breached",
                   tracker.recordAccess(now + 120));
        Threshold recorder = new Threshold(100, 5);
        assertTrue("the recorder rule of 100 in 5s never breached",
                   tracker.isBreached(recorder, now + 120));
    }

    /**
     * A deny rule keeps one timestamp for itself - that is what keeps a deny list of
     * thousands of destinations cheap - but a recorder reading the same attempts
     * still gets the attempts its own rule asks for.
     */
    @Test
    public void testDenyDefaultStillFeedsAWiderRecorder() {
        DestTracker tracker = new DestTracker(Hash.FAKE_HASH, Threshold.DENY, 30);
        long now = 100000;
        for (int i = 0; i < 29; i++) {
            tracker.recordAccess(now + i);
        }
        assertFalse("the recorder rule of 30 breached before the 30th attempt",
                    tracker.isBreached(new Threshold(30, 5), now + 28));
        tracker.recordAccess(now + 29);
        assertTrue("the recorder rule of 30 in 5s never breached",
                   tracker.isBreached(new Threshold(30, 5), now + 29));
    }

    /**
     * The retention is the wider of the two rules: the recorder count when it is
     * larger, the admission rule's own sizing when it is not.
     */
    @Test
    public void testRetentionIsTheWiderOfTheTwoRules() {
        assertEquals(100, AccessCounter.retainedFor(new Threshold(15, 5), 100));
        assertEquals(MIN_RETAINED, AccessCounter.retainedFor(Threshold.DENY, 30));
        Threshold admission = new Threshold(15, 5);
        assertEquals(MIN_RETAINED, AccessCounter.retainedFor(admission, 0));
        assertEquals(MIN_RETAINED, AccessCounter.retainedFor(admission, 5));
    }

    /**
     * A rule counting more attempts than the cap retains is still capped, and is
     * never breached - the answer the definition is warned about, not a silent one.
     */
    @Test
    public void testOverLargeRuleStaysCappedAndUnbreached() {
        Threshold wide = new Threshold(20000, 60);
        assertEquals(AccessCounter.MAX_RETAINED, AccessCounter.retainedFor(wide, 0));
        AccessCounter counter = new AccessCounter(wide, 0);
        for (int i = 0; i < AccessCounter.MAX_RETAINED; i++) {
            counter.recordAccess(i);
        }
        assertFalse("a rule wider than the retention was breached",
                    counter.isBreached(wide, AccessCounter.MAX_RETAINED - 1));
    }

    /**
     * The definition reports the widest of its recorder rules, which is what the
     * trackers are sized from, and zero when it has no recorders at all.
     */
    @Test
    public void testDefinitionReportsTheWidestRecorder() {
        FilterDefinitionElement[] none = new FilterDefinitionElement[0];
        Threshold defaultRule = new Threshold(15, 5);
        Recorder[] recorders = {
                new Recorder(new File("suspicious.txt"), new Threshold(30, 5)),
                new Recorder(new File("noisy.txt"), new Threshold(100, 5))
        };
        FilterDefinition wide = new FilterDefinition(defaultRule, none, recorders);
        FilterDefinition bare =
                new FilterDefinition(defaultRule, none, new Recorder[0]);
        assertEquals(100, wide.getMaxRecorderConnections());
        assertEquals(0, bare.getMaxRecorderConnections());
    }
}
