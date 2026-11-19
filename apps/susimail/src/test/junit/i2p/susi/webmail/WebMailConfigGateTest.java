package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Properties;
import org.junit.Test;

/**
 * Who may read and rewrite the global mail settings.
 *
 * <p>The defect: the settings page was reachable and saveable without ever signing into
 * a mailbox. The page printed the whole on-disk config, and one POST carrying a form
 * nonce replaced that file wholesale - the POP3 and SMTP ports mail goes to, the sender
 * domain, the policy that decides whether HTML messages are rendered - for every session
 * and across restarts. The nonce proves the click came from this webmail, not that
 * whoever clicked is entitled to move where the next cleartext login is sent.
 *
 * @since 0.9.72+
 */
public class WebMailConfigGateTest {

    /** The POP3 port in force, which a settings POST must not move. */
    private static final String POP3_IN_FORCE = "7660";
    /** The SMTP port in force. */
    private static final String SMTP_IN_FORCE = "7659";

    /**
     * A fixture group for the settings gate; it holds no state.
     *
     * @since 0.9.72+
     */
    public WebMailConfigGateTest() {}

    /**
     * @return the settings in force, as the config page would have shown them
     */
    private static Properties inForce() {
        Properties props = new Properties();
        props.setProperty("host", "mail.i2p");
        props.setProperty("ports.pop3", POP3_IN_FORCE);
        props.setProperty("ports.smtp", SMTP_IN_FORCE);
        props.setProperty("ports.fixed", "true");
        props.setProperty("view.html.allowed", "true");
        return props;
    }

    /**
     * The page is closed to a session that has not signed into a mailbox, and the reason
     * says what to do about it.
     */
    @Test
    public void testThePageNeedsASignedInMailbox() {
        String why = WebMail.configPageRejection(false, true);
        assertNotNull(why);
        assertTrue(why, why.toLowerCase().contains("sign in"));
        assertNull(WebMail.configPageRejection(true, true));
    }

    /**
     * A first run with no config file on disk can still open the page, or there would be
     * no way to set the host up at all.
     */
    @Test
    public void testAFirstRunMayOpenThePage() {
        assertNull(WebMail.configPageRejection(false, false));
    }

    /**
     * A save from a session that never signed in is refused even when it carries a
     * password, because there is no login to check it against.
     */
    @Test
    public void testAnUnsignedInSessionCannotSave() {
        String why = WebMail.configWriteRejection(false, true, "hunter2", true, 0);
        assertNotNull(why);
        assertTrue(why, why.toLowerCase().contains("sign in"));
    }

    /**
     * The defect: a save carrying only the form nonce, and no password, used to write
     * the file. It does not now.
     */
    @Test
    public void testASaveNeedsThePassword() {
        assertNotNull(WebMail.configWriteRejection(true, true, null, false, 0));
        assertNotNull(WebMail.configWriteRejection(true, true, "", false, 0));
    }

    /**
     * The right password saves, the wrong one does not, and the wrong one says so.
     */
    @Test
    public void testTheRightPasswordSavesAndTheWrongOneDoesNot() {
        assertNull(WebMail.configWriteRejection(true, true, "hunter2", true, 0));
        String why = WebMail.configWriteRejection(true, true, "wrong", false, 0);
        assertNotNull(why);
        assertTrue(why, why.toLowerCase().contains("password"));
    }

    /**
     * A mailbox signed into without a password has no secret to re-ask for, and the
     * sign-in it did present is enough.
     */
    @Test
    public void testASessionWithNoLoginPasswordIsSignedInEnough() {
        assertNull(WebMail.configWriteRejection(true, false, null, false, 0));
    }

    /**
     * The check cannot be worked through: once the session has guessed often enough,
     * even the right password is refused until the account is signed into again.
     */
    @Test
    public void testGuessingLocksTheSave() {
        assertNotNull(WebMail.configWriteRejection(true, true, "guess", false,
                                                   WebMail.MAX_CONFIG_ATTEMPTS - 1));
        String why = WebMail.configWriteRejection(true, true, "hunter2", true,
                                                  WebMail.MAX_CONFIG_ATTEMPTS);
        assertNotNull(why);
        assertTrue(why, why.toLowerCase().contains("sign in again"));
    }

    /**
     * The defect: one POST could set ports.pop3 and redirect where the next login's
     * cleartext USER and PASS go. The ports stay where they are.
     */
    @Test
    public void testThePortsStayWhereTheyAre() {
        Properties saved = inForce();
        saved.setProperty("ports.pop3", "110");
        saved.setProperty("ports.smtp", "25");
        saved.setProperty("ports.fixed", "false");
        assertTrue(WebMail.pinMailEndpoint(saved, inForce()));
        assertEquals(POP3_IN_FORCE, saved.getProperty("ports.pop3"));
        assertEquals(SMTP_IN_FORCE, saved.getProperty("ports.smtp"));
        assertEquals("true", saved.getProperty("ports.fixed"));
    }

    /**
     * A settings change that does not touch the ports is left alone, including the
     * policy that decides whether HTML messages are rendered.
     */
    @Test
    public void testOtherSettingsAreStillSaved() {
        Properties saved = inForce();
        saved.setProperty("view.html.allowed", "false");
        assertFalse(WebMail.pinMailEndpoint(saved, inForce()));
        assertEquals("false", saved.getProperty("view.html.allowed"));
        assertEquals(POP3_IN_FORCE, saved.getProperty("ports.pop3"));
    }

    /**
     * Dropping a pinned key from the text area does not drop it from the file either:
     * the value in force is put back, and a key that was never set stays unset.
     */
    @Test
    public void testDroppingAPinnedKeyDoesNotDropItFromTheFile() {
        Properties saved = inForce();
        saved.remove("ports.pop3");
        assertTrue(WebMail.pinMailEndpoint(saved, inForce()));
        assertEquals(POP3_IN_FORCE, saved.getProperty("ports.pop3"));

        Properties current = inForce();
        current.remove("ports.smtp");
        Properties posted = inForce();
        assertTrue(WebMail.pinMailEndpoint(posted, current));
        assertFalse(posted.containsKey("ports.smtp"));
    }

    /**
     * The host is kept by its own rule, which allows localhost as well as the value in
     * force; the port pinning does not interfere with it.
     */
    @Test
    public void testTheHostIsLeftToItsOwnRule() {
        Properties saved = inForce();
        saved.setProperty("host", "localhost");
        assertFalse(WebMail.pinMailEndpoint(saved, inForce()));
        assertEquals("localhost", saved.getProperty("host"));
    }
}
