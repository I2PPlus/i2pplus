package net.i2p.router.web;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;

import org.junit.Test;

public class CSSHelperTest {

    @Test
    public void testCapitalizeWord_NormalSentence() {
        assertEquals("Configure I2p", CSSHelper.StringFormatter.capitalizeWord("configure i2p"));
    }

    @Test
    public void testCapitalizeWord_SingleWord() {
        assertEquals("Router", CSSHelper.StringFormatter.capitalizeWord("router"));
    }

    @Test
    public void testCapitalizeWord_AlreadyCapitalized() {
        assertEquals("Router Console", CSSHelper.StringFormatter.capitalizeWord("Router Console"));
    }

    @Test
    public void testCapitalizeWord_MixedCase() {
        assertEquals("I2p Tunnel", CSSHelper.StringFormatter.capitalizeWord("i2p tunnel"));
    }

    @Test
    public void testCapitalizeWord_MultipleSpaces() {
        assertEquals("Config Advanced", CSSHelper.StringFormatter.capitalizeWord("config   advanced"));
    }

    @Test
    public void testCapitalizeWord_LeadingAndTrailingSpaces() {
        assertEquals("Home Page", CSSHelper.StringFormatter.capitalizeWord("  home page  "));
    }

    @Test
    public void testCapitalizeWord_EmptyString() {
        assertEquals("", CSSHelper.StringFormatter.capitalizeWord(""));
    }

    @Test
    public void testCapitalizeWord_NumbersAndSpecialChars() {
        assertEquals("I2cp Stats 2024", CSSHelper.StringFormatter.capitalizeWord("i2cp stats 2024"));
    }

    @Test
    public void testCapitalizeWord_WhitespaceOnly() {
        assertEquals("", CSSHelper.StringFormatter.capitalizeWord("   "));
    }

    @Test
    public void testCapitalizeWord_SingleCharacterWords() {
        assertEquals("A B C", CSSHelper.StringFormatter.capitalizeWord("a b c"));
    }

    @Test
    public void testCapitalizeWord_WithHyphens() {
        assertEquals("String-formatter Test", CSSHelper.StringFormatter.capitalizeWord("string-formatter test"));
    }

    /**
     *  Session whose attribute access throws IllegalStateException,
     *  mimicking a Jetty session invalidated before/while the JSP rendered.
     *  @since 0.9.71
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
     *  Minimal valid session backed by a HashMap for queue storage/retrieval.
     *  @since 0.9.71
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

    /**
     *  AN INVALID session is not null; Jetty hands it out and getAttribute
     *  throws ISE. getNonce must degrade to the static nonce, not throw.
     *  @since 0.9.71
     */
    @Test
    public void testGetNonce_invalidSessionFallsBackToStatic() {
        String rv = CSSHelper.getNonce(new InvalidSession());
        assertNotNull(rv);
        assertFalse("FAIL_SESSION_NOT_SET", "FAIL_SESSION_NOT_SET".equals(rv));
        assertEquals("Static nonce fallback", CSSHelper.getNonce(), rv);
    }

    /**
     *  Null session returns the FAIL marker (unchanged contract).
     *  @since 0.9.71
     */
    @Test
    public void testGetNonce_nullSession() {
        assertEquals("FAIL_SESSION_NOT_SET", CSSHelper.getNonce(null));
    }

    /**
     *  Valid session stores the nonce in the session-bound queue, not static.
     *  @since 0.9.71
     */
    @Test
    public void testGetNonce_validSessionUsesQueue() {
        MapSession session = new MapSession();
        String rv = CSSHelper.getNonce(session);
        assertNotNull(rv);
        assertTrue("CN prefix", rv.startsWith("CN"));
        // static nonce differs when the queue is used
        assertNotEquals("Static nonce must differ from session-bound", CSSHelper.getNonce(), rv);
        assertTrue("Queue holds the nonce", CSSHelper.validateNonce(session, rv, true));
    }

    /**
     *  Validation against an invalid session fails closed (false) so
     *  FormHandler falls back to the static nonce path.
     *  @since 0.9.71
     */
    @Test
    public void testValidateNonce_invalidSession() {
        assertFalse(CSSHelper.validateNonce(new InvalidSession(), "CN123"));
        assertFalse(CSSHelper.validateNonce(new InvalidSession(), "CN123", true));
    }
}
