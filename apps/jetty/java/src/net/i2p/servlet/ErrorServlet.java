package net.i2p.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.ServletException;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.NativeBigInteger;
import net.i2p.util.Translate;

import org.eclipse.jetty.server.Server;

/**
 * Common servlet for errors
 * This is intended for webapps and local plugins.
 * It is not appropriate for eepsites or remotely-accessible plugins,
 * as it uses local console resources.
 *
 * See http://www.eclipse.org/jetty/documentation/current/custom-error-pages.html
 * for how to add to web.xml or see examples in bundled webapps.
 *
 * Init parameters:
 * <ul>
 * <li>CSSPath - absolue URL of CSS to reference, starting with /, defaults to console theme
 * <li>name - webapp or plugin name, will be translated with bundle, e.g. SusiMail
 * <li>bundle - package of translation bundle, e.g. net.i2p.susimail.web.messages
 * </ul>
 *
 * Most strings are translated with the console bundle, as we're using the same strings
 * as in error.jsp and error500.jsp.
 *
 * Supported error codes: 403, 404, and 500+.
 * Others must be added here.
 *
 * @since 0.9.34 adapted from routerconsole error.jsp, error500.jsp, and CSSHelper
 */
public class ErrorServlet extends HttpServlet {

    private static final long serialVersionUID = 99356750L;
    private final I2PAppContext _context;
    private static final String CONSOLE_BUNDLE_NAME = "net.i2p.router.web.messages";
    private static final String PROP_THEME_NAME = "routerconsole.theme";
    private static final String PROP_THEME_PFX = PROP_THEME_NAME + '.';
    private static final String DEFAULT_THEME = "dark";
    private static final String BASE_THEME_PATH = "/themes/console/";
    private static final String DEFAULT_ICO = "images/favicon.svg";
    private static final String DEFAULT_CSS = "console.css";
    /** to be added to head */
    private final String _icoPath = BASE_THEME_PATH + '/' + DEFAULT_ICO;
    private String _cssPath;
    /** for webapp translation */
    private String _webappName;
    private String _bundleName;
    private String _defaultBundle;

    public ErrorServlet() {
        super();
        _context = I2PAppContext.getGlobalContext();
    }

    @Override
    public void init() throws ServletException {
        super.init();
        _cssPath = getInitParameter("CSSPath");
        if (_cssPath == null) {
            String dir = BASE_THEME_PATH + _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME);
            _cssPath = dir + '/' + DEFAULT_CSS;
            String errorCSS = dir + "/errors.css";
        }
        _webappName = getInitParameter("name");
        if (_webappName == null)
            _webappName = "unknown";
        _bundleName = getInitParameter("bundle");
        _defaultBundle = _bundleName != null ? _bundleName : CONSOLE_BUNDLE_NAME;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Accept-Ranges", "none");
        resp.setDateHeader("Expires", 0);
        resp.setHeader("Cache-Control", "no-store, max-age=0, no-cache, must-revalidate");
        resp.setHeader("Pragma", "no-cache");
//        resp.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'none'");
        // add unsafe-inline script-src to allow iframe escape to function
        resp.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; script-src 'self unsafe-inline'; form-action 'none'; frame-ancestors 'self'; object-src 'none'; media-src 'none'");
        Integer ERROR_CODE = (Integer) req.getAttribute("javax.servlet.error.status_code");
        String ERROR_URI = (String) req.getAttribute("javax.servlet.error.request_uri");
        String ERROR_MESSAGE = (String) req.getAttribute("javax.servlet.error.message");
        Throwable ERROR_THROWABLE = (Throwable) req.getAttribute("javax.servlet.error.exception");
        int errorCode = ERROR_CODE != null ? ERROR_CODE.intValue() : 0;
        if (ERROR_CODE != null) {
            resp.setStatus(errorCode);
        }
        if (ERROR_URI == null)
            ERROR_URI = "";
        else
            ERROR_URI = DataHelper.escapeHTML(ERROR_URI);
        if (ERROR_MESSAGE == null)
            ERROR_MESSAGE = "";
        else
            ERROR_MESSAGE = DataHelper.escapeHTML(ERROR_MESSAGE);
        if (errorCode == 404 &&
            (ERROR_URI.endsWith(".png") ||
             ERROR_URI.endsWith(".jpg") ||
             ERROR_URI.endsWith(".gif") ||
             ERROR_URI.endsWith(".ico") ||
             ERROR_URI.endsWith(".svg") ||
             ERROR_URI.endsWith(".txt") ||
             ERROR_URI.endsWith(".js") ||
             ERROR_URI.endsWith(".css"))) {
            // keep it simple
            resp.setContentType("text/plain");
            PrintWriter out = resp.getWriter();
            out.println(_t("Error {0}", 404) + ": " + ERROR_URI + ' ' + _t("not found"));
            out.close();
            return;
        }
        resp.setContentType("text/html");
        PrintWriter out = resp.getWriter();
        out.println("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\">\n");
        out.print("<html>\n<head>\n<title>");
        if (errorCode == 404)
            out.print(_t("Error 404: Page Not Found").replace("Page", "Resource"));
        else
            out.print(_t("Error 500: Internal Error"));
        out.println("</title>\n");
        out.println("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n");
        if (_icoPath != null)
            out.println("<link rel=\"icon\" href=\"" + _icoPath + "\">\n");
        out.println("<link href=\"" + _cssPath + '?' + CoreVersion.VERSION + "\" rel=\"stylesheet\" type=\"text/css\">\n");
        if (errorCSS != null)
            out.println("<link href=\"" + errorCSS + '?' + CoreVersion.VERSION + "\" rel=\"stylesheet\" type=\"text/css\">\n");
        out.println("<script type=\"text/javascript\">if (window.location !== window.top.location) {window.top.location = window.location;}</script>\n"); // breakout of iframe
        out.println("<script type=\"text/javascript\" src=\"/js/iframeResizer/iframeResizer.contentWindow.js\"></script>\n"); // or ensure embedded correctly elsewise
        out.println("</head>\n<body id=\"servletError\">\n");
        out.println("<div class=\"logo\">");
        out.println("<a href=\"/\" title=\"" + _t("Router Console") +
                    "\"><img src=\"" + BASE_THEME_PATH + _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME) + "/images/i2plogo.png\" alt=\"" +
                    _t("I2P Router Console").replace("I2P", "I2P+") + "\" border=\"0\"></a>\n<hr>\n");
        out.println("<a href=\"/config\">" + _t("Configuration") + "</a> <a href=\"/help\">" + _t("Help") + "</a>");
        out.println("</div>\n");
        out.println("<div class=\"warning\" id=\"warning\">\n");
        out.println("<h3>" + _w(_webappName) + ": ");
        if (errorCode == 404)
            out.print(_t("Page Not Found").replace("Page", "Resource"));
        else
            out.print(_t("Internal Server Error"));
        out.print("</h3>\n");
        outputMessage(out, errorCode, ERROR_MESSAGE, ERROR_URI, ERROR_THROWABLE);
        out.println("<span data-iframe-height></span>\n</div>\n</body>\n</html>");
        out.close();
    }

    /**
     *  Needed if the errored page was a POST
     *  @since 0.9.35
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doGet(req, resp);
    }

    /**
     *  Override for specific cases.
     *  This supports 403, 404, and 500+.
     *  Output HTML that goes inside the div.
     *
     *  @param errorCode e.g. 404
     *  @param errorMsg non-null, may be empty, already HTML-escaped
     *  @param errorURI non-null, may be empty, already HTML-escaped
     *  @param errorCause may be null
     */
    protected void outputMessage(PrintWriter out, int errorCode, String errorMsg, String errorURI, Throwable errorCause) {
        if (errorCode == 404) {
            // TODO: if service is available but not started, provide a link to /configclients or /configwebapps and explain the error
            out.println("<p>" + _t("Sorry! You appear to be requesting a non-existent Router Console page or resource.") + "</p>");
            out.println("<hr>");
            out.println("<p><b>" + _t("Error {0}", 404) + ": " + errorURI + "&nbsp;" + _t("not found") + "</b></p>");
        } else if (errorCode == 403 || errorCode >= 500 || errorCause != null) {
            out.println("<p><b>" + _t("Sorry! There has been an internal error.") + "</b></p>");
            out.println("<hr>");
            out.println("<p>");
            out.println(_t("Please report bugs on {0} or {1}",
                           "<a href=\"http://git.idk.i2p/i2p-hackers/i2p.i2p/-/issues\">git.idk.i2p</a>",
                           "<a href=\"https://i2pgit.org/i2p-hackers/i2p.i2p/-/issues\">i2pgit.org</a>"));
            out.print(".</p>");
            out.println("<p>" + _t("Please include this information in bug reports") + ":</p>\n");
            out.print("</div>\n<div class=\"sorry\" id=\"warning2\">\n<h3>");
            out.print(_t("Error Details"));
            out.print("</h3>\n<div id=\"stacktrace\">\n<p>");
            out.print(_t("Error {0}", errorCode) + ": " + errorURI + "&nbsp;" + errorMsg);
            out.print("</p>\n<p>");
            if (errorCause != null) {
                StringWriter sw = new StringWriter(2048);
                PrintWriter pw = new PrintWriter(sw);
                errorCause.printStackTrace(pw);
                pw.flush();
                String trace = sw.toString();
                trace = trace.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                trace = trace.replace("\n", "<br>&nbsp;&nbsp;&nbsp;&nbsp;\n");
                out.print(trace);
            }
            out.print("</p>\n</div>\n<h3>");
            out.print(_t("I2P Version and Running Environment"));
            out.print("</h3>\n<p id=\"sysinfo\">");
            // router puts its version here
            String version = System.getProperty("router.version", CoreVersion.VERSION);
            out.println("<b>I2P version:</b> " + version + "<br>");
            out.println("<b>Java version:</b> " + System.getProperty("java.vendor") + ' ' + System.getProperty("java.version") +
                        " (" + System.getProperty("java.runtime.name") + ' ' + System.getProperty("java.runtime.version") + ")<br>");
            out.println("<b>Wrapper version:</b> " + System.getProperty("wrapper.version", "none") + "<br>");
            try {
                // wrap in case not running on Jetty
                out.println("<b>Server version:</b> " + Server.getVersion() + "<br>");
            }  catch (Throwable t) {}
            out.println("<b>Platform:</b> " + System.getProperty("os.name") + ' ' + System.getProperty("os.arch") +
                        ' ' + System.getProperty("os.version") + "<br>");
            out.println("<b>Processor:</b> " + NativeBigInteger.cpuModel() + " (" + NativeBigInteger.cpuType() + ")<br>");
            out.println("<b>Jbigi:</b> " + NativeBigInteger.loadStatus() + "<br>");
            out.println("<b>Encoding:</b> " + System.getProperty("file.encoding") + "<br>");
            out.println("<b>Charset:</b> " + Charset.defaultCharset().name());
            out.println("</p><p>");
            out.println(_t("Note that system information, log timestamps, and log messages may provide clues to your location; please review everything you include in a bug report."));
            out.println("</p>\n");
        } else {
            out.println("<p>Unsupported error " + errorCode + "</p>\n");
        }
    }

    /** translate a string, with webapp bundle */
    protected String _w(String s) {
        return Translate.getString(s, _context, _defaultBundle);
    }

    /** translate a string, console bundle */
    protected String _t(String s) {
        return Translate.getString(s, _context, CONSOLE_BUNDLE_NAME);
    }

    /** translate a string, console bundle */
    protected String _t(String s, Object o) {
        return Translate.getString(s, o, _context, CONSOLE_BUNDLE_NAME);
    }

    /** translate a string, console bundle */
    protected String _t(String s, Object o, Object o2) {
        return Translate.getString(s, o, o2, _context, CONSOLE_BUNDLE_NAME);
    }
}
