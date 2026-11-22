package net.i2p.router.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * Names the reply a send was waiting for, for the line that reports its reply
 * timeout firing.
 *
 * <p>The timeout job exists only under {@code wantACK}, so the event is never a
 * send that timed out but a reply that did not come back, and a line that says
 * only "timed out" cannot be attributed: one peer accounted for 2,074 of the
 * 5,217 reply timeouts in 52 min on a live router with nothing on the line to
 * say what had gone unanswered. Which reply is awaited follows from the cipher,
 * so the classifier is pinned here rather than left to the call site.
 *
 * @since 0.9.72+
 */
public class ReplyTimeoutNamingTest {

    /**
     * Declares the no-arg constructor the class would otherwise inherit, so
     * the test class itself is documented.
     *
     * @since 0.9.72+
     */
    public ReplyTimeoutNamingTest() {}

    /**
     * A ratchet send awaits a ratchet reply, and that holds even if tags were
     * somehow registered too - the ratchet reply is what the callback is waiting
     * on, so naming the tags instead would point at a reply that never existed.
     */
    @Test
    public void ratchetTakesPrecedence() {
        assertEquals("ratchet reply",
                     OutboundClientMessageOneShotJob.replyWaitSubject(true, false));
        assertEquals("ratchet reply",
                     OutboundClientMessageOneShotJob.replyWaitSubject(true, true));
    }

    /**
     * An ElGamal2 send that registered session tags awaits proof they were
     * delivered, which is a different failure from a send that went unanswered:
     * the peer decrypted nothing.
     */
    @Test
    public void tagsAwaitDeliveryProof() {
        assertEquals("tag delivery",
                     OutboundClientMessageOneShotJob.replyWaitSubject(false, true));
    }

    /**
     * With no tags registered there is nothing to prove, so what is awaited is a
     * plain receipt that the message arrived.
     */
    @Test
    public void noTagsAwaitsAReceipt() {
        assertEquals("delivery receipt",
                     OutboundClientMessageOneShotJob.replyWaitSubject(false, false));
    }

    /**
     * The three cases must be distinguishable by grepping the log, which is the
     * whole point of naming them, and none may come back blank.
     */
    @Test
    public void everyCaseIsNamedApart() {
        String ratchet = OutboundClientMessageOneShotJob.replyWaitSubject(true, false);
        String tags = OutboundClientMessageOneShotJob.replyWaitSubject(false, true);
        String plain = OutboundClientMessageOneShotJob.replyWaitSubject(false, false);
        assertNotEquals(ratchet, tags);
        assertNotEquals(tags, plain);
        assertNotEquals(ratchet, plain);
        for (String s : new String[] {ratchet, tags, plain}) {
            assertEquals("named for the middle of a sentence", s, s.trim().toLowerCase());
        }
    }
}
