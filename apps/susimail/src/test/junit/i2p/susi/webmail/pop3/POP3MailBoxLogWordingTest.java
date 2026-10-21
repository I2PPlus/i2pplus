package i2p.susi.webmail.pop3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the wording of the POP3 mailbox log. The earlier lines named the server
 * inside a sentence and answered the capability questions with a bare true or
 * false, so a reader could not tell which mailbox was meant or whether the
 * server actually supported the command being complained about.
 *
 * @since 0.9.72+
 */
public class POP3MailBoxLogWordingTest {

    @Test
    public void testMailCountNamesTheServer() {
        assertEquals("Server reports 42 messages in the mailbox\n* Server: pop.postman.i2p:110",
                     POP3MailBox.mailCountText(42, "pop.postman.i2p", 110));
    }

    @Test
    public void testLoggedInNamesTheServer() {
        assertEquals("Logged in to the POP3 server pop.postman.i2p:110",
                     POP3MailBox.loggedInText("pop.postman.i2p", 110));
    }

    /**
     * A capability line written as "UIDL? false" reads like a question the log
     * never answers, and a reader who does not know POP3 cannot tell what TOP
     * is for. Each capability is answered yes or no with its wire name beside
     * the prose.
     */
    @Test
    public void testCapabilitiesAreAnswered() {
        String caps = POP3MailBox.capsText(true, false, true);
        assertTrue(caps.contains("Pipelining (PIPELINING): Yes"));
        assertTrue(caps.contains("Stable message ids (UIDL): No"));
        assertTrue(caps.contains("Headers without the body (TOP): Yes"));
        assertFalse("a question-mark key says nothing", caps.contains("?"));
    }
}
