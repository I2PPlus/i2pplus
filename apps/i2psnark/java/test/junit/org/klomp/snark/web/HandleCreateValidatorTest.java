package org.klomp.snark.web;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.klomp.snark.TorrentCreateFilter;
import org.junit.Test;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;

/**
 * Tests for I2PSnarkServlet handleCreate pure helpers:
 * {@link I2PSnarkServlet#parseAnnounceParams},
 * {@link I2PSnarkServlet#buildAnnounceList},
 * {@link I2PSnarkServlet#parseCreateFilters}.
 *
 * @since 0.9.71+
 */
public class HandleCreateValidatorTest {

    // ---- parseAnnounceParams ----------------------------------------------

    @Test
    public void testParseAnnounceParamsNone() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("announceURL", "none");
        I2PSnarkServlet.AnnounceParams p = I2PSnarkServlet.parseAnnounceParams(req);
        assertNull(p.primary);
        assertTrue(p.backupURLs.isEmpty());
    }

    @Test
    public void testParseAnnounceParamsWithBackups() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("announceURL", "http://tracker1.i2p/announce");
        req.addParameter("backup_http://tracker2.i2p/announce", "");
        req.addParameter("backup_http://tracker3.i2p/announce", "");
        I2PSnarkServlet.AnnounceParams p = I2PSnarkServlet.parseAnnounceParams(req);
        assertEquals("http://tracker1.i2p/announce", p.primary);
        assertEquals(2, p.backupURLs.size());
        assertTrue(p.backupURLs.contains("http://tracker2.i2p/announce"));
        assertTrue(p.backupURLs.contains("http://tracker3.i2p/announce"));
    }

    @Test
    public void testParseAnnounceParamsDuplicateBackupIgnored() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("announceURL", "http://tracker1.i2p/announce");
        req.addParameter("backup_http://tracker1.i2p/announce", "");
        I2PSnarkServlet.AnnounceParams p = I2PSnarkServlet.parseAnnounceParams(req);
        assertEquals("http://tracker1.i2p/announce", p.primary);
        assertTrue(p.backupURLs.isEmpty());
    }

    @Test
    public void testParseAnnounceParamsEmptyString() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("announceURL", "");
        I2PSnarkServlet.AnnounceParams p = I2PSnarkServlet.parseAnnounceParams(req);
        assertEquals("", p.primary);
        assertTrue(p.backupURLs.isEmpty());
    }

    // ---- buildAnnounceList ------------------------------------------------

    @Test
    public void testBuildAnnounceListNoBackupsPrivate() {
        I2PSnarkServlet.AnnounceParams p = new I2PSnarkServlet.AnnounceParams("http://t1.i2p/announce", Collections.emptyList());
        List<String> privateTrackers = Arrays.asList("http://t1.i2p/announce");
        I2PSnarkServlet.CreateAnnounceListResult r = I2PSnarkServlet.buildAnnounceList(p, privateTrackers);
        assertTrue(r.isValid());
        assertNull(r.announceList);
        assertTrue(r.isPrivate);
    }

    @Test
    public void testBuildAnnounceListNoBackupsPublic() {
        I2PSnarkServlet.AnnounceParams p = new I2PSnarkServlet.AnnounceParams("http://public.tracker/announce", Collections.emptyList());
        List<String> privateTrackers = Arrays.asList("http://t1.i2p/announce");
        I2PSnarkServlet.CreateAnnounceListResult r = I2PSnarkServlet.buildAnnounceList(p, privateTrackers);
        assertTrue(r.isValid());
        assertNull(r.announceList);
        assertFalse(r.isPrivate);
    }

    @Test
    public void testBuildAnnounceListWithBackupsValid() {
        I2PSnarkServlet.AnnounceParams p = new I2PSnarkServlet.AnnounceParams(
            "http://t1.i2p/announce",
            Arrays.asList("http://t2.i2p/announce", "http://t3.i2p/announce"));
        List<String> privateTrackers = Arrays.asList("http://t1.i2p/announce", "http://t2.i2p/announce", "http://t3.i2p/announce");
        I2PSnarkServlet.CreateAnnounceListResult r = I2PSnarkServlet.buildAnnounceList(p, privateTrackers);
        assertTrue(r.isValid());
        assertNotNull(r.announceList);
        assertEquals(3, r.announceList.size());
        assertTrue(r.isPrivate);
    }

    @Test
    public void testBuildAnnounceListWithBackupsAllPublic() {
        I2PSnarkServlet.AnnounceParams p = new I2PSnarkServlet.AnnounceParams(
            "http://public1.tracker/announce",
            Arrays.asList("http://public2.tracker/announce"));
        List<String> privateTrackers = Arrays.asList("http://t1.i2p/announce");
        I2PSnarkServlet.CreateAnnounceListResult r = I2PSnarkServlet.buildAnnounceList(p, privateTrackers);
        assertTrue(r.isValid());
        assertNotNull(r.announceList);
        assertEquals(2, r.announceList.size());
        assertFalse(r.isPrivate);
    }

    @Test
    public void testBuildAnnounceListMissingPrimary() {
        I2PSnarkServlet.AnnounceParams p = new I2PSnarkServlet.AnnounceParams(null, Arrays.asList("http://t2.i2p/announce"));
        List<String> privateTrackers = Collections.emptyList();
        I2PSnarkServlet.CreateAnnounceListResult r = I2PSnarkServlet.buildAnnounceList(p, privateTrackers);
        assertFalse(r.isValid());
        assertTrue(r.errorMessage.contains("Cannot include alternate trackers without a primary tracker"));
    }

    @Test
    public void testBuildAnnounceListMixedPrivatePublic() {
        I2PSnarkServlet.AnnounceParams p = new I2PSnarkServlet.AnnounceParams(
            "http://t1.i2p/announce",
            Arrays.asList("http://public.tracker/announce"));
        List<String> privateTrackers = Arrays.asList("http://t1.i2p/announce");
        I2PSnarkServlet.CreateAnnounceListResult r = I2PSnarkServlet.buildAnnounceList(p, privateTrackers);
        assertFalse(r.isValid());
        assertTrue(r.errorMessage.contains("Cannot mix private and public trackers"));
    }

    // ---- parseCreateFilters -----------------------------------------------

    @Test
    public void testParseCreateFiltersEmpty() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        Map<String, TorrentCreateFilter> map = new HashMap<>();
        List<TorrentCreateFilter> filters = I2PSnarkServlet.parseCreateFilters(req, map);
        assertTrue(filters.isEmpty());
    }

    @Test
    public void testParseCreateFiltersValid() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("filters", "filter1");
        req.addParameter("filters", "filter2");
        TorrentCreateFilter f1 = new TorrentCreateFilter("Filter 1", "*.tmp", "exclude", false);
        TorrentCreateFilter f2 = new TorrentCreateFilter("Filter 2", "*.log", "exclude", true);
        Map<String, TorrentCreateFilter> map = new HashMap<>();
        map.put("filter1", f1);
        map.put("filter2", f2);
        List<TorrentCreateFilter> filters = I2PSnarkServlet.parseCreateFilters(req, map);
        assertEquals(2, filters.size());
        assertTrue(filters.contains(f1));
        assertTrue(filters.contains(f2));
    }

    @Test
    public void testParseCreateFiltersUnknownIgnored() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addParameter("filters", "filter1");
        req.addParameter("filters", "unknown");
        TorrentCreateFilter f1 = new TorrentCreateFilter("Filter 1", "*.tmp", "exclude", false);
        Map<String, TorrentCreateFilter> map = new HashMap<>();
        map.put("filter1", f1);
        List<TorrentCreateFilter> filters = I2PSnarkServlet.parseCreateFilters(req, map);
        assertEquals(1, filters.size());
        assertEquals(f1, filters.get(0));
    }

    @Test
    public void testParseCreateFiltersNullValues() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        Map<String, TorrentCreateFilter> map = new HashMap<>();
        List<TorrentCreateFilter> filters = I2PSnarkServlet.parseCreateFilters(req, map);
        assertTrue(filters.isEmpty());
    }

    // ---- Mock classes -----------------------------------------------------
private static class MockHttpServletRequest implements javax.servlet.http.HttpServletRequest {
        private final Map<String, List<String>> params = new HashMap<>();

        void addParameter(String name, String value) {
            params.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }

        @Override
        public String getParameter(String name) {
            List<String> list = params.get(name);
            return list != null && !list.isEmpty() ? list.get(0) : null;
        }

        @Override
        public String[] getParameterValues(String name) {
            List<String> list = params.get(name);
            return list != null ? list.toArray(new String[0]) : null;
        }

        @Override
        public java.util.Enumeration<String> getParameterNames() {
            return Collections.enumeration(params.keySet());
        }

        // Minimal unused methods
        @Override public String getAuthType() { return null; }
        @Override public String getContextPath() { return ""; }
        @Override public Cookie[] getCookies() { return null; }
        @Override public long getDateHeader(String name) { return -1; }
        @Override public String getHeader(String name) { return null; }
        @Override public java.util.Enumeration<String> getHeaderNames() { return Collections.emptyEnumeration(); }
        @Override public java.util.Enumeration<String> getHeaders(String name) { return Collections.emptyEnumeration(); }
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
        // ServletRequest methods
        @Override public Object getAttribute(String name) { return null; }
        @Override public java.util.Enumeration<String> getAttributeNames() { return Collections.emptyEnumeration(); }
        @Override public String getCharacterEncoding() { return "UTF-8"; }
        @Override public void setCharacterEncoding(String env) throws java.io.UnsupportedEncodingException {}
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
        @Override public java.util.Locale getLocale() { return java.util.Locale.US; }
        @Override public java.util.Enumeration<java.util.Locale> getLocales() { return Collections.enumeration(Arrays.asList(java.util.Locale.US)); }
        @Override public boolean isSecure() { return false; }
        @Override public javax.servlet.RequestDispatcher getRequestDispatcher(String path) { return null; }
        @Override public String getRealPath(String path) { return null; }
        @Override public void setAttribute(String name, Object o) {}
        @Override public void removeAttribute(String name) {}
        @Override public javax.servlet.DispatcherType getDispatcherType() { return javax.servlet.DispatcherType.REQUEST; }
        @Override public javax.servlet.AsyncContext getAsyncContext() { return null; }
        @Override public boolean isAsyncStarted() { return false; }
        @Override public boolean isAsyncSupported() { return false; }
        @Override public javax.servlet.AsyncContext startAsync() { return null; }
        @Override public javax.servlet.AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) { return null; }
        @Override public <T extends javax.servlet.http.HttpUpgradeHandler> T upgrade(Class<T> handlerClass) { return null; }
        @Override public java.util.Map<String, String[]> getParameterMap() {
            return Collections.emptyMap();
        }
    }
}