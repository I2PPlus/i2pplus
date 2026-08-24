package org.klomp.snark.web;

import static org.junit.Assert.*;

import java.util.*;

import org.klomp.snark.Tracker;
import org.klomp.snark.TrackerClient;
import org.junit.Test;

import javax.servlet.http.Cookie;

/**
 * Tests for I2PSnarkServlet processTrackerForm pure helpers:
 * {@link I2PSnarkServlet#parseTrackerFormDeleteSave},
 * {@link I2PSnarkServlet#processTrackerFormDeleteSave},
 * {@link I2PSnarkServlet#parseAddTrackerParams},
 * {@link I2PSnarkServlet#validateAddTrackerParams},
 * {@link I2PSnarkServlet#processAddTracker}.
 *
 * @since 0.9.71+
 */
public class ProcessTrackerFormValidatorTest {

    // ---- parseTrackerFormDeleteSave ----------------------------------------

    @Test
    public void testParseTrackerFormDeleteSaveEmpty() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        I2PSnarkServlet.TrackerFormParams params = I2PSnarkServlet.parseTrackerFormDeleteSave(req);
        assertTrue(params.deletedKeys.isEmpty());
        assertTrue(params.typeChanges.isEmpty());
    }

    @Test
    public void testParseTrackerFormDeleteSaveWithDeletes() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("delete_key1", "");
        req.addParameter("delete_key2", "");
        I2PSnarkServlet.TrackerFormParams params = I2PSnarkServlet.parseTrackerFormDeleteSave(req);
        assertEquals(Arrays.asList("key1", "key2"), params.deletedKeys);
        assertTrue(params.typeChanges.isEmpty());
    }

    @Test
    public void testParseTrackerFormDeleteSaveWithTypeChanges() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("ttype_key1", "1");
        req.addParameter("ttype_key2", "2");
        I2PSnarkServlet.TrackerFormParams params = I2PSnarkServlet.parseTrackerFormDeleteSave(req);
        assertTrue(params.deletedKeys.isEmpty());
        assertEquals("1", params.typeChanges.get("key1"));
        assertEquals("2", params.typeChanges.get("key2"));
    }

    @Test
    public void testParseTrackerFormDeleteSaveMixed() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("delete_key1", "");
        req.addParameter("ttype_key2", "1");
        I2PSnarkServlet.TrackerFormParams params = I2PSnarkServlet.parseTrackerFormDeleteSave(req);
        assertEquals(Arrays.asList("key1"), params.deletedKeys);
        assertEquals("1", params.typeChanges.get("key2"));
    }

    // ---- processTrackerFormDeleteSave --------------------------------------

    @Test
    public void testProcessTrackerFormDeleteSaveNoChanges() {
        Map<String, Tracker> trackers = new HashMap<>();
        trackers.put("key1", new Tracker("t1", "http://t1.i2p/announce", "http://t1.i2p"));
        List<String> openTrackers = Arrays.asList("http://t1.i2p/announce");
        List<String> privateTrackers = Collections.emptyList();

        I2PSnarkServlet.TrackerFormParams params = new I2PSnarkServlet.TrackerFormParams(
            Collections.emptyList(), Collections.emptyMap());

        I2PSnarkServlet.TrackerFormResult result = I2PSnarkServlet.processTrackerFormDeleteSave(
            params, trackers, openTrackers, privateTrackers);

        assertFalse(result.changed);
        assertTrue(result.removedUrls.isEmpty());
        assertEquals(trackers, result.updatedTrackers);
        assertEquals(openTrackers, result.newOpen);
        assertEquals(privateTrackers, result.newPrivate);
    }

    @Test
    public void testProcessTrackerFormDeleteSaveWithDeletion() {
        Tracker tracker = new Tracker("t1", "http://t1.i2p/announce", "http://t1.i2p");
        Map<String, Tracker> trackers = new HashMap<>();
        trackers.put("key1", tracker);
        List<String> openTrackers = Arrays.asList("http://t1.i2p/announce");
        List<String> privateTrackers = Collections.emptyList();

        I2PSnarkServlet.TrackerFormParams params = new I2PSnarkServlet.TrackerFormParams(
            Arrays.asList("key1"), Collections.emptyMap());

        I2PSnarkServlet.TrackerFormResult result = I2PSnarkServlet.processTrackerFormDeleteSave(
            params, trackers, openTrackers, privateTrackers);

        assertTrue(result.changed);
        assertEquals(Arrays.asList("http://t1.i2p/announce"), result.removedUrls);
        assertTrue(result.updatedTrackers.isEmpty());
        assertTrue(result.newOpen.isEmpty());
        assertTrue(result.newPrivate.isEmpty());
    }

    @Test
    public void testProcessTrackerFormDeleteSaveTypeChangeOpen() {
        Tracker tracker = new Tracker("t1", "http://t1.i2p/announce", "http://t1.i2p");
        Map<String, Tracker> trackers = new HashMap<>();
        trackers.put("key1", tracker);
        List<String> openTrackers = Collections.emptyList();
        List<String> privateTrackers = Collections.emptyList();

        I2PSnarkServlet.TrackerFormParams params = new I2PSnarkServlet.TrackerFormParams(
            Collections.emptyList(), Collections.singletonMap("key1", "1"));

        I2PSnarkServlet.TrackerFormResult result = I2PSnarkServlet.processTrackerFormDeleteSave(
            params, trackers, openTrackers, privateTrackers);

        assertTrue(result.changed);
        assertEquals(Arrays.asList("http://t1.i2p/announce"), result.newOpen);
        assertTrue(result.newPrivate.isEmpty());
    }

    @Test
    public void testProcessTrackerFormDeleteSaveTypeChangePrivate() {
        Tracker tracker = new Tracker("t1", "http://t1.i2p/announce", "http://t1.i2p");
        Map<String, Tracker> trackers = new HashMap<>();
        trackers.put("key1", tracker);
        List<String> openTrackers = Collections.emptyList();
        List<String> privateTrackers = Collections.emptyList();

        I2PSnarkServlet.TrackerFormParams params = new I2PSnarkServlet.TrackerFormParams(
            Collections.emptyList(), Collections.singletonMap("key1", "2"));

        I2PSnarkServlet.TrackerFormResult result = I2PSnarkServlet.processTrackerFormDeleteSave(
            params, trackers, openTrackers, privateTrackers);

        assertTrue(result.changed);
        assertTrue(result.newOpen.isEmpty());
        assertEquals(Arrays.asList("http://t1.i2p/announce"), result.newPrivate);
    }

    @Test
    public void testProcessTrackerFormDeleteSaveOpenTrumpsPrivate() {
        Tracker tracker = new Tracker("t1", "http://t1.i2p/announce", "http://t1.i2p");
        Map<String, Tracker> trackers = new HashMap<>();
        trackers.put("key1", tracker);
        List<String> openTrackers = Arrays.asList("http://t1.i2p/announce");
        List<String> privateTrackers = Arrays.asList("http://t1.i2p/announce");

        I2PSnarkServlet.TrackerFormParams params = new I2PSnarkServlet.TrackerFormParams(
            Collections.emptyList(), Collections.emptyMap());

        I2PSnarkServlet.TrackerFormResult result = I2PSnarkServlet.processTrackerFormDeleteSave(
            params, trackers, openTrackers, privateTrackers);

        // open trumps private - should be removed from private
        assertFalse(result.changed);
        assertEquals(Arrays.asList("http://t1.i2p/announce"), result.newOpen);
        assertTrue(result.newPrivate.isEmpty());
    }

    // ---- parseAddTrackerParams --------------------------------------------

    @Test
    public void testParseAddTrackerParamsValid() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("tname", "  My Tracker  ");
        req.addParameter("thurl", "http://tracker.i2p");
        req.addParameter("taurl", "http://tracker.i2p/announce");
        req.addParameter("add_tracker_type", "1");

        I2PSnarkServlet.AddTrackerParams params = I2PSnarkServlet.parseAddTrackerParams(req);
        assertNotNull(params);
        assertEquals("My Tracker", params.name);
        assertEquals("http://tracker.i2p", params.httpUrl);
        assertEquals("http://tracker.i2p/announce", params.announceUrl);
        assertEquals("1", params.type);
    }

    @Test
    public void testParseAddTrackerParamsMissingParams() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("tname", "Tracker");
        // missing thurl
        req.addParameter("taurl", "http://tracker.i2p/announce");

        I2PSnarkServlet.AddTrackerParams params = I2PSnarkServlet.parseAddTrackerParams(req);
        assertNull(params);
    }

    @Test
    public void testParseAddTrackerParamsAutoHttp() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("tname", "Tracker");
        req.addParameter("thurl", "tracker.i2p"); // no scheme
        req.addParameter("taurl", "tracker.i2p/announce"); // no scheme

        I2PSnarkServlet.AddTrackerParams params = I2PSnarkServlet.parseAddTrackerParams(req);
        assertNotNull(params);
        assertEquals("http://tracker.i2p", params.httpUrl);
        assertEquals("http://tracker.i2p/announce", params.announceUrl);
    }

    @Test
    public void testParseAddTrackerParamsAnnounceEquals() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("tname", "Tracker");
        req.addParameter("thurl", "http://tracker.i2p");
        req.addParameter("taurl", "http://tracker.i2p/announce=param");

        I2PSnarkServlet.AddTrackerParams params = I2PSnarkServlet.parseAddTrackerParams(req);
        assertNotNull(params);
        assertEquals("http://tracker.i2p/announce&#61;param", params.announceUrl);
    }

    // ---- validateAddTrackerParams -----------------------------------------

    @Test
    public void testValidateAddTrackerParamsValid() {
        I2PSnarkServlet.AddTrackerParams params = new I2PSnarkServlet.AddTrackerParams(
            "Test", "http://tracker.i2p", "http://tracker.i2p/announce", "1");
        assertTrue(I2PSnarkServlet.validateAddTrackerParams(params));
    }

    @Test
    public void testValidateAddTrackerParamsInvalidName() {
        I2PSnarkServlet.AddTrackerParams params = new I2PSnarkServlet.AddTrackerParams(
            "", "http://tracker.i2p", "http://tracker.i2p/announce", "1");
        assertFalse(I2PSnarkServlet.validateAddTrackerParams(params));
    }

    @Test
    public void testValidateAddTrackerParamsInvalidHttpUrl() {
        I2PSnarkServlet.AddTrackerParams params = new I2PSnarkServlet.AddTrackerParams(
            "Test", "ftp://tracker.i2p", "http://tracker.i2p/announce", "1");
        assertFalse(I2PSnarkServlet.validateAddTrackerParams(params));
    }

    @Test
    public void testValidateAddTrackerParamsInvalidAnnounce() {
        I2PSnarkServlet.AddTrackerParams params = new I2PSnarkServlet.AddTrackerParams(
            "Test", "http://tracker.i2p", "http://example.com/announce", "1");
        assertFalse(I2PSnarkServlet.validateAddTrackerParams(params));
    }

    @Test
    public void testValidateAddTrackerParamsNull() {
        assertFalse(I2PSnarkServlet.validateAddTrackerParams(null));
    }

    // ---- processAddTracker ------------------------------------------------

    @Test
    public void testProcessAddTrackerSuccess() {
        I2PSnarkServlet.AddTrackerParams params = new I2PSnarkServlet.AddTrackerParams(
            "Test Tracker", "http://tracker.i2p", "http://tracker.i2p/announce", "1");
        Map<String, Tracker> trackers = new HashMap<>();
        List<String> openTrackers = new ArrayList<>();
        List<String> privateTrackers = new ArrayList<>();

        boolean result = I2PSnarkServlet.processAddTracker(params, trackers, openTrackers, privateTrackers);

        assertTrue(result);
        assertEquals(1, trackers.size());
        Tracker tracker = trackers.get("Test Tracker");
        assertNotNull(tracker);
        assertEquals("http://tracker.i2p/announce", tracker.announceURL);
    }

    // ---- TrackerClient.isValidAnnounce ------------------------------------

    @Test
    public void testIsValidAnnounceI2pHttp() {
        assertTrue(TrackerClient.isValidAnnounce("http://tracker.i2p/announce"));
    }

    @Test
    public void testIsValidAnnounceI2pUdp() {
        assertTrue(TrackerClient.isValidAnnounce("udp://tracker.i2p/announce"));
    }

    @Test
    public void testIsValidAnnounceNonI2p() {
        assertFalse(TrackerClient.isValidAnnounce("http://tracker.example.com/announce"));
    }

    @Test
    public void testIsValidAnnounceInvalid() {
        assertFalse(TrackerClient.isValidAnnounce("not-a-url"));
        assertFalse(TrackerClient.isValidAnnounce(null));
    }

    // ---- Mock classes -----------------------------------------------------

    private static class MockHttpServletRequest extends javax.servlet.http.HttpServletRequestWrapper {
        private final Map<String, List<String>> params = new HashMap<>();

        MockHttpServletRequest() {
            super(new MockServletRequest());
        }

        void addParameter(String name, String value) {
            params.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }

        @Override
        public String getParameter(String name) {
            List<String> list = params.get(name);
            return list != null && !list.isEmpty() ? list.get(0) : null;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(params.keySet());
        }

        private static class MockServletRequest implements javax.servlet.http.HttpServletRequest {
            @Override public Object getAttribute(String name) { return null; }
            @Override public Enumeration<String> getAttributeNames() { return Collections.emptyEnumeration(); }
            @Override public String getCharacterEncoding() { return "UTF-8"; }
            @Override public void setCharacterEncoding(String env) { }
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1; }
            @Override public String getContentType() { return null; }
            @Override public javax.servlet.ServletInputStream getInputStream() { return null; }
            @Override public String getLocalAddr() { return "127.0.0.1"; }
            @Override public String getLocalName() { return "localhost"; }
            @Override public int getLocalPort() { return 8080; }
            @Override public javax.servlet.ServletContext getServletContext() { return null; }
            @Override public String getProtocol() { return "HTTP/1.1"; }
            @Override public String getScheme() { return "http"; }
            @Override public String getServerName() { return "localhost"; }
            @Override public int getServerPort() { return 8080; }
            @Override public java.io.BufferedReader getReader() { return null; }
            @Override public String getRemoteAddr() { return "127.0.0.1"; }
            @Override public String getRemoteHost() { return "localhost"; }
            @Override public int getRemotePort() { return 8080; }
            @Override public Locale getLocale() { return Locale.US; }
            @Override public Enumeration<Locale> getLocales() { return Collections.enumeration(Arrays.asList(Locale.US)); }
            @Override public boolean isSecure() { return false; }
            @Override public javax.servlet.RequestDispatcher getRequestDispatcher(String path) { return null; }
            @Override public String getRealPath(String path) { return null; }
            @Override public void setAttribute(String name, Object o) {}
            @Override public void removeAttribute(String name) {}
            @Override public String getAuthType() { return null; }
            @Override public String getContextPath() { return ""; }
            @Override public javax.servlet.http.Cookie[] getCookies() { return null; }
            @Override public long getDateHeader(String name) { return -1; }
            @Override public String getHeader(String name) { return null; }
            @Override public Enumeration<String> getHeaderNames() { return Collections.emptyEnumeration(); }
            @Override public Enumeration<String> getHeaders(String name) { return Collections.emptyEnumeration(); }
            @Override public int getIntHeader(String name) { return -1; }
            @Override public String getMethod() { return "POST"; }
            @Override public String getPathInfo() { return null; }
            @Override public String getPathTranslated() { return null; }
            @Override public String getQueryString() { return null; }
            @Override public String getRemoteUser() { return null; }
            @Override public boolean isUserInRole(String role) { return false; }
            @Override public java.security.Principal getUserPrincipal() { return null; }
            @Override public String getRequestedSessionId() { return null; }
            @Override public String getRequestURI() { return ""; }
            @Override public StringBuffer getRequestURL() { return new StringBuffer(); }
            @Override public String getServletPath() { return ""; }
            @Override public javax.servlet.http.HttpSession getSession(boolean create) { return null; }
            @Override public javax.servlet.http.HttpSession getSession() { return null; }
            @Override public String changeSessionId() { return null; }
            @Override public boolean isRequestedSessionIdValid() { return false; }
            @Override public boolean isRequestedSessionIdFromCookie() { return false; }
            @Override public boolean isRequestedSessionIdFromURL() { return false; }
            @Override public boolean isRequestedSessionIdFromUrl() { return false; }
            @Override public boolean authenticate(javax.servlet.http.HttpServletResponse response) { return false; }
            @Override public void login(String username, String password) throws javax.servlet.ServletException {}
            @Override public void logout() throws javax.servlet.ServletException {}
            @Override public java.util.Collection<javax.servlet.http.Part> getParts() { return Collections.emptyList(); }
            @Override public javax.servlet.http.Part getPart(String name) { return null; }
            @Override public String getParameter(String name) { return null; }
            @Override public Enumeration<String> getParameterNames() { return Collections.emptyEnumeration(); }
            @Override public String[] getParameterValues(String name) { return null; }
            @Override public Map<String, String[]> getParameterMap() { return Collections.emptyMap(); }
            @Override public javax.servlet.DispatcherType getDispatcherType() { return javax.servlet.DispatcherType.REQUEST; }
            @Override public javax.servlet.AsyncContext getAsyncContext() { return null; }
            @Override public boolean isAsyncStarted() { return false; }
            @Override public boolean isAsyncSupported() { return false; }
            @Override public javax.servlet.AsyncContext startAsync() { return null; }
            @Override public javax.servlet.AsyncContext startAsync(javax.servlet.ServletRequest servletRequest, javax.servlet.ServletResponse servletResponse) { return null; }
            @Override public <T extends javax.servlet.http.HttpUpgradeHandler> T upgrade(Class<T> handlerClass) { return null; }
        }
    }
}