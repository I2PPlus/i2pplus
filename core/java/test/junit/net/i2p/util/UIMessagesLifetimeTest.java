package net.i2p.util;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

/**
 * Pins the lifetime a message can be queued with: a brief event is shown and then leaves
 * the queue on its own, while the messages queued without a lifetime stay exactly as they
 * did - until the buffer wraps or the user clears them. The pruning runs on read, so no
 * timer of its own is needed and a consumer that polls the queue sees the entry gone on
 * the next render.
 *
 * @since 0.9.72+
 */
public class UIMessagesLifetimeTest {

    /** How long the short-lived messages in these tests stay. */
    private static final long SHORT = 40;

    /** How long to wait for one to run out. */
    private static final long PAST = 120;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each one.
     */
    public UIMessagesLifetimeTest() {}

    /**
     * A message queued with a lifetime is gone once that lifetime has run out.
     *
     * @throws InterruptedException if the wait for the expiry is interrupted
     */
    @Test
    public void testALifetimeMessageExpires() throws InterruptedException {
        UIMessages msgs = new UIMessages(10);
        msgs.addMessageNoEscape("a lookup came and went", SHORT);
        assertEquals(1, msgs.getMessages().size());
        Thread.sleep(PAST);
        assertTrue(msgs.getMessages().isEmpty());
        assertTrue(msgs.isEmpty());
    }

    /**
     * A message queued without a lifetime is untouched by the pruning - the expiry is
     * per message, not a global filter that would clear the log.
     *
     * @throws InterruptedException if the wait for the other expiry is interrupted
     */
    @Test
    public void testALifetimelessMessageIsRetained() throws InterruptedException {
        UIMessages msgs = new UIMessages(10);
        msgs.addMessageNoEscape("a torrent finished");
        msgs.addMessageNoEscape("a lookup came and went", SHORT);
        Thread.sleep(PAST);
        List<UIMessages.Message> kept = msgs.getMessages();
        assertEquals(1, kept.size());
        assertEquals("a torrent finished", kept.get(0).message);
        assertFalse(msgs.isEmpty());
    }

    /**
     * The rendered-string cache does not keep an expired message alive: it is rebuilt
     * when the pruning removes something.
     *
     * @throws InterruptedException if the wait for the expiry is interrupted
     */
    @Test
    public void testTheStringCacheFollowsThePruning() throws InterruptedException {
        UIMessages msgs = new UIMessages(10);
        msgs.addMessageNoEscape("a torrent finished");
        msgs.addMessageNoEscape("a lookup came and went", SHORT);
        assertEquals(2, msgs.getMessageStrings().size());
        Thread.sleep(PAST);
        List<String> kept = msgs.getMessageStrings();
        assertEquals(1, kept.size());
        assertEquals("a torrent finished", kept.get(0));
    }

    /**
     * An expired message stops being queued rather than being counted against the buffer:
     * the pruning runs on the way in as well, so a queue of short-lived entries never
     * displaces the ordinary messages behind it.
     *
     * @throws InterruptedException if the wait for the expiry is interrupted
     */
    @Test
    public void testAnExpiredMessageDoesNotDisplaceOthers() throws InterruptedException {
        UIMessages msgs = new UIMessages(3);
        msgs.addMessageNoEscape("one");
        msgs.addMessageNoEscape("two");
        for (int i = 0; i < 4; i++) {
            msgs.addMessageNoEscape("lookup " + i, SHORT);
            // each one runs out before the next is queued, as a lookup does
            Thread.sleep(PAST);
        }
        msgs.addMessageNoEscape("three");
        List<UIMessages.Message> kept = msgs.getMessages();
        assertEquals(3, kept.size());
        assertEquals("one", kept.get(0).message);
        assertEquals("two", kept.get(1).message);
        assertEquals("three", kept.get(2).message);
    }
}
