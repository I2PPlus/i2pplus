package i2p.susi.webmail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import i2p.susi.util.ReadBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Tests what a message with an unreasonable MIME structure does, and what happens
 * to its bytes.
 *
 * <p>The defect: the parser builds one part per nesting level and one per boundary
 * line, with no limit on either, so a crafted message decides how deep the parser
 * recurses - a StackOverflowError, which is an Error, so the callers' catches do
 * not fire and the thread parsing dies partway through its queue. And the body was
 * recorded before the parse, so a message nobody could read still counted as
 * fetched: with the default of not leaving mail on the server, that message was
 * deleted from the server and survived only as bytes this tree cannot open.
 *
 * <p>The limits stop that without taking the message away: a structure past a limit
 * is cut where the limit falls, the parts that parsed are kept, the body is kept so
 * the message can be read and is not fetched again, and the server copy is kept for
 * the parts that were not parsed.
 *
 * @since 0.9.72+
 */
public class MailPartDepthTest {

    /**
     * How deeply a crafted message wraps itself: far past the limit, and past what
     * the parser's own stack survives without one.
     */
    private static final int TOO_DEEP = 5000;

    /** Part count a crafted message asks for, past the 1024 the parser allows. */
    private static final int TOO_MANY_PARTS = 1100;

    /**
     * A fixture group for the MIME structure limits; it holds no state.
     *
     * @since 0.9.72+
     */
    public MailPartDepthTest() {}

    /**
     * The regression: a message wrapped deeper than the limit is cut at the limit
     * rather than refused outright. Without a limit the parser recursed as deep as
     * the message asked and raised a StackOverflowError - an Error, so none of the
     * callers' catches fired and the thread parsing died partway through its queue.
     * Refusing the whole message was the first fix, and it cost the user the mail:
     * an unreadable body meant the message was never displayed, never dropped from
     * the server, and downloaded again on every refresh.
     */
    @Test
    public void testADeeperThanAllowedNestingIsCutAtTheLimit() {
        Mail mail = new Mail("deep-uidl");
        mail.setBody(buffer(wrapped(TOO_DEEP)));
        MailPart part = mail.getPart();
        assertNotNull("the parser threw the parts it had parsed away", part);
        assertTrue("the body of a truncated message was not kept", mail.hasBody());
        assertTrue("the tree was not marked incomplete", mail.structureTruncated());
        int depth = 0;
        while (!part.parts.isEmpty()) {
            part = part.parts.get(0);
            depth++;
        }
        assertEquals("the parser went deeper than its own limit",
                     MailPart.MAX_MIME_DEPTH, depth);
    }

    /**
     * The limits themselves, so the constants are what the parser enforces: the
     * deepest allowed level and the last part that still fits parse, and one past
     * either does not.
     */
    @Test
    public void testTheLimitsAreWhatTheParserEnforces() {
        assertTrue(MailPart.withinLimits(MailPart.MAX_MIME_DEPTH, 0));
        assertFalse(MailPart.withinLimits(MailPart.MAX_MIME_DEPTH + 1, 0));
        assertTrue(MailPart.withinLimits(0, MailPart.MAX_MIME_PARTS - 1));
        assertFalse(MailPart.withinLimits(0, MailPart.MAX_MIME_PARTS));
        // the message itself is always within reach of both
        assertTrue(MailPart.withinLimits(0, 0));
    }

    /**
     * A message nested the way real messages are - a signed or forwarded mail is a
     * few levels deep - still parses down to its text part.
     */
    @Test
    public void testAnOrdinaryNestedMessageStillParses() {
        Mail mail = new Mail("mixed-uidl");
        mail.setBody(buffer(nested(5)));
        assertTrue("a five-level message was refused", mail.hasBody());
        MailPart part = mail.getPart();
        assertNotNull(part);
        for (int i = 1; i < 5; i++) {
            assertEquals("level " + i + " has no child", 1, part.parts.size());
            part = part.parts.get(0);
        }
        assertEquals("multipart/mixed", part.type);
        assertEquals("the leaf is not the text part", 1, part.parts.size());
        assertEquals("text/plain", part.parts.get(0).type);
    }

    /**
     * A message that repeats its own boundary line, rather than nesting, is the
     * other way to make the parser build parts forever: it is cut by the part count,
     * and the parts that fit are kept.
     */
    @Test
    public void testTooManyPartsIsCutAtTheLimit() {
        Mail mail = new Mail("many-uidl");
        mail.setBody(buffer(flat(TOO_MANY_PARTS)));
        MailPart part = mail.getPart();
        assertNotNull("the parser built every part the message asked for", part);
        assertTrue("the body of a truncated message was not kept", mail.hasBody());
        assertTrue("the tree was not marked incomplete", mail.structureTruncated());
        assertEquals("the parser kept more parts than its own limit",
                     MailPart.MAX_MIME_PARTS - 1, part.parts.size());
    }

    /**
     * A message with many parts, but under the limit, keeps them all - the count is
     * a ceiling on a remote message, not a cap on what the user may see.
     */
    @Test
    public void testManyPartsUnderTheLimitAllParse() {
        Mail mail = new Mail("some-uidl");
        mail.setBody(buffer(flat(100)));
        MailPart part = mail.getPart();
        assertNotNull(part);
        assertEquals(100, part.parts.size());
    }

    /**
     * The mail-loss half: a message whose parts were cut short is kept on the
     * server, because the parts past the cut exist nowhere else, while one that
     * parsed in full is offered for deletion as usual.
     */
    @Test
    public void testATruncatedMessageIsLeftOnTheServer() {
        Mail cutShort = new Mail("lost-uidl");
        cutShort.setBody(buffer(wrapped(TOO_DEEP)));
        assertTrue("the truncated message was not parsed at all", cutShort.hasBody());
        assertFalse("the server copy of a message with parts still only on it would be deleted",
                    MailCache.canDropFromServer(cutShort));

        Mail many = new Mail("many-uidl");
        many.setBody(buffer(flat(TOO_MANY_PARTS)));
        assertFalse("the server copy of a message with parts still only on it would be deleted",
                    MailCache.canDropFromServer(many));

        Mail parsed = new Mail("kept-uidl");
        parsed.setBody(buffer(nested(3)));
        assertFalse("an ordinary message was marked incomplete", parsed.structureTruncated());
        assertTrue("an ordinary message stopped being deleted from the server",
                   MailCache.canDropFromServer(parsed));
    }

    /**
     * A message whose top-level part and each of whose children down to
     * {@code levels} are multipart/mixed, with a text/plain leaf at the bottom.
     *
     * @param levels how many multipart levels, counting the message itself, at
     *               least one
     * @return the message as its sender wrote it
     */
    private static String nested(int levels) {
        StringBuilder buf = new StringBuilder(64 * levels);
        buf.append("Subject: nested\r\n")
           .append("Content-Type: multipart/mixed; boundary=B0\r\n")
           .append("\r\n")
           .append("--B0\r\n");
        for (int i = 1; i < levels; i++) {
            buf.append("Content-Type: multipart/mixed; boundary=B").append(i).append("\r\n\r\n")
               .append("--B").append(i).append("\r\n");
        }
        buf.append("Content-Type: text/plain\r\n\r\nleaf\r\n");
        for (int i = levels - 1; i >= 0; i--) {
            buf.append("--B").append(i).append("--\r\n");
        }
        return buf.toString();
    }

    /**
     * A chain of {@code levels} message/rfc822 wrappers around a text part, the
     * shape that makes the parser recurse exactly {@code levels} deep: each wrapper
     * is one more part with no boundary lines to confuse the reader.
     *
     * @param levels how many nested parts, counting the message itself
     * @return the message as its sender wrote it
     */
    private static String wrapped(int levels) {
        StringBuilder buf = new StringBuilder(32 * levels);
        buf.append("Subject: wrapped\r\n")
           .append("Content-Type: message/rfc822\r\n")
           .append("\r\n");
        for (int i = 1; i < levels; i++) {
            buf.append("Content-Type: message/rfc822\r\n\r\n");
        }
        buf.append("Content-Type: text/plain\r\n\r\nleaf\r\n");
        return buf.toString();
    }

    /**
     * A single multipart/mixed message with {@code count} text parts, the shape a
     * message gets by repeating one boundary line.
     *
     * @param count how many parts, at least one
     * @return the message as its sender wrote it
     */
    private static String flat(int count) {
        StringBuilder buf = new StringBuilder(64 * count);
        buf.append("Subject: many parts\r\n")
           .append("Content-Type: multipart/mixed; boundary=B\r\n")
           .append("\r\n")
           .append("--B\r\n");
        for (int i = 0; i < count; i++) {
            buf.append("Content-Type: text/plain\r\n\r\npart ").append(i).append("\r\n");
            if (i + 1 < count) {buf.append("--B\r\n");}
        }
        buf.append("--B--\r\n");
        return buf.toString();
    }

    /**
     * @param message the bytes a POP3 server would have sent
     * @return a buffer the mail can be asked to read
     */
    private static ReadBuffer buffer(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return new ReadBuffer(bytes, 0, bytes.length);
    }
}
