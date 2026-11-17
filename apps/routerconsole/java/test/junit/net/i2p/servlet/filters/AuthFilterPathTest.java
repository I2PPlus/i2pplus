package net.i2p.servlet.filters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

/**
 * The console login filter must answer the public-page question about the path
 * the container will dispatch on, not the request target as the client sent it.
 * Jetty 9.3 decodes that path and resolves {@code .} and {@code ..} before
 * mapping the request, so a traversal segment written after a public prefix
 * satisfied a prefix match on the raw URI and named a protected page.
 *
 * @since 0.9.72+
 */
public class AuthFilterPathTest {

    /**
     * The eleven URIs the assessment sent at the console: the request target as
     * the client sent it, the path the container hands the filter once it has
     * decoded and resolved it, and the page that path names. A null page means
     * the path carries a path parameter and is refused outright.
     *
     * @return raw URI, decoded path, canonical page
     */
    private static final String[][] TRAVERSALS = {
        {"/js/../configclients", "/js/../configclients", "/configclients"},
        {"/js/..%2fconfigclients", "/js/../configclients", "/configclients"},
        {"/js/..%2Fconfigclients", "/js/../configclients", "/configclients"},
        {"/session/%2e%2e/configadvanced", "/session/../configadvanced", "/configadvanced"},
        {"/login/../configadvanced", "/login/../configadvanced", "/configadvanced"},
        {"/themes/login/../../configservice", "/themes/login/../../configservice", "/configservice"},
        {"/index.jsp/../configupdate", "/index.jsp/../configupdate", "/configupdate"},
        {"/js/..;/configclients", "/js/..;/configclients", null},
        {"/flags.jsp/../configreseed", "/flags.jsp/../configreseed", "/configreseed"},
        {"/prefs/../configplugins", "/prefs/../configplugins", "/configplugins"},
        {"/js/;/../configclients", "/js/;/../configclients", null},
    };

    /** Holds no state; the filter's path tests read no router state. */
    public AuthFilterPathTest() {}

    /**
     * Every traversal resolves to the protected page the container would run,
     * not to the public prefix it was written under.
     */
    @Test
    public void traversalAfterAPublicPrefixResolvesToTheProtectedPage() {
        AuthFilter filter = new AuthFilter();
        for (String[] row : TRAVERSALS) {
            String canonical = AuthFilter.canonicalPath(row[1]);
            assertEquals(row[1], row[2], canonical);
            if (canonical != null) {
                assertFalse(row[0] + " resolves to " + canonical + ", a protected page",
                            filter.isPublicPath(canonical));
            }
        }
    }

    /**
     * Before the fix, the same URIs were public: each one starts with a public
     * prefix, so the raw-URI prefix match let the request through.
     */
    @Test
    public void traversalStillStartsWithAPublicPrefix() {
        AuthFilter filter = new AuthFilter();
        for (String[] row : TRAVERSALS) {
            assertTrue(row[0] + " still starts with a public prefix", filter.isPublicPath(row[0]));
        }
    }

    /** The pages that are meant to be reachable without a login stay reachable. */
    @Test
    public void publicPagesStayPublic() {
        AuthFilter filter = new AuthFilter();
        String[] publicPaths = {"/index.jsp", "/login", "/logout", "/session/login", "/prefs",
                                "/prefs/", "/themes/login/style.css", "/js/refresh.js", "/flags.jsp"};
        for (String path : publicPaths) {
            String canonical = AuthFilter.canonicalPath(path);
            assertTrue(path + " should still be public", filter.isPublicPath(canonical));
        }
    }

    /** The pages behind the login stay behind it. */
    @Test
    public void protectedPagesStayProtected() {
        AuthFilter filter = new AuthFilter();
        String[] protectedPaths = {"/configclients", "/configadvanced", "/configservice", "/configupdate",
                                   "/configreseed", "/configplugins", "/speed.jsp"};
        for (String path : protectedPaths) {
            String canonical = AuthFilter.canonicalPath(path);
            assertFalse(path + " should still need a login", filter.isPublicPath(canonical));
        }
    }

    /**
     * A single dot is dropped, a trailing slash is kept so a public directory
     * prefix still matches, and an empty path is the root.
     */
    @Test
    public void dotSegmentsAndTrailingSlashesAreHandled() {
        assertEquals("/js/refresh.js", AuthFilter.canonicalPath("/js/./refresh.js"));
        assertEquals("/session/", AuthFilter.canonicalPath("/session/"));
        assertEquals("/session/", AuthFilter.canonicalPath("/session/./"));
        assertEquals("/", AuthFilter.canonicalPath("/"));
        assertEquals("/", AuthFilter.canonicalPath(""));
        assertEquals("/", AuthFilter.canonicalPath(null));
        assertEquals("/", AuthFilter.canonicalPath("/js/.."));
    }

    /**
     * A path that walks out of the root has no page to name, so it is refused
     * rather than guessed at.
     */
    @Test
    public void escapingTheRootIsRefused() {
        assertNull(AuthFilter.canonicalPath("/../configclients"));
        assertNull(AuthFilter.canonicalPath("/js/../../configclients"));
        assertNull(AuthFilter.canonicalPath("/.."));
    }

    /**
     * A path parameter is not resolved the same way by every part of the stack,
     * so a path still carrying one is refused instead of matched.
     */
    @Test
    public void pathParametersAreRefused() {
        assertNull(AuthFilter.canonicalPath("/js/..;/configclients"));
        assertNull(AuthFilter.canonicalPath("/prefs;x/../configplugins"));
    }

    /**
     * The path is taken from the container's own servlet mapping, not from the
     * request target, and a request the container has not mapped is refused.
     */
    @Test
    public void dispatchPathUsesTheContainerMapping() {
        assertEquals("/configclients", AuthFilter.dispatchPath(request("/configclients", null)));
        assertEquals("/js/refresh.js", AuthFilter.dispatchPath(request("/js/refresh.js", null)));
        assertEquals("/r/detail", AuthFilter.dispatchPath(request("/r", "/detail")));
        assertEquals("/themes/console/dark/style.css",
                     AuthFilter.dispatchPath(request("/themes", "/console/dark/style.css")));
        assertEquals("/graph", AuthFilter.dispatchPath(request("", "/graph")));
        assertNull(AuthFilter.dispatchPath(request(null, null)));
    }

    /**
     * A request that answers only the two methods {@link AuthFilter#dispatchPath}
     * reads; every other call returns null.
     *
     * @param servletPath what getServletPath() returns
     * @param pathInfo what getPathInfo() returns
     * @return the stub request
     */
    private static HttpServletRequest request(final String servletPath, final String pathInfo) {
        return (HttpServletRequest) Proxy.newProxyInstance(AuthFilterPathTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getServletPath": return servletPath;
                        case "getPathInfo": return pathInfo;
                        default: return null;
                    }
                });
    }
}
