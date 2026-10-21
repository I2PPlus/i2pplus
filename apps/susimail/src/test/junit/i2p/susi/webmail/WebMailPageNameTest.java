package i2p.susi.webmail;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the wording susimail puts in its request debug log.
 *
 * <p>The regression this pins: the per-request debug lines printed the internal
 * state value twice per request ("Prelim. state is LIST", "Final state is LIST").
 * A reader trying to work out why the message list came up empty learned nothing
 * from the word LIST — the state is an internal branch selector, not a fact about
 * the mailbox. The lines now name the page the browser is about to show and the
 * mailbox condition that explains an empty list.
 *
 * @since 0.9.72+
 */
public class WebMailPageNameTest {

    /** Every name in the private {@code WebMail.State} enum. */
    private static final String[] STATES = {"AUTH", "LOADING", "LIST", "SHOW", "NEW", "CONFIG"};

    @Test
    public void testEveryStateIsDescribedInProse() {
        for (String state : STATES) {
            String page = WebMail.pageName(state);
            assertNotEquals("state " + state + " is printed raw", state, page);
            assertFalse("state " + state + " maps to empty text", page.isEmpty());
        }
    }

    @Test
    public void testKnownPages() {
        assertEquals("the login page", WebMail.pageName("AUTH"));
        assertEquals("the message list", WebMail.pageName("LIST"));
        assertEquals("one message", WebMail.pageName("SHOW"));
        assertEquals("the compose form", WebMail.pageName("NEW"));
        assertEquals("the settings page", WebMail.pageName("CONFIG"));
        assertTrue(WebMail.pageName("LOADING").startsWith("the loading page"));
    }

    @Test
    public void testUnknownStateStaysGreppable() {
        // An unmapped value must fall back to the name itself, so the log can
        // still be matched against the code that emitted it.
        assertEquals("SOMETHING_NEW", WebMail.pageName("SOMETHING_NEW"));
    }

    @Test
    public void testNoPageChosenYet() {
        // A GET for a mailbox that needs no login step reaches the log sites with
        // the state still null — calling state.name() there was an NPE and an HTTP
        // 500 on /susimail/. The wording must come from a null-tolerant path.
        assertEquals("no page chosen yet", WebMail.pageName((String) null));
    }

    @Test
    public void testCacheReadiness() {
        assertEquals("Ready", WebMail.cacheStateText(true, true));
        assertEquals("Loading", WebMail.cacheStateText(false, true));
        assertEquals("Not loaded", WebMail.cacheStateText(false, false));
        // No mailbox selected at all is a distinct condition from an unloaded one.
        assertEquals("None", WebMail.cacheState(null));
    }
}
