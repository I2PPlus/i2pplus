package net.i2p.router.web.servlets;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Cookie;
import net.i2p.I2PAppContext;
import net.i2p.router.web.CSSHelper;
import net.i2p.servlet.filters.SessionManager;
import net.i2p.util.Log;

/**
 * Logout servlet to invalidate session.
 */
public class LogoutServlet extends HttpServlet {
/**
 * Explicit no-arg constructor, as the servlet container requires.
 */
public LogoutServlet() {
}
    /** Logger for this class. */
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(LogoutServlet.class);

    @Override
    public void init() throws ServletException {
        _log.info("=== LogoutServlet.init() CALLED ===");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        handle(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        handle(req, resp);
    }

    /**
     * Logs the console out, or refuses to.
     *
     * @param req the request, GET or POST
     * @param resp the response
     * @throws IOException if the response's redirect cannot be written
     */
    private void handle(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");
        resp.setDateHeader("Expires", 0);
        if (!nonceMatches(req)) {
            // Nothing was invalidated and no cookie was cleared, so the operator
            // is still logged in and back at the console.
            resp.sendRedirect(req.getContextPath() + "/");
            return;
        }

        String sessionToken = SessionManager.getSessionCookie(req);
        if (sessionToken != null) {
            SessionManager.getInstance().invalidateSession(sessionToken);
            _log.info("User logged out");
        }

        Cookie sessionCookie = new Cookie(
            SessionManager.SESSION_COOKIE_NAME, "");
        sessionCookie.setPath("/");
        sessionCookie.setMaxAge(0);
        resp.addCookie(sessionCookie);

        String loginPath = req.getContextPath() + "/login";
        resp.sendRedirect(loginPath);
    }

    /**
     * Does this request carry the console's form nonce - the one thing that says
     * the operator clicked the logout link rather than some page they opened
     * asking for the console by URL.
     * <p>
     * Logout used to be a bare GET, and a GET needs no token and no cooperation
     * from the page that issues it: an {@code <img src=http://127.0.0.1:7657/logout>}
     * on any page the operator visits, or a link in a mail, ended the session
     * with nothing clicked at all. The home page now puts the nonce it already
     * holds into the logout link, and a request without one - or with one this
     * session has already used - does nothing. Validation consumes the nonce, so
     * the same link cannot be replayed.
     * <p>
     * Package-visible and static so the decision can be tested without a console.
     *
     * @param req the request, GET or POST
     * @return true if the request carries a nonce this session issued and has not
     *         used
     * @since 0.9.72+
     */
    static boolean nonceMatches(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return CSSHelper.validateNonce(session, req.getParameter("nonce"));
    }
}
