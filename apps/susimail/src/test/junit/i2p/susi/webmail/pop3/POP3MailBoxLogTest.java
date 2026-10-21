package i2p.susi.webmail.pop3;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests that the POP3 command echo cannot carry the login credentials into the
 * log files. The account name and the password are both sent as plain command
 * arguments, and the log page is readable by anyone with console access.
 *
 * @since 0.9.72+
 */
public class POP3MailBoxLogTest {

    @Test
    public void testCredentialsAreHidden() {
        assertEquals("USER (hidden)", POP3MailBox.maskCommand("USER someone@example.com"));
        assertEquals("PASS (hidden)", POP3MailBox.maskCommand("PASS hunter2"));
    }

    /**
     * Everything else is logged verbatim, so the protocol exchange stays
     * greppable when diagnosing a server that will not answer.
     */
    @Test
    public void testOtherCommandsPassThrough() {
        assertEquals("STAT", POP3MailBox.maskCommand("STAT"));
        assertEquals("UIDL", POP3MailBox.maskCommand("UIDL"));
        assertEquals("TOP 3 0", POP3MailBox.maskCommand("TOP 3 0"));
        assertEquals("RETR 12", POP3MailBox.maskCommand("RETR 12"));
    }
}
