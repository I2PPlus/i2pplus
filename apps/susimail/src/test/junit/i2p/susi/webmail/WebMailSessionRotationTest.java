package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

/**
 * Tests which page transition re-keys the HTTP session identifier, and which one
 * discards the session.
 *
 * <p>The defect: nothing rotated the identifier when a user authenticated into a
 * mailbox. The cookie a browser holds before login is the cookie that identifies
 * the signed-in session afterwards, so an identifier fixed earlier - by an earlier
 * request, or handed to the user already set - becomes the authenticated session's
 * identifier, and everything the attacker could read or predict about that session
 * before the login carries over after it. The only transition that was handled was
 * the mirror-image one, leaving the mailbox, which invalidates the session.
 *
 * <p>No servlet container: the request is a mock, so the call to changeSessionId()
 * itself is what is observed.
 *
 * @since 0.9.72+
 */
public class WebMailSessionRotationTest {

    /**
     * A fixture group for the session transitions; it holds no state.
     *
     * @since 0.9.72+
     */
    public WebMailSessionRotationTest() {}

    /**
     * The transition that was missing: the login page before the buttons ran, a
     * mailbox after, so the session has just become an authenticated one.
     */
    @Test
    public void testOpeningAMailboxReKeysTheSession() {
        assertTrue(WebMail.rotatesSessionId(WebMail.State.AUTH, true));
    }

    /**
     * A request that was already inside a mailbox keeps its identifier - the
     * rotation belongs to the anonymous-to-authenticated step, not to every
     * request an authenticated user makes.
     */
    @Test
    public void testAnOrdinaryAuthenticatedRequestKeepsItsIdentifier() {
        assertFalse(WebMail.rotatesSessionId(WebMail.State.LOADING, true));
        assertFalse(WebMail.rotatesSessionId(WebMail.State.LIST, true));
        assertFalse(WebMail.rotatesSessionId(WebMail.State.SHOW, true));
        // an already authenticated request reaches the buttons with no state at all
        assertFalse(WebMail.rotatesSessionId(null, true));
    }

    /**
     * A login that produced no mailbox has nothing to protect: the session stays
     * the anonymous one it was, and the user sees the login form again.
     */
    @Test
    public void testAFailedLoginKeepsTheAnonymousSession() {
        assertFalse(WebMail.rotatesSessionId(WebMail.State.AUTH, false));
        assertFalse(WebMail.rotatesSessionId(null, false));
    }

    /**
     * Leaving for the login page is the mirror transition, and it discards the
     * session rather than re-keying it.
     */
    @Test
    public void testLeavingForTheLoginPageDiscardsTheSession() {
        assertTrue(WebMail.leavesMailbox(WebMail.State.LIST, WebMail.State.AUTH));
        assertTrue(WebMail.leavesMailbox(WebMail.State.CONFIG, WebMail.State.AUTH));
        assertFalse(WebMail.leavesMailbox(WebMail.State.AUTH, WebMail.State.AUTH));
        assertFalse(WebMail.leavesMailbox(WebMail.State.AUTH, WebMail.State.LOADING));
    }

    /**
     * The re-key really is applied to the request, and the new identifier is
     * handed back for the log line.
     */
    @Test
    public void testTheRequestIsReKeyedOnLogin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.changeSessionId()).thenReturn("0123456789abcdef");
        assertEquals("0123456789abcdef",
                     WebMail.rotateSessionIdIfNeeded(request, WebMail.State.AUTH, true));
        verify(request).changeSessionId();
    }

    /**
     * Every other transition leaves the identifier alone, and no byte of the
     * session is touched.
     */
    @Test
    public void testTheRequestIsLeftAloneOtherwise() {
        for (WebMail.State state : WebMail.State.values()) {
            HttpServletRequest request = mock(HttpServletRequest.class);
            assertNull(WebMail.rotateSessionIdIfNeeded(request, state, false));
            verify(request, never()).changeSessionId();
            if (state != WebMail.State.AUTH) {
                assertNull(WebMail.rotateSessionIdIfNeeded(request, state, true));
                verify(request, never()).changeSessionId();
            }
        }
        HttpServletRequest request = mock(HttpServletRequest.class);
        assertNull(WebMail.rotateSessionIdIfNeeded(request, null, true));
        verify(request, never()).changeSessionId();
    }

    /**
     * One request cannot both re-key the session and throw it away: the rotation
     * needs a mailbox at the end of it and the discard needs the login page at the
     * end, and no pair of states satisfies both.
     */
    @Test
    public void testOneRequestNeverBothReKeysAndDiscards() {
        for (WebMail.State oldstate : WebMail.State.values()) {
            for (WebMail.State state : WebMail.State.values()) {
                boolean rotates = WebMail.rotatesSessionId(oldstate, state != WebMail.State.AUTH);
                boolean leaves = WebMail.leavesMailbox(oldstate, state);
                assertFalse("the pair " + oldstate + " -> " + state + " did both",
                            rotates && leaves);
            }
        }
    }
}
