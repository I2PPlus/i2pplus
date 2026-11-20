package net.i2p.servlet.filters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

/**
 * The console session token as it is read out of the Cookie header.
 *
 * <p>Jetty 9.3.30, the console's container, starts a quoted string at a
 * <code>"</code> at the head of a cookie value and lets it run across the
 * <code>;</code> separators to the next <code>"</code> (CVE-2023-26049), so a
 * page on another port of 127.0.0.1 that sets <code>a="b</code> and
 * <code>c=d"</code> - browsers do not scope cookies by port - swallows the
 * console's token into that page's own cookie and the console loses the session.
 * RFC 6265 ends a value at the separator and quotes nothing, which is what the
 * console does now.
 *
 * @since 0.9.72+
 */
public class SessionCookieDecisionTest {

    /** Runs the decisions; it holds no state. */
    public SessionCookieDecisionTest() {}

    /** The token alone, and the token among other cookies. */
    @Test
    public void findsTheToken() {
        assertEquals("secret", SessionManager.sessionCookieFrom("I2PAUTH=secret"));
        assertEquals("secret", SessionManager.sessionCookieFrom("other=1; I2PAUTH=secret"));
        assertEquals("secret", SessionManager.sessionCookieFrom("I2PAUTH=secret; other=1"));
        assertEquals("secret", SessionManager.sessionCookieFrom("a=1; I2PAUTH=secret; b=2"));
    }

    /**
     * The advisory's shape: two cookies from a page on another loopback port,
     * whose quotes run across the separators in Jetty 9.3.30 and take the token
     * with them. Here the separators separate, and the token is still the
     * console's.
     */
    @Test
    public void quotesDoNotSpanSeparators() {
        assertEquals("secret", SessionManager.sessionCookieFrom("a=\"b; I2PAUTH=secret; c=d\""));
    }

    /**
     * An unterminated quote costs that one cookie, not the rest of the header:
     * 9.3.30 drops everything after it, which is a forced logout from one
     * malformed cookie.
     */
    @Test
    public void anUnterminatedQuoteCostsOneCookieOnly() {
        assertEquals("secret", SessionManager.sessionCookieFrom("a=\"b; I2PAUTH=secret"));
        assertEquals("secret", SessionManager.sessionCookieFrom("I2PAUTH=secret; a=\"b"));
    }

    /** A quote in the middle of a value is an ordinary character. */
    @Test
    public void aQuoteInsideAValueIsOrdinary() {
        assertEquals("a\"b\"c", SessionManager.sessionCookieFrom("I2PAUTH=a\"b\"c"));
    }

    /** Optional whitespace around the name and the value is not part of either. */
    @Test
    public void whitespaceIsTrimmed() {
        assertEquals("secret", SessionManager.sessionCookieFrom("  I2PAUTH=secret  ; a=1"));
        assertEquals("secret", SessionManager.sessionCookieFrom("a=1; I2PAUTH= secret"));
    }

    /** A comma is not a separator - one cookie, and not the session's. */
    @Test
    public void aCommaIsNotASeparator() {
        assertNull(SessionManager.sessionCookieFrom("a=1, I2PAUTH=secret"));
    }

    /** An empty value is an empty token, not a missing one. */
    @Test
    public void anEmptyValueIsAnEmptyToken() {
        assertEquals("", SessionManager.sessionCookieFrom("I2PAUTH="));
        assertEquals("", SessionManager.sessionCookieFrom("a=1; I2PAUTH=; b=2"));
    }

    /** A value may contain <code>=</code>; only the first one splits it. */
    @Test
    public void theFirstEqualsSplits() {
        assertEquals("a=b", SessionManager.sessionCookieFrom("I2PAUTH=a=b"));
    }

    /** A cookie whose name merely contains the session name is not the session. */
    @Test
    public void onlyTheExactNameMatches() {
        assertNull(SessionManager.sessionCookieFrom("XI2PAUTH=nope"));
        assertNull(SessionManager.sessionCookieFrom("I2PAUTHX=nope"));
        // cookie names are case-sensitive
        assertNull(SessionManager.sessionCookieFrom("i2pauth=nope"));
        assertEquals("yes", SessionManager.sessionCookieFrom("XI2PAUTH=nope; I2PAUTH=yes"));
    }

    /** No header, no cookie of that name, and an empty header, all mean absent. */
    @Test
    public void absentMeansNull() {
        assertNull(SessionManager.sessionCookieFrom(null));
        assertNull(SessionManager.sessionCookieFrom(""));
        assertNull(SessionManager.sessionCookieFrom(";"));
        assertNull(SessionManager.sessionCookieFrom("a=1; b=2"));
    }

    /** A trailing separator, and a run of them, do not end the scan early. */
    @Test
    public void emptyCookiesAreSkipped() {
        assertEquals("secret", SessionManager.sessionCookieFrom("I2PAUTH=secret;"));
        assertEquals("secret", SessionManager.sessionCookieFrom(";;a=1;;I2PAUTH=secret;;"));
    }

    /**
     * The request-facing entry point reads the <code>Cookie</code> header, not a
     * container-parsed cookie list, so the container's parser cannot be in the
     * path at all.
     */
    @Test
    public void theRequestIsReadByHeader() {
        HttpServletRequest req = requestWithHeader("Cookie", "a=\"b; I2PAUTH=secret; c=d\"");
        assertEquals("secret", SessionManager.getSessionCookie(req));
        HttpServletRequest none = requestWithHeader("Cookie", "a=1");
        assertNull(SessionManager.getSessionCookie(none));
    }

    /**
     * A client may send its cookies over more than one <code>Cookie</code>
     * header line, and the container's own <code>getCookies()</code> has always
     * joined them. Reading only the first header loses the token for a client
     * that splits them - <code>curl</code> with two <code>-H 'Cookie: …'</code>
     * arguments, or a proxy that forwards them separately - and the operator is
     * bounced to the login page while holding a valid session.
     */
    @Test
    public void tokenInASecondCookieHeaderIsFound() {
        HttpServletRequest split = requestWithHeaders("Cookie",
                Arrays.asList("a=1", "I2PAUTH=secret"));
        assertEquals("secret", SessionManager.getSessionCookie(split));
        // and a header that carries none does not stop the search
        HttpServletRequest two = requestWithHeaders("Cookie",
                Arrays.asList("I2PAUTH=first", "I2PAUTH=second"));
        assertEquals("first", SessionManager.getSessionCookie(two));
        HttpServletRequest none = requestWithHeaders("Cookie", Arrays.asList("a=1", "b=2"));
        assertNull(SessionManager.getSessionCookie(none));
    }

    /**
     * A request that answers the one header lookup.
     *
     * @param name the header the request has
     * @param value its value
     * @return a proxy implementing {@link HttpServletRequest}
     */
    private static HttpServletRequest requestWithHeader(String name, String value) {
        return requestWithHeaders(name, Arrays.asList(value));
    }

    /**
     * A request that answers {@code getHeader} with the first value and
     * {@code getHeaders} with all of them, as the servlet contract says.
     *
     * @param name the header the request has
     * @param values its values, one per header line
     * @return a proxy implementing {@link HttpServletRequest}
     */
    private static HttpServletRequest requestWithHeaders(String name, List<String> values) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                SessionCookieDecisionTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> {
                    String m = method.getName();
                    if ("getHeader".equals(m) && name.equals(args[0])) {return values.get(0);}
                    if ("getHeaders".equals(m) && name.equals(args[0])) {
                        return Collections.enumeration(values);
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {return Boolean.FALSE;}
                    if (rt == int.class) {return Integer.valueOf(0);}
                    if (rt == long.class) {return Long.valueOf(0L);}
                    return null;
                });
    }
}
