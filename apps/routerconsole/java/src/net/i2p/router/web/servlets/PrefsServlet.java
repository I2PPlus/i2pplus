package net.i2p.router.web.servlets;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.I2PAppContext;
import net.i2p.router.Router;
import net.i2p.util.Log;

/**
 * Handles theme and language preference updates via AJAX.
 */
public class PrefsServlet extends HttpServlet {
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(PrefsServlet.class);

    private static final String PROP_THEME = "routerconsole.theme";
    private static final String PROP_LANG = "routerconsole.lang";

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        _log.error("PrefsServlet.doPost CALLED, params: " + req.getParameterMap());
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!(ctx instanceof net.i2p.router.RouterContext)) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        net.i2p.router.RouterContext rctx = (net.i2p.router.RouterContext) ctx;
        Router router = rctx.router();

        String theme = req.getParameter("theme");
        String lang = req.getParameter("lang");

        if (theme != null && !theme.isEmpty()) {
            router.saveConfig(PROP_THEME, theme);
            _log.info("Theme changed to: " + theme);
        }
        if (lang != null && !lang.isEmpty()) {
            router.saveConfig(PROP_LANG, lang);
            _log.info("Language changed to: " + lang);
        }
        resp.setContentType("application/json");
        resp.getWriter().write("{\"success\":true}");
    }
}