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
 * <p>The gate that replaced it had two defects of its own, pinned below. It closed the
 * page as well as the save, so an operator whose mailbox password changed on the server
 * could not reach the settings they needed to change; and it let any session with no
 * mailbox password write the file with nothing to prove, which is what a logged-out
 * session has, since logging out drops the hashed password while the user name stays.
 * Reading the page is open now and the save carries the proof: a mailbox in hand, plus
 * that mailbox's password typed back again when it has one.
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
     * The defect: a session that cannot sign in - the mailbox password changed on the
     * server - was refused the settings page outright, and the only way to change
     * anything was to hand-edit susimail.config. The page is open to it now, and says
     * what the save will need instead of refusing to show anything.
     */
    @Test
    public void testASessionThatCannotSignInStillSeesTheSettings() {
        String why = WebMail.configSaveNotice(false, true);
        assertNotNull(why);
        assertTrue(why, why.toLowerCase().contains("sign in"));
        String html = WebMail.configSaveNoticeHTML(why);
        assertTrue(html, html.contains("Sign in to the mailbox"));
        assertTrue(html, html.contains("id=savenotice"));
    }

    /**
     * The notice is escaped like any other text on the page, and is absent when the save
     * is open to the session.
     */
    @Test
    public void testTheNoticeIsEscapedAndAbsentWhenTheSaveIsOpen() {
        assertEquals("", WebMail.configSaveNoticeHTML(null));
        String html = WebMail.configSaveNoticeHTML("A & B <c>");
        assertTrue(html, html.contains("&amp;"));
        assertTrue(html, html.contains("&lt;c&gt;"));
        assertNull(WebMail.configSaveNotice(true, true));
    }

    /**
     * A first run has no file on disk to protect and no mailbox to sign into, so the
     * save that creates the file is open.
     */
    @Test
    public void testAFirstRunMaySave() {
        assertNull(WebMail.configSaveNotice(false, false));
        assertNull(WebMail.configWriteRejection(false, false, null, false, 0, false));
    }

    /**
     * A save from a session with no mailbox in hand is refused even when it carries a
     * password, because there is no login to check it against.
     */
    @Test
    public void testASessionWithNoMailboxCannotSave() {
        String why = WebMail.configWriteRejection(false, true, "hunter2", true, 0, true);
        assertNotNull(why);
        assertTrue(why, why.toLowerCase().contains("sign in"));
    }

    /**
     * The defect: a session that signed in and then logged out kept its user name, lost
     * its hashed password, and could rewrite the global settings with nothing to prove.
     * The mailbox in hand is the proof now, and a logout takes it away.
     */
    @Test
    public void testALoggedOutSessionCannotSave() {
        String why = WebMail.configWriteRejection(false, false, null, false, 0, true);
        assertNotNull(why);
        assertTrue(why, why.toLowerCase().contains("sign in"));
    }

    /**
     * The defect: a save carrying only the form nonce, and no password, used to write
     * the file. It does not now.
     */
    @Test
    public void testASaveNeedsThePassword() {
        assertNotNull(WebMail.configWriteRejection(true, true, null, false, 0, true));
        assertNotNull(WebMail.configWriteRejection(true, true, "", false, 0, true));
    }

    /**
     * The right password saves, the wrong one does not, and the wrong one says so.
     */
    @Test
    public void testTheRightPasswordSavesAndTheWrongOneDoesNot() {
        assertNull(WebMail.configWriteRejection(true, true, "hunter2", true, 0, true));
        String why = WebMail.configWriteRejection(true, true, "wrong", false, 0, true);
        assertNotNull(why);
        assertTrue(why, why.toLowerCase().contains("password"));
    }

    /**
     * A mailbox signed into without a password has no secret to ask back, so the mailbox
     * in hand is the proof - which is only worth anything while the mailbox is there, as
     * the case above shows.
     */
    @Test
    public void testASessionWithNoLoginPasswordIsSignedInEnough() {
        assertNull(WebMail.configWriteRejection(true, false, null, false, 0, true));
    }

    /**
     * The check cannot be worked through: once the session has guessed often enough,
     * even the right password is refused until the account is signed into again.
     */
    @Test
    public void testGuessingLocksTheSave() {
        int max = WebMail.MAX_CONFIG_ATTEMPTS;
        assertNotNull(WebMail.configWriteRejection(true, true, "guess", false,
                                                   max - 1, true));
        String why = WebMail.configWriteRejection(true, true, "hunter2", true,
                                                  max, true);
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
