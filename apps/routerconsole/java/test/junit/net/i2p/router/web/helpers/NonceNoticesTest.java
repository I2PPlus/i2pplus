package net.i2p.router.web.helpers;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;

import net.i2p.I2PAppContext;

import org.junit.Test;

/**
 * Pins the one-shot notice that explains a console control which refused to
 * run.
 *
 * <p>The log pages' Clear logs link and the sidebar's restart/shutdown form are
 * not form handlers, so they do not get the visible form error that
 * {@code FormHandler} adds when a nonce is refused. Both returned silently, and
 * a control that returns silently on a spent nonce is indistinguishable from a
 * broken console — which is exactly how the spent-nonce bug in the nonce queue
 * was reported: "the button does nothing".
 *
 * <p>So a refusal has to be recorded, shown once, and never haunt a later page.
 * All three are pinned here, along with the sentence's freedom from the
 * MessageFormat quoting trap that a placeholder in a translated string invites.
 *
 * @since 0.9.72+
 */
public class NonceNoticesTest {

    /**
     * One instance per test method, as JUnit builds a fresh test object for
     * each one; the class carries no per-test state.
     *
     * @since 0.9.72+
     */
    public NonceNoticesTest() {}

    /**
     * Session whose attribute access throws IllegalStateException, mimicking a
     * Jetty session invalidated mid-request.
     *
     * @since 0.9.72+
     */
    private static class InvalidSession implements HttpSession {
        public long getCreationTime() {return 0L;}
        public String getId() {return "dead-session";}
        public long getLastAccessedTime() {return 0L;}
        public ServletContext getServletContext() {return null;}
        public void setMaxInactiveInterval(int interval) {}
        public int getMaxInactiveInterval() {return 1800000;}
        @SuppressWarnings("deprecation")
        public HttpSessionContext getSessionContext() {return null;}
        public Object getAttribute(String name) {throw new IllegalStateException();}
        public Enumeration<String> getAttributeNames() {return Collections.emptyEnumeration();}
        public void setAttribute(String name, Object value) {throw new IllegalStateException();}
        public void removeAttribute(String name) {}
        public Object getValue(String name) {return null;}
        public String[] getValueNames() {return new String[0];}
        public void putValue(String name, Object value) {}
        public void removeValue(String name) {}
        public void invalidate() {}
        public boolean isNew() {return false;}
    }

    /**
     * Minimal valid session backed by a map, as the console's own session
     * attribute use requires.
     *
     * @since 0.9.72+
     */
    private static class MapSession implements HttpSession {
        private final Map<String, Object> _attrs = new java.util.concurrent.ConcurrentHashMap<>();
        public long getCreationTime() {return 0L;}
        public String getId() {return "valid-session";}
        public long getLastAccessedTime() {return 0L;}
        public ServletContext getServletContext() {return null;}
        public void setMaxInactiveInterval(int interval) {}
        public int getMaxInactiveInterval() {return 1800000;}
        @SuppressWarnings("deprecation")
        public HttpSessionContext getSessionContext() {return null;}
        public Object getAttribute(String name) {return _attrs.get(name);}
        public Enumeration<String> getAttributeNames() {return Collections.enumeration(_attrs.keySet());}
        public void setAttribute(String name, Object value) {_attrs.put(name, value);}
        public void removeAttribute(String name) {_attrs.remove(name);}
        public Object getValue(String name) {return _attrs.get(name);}
        public String[] getValueNames() {return _attrs.keySet().toArray(new String[0]);}
        public void putValue(String name, Object value) {_attrs.put(name, value);}
        public void removeValue(String name) {_attrs.remove(name);}
        public void invalidate() {_attrs.clear();}
        public boolean isNew() {return false;}
    }

    // ---------- a refusal is shown once ----------

    /** A recorded refusal is rendered by the next read. */
    @Test
    public void testRefusalIsShown() {
        MapSession session = new MapSession();
        NonceNotices.refuse(session, "clear logs");
        assertTrue("a refusal must produce a sentence",
                   NonceNotices.take(session).length() > 0);
    }

    /**
     * Reading clears it: a notice that stayed would keep accusing pages whose
     * tokens are fine.
     */
    @Test
    public void testRefusalIsShownOnce() {
        MapSession session = new MapSession();
        NonceNotices.refuse(session, "router control");
        NonceNotices.take(session);
        assertEquals("", NonceNotices.take(session));
    }

    /** A page with no refusal shows nothing. */
    @Test
    public void testNoRefusalNoNotice() {
        assertEquals("", NonceNotices.take(new MapSession()));
    }

    /**
     * The notice is keyed by the session attribute the class documents, so a
     * stale entry of the wrong shape cannot be rendered as a notice.
     */
    @Test
    public void testForeignAttributeIgnored() {
        MapSession session = new MapSession();
        session.setAttribute(NonceNotices.SESSION_ATTR, "not a notice");
        assertEquals("", NonceNotices.take(session));
    }

    /** A notice with an unparseable timestamp is dropped, not shown. */
    @Test
    public void testUnparseableTimestampIgnored() {
        MapSession session = new MapSession();
        session.setAttribute(NonceNotices.SESSION_ATTR, new String[] {"clear logs", "yesterday"});
        assertEquals("", NonceNotices.take(session));
    }

    /** A truncated notice record is dropped. */
    @Test
    public void testShortRecordIgnored() {
        MapSession session = new MapSession();
        session.setAttribute(NonceNotices.SESSION_ATTR, new String[] {"clear logs"});
        assertEquals("", NonceNotices.take(session));
    }

    // ---------- staleness ----------

    /** The window is a minute: long enough for the submit's own render. */
    @Test
    public void testNoticeWindowIsAMinute() {
        assertEquals(60 * 1000L, NonceNotices.NOTICE_TTL_MS);
    }

    /** The boundary is inclusive: at exactly the window the notice is good. */
    @Test
    public void testStaleBoundary() {
        long issued = 1_000_000L;
        assertFalse(NonceNotices.isStale(issued, issued, NonceNotices.NOTICE_TTL_MS));
        assertFalse(NonceNotices.isStale(issued + NonceNotices.NOTICE_TTL_MS, issued,
                                         NonceNotices.NOTICE_TTL_MS));
        assertTrue(NonceNotices.isStale(issued + NonceNotices.NOTICE_TTL_MS + 1, issued,
                                        NonceNotices.NOTICE_TTL_MS));
    }

    /** A clock that moved backwards does not make a notice stale. */
    @Test
    public void testClockSkewIsNotStale() {
        assertFalse(NonceNotices.isStale(1_000L, 5_000L, NonceNotices.NOTICE_TTL_MS));
    }

    /**
     * A refusal recorded before the user reached this page is not this page's
     * refusal, so it is dropped rather than shown.
     */
    @Test
    public void testOldRefusalNotShown() {
        MapSession session = new MapSession();
        long ancient = System.currentTimeMillis() - NonceNotices.NOTICE_TTL_MS - 5_000L;
        session.setAttribute(NonceNotices.SESSION_ATTR,
                             new String[] {"clear logs", Long.toString(ancient)});
        assertEquals("", NonceNotices.take(session));
    }

    // ---------- sessions that are not there ----------

    /** A null session is a no-op in both directions. */
    @Test
    public void testNullSession() {
        NonceNotices.refuse(null, "clear logs");
        assertEquals("", NonceNotices.take(null));
    }

    /**
     * Jetty hands out an invalidated session whose attribute access throws; the
     * notice is dropped, and nothing escapes to the page rendering it.
     */
    @Test
    public void testInvalidSessionDropsNotice() {
        NonceNotices.refuse(new InvalidSession(), "clear logs");
        assertEquals("", NonceNotices.take(new InvalidSession()));
    }

    // ---------- the sentence ----------

    /**
     * The sentence names the situation and the remedy, and carries no
     * placeholder: a translated string with braces is a MessageFormat pattern,
     * where an apostrophe in the translation quotes the braces and swallows the
     * value. The control name stays in the debug log for that reason.
     */
    @Test
    public void testSentenceIsPlainProse() {
        String message = NonceNotices.messageFor(I2PAppContext.getGlobalContext());
        assertTrue(message.length() > 0);
        assertFalse("no MessageFormat placeholder", message.contains("{"));
        assertFalse("no MessageFormat quote character", message.contains("'"));
        assertTrue("names the remedy", message.toLowerCase().contains("reload"));
    }
}
