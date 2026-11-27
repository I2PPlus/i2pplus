package net.i2p.servlet.filters;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;

/**
 * Tests for {@link OriginFilter}, the cross-origin POST refusal the address book
 * reaches through its <code>web.xml</code>.
 *
 * <p>The decision itself is <code>OriginCheck</code>'s and is
 * pinned by <code>OriginCheckDecisionTest</code>; what is pinned here is the
 * wiring around it, which is where the functionality breaks if it is wrong. Only
 * POST is guarded, a POST with no Origin at all still reaches the servlet, and a
 * refused POST must not continue down the chain - a chain that runs after the
 * refusal would carry out the add, delete or import the refusal was meant to stop.
 *
 * <p>The <code>Origin: null</code> case is the one that decides whether the
 * operator can still use the page: LibreWolf with uMatrix, and Firefox's tracking
 * protection "origin string cleanup", both send it for a plain same-origin form
 * POST, so refusing it locks the address book's own forms out.
 *
 * @since 0.9.72+
 */
public class OriginFilterTest {

    /** The Host the webapp is reached at in every case. */
    private static final String HOST = "127.0.0.1:7657";

    /**
     * A request carrying the given method, Origin and Host.
     *
     * @param method the HTTP method
     * @param origin the Origin header, null for a request that sends none
     * @param host the Host header
     * @return the mocked request
     */
    private static HttpServletRequest request(String method, String origin, String host) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getHeader("Origin")).thenReturn(origin);
        when(req.getHeader("Host")).thenReturn(host);
        when(req.getRequestURI()).thenReturn("/susidns/addressbook");
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        return req;
    }

    /**
     * Run one request through the filter and assert it did not reach the chain,
     * for the cases that must be refused.
     *
     * @param req the request expected to be refused
     * @return the response it was filtered against, for the assertions
     * @throws Exception if the filter fails
     */
    private static HttpServletResponse refuse(HttpServletRequest req) throws Exception {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new OriginFilter().doFilter(req, resp, chain);
        verify(chain, never()).doFilter(req, resp);
        return resp;
    }

    /**
     * A POST that sends no Origin is not a browser form POST - a script or a
     * command-line client - and is left to the checks behind this one.
     *
     * @throws Exception if the filter fails
     */
    @Test
    public void postWithoutOriginReachesTheChain() throws Exception {
        HttpServletRequest req = request("POST", null, HOST);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new OriginFilter().doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
        verify(resp, never()).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
    }

    /**
     * <code>Origin: null</code> names no origin, and a hardened browser sends it
     * on the operator's own same-origin form POST, so the address book must still
     * work behind LibreWolf.
     *
     * @throws Exception if the filter fails
     */
    @Test
    public void postWithNullOriginReachesTheChain() throws Exception {
        HttpServletRequest req = request("POST", "null", HOST);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new OriginFilter().doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
        verify(resp, never()).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
    }

    /**
     * A POST whose Origin is the Host it arrived at is the form the page sent.
     *
     * @throws Exception if the filter fails
     */
    @Test
    public void postFromTheSameOriginReachesTheChain() throws Exception {
        HttpServletRequest req = request("POST", "http://" + HOST, HOST);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new OriginFilter().doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
        verify(resp, never()).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
    }

    /**
     * A named host is matched as text, without a lookup: the console is also
     * reached through a local hostname, and that POST is same-origin.
     *
     * @throws Exception if the filter fails
     */
    @Test
    public void postToALocalHostnameMatchesItsOrigin() throws Exception {
        String host = "mybox.local:7657";
        HttpServletRequest req = request("POST", "http://mybox.local:7657", host);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new OriginFilter().doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
    }

    /**
     * Another port on the same address is a different origin: without the port in
     * the comparison, a page on any other local web UI would be accepted.
     *
     * @throws Exception if the filter fails
     */
    @Test
    public void postFromAnotherLocalPortIsRefused() throws Exception {
        HttpServletRequest req = request("POST", "http://127.0.0.1:8080", HOST);
        HttpServletResponse resp = refuse(req);
        verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
    }

    /**
     * A foreign page's POST is the CSRF attempt this filter exists for, and the
     * chain must not run - the address book would otherwise carry out the delete.
     *
     * @throws Exception if the filter fails
     */
    @Test
    public void postFromAForeignOriginIsRefusedAndNotCarriedOut() throws Exception {
        HttpServletRequest req = request("POST", "http://evil.example", HOST);
        HttpServletResponse resp = refuse(req);
        verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
    }

    /**
     * The refusal says which rule refused it, so an operator looking at a locked
     * form can tell this from a nonce failure.
     *
     * @throws Exception if the filter fails
     */
    @Test
    public void refusalNamesTheOriginRule() throws Exception {
        HttpServletRequest req = request("POST", "http://evil.example", HOST);
        HttpServletResponse resp = refuse(req);
        verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN),
                               eq("Invalid Origin header for POST request"));
    }

    /**
     * A cross-origin GET cannot change state here, so it is not guarded - the
     * address book's own reads, exports and the SVG images stay working.
     *
     * @throws Exception if the filter fails
     */
    @Test
    public void getFromAForeignOriginIsNotGuarded() throws Exception {
        HttpServletRequest req = request("GET", "http://evil.example", HOST);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new OriginFilter().doFilter(req, resp, chain);
        verify(chain).doFilter(req, resp);
        verify(resp, never()).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
    }
}
