package i2p.susi.dns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Every action that writes to the address book is gated on the nonce the page
 * holding its form was given, so a form posted by another site - arriving with a
 * session it created itself, and no nonce - cannot write. The file import is
 * dispatched by the JSP rather than by {@code getMessages()}, and
 * {@code getSerial()} replaces the nonce with a fresh one for the next page, so
 * the JSP has to read the previous page's nonce first.
 *
 * @since 0.9.72+
 */
public class AddressbookNonceTest {

    /** The nonce a page would have been given. */
    private static final String ISSUED = "-1234567890123456789";

    /** Holds no state; the gate is a static and the bean is built per case. */
    public AddressbookNonceTest() {}

    /** A POST carrying the nonce the page was given runs. */
    @Test
    public void postWithTheIssuedNonceRuns() {
        assertTrue(BaseBean.isAuthorizedAction(ISSUED, ISSUED, "POST"));
    }

    /**
     * The attack: a form on another site posts with no session and so no nonce,
     * and the request that creates the session gets to choose the book the
     * import writes into.
     */
    @Test
    public void requestWithNoNonceIsRefused() {
        assertFalse(BaseBean.isAuthorizedAction(null, ISSUED, "POST"));
        assertFalse(BaseBean.isAuthorizedAction("", ISSUED, "POST"));
    }

    /**
     * A session that has not rendered a page has no nonce to match against, so
     * nothing it posts runs - not even a request that guesses a nonce.
     */
    @Test
    public void sessionWithNoNonceIssuedIsRefused() {
        assertFalse(BaseBean.isAuthorizedAction(ISSUED, null, "POST"));
        assertFalse(BaseBean.isAuthorizedAction(ISSUED, "", "POST"));
    }

    /** A nonce from an earlier page, or a guessed one, is refused. */
    @Test
    public void staleOrGuessedNonceIsRefused() {
        assertFalse(BaseBean.isAuthorizedAction("0", ISSUED, "POST"));
        assertFalse(BaseBean.isAuthorizedAction(ISSUED + "0", ISSUED, "POST"));
        assertFalse(BaseBean.isAuthorizedAction(ISSUED.substring(1), ISSUED, "POST"));
    }

    /**
     * An action that writes has to arrive by POST. A GET cannot carry a file and
     * needs no preflight from a browser, so a URL an img tag or a link can
     * reach is not enough on its own, nonce or not.
     */
    @Test
    public void getRequestIsRefused() {
        assertFalse(BaseBean.isAuthorizedAction(ISSUED, ISSUED, "GET"));
        assertFalse(BaseBean.isAuthorizedAction(ISSUED, ISSUED, "post"));
        assertFalse(BaseBean.isAuthorizedAction(ISSUED, ISSUED, null));
    }

    /**
     * The ordering the JSP has to get right: the nonce a page was given is still
     * the one its forms carry until {@code getSerial()} replaces it, and the
     * replacement is what the next page's forms carry.
     */
    @Test
    public void nonceIsReplacedOnlyWhenTheNextPageIsBuilt() {
        BaseBean bean = new BaseBean();
        assertNull("a session that has not rendered a page has no nonce", bean.getLastSerial());
        String issued = bean.getSerial();
        assertEquals(issued, bean.getLastSerial());
        // The page holding the form was built with this nonce, so a POST quoting
        // it runs - this is the state the JSP has to be in when it checks.
        assertTrue(BaseBean.isAuthorizedAction(issued, bean.getLastSerial(), "POST"));
        // Building the next page replaces it, and the old nonce is spent.
        String next = bean.getSerial();
        assertNotEquals("getSerial() must hand out a fresh nonce", issued, next);
        assertEquals(next, bean.getLastSerial());
        assertFalse("the previous page's nonce must stop working",
                    BaseBean.isAuthorizedAction(issued, bean.getLastSerial(), "POST"));
    }
}
