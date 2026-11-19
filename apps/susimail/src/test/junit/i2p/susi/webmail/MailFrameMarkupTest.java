package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import i2p.susi.util.ReadBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * The markup and the response header that decide what an HTML message may do to the
 * mail session.
 *
 * <p>The defect: the message body is served from the webmail's own origin and shown
 * in a frame with no sandbox, and the frame's Content-Security-Policy named three of
 * our own scripts and a nonce as allowed script - so the message document was a
 * normal document of the webmail, able to read the form nonce off the parent page and
 * rewrite it. The nonce was not even a per-response secret. The fix takes script away
 * from the frame and moves the work that needed it - blocked-image placeholders, the
 * blocked and tracking tallies, the sizing - into the page, which reaches the frame
 * through contentDocument.
 *
 * @since 0.9.72+
 */
public class MailFrameMarkupTest {

    /** The servlet URL the frame is asked to load. */
    private static final String MYSELF = "http://127.0.0.1:7657/susimail/";
    /** The host and port as a request named them. */
    private static final String ME = "127.0.0.1:7657";

    /**
     * A fixture group for the message frame's markup; it holds no state.
     *
     * @since 0.9.72+
     */
    public MailFrameMarkupTest() {}

    /**
     * The frame is sandboxed, and only to the one permission the page needs in order
     * to read the frame's document.
     */
    @Test
    public void testTheFrameIsSandboxed() {
        String frame = WebMail.mailFrame(MYSELF, htmlPart(), "default", false, false);
        assertTrue("the frame carries no sandbox: " + frame,
                   frame.contains("sandbox=\"allow-same-origin\""));
        assertFalse("the sandbox grants script: " + frame, frame.contains("allow-scripts"));
    }

    /**
     * Nothing in the frame markup loads a script: the helpers the frame used to
     * inject are gone, and the only src is the message part itself.
     */
    @Test
    public void testTheFrameLoadsNoScript() {
        String frame = WebMail.mailFrame(MYSELF, htmlPart(), "default", false, false);
        assertFalse("a script tag in the frame markup: " + frame, frame.contains("<script"));
        assertFalse("a .js src in the frame markup: " + frame, frame.contains(".js"));
    }

    /**
     * The frame still addresses the message's own part, by the raw-attachment
     * parameter and the id, with the uidl alongside it.
     */
    @Test
    public void testTheFrameStillPointsAtTheMessagePart() {
        MailPart part = htmlPart();
        String frame = WebMail.mailFrame(MYSELF, part, "default", false, false);
        assertTrue(frame, frame.contains("src=\"" + MYSELF + "?att=" + part.getID()));
        assertTrue(frame, frame.contains("&amp;msg="));
    }

    /**
     * The two display options are still carried on the frame's class, so the page's
     * CSS and the parent-side helper can both see them.
     */
    @Test
    public void testTheDisplayOptionsAreStillOnTheFrame() {
        String both = WebMail.mailFrame(MYSELF, htmlPart(), "darkmidnight", true, true);
        assertTrue(both, both.contains("darkModeActive"));
        assertTrue(both, both.contains("showBlockedImages"));
        assertTrue(both, both.contains("data-theme=\"darkmidnight\""));
        String neither = WebMail.mailFrame(MYSELF, htmlPart(), "default", false, false);
        assertFalse(neither, neither.contains("darkModeActive"));
        assertFalse(neither, neither.contains("showBlockedImages"));
    }

    /**
     * The defect: the response that carries the message's HTML allowed three named
     * scripts and a nonce. It now allows no script at all, and says the document is
     * sandboxed.
     */
    @Test
    public void testTheMessageResponseAllowsNoScript() {
        String csp = WebMail.attachmentCsp(ME, MYSELF);
        assertFalse("the header still names a script-src: " + csp, csp.contains("script-src"));
        assertFalse("the header still carries a nonce: " + csp, csp.contains("nonce-"));
        assertFalse("the header still names a .js file: " + csp, csp.contains(".js"));
        assertTrue("default-src does not cover script: " + csp, csp.contains("default-src 'none'"));
    }

    /**
     * The header says what the frame is allowed to be, and closes the one hole the
     * old header left open: a mail-authored &lt;base&gt; could otherwise redirect
     * relative URLs anywhere.
     */
    @Test
    public void testTheMessageResponseSaysWhatTheFrameMayBe() {
        String csp = WebMail.attachmentCsp(ME, MYSELF);
        assertTrue(csp, csp.contains("sandbox allow-same-origin"));
        assertTrue(csp, csp.contains("base-uri 'none'"));
    }

    /**
     * The restrictions that were already doing real work are not lost while the
     * script allowlist is removed.
     */
    @Test
    public void testTheOtherMessageRestrictionsSurvive() {
        String csp = WebMail.attachmentCsp(ME, MYSELF);
        assertTrue(csp, csp.contains("form-action 'none'"));
        assertTrue(csp, csp.contains("object-src 'none'"));
        assertTrue(csp, csp.contains("frame-src 'none'"));
        assertTrue(csp, csp.contains("worker-src 'none'"));
        assertTrue(csp, csp.contains("media-src 'none'"));
        assertTrue(csp, csp.contains("img-src 'self' " + ME + MYSELF + " data:"));
        assertTrue(csp, csp.contains("frame-ancestors " + ME + MYSELF));
    }

    /**
     * A message part is addressed by a number the parser assigned, and the frame has
     * to carry that number, not a name the message could have chosen.
     */
    @Test
    public void testTheFrameUsesTheParsersOwnPartNumber() {
        MailPart part = htmlPart();
        String frame = WebMail.mailFrame(MYSELF, part, "default", false, false);
        assertTrue("the part number is not in the frame: " + frame,
                   frame.contains("name=\"mailhtmlframe" + part.getID() + "\""));
        assertEquals("more than one src in the frame: " + frame, 1, frame.split("src=", -1).length - 1);
    }

    /**
     * @return a one-part text/html message's part, as the parser builds it
     */
    private static MailPart htmlPart() {
        Mail mail = new Mail("uidl-1");
        mail.setBody(buffer("Subject: html\r\nContent-Type: text/html\r\n\r\n<p>hi</p>\r\n"));
        MailPart part = mail.getPart();
        if (part == null) {throw new AssertionError("the fixture message did not parse");}
        return part;
    }

    /**
     * @param s the message as its sender wrote it
     * @return a buffer over those bytes
     */
    private static ReadBuffer buffer(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return new ReadBuffer(b, 0, b.length);
    }
}
