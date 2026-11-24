package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Released into the public domain with no warranty of any kind,
 * either expressed or implied.
 */

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the result words and the log shape of {@link HostReplyMessage}.
 *
 * <p>The result of a destination lookup reached the log as a bare number
 * ({@code Result: 6}), which is only readable with proposal 167 open, and the
 * session id was written unlabelled at the head of the bullet, so a reader could
 * not tell which number was which.
 *
 * @since 0.9.72+
 */
public class HostReplyMessageTest {

    /**
     * Groups the result-word and log-shape checks for one message class; the
     * class holds no state, each test builds its own message.
     */
    public HostReplyMessageTest() {}

    /** Every defined code has words, so none of them can reach the log as a bare int. */
    @Test
    public void everyDefinedResultHasWords() {
        assertNotEquals("0", HostReplyMessage.getResultString(HostReplyMessage.RESULT_SUCCESS));
        assertEquals("SUCCESS", HostReplyMessage.getResultString(HostReplyMessage.RESULT_SUCCESS));
        assertEquals("FAILURE", HostReplyMessage.getResultString(HostReplyMessage.RESULT_FAILURE));
        assertEquals("SECRET REQUIRED", HostReplyMessage.getResultString(HostReplyMessage.RESULT_SECRET_REQUIRED));
        assertEquals("KEY REQUIRED", HostReplyMessage.getResultString(HostReplyMessage.RESULT_KEY_REQUIRED));
        assertEquals("SECRET AND KEY REQUIRED",
                     HostReplyMessage.getResultString(HostReplyMessage.RESULT_SECRET_AND_KEY_REQUIRED));
        assertEquals("DECRYPTION FAILURE",
                     HostReplyMessage.getResultString(HostReplyMessage.RESULT_DECRYPTION_FAILURE));
        assertEquals("LEASESET LOOKUP FAILURE",
                     HostReplyMessage.getResultString(HostReplyMessage.RESULT_LEASESET_LOOKUP_FAILURE));
        assertEquals("LOOKUP TYPE UNSUPPORTED",
                     HostReplyMessage.getResultString(HostReplyMessage.RESULT_LOOKUP_TYPE_UNSUPPORTED));
    }

    /** An undefined code still says what it is, and keeps the number for grepping. */
    @Test
    public void undefinedResultKeepsTheNumber() {
        assertEquals("RESULT CODE: 42", HostReplyMessage.getResultString(42));
    }

    /**
     * The class leads, then one bullet carries the result words with the number
     * beside them and the two identifiers. No arrow: the message that interpolates
     * this owns the `Event -&gt; Result` spine.
     */
    @Test
    public void toStringLabelsEveryValue() {
        HostReplyMessage msg = new HostReplyMessage(new SessionId(7),
                                                    HostReplyMessage.RESULT_LEASESET_LOOKUP_FAILURE, 99);
        String s = msg.toString();
        assertTrue("class name leads: " + s, s.startsWith("HostReplyMessage"));
        assertTrue(s.contains("* Result: LEASESET LOOKUP FAILURE (6)"));
        assertTrue(s.contains("* SessionID: 7"));
        assertTrue(s.contains("* RequestID: 99"));
        assertFalse("no bracketed value squatting on the hash form: " + s, s.contains("[SessionID"));
        assertFalse("a toString() does not own the arrow: " + s, s.contains(" -> "));
    }

    /** A failed reply carries no destination bullet - there is nothing to carry. */
    @Test
    public void failedReplyCarriesNoDestination() {
        HostReplyMessage msg = new HostReplyMessage(new SessionId(7),
                                                    HostReplyMessage.RESULT_KEY_REQUIRED, 1);
        String s = msg.toString();
        assertTrue(s.contains("* Result: KEY REQUIRED (3)"));
        assertFalse("no destination on a failure: " + s, s.contains("Destination"));
    }
}
