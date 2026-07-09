package net.i2p.router.web.servlets;

import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.I2PAppContext;
import net.i2p.router.RouterContext;
import net.i2p.router.web.ConsolePasswordManager;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.servlet.filters.SessionManager;
import net.i2p.util.Log;

/**
 * Lightweight session validation endpoint for AJAX polling.
 * Returns 200 if session is valid, 401 if expired/invalid.
 * Returns 200 if no password is configured (no auth required).
 */
public class SessionCheckServlet extends HttpServlet {
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(SessionCheckServlet.class);

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");
        resp.setContentType("text/plain");

        // No password configured — no auth required, always ok
        if (!hasAnyPassword()) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write("ok");
            return;
        }

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

    private boolean hasAnyPassword() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!(ctx instanceof RouterContext)) return false;
        RouterContext rctx = (RouterContext) ctx;
        ConsolePasswordManager mgr = new ConsolePasswordManager(rctx);
        if (!mgr.getMD5(RouterConsoleRunner.PROP_CONSOLE_PW).isEmpty()) {
            return true;
        }
        String pfx = RouterConsoleRunner.PROP_CONSOLE_PW + ".";
        for (java.util.Map.Entry<String, String> e : rctx.router().getConfigMap().entrySet()) {
            String key = e.getKey();
            if (key != null && key.startsWith(pfx) && key.endsWith(".pbkdf2")) {
                String val = e.getValue();
                if (val != null && !val.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
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
