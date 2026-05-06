package net.i2p.router.web.servlets;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.I2PAppContext;
import net.i2p.servlet.filters.SessionManager;
import net.i2p.util.Log;

/**
 * Logout servlet to invalidate session.
 */
public class LogoutServlet extends HttpServlet {
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(LogoutServlet.class);

    @Override
    public void init() throws ServletException {
        _log.info("=== LogoutServlet.init() CALLED ===");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        String sessionToken = getSessionCookie(req);
        if (sessionToken != null) {
            SessionManager.getInstance().invalidateSession(sessionToken);
            _log.info("User logged out");
        }

        javax.servlet.http.Cookie sessionCookie = new javax.servlet.http.Cookie(
            SessionManager.SESSION_COOKIE_NAME, "");
        sessionCookie.setPath("/");
        sessionCookie.setMaxAge(0);
        resp.addCookie(sessionCookie);

        resp.sendRedirect(req.getContextPath() + "/login");
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