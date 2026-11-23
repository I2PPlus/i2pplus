package net.i2p.router.networkdb.kademlia;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure reason decision {@link IterativeSearchJob#failureReason} and
 * the three reason strings a search records when it stops of its own accord.
 *
 * <p>A search that ran out of window, or spent its whole query allowance with
 * nothing outstanding, ended in {@code cancelJob()}, which fired the waiters'
 * failure jobs and nothing else: no {@code netDb.lookupsFailed*} sample, no
 * negative-lookup cache entry, no blame for the peers that never answered, and
 * no log line. Live, that is how a lookup for a destination nobody has ends -
 * it asked every floodfill it was allowed to, heard "no" from all of them, and
 * then the caller asked again, uncached, on its next pass. Those endings now
 * go through the failure path, and this pins what they say.
 *
 * @since 0.9.72+
 */
public class SearchFailureReasonTest {

    /**
     * The reason cases. Every case calls the static decision, so the instance
     * holds no state and needs no facade, key or peer.
     */
    public SearchFailureReasonTest() {
    }

    /**
     * A search that stopped by its own decision says what made it stop, and
     * that reason outranks the generic wording - including when it never
     * queried anybody, where the generic text would have been the zero-try one.
     */
    @Test
    public void giveUpReasonWins() {
        assertEquals(IterativeSearchJob.GIVE_UP_BUDGET,
                     IterativeSearchJob.failureReason(
                         IterativeSearchJob.GIVE_UP_BUDGET, false));
        assertEquals(IterativeSearchJob.GIVE_UP_WINDOW,
                     IterativeSearchJob.failureReason(
                         IterativeSearchJob.GIVE_UP_WINDOW, true));
        assertEquals(IterativeSearchJob.GIVE_UP_NO_TIME,
                     IterativeSearchJob.failureReason(
                         IterativeSearchJob.GIVE_UP_NO_TIME, true));
    }

    /**
     * A search that timed out or was dropped says only whether it got as far as
     * asking somebody, which is the difference the caller can act on. Both
     * strings are pre-existing log text and stay byte-identical.
     */
    @Test
    public void noGiveUpReasonSaysWhetherAnyoneWasAsked() {
        assertEquals("No peer was ever queried",
                     IterativeSearchJob.failureReason(null, true));
        assertEquals("Every peer queried failed",
                     IterativeSearchJob.failureReason(null, false));
    }

    /**
     * The three reasons are what the log prints after "failed -> ", so each is
     * capitalized prose on one line, and short enough to leave the rest of the
     * line for the peer count, elapsed time and window left that follow it.
     */
    @Test
    public void reasonsAreFitToLog() {
        String[] reasons = {IterativeSearchJob.GIVE_UP_WINDOW,
                            IterativeSearchJob.GIVE_UP_NO_TIME,
                            IterativeSearchJob.GIVE_UP_BUDGET};
        for (String reason : reasons) {
            assertFalse(reason, reason.isEmpty());
            assertTrue(reason, Character.isUpperCase(reason.charAt(0)));
            assertTrue(reason, reason.indexOf('\n') < 0);
            assertTrue(reason + " is " + reason.length() + " chars",
                       reason.length() <= 40);
        }
    }

    /**
     * The three reasons name different endings. A single string for all of them
     * would leave the log unable to tell a starved search from an expired one.
     */
    @Test
    public void reasonsAreDistinct() {
        assertFalse(IterativeSearchJob.GIVE_UP_WINDOW.equals(
                    IterativeSearchJob.GIVE_UP_NO_TIME));
        assertFalse(IterativeSearchJob.GIVE_UP_WINDOW.equals(
                    IterativeSearchJob.GIVE_UP_BUDGET));
        assertFalse(IterativeSearchJob.GIVE_UP_NO_TIME.equals(
                    IterativeSearchJob.GIVE_UP_BUDGET));
    }
}
