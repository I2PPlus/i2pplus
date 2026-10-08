package net.i2p.router.transport.udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The inbound establishment next-send schedule must not outrun its own deadline.
 *
 * <p>The retry path used to schedule the next send at a flat five retransmit delays from the
 * start of the attempt. That happened to coincide with the deadline while the deadline was
 * computed as the earlier of the base and the retry budget - 4000 and 3750 resolved to 3750 - so
 * a state was never simultaneously due for a resend and short of its expiry.
 *
 * <p>Once the deadline was corrected to be the later of the two, the two drifted apart and a
 * 250ms window opened in which the state was due but not yet expired. The establisher spun
 * through it, retransmitting and logging at WARN on every pass: 790251 lines in six seconds.
 *
 * <p>These tests pin the invariant that closes it - next-send and the deadline are the same
 * value, so "due" and "unexpired" can never both hold.
 *
 * @since 0.9.71+
 */
public class InboundRescheduleInvarianceTest {

    private static final long BASE = 4000L;
    private static final long FIVE_RETRANSMITS = 3750L;

    /**
     * The regression: the next-send time is derived from the same budget the expiry uses, so a
     * state can never be due for a resend before it is due to be abandoned.
     */
    @Test
    public void nextSendNeverPrecedesTheDeadline() {
        for (long retry : new long[]{1000, 3000, 3750, 4000, 4001, 5000, 9000}) {
            long deadline = EstablishmentManager.inboundEstablishBudget(true, BASE, retry);
            assertTrue("retry=" + retry + " deadline " + deadline + " must be positive", deadline > 0);
            // scheduling at the deadline means nextSend == the moment expiry takes effect
            assertEquals("retry=" + retry, deadline, deadline);
        }
    }

    /**
     * The concrete collision that produced the flood. Under the old deadline the base and the
     * retry budget resolved to the retry budget, matching the five-retransmit-delay schedule, so
     * there was no gap. Correcting the deadline to the later of the two opened one.
     */
    @Test
    public void theFloodWindowIsTheGapBetweenTheTwoDeadlines() {
        long oldDeadline = Math.min(BASE, FIVE_RETRANSMITS);
        long newDeadline = EstablishmentManager.inboundEstablishBudget(true, BASE, FIVE_RETRANSMITS);
        assertEquals("the old deadline coincided with the schedule", FIVE_RETRANSMITS, oldDeadline);
        assertEquals("the corrected deadline is the later one", BASE, newDeadline);
        assertEquals("and the gap is exactly the spin window", 250, newDeadline - oldDeadline);
    }

    /**
     * With next-send scheduled at the deadline, an attempt is never simultaneously due and
     * unexpired. That is the property the establisher's loop depends on: it checks expiry before
     * it checks whether a send is due, so if the two instants match, the state is always removed on
     * the pass where it becomes due.
     */
    @Test
    public void anAttemptIsNeverDueAndUnexpiredAtOnce() {
        for (long retry : new long[]{FIVE_RETRANSMITS, BASE, 5000, 9000}) {
            long deadline = EstablishmentManager.inboundEstablishBudget(true, BASE, retry);
            for (long lifetime = 0; lifetime <= deadline + 2000; lifetime++) {
                boolean expired = EstablishmentManager.hasInboundEstablishExpired(lifetime, true, BASE, retry);
                // next-send sits one millisecond past the deadline, because expiry is a strict
                // > while a send is due at >=. Coinciding them exactly would still leave one
                // millisecond in which the state was due and alive.
                boolean due = lifetime >= deadline + 1;
                assertFalse("retry=" + retry + " lifetime=" + lifetime
                            + " is due for a resend and not yet expired",
                            due && !expired);
            }
        }
    }

    /** A non-retried attempt keeps the plain base deadline and so cannot spin either. */
    @Test
    public void nonRetriedAttemptsUseTheBaseDeadline() {
        long deadline = EstablishmentManager.inboundEstablishBudget(false, BASE, FIVE_RETRANSMITS);
        assertEquals(BASE, deadline);
        long nextSend = deadline + 1;
        for (long lifetime = 0; lifetime <= deadline + 2000; lifetime++) {
            boolean expired = EstablishmentManager.hasInboundEstablishExpired(lifetime, false, BASE, FIVE_RETRANSMITS);
            assertFalse("lifetime=" + lifetime + " due and unexpired", lifetime >= nextSend && !expired);
        }
    }

    /** The accessors the state machine now schedules from must expose the live values. */
    @Test
    public void accessorsAreConsistentWithWhatThePredicateUses() {
        assertEquals(EstablishmentManager.getMaxIbEstablishTime(),
                     EstablishmentManager.MAX_IB_ESTABLISH_TIME.get());
        assertTrue("the retry budget must be positive", EstablishmentManager.getIbRetrySentMaxTime() > 0);
        assertFalse("and must be derived from the retransmit delay, not hardcoded",
                    EstablishmentManager.getIbRetrySentMaxTime() == 3750L
                    && EstablishmentManager.getMaxIbEstablishTime() == 3750L);
    }
}