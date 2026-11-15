package net.i2p.router.web;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;

import net.i2p.router.RouterContext;
import org.junit.Test;

/**
 * Hide news and Show news on the home page are GET links carrying a
 * {@code consoleNonce}, and headers.jsi applies the {@code news} parameter only if that
 * nonce validates against the session's nonce queue. Both emitters minted the nonce with
 * the static rotating {@link CSSHelper#getNonce()}, which is never in that queue, so every
 * click was refused at the check and the news never hid or showed. These pin that the link
 * the console renders carries a nonce the same session accepts.
 *
 * @since 0.9.72+
 */
public class NewsStatusNonceTest {

    /**
     * Fixture group for the news toggle nonce; it holds no state.
     */
    public NewsStatusNonceTest() {}

    /** The nonce as it is written into the toggle link. */
    private static final Pattern NONCE = Pattern.compile("consoleNonce=([^&\"]+)");

    /**
     * A rendered status block's toggle link has to validate against the session it was
     * rendered for - that is the only check headers.jsi makes before it calls
     * {@link CSSHelper#setNews(String)}.
     */
    @Test
    public void theNewsToggleLinkCarriesASessionNonce() {
        HttpSession session = new MapSession();
        String nonce = nonceOf(NewsHelper.status(newsContext(), session));
        assertNotNull("the status block renders a news toggle link", nonce);
        assertTrue("the session must recognise the nonce it was handed",
                   CSSHelper.validateNonce(session, nonce, true));
    }

    /**
     * What the link used to carry, and why the click did nothing: the static nonce is a
     * different value space from the session queue, so it can never validate.
     */
    @Test
    public void theStaticNonceNeverValidatesAgainstASession() {
        HttpSession session = new MapSession();
        CSSHelper.getNonce(session);
        assertFalse(CSSHelper.validateNonce(session, CSSHelper.getNonce(), true));
    }

    /**
     * A sessionless caller - the static {@link NewsHelper#status(RouterContext)} form -
     * still gets a nonce, the static one, so the link renders rather than disappearing.
     */
    @Test
    public void withoutASessionTheLinkCarriesTheStaticNonce() {
        assertEquals(CSSHelper.getNonce(), nonceOf(NewsHelper.status(newsContext(), null)));
    }

    /**
     * A context with news already downloaded, so the status block renders the toggle link
     * at all, and with both stamps stored, so nothing reaches for the router to re-derive
     * and save them.
     *
     * @return a context the status block will render a link for
     */
    private static RouterContext newsContext() {
        Properties env = new Properties();
        long now = System.currentTimeMillis();
        env.setProperty(NewsHelper.PROP_LAST_UPDATED, Long.toString(now));
        env.setProperty(NewsHelper.PROP_LAST_CHECKED, Long.toString(now));
        env.setProperty(NewsHelper.PROP_LAST_NEW_ENTRY, Long.toString(now));
        return new RouterContext(null, env);
    }

    /**
     * The nonce out of the first toggle link in the rendered block.
     *
     * @param html the rendered status block
     * @return the nonce the link carries, or null if it rendered no link
     */
    private static String nonceOf(String html) {
        Matcher m = NONCE.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Minimal session backed by a map, the way a live one holds the nonce queue.
     */
    private static class MapSession implements HttpSession {
        private final Map<String, Object> _attrs = new ConcurrentHashMap<>();
        public long getCreationTime() {return 0L;}
        public String getId() {return "news-test-session";}
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
}
