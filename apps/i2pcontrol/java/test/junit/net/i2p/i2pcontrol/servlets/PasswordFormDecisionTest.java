package net.i2p.i2pcontrol.servlets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Test;

/**
 * Decisions behind the I2PControl API password form.
 * <p>
 * The form had no token and, while the shipped default password was in place,
 * it hid the current-password field and the handler substituted the default for
 * a missing password. A cross-origin POST carrying only the two new-password
 * fields - a CORS simple request, so no preflight and no Same-Origin policy in
 * the way - therefore replaced the API password in about six seconds, from any
 * web page the operator's browser visited. These pin the two halves of the fix:
 * the current password is always required, and the submission must carry a
 * token the form handed out, used once.
 *
 * @since 0.9.72+
 */
public class PasswordFormDecisionTest {

    /** The time the nonce cases are judged at. */
    private static final long NOW = 1_700_000_000_000L;

    /** Runs the field checks; it holds no state. */
    public PasswordFormDecisionTest() {}

    /** A submission with no current password is refused, not defaulted. */
    @Test
    public void missingCurrentPasswordIsRefused() {
        assertNotNull(JSONRPC2Servlet.passwordFormError(null, "new", "new"));
        assertNotNull(JSONRPC2Servlet.passwordFormError("", "new", "new"));
        assertNotNull(JSONRPC2Servlet.passwordFormError("   ", "new", "new"));
    }

    /** A submission with the current password and two matching new ones passes. */
    @Test
    public void completeSubmissionIsAccepted() {
        assertNull(JSONRPC2Servlet.passwordFormError("itoopie", "new", "new"));
        assertNull(JSONRPC2Servlet.passwordFormError(" itoopie ", " new ", " new "));
    }

    /** The new-password checks keep the messages the page already showed. */
    @Test
    public void newPasswordFieldsStillChecked() {
        assertEquals("Please fill in both fields&hellip;",
                     JSONRPC2Servlet.passwordFormError("itoopie", null, "new"));
        assertEquals("Please fill in both fields&hellip;",
                     JSONRPC2Servlet.passwordFormError("itoopie", "new", null));
        assertEquals("Enter new password twice!",
                     JSONRPC2Servlet.passwordFormError("itoopie", "", "  "));
        assertEquals("New passwords don't match!",
                     JSONRPC2Servlet.passwordFormError("itoopie", "one", "two"));
    }

    /** A submission with no token at all - what a foreign form sends - is refused. */
    @Test
    public void absentTokenIsRefused() {
        Map<String, Long> nonces = new ConcurrentHashMap<String, Long>();
        nonces.put("token", NOW + 60_000L);
        assertFalse(JSONRPC2Servlet.nonceMatches(nonces, null, NOW));
        assertFalse(JSONRPC2Servlet.nonceMatches(nonces, "", NOW));
    }

    /** A token the store handed out is accepted once and consumed. */
    @Test
    public void tokenIsSingleUse() {
        Map<String, Long> nonces = new ConcurrentHashMap<String, Long>();
        nonces.put("token", NOW + 60_000L);
        assertTrue(JSONRPC2Servlet.nonceMatches(nonces, "token", NOW));
        assertFalse("the same token was accepted twice",
                    JSONRPC2Servlet.nonceMatches(nonces, "token", NOW));
    }

    /** A token from a page the browser left open past its lifetime is refused. */
    @Test
    public void expiredTokenIsRefused() {
        Map<String, Long> nonces = new ConcurrentHashMap<String, Long>();
        nonces.put("token", NOW - 1L);
        assertFalse(JSONRPC2Servlet.nonceMatches(nonces, "token", NOW));
    }

    /** A token the store never handed out - a guessed one - is refused. */
    @Test
    public void unknownTokenIsRefused() {
        Map<String, Long> nonces = new ConcurrentHashMap<String, Long>();
        nonces.put("token", NOW + 60_000L);
        assertFalse(JSONRPC2Servlet.nonceMatches(nonces, "guessed", NOW));
    }
}
