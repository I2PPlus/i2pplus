package net.i2p.router.web.servlets;

import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.I2PAppContext;
import net.i2p.servlet.filters.SessionManager;
import net.i2p.util.Log;

/**
 * Lightweight session validation endpoint for AJAX polling.
 * Returns 200 if session is valid, 401 if expired/invalid.
 */
public class SessionCheckServlet extends HttpServlet {
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(SessionCheckServlet.class);

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");
        resp.setContentType("text/plain");

        String token = getSessionCookie(req);
        String username = SessionManager.getInstance().validateSession(token);
        if (username != null) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write("ok");
        } else {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.getWriter().write("expired");
        }
    }

    private String getSessionCookie(HttpServletRequest req) {
        javax.servlet.http.Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (javax.servlet.http.Cookie cookie : cookies) {
            if (SessionManager.SESSION_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
