package net.i2p.router.web.servlets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import net.i2p.router.web.CSSHelper;

import org.junit.Test;

/**
 * The token a logout request has to carry before it ends a console session.
 *
 * <p>Logout was a bare GET with no token on it, and a GET asks nothing of the
 * page that issues it: an <code>&lt;img src=http://127.0.0.1:7657/logout&gt;</code>
 * on any page the operator opens, a link in a mail, or a prefetch from another
 * port on the loopback interface ended the session with nothing clicked at all.
 * The home page now puts the nonce it already holds into the logout link, and a
 * request without one - or with one this session has already spent - does
 * nothing. The same token the console's forms carry is what says the operator
 * asked to be logged out.
 *
 * @since 0.9.72+
 */
public class LogoutNonceTest {

    /** Builds the requests and sessions the decision reads. */
    public LogoutNonceTest() {}

    /**
     * A session that holds its attributes in a map, as a container's does, so
     * the nonce queue CSSHelper keeps behaves as it does on the console.
     *
     * @return a session with no attributes yet
     */
    private static HttpSession session() {
        final Map<String, Object> attrs = new HashMap<>(4);
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getAttribute":
                    return attrs.get(args[0]);
                case "setAttribute":
                    if (args[1] == null) {attrs.remove(args[0]);}
                    else {attrs.put((String) args[0], args[1]);}
                    return null;
                case "removeAttribute":
                    attrs.remove(args[0]);
                    return null;
                case "getId":
                    return "test-session";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "session";
                default:
                    return null;
            }
        };
        return (HttpSession) Proxy.newProxyInstance(getClassLoader(),
                                                    new Class<?>[] {HttpSession.class}, h);
    }

    /**
     * A request for /logout on the given session carrying the given nonce
     * parameter.
     *
     * @param sess the session the request presents, null for a request with no
     *             session of its own
     * @param nonce the nonce query parameter, null when the request carries none
     * @return the request to hand to the decision
     */
    private static HttpServletRequest request(HttpSession sess, String nonce) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getSession":
                    return sess;
                case "getParameter":
                    return "nonce".equals(args[0]) ? nonce : null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "request";
                default:
                    return null;
            }
        };
        Class<?>[] ifaces = {HttpServletRequest.class};
        return (HttpServletRequest) Proxy.newProxyInstance(getClassLoader(), ifaces, h);
    }

    /**
     * The class loader to define the proxies in - the servlet API's own.
     *
     * @return the loader the servlet interfaces came from
     */
    private static ClassLoader getClassLoader() {
        return HttpServletRequest.class.getClassLoader();
    }

    /**
     * The link the home page writes: the nonce the session holds, in the query
     * string, is accepted.
     */
    @Test
    public void testTheLinkNonceIsAccepted() {
        HttpSession sess = session();
        String nonce = CSSHelper.getNonce(sess);
        assertTrue(LogoutServlet.nonceMatches(request(sess, nonce)));
    }

    /**
     * The defect: a request that names no nonce at all - the image tag, the mail
     * link, the address bar - used to end the session, and now does not.
     */
    @Test
    public void testNoNonceIsRefused() {
        HttpSession sess = session();
        CSSHelper.getNonce(sess);
        assertFalse(LogoutServlet.nonceMatches(request(sess, null)));
        assertFalse("an empty nonce is not a token", LogoutServlet.nonceMatches(request(sess, "")));
    }

    /**
     * A nonce from one session does not log out another: a page that managed to
     * read a nonce from somewhere still has to be talking to the session that
     * issued it.
     */
    @Test
    public void testAForeignNonceIsRefused() {
        HttpSession issuer = session();
        HttpSession victim = session();
        CSSHelper.getNonce(victim);
        String nonce = CSSHelper.getNonce(issuer);
        assertFalse(LogoutServlet.nonceMatches(request(victim, nonce)));
    }

    /**
     * A request with no session of its own - the console with no password, or a
     * session that has already timed out - has nothing to end, and no nonce can
     * make it valid.
     */
    @Test
    public void testNoSessionIsRefused() {
        String nonce = CSSHelper.getNonce(session());
        assertFalse(LogoutServlet.nonceMatches(request(null, nonce)));
    }

    /**
     * A nonce is spent once. The same link replayed - a back button, a bookmark,
     * a double click - cannot log the operator out a second time on a session
     * that has since issued another one.
     */
    @Test
    public void testANonceIsUsedOnce() {
        HttpSession sess = session();
        String nonce = CSSHelper.getNonce(sess);
        assertTrue(LogoutServlet.nonceMatches(request(sess, nonce)));
        CSSHelper.getNonce(sess);
        assertFalse(LogoutServlet.nonceMatches(request(sess, nonce)));
    }

    /**
     * A nonce minted for a page is the one that page's logout link has to carry,
     * and two pages mint different ones - what makes a token an attacker cannot
     * guess is that it is not a constant.
     */
    @Test
    public void testEveryPageGetsItsOwnNonce() {
        HttpSession sess = session();
        String first = CSSHelper.getNonce(sess);
        String second = CSSHelper.getNonce(sess);
        assertFalse(first.equals(second));
        assertTrue(first, first.startsWith("CN"));
    }
}
