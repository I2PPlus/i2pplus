package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the message body box of the compose form.
 *
 * <p>The defect: every other field of that form - To, Cc, Bcc, Subject - is
 * escaped on its way into the page, and the body was not. A reply quotes the
 * message it answers, so the text in the box is attacker-authored, and a body
 * containing &lt;/textarea&gt; closed the element early. Everything after it was
 * parsed as markup by the browser, inside susimail's own page and inside the
 * compose form itself, so a quoted reply could add form fields and controls to
 * the form that sends the mail.
 *
 * @since 0.9.72+
 */
public class WebMailComposeTextareaTest {

    /** The body that closes the box early if it is written raw. */
    private static final String CLOSING_BODY =
            "On 1 Jan, Susi wrote:\n> </textarea><img src=x onerror=alert(1)>\n> hi";

    /**
     * A fixture group for the compose body box; it holds no state.
     *
     * @since 0.9.72+
     */
    public WebMailComposeTextareaTest() {}

    /**
     * The regression: markup in a quoted reply is shown as text, and the box keeps
     * exactly one closing tag - the one we wrote.
     */
    @Test
    public void testABodyClosingTheBoxIsShownAsText() {
        String html = WebMail.composeTextarea("new_text", 80, 10, CLOSING_BODY);
        assertTrue("the body closed the box early", html.contains("&lt;/textarea&gt;"));
        assertFalse("a tag from the body reached the page", html.contains("<img"));
        assertEquals("more than one closing tag", 1, count(html, "</textarea>"));
        assertTrue("the box is not closed last", html.endsWith("</textarea>"));
    }

    /**
     * The box still names its field and carries the configured size, so escaping
     * the body did not break the form.
     */
    @Test
    public void testTheBoxNamesItsFieldAndItsSize() {
        String html = WebMail.composeTextarea("new_text", 72, 24, "text");
        assertTrue(html.startsWith("<textarea "));
        assertTrue(html.contains("name=\"new_text\""));
        assertTrue(html.contains("cols=\"72\""));
        assertTrue(html.contains("rows=\"24\""));
    }

    /**
     * The size comes from the configuration page, which takes any integer, and the
     * key can also be set to 0 or a negative number by hand. A browser draws no box
     * at all for those, so both dimensions are floored.
     */
    @Test
    public void testASizeTheConfigPageAllowsStillDrawsABox() {
        for (int size : new int[] {0, -1, -80}) {
            String html = WebMail.composeTextarea("new_text", size, size, "text");
            assertTrue("cols " + size + " drew nothing", html.contains("cols=\"1\""));
            assertTrue("rows " + size + " drew nothing", html.contains("rows=\"1\""));
        }
    }

    /**
     * A size above the floor is used as configured, however large, so the floor is
     * not a cap.
     */
    @Test
    public void testALargeConfiguredSizeIsUsedAsGiven() {
        String html = WebMail.composeTextarea("new_text", 200, 60, "text");
        assertTrue(html.contains("cols=\"200\""));
        assertTrue(html.contains("rows=\"60\""));
    }

    /**
     * A draft that has no body yet, or a session with nothing to show, gives an
     * empty box rather than the text "null".
     */
    @Test
    public void testNoBodyAtAllGivesAnEmptyBox() {
        String html = WebMail.composeTextarea("new_text", 80, 10, null);
        assertEquals("<textarea cols=\"80\" rows=\"10\" name=\"new_text\"></textarea>", html);
    }

    /**
     * Ordinary text - the case every user hits - is escaped only where a character
     * starts markup or an entity, and keeps its line breaks, since a textarea
     * shows them as typed.
     */
    @Test
    public void testAnOrdinaryBodyKeepsItsText() {
        String html = WebMail.composeTextarea("new_text", 80, 10,
                                             "Dear Susi,\n\na & b, 1 < 2, \"quoted\".\nBye.");
        assertEquals("Dear Susi,\n\na &amp; b, 1 &lt; 2, &quot;quoted&quot;.\nBye.",
                     inner(html));
    }

    /**
     * The text between the tags.
     *
     * @param html the element as written
     * @return what the browser shows in the box
     */
    private static String inner(String html) {
        int open = html.indexOf('>') + 1;
        int close = html.indexOf("</textarea>");
        assertTrue("the element is not closed", close >= open);
        return html.substring(open, close);
    }

    /**
     * @param haystack the text to search
     * @param needle what to count
     * @return how many times needle occurs, without overlapping
     */
    private static int count(String haystack, String needle) {
        int hits = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
             at = haystack.indexOf(needle, at + needle.length())) {
            hits++;
        }
        return hits;
    }
}
