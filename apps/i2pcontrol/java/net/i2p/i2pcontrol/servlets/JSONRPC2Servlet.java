package net.i2p.i2pcontrol.servlets;
/*
 * Copyright 2011 hottuna (dev@robertfoss.se)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.thetransactioncompany.jsonrpc2.*;
import com.thetransactioncompany.jsonrpc2.server.Dispatcher;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.i2pcontrol.I2PControlVersion;
import net.i2p.i2pcontrol.security.SecurityManager;
import net.i2p.i2pcontrol.servlets.configuration.ConfigurationManager;
import net.i2p.i2pcontrol.servlets.jsonrpc2handlers.*;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;
import net.i2p.util.RandomSource;

import java.nio.charset.StandardCharsets;
/**
 * Provide an JSON-RPC 2.0 API for remote controlling of I2P
 */
public class JSONRPC2Servlet extends HttpServlet {

    /** Pinned for serialized-form compatibility. */
    private static final long serialVersionUID = -45075606818515212L;
    /** Bytes read at a time from the request. */
    private static final int BUFFER_LENGTH = 2048;
    /** Service name checked for HTTP access. */
    private static final String SVC_HTTP_I2PCONTROL = "http_i2pcontrol";
    /** Service name checked for HTTPS access. */
    private static final String SVC_HTTPS_I2PCONTROL = "https_i2pcontrol";
    /** Bytes in a password-form nonce, rendered as Base64. */
    private static final int NONCE_BYTES = 16;
    /**
     * How long a password-form nonce stays usable. Long enough to type two
     * passwords into the form, short enough that a token left in a page that is
     * still open cannot be replayed for hours.
     */
    private static final long FORM_NONCE_TTL_MS = 10 * 60 * 1000L;
    /**
     * Password-form nonces this servlet has handed out, nonce -&gt; the time it
     * stops being usable. Held here rather than in the session because the
     * standalone server has no session manager at all: it wires a plain
     * ServletHandler, so getSession() throws there.
     */
    private final Map<String, Long> _formNonces = new ConcurrentHashMap<String, Long>(8);
    /** JSON-RPC 2.0 request dispatcher. */
    private Dispatcher disp;
    /** Class logger. */
    private Log _log;
    /** Security manager. */
    private final SecurityManager _secMan;
    /** Configuration manager. */
    private final ConfigurationManager _conf;
    /** JSON-RPC 2.0 helper. */
    private final JSONRPC2Helper _helper;
    /** Router context. */
    private final RouterContext _context;
    /** Whether running as a webapp. */
    private final boolean _isWebapp;
    /** Whether HTTP is available. */
    private boolean _isHTTP;
    /** Whether HTTPS is available. */
    private boolean _isHTTPS;

    /**
     * Webapp form: requires a router context and builds its own config and
     * security managers.
     */
    public JSONRPC2Servlet() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!ctx.isRouterContext())
            throw new IllegalStateException();
        _context = (RouterContext) ctx;
        File appDir = ctx.getAppDir();
        _conf = new ConfigurationManager(ctx, appDir, false);
        _secMan = new SecurityManager(ctx, null, _conf);
        _helper = new JSONRPC2Helper(_secMan);
        _log = ctx.logManager().getLog(JSONRPC2Servlet.class);
        _conf.writeConfFile();
        _isWebapp = true;
    }

    /**
     * Plugin form: takes the host application's context and security manager,
     * and creates no config file.
     *
     * @param ctx the router context, may be null when there is none available
     * @param secMan the security manager backing the authentication handler
     */
    public JSONRPC2Servlet(RouterContext ctx, SecurityManager secMan) {
        _context = ctx;
        _secMan = secMan;
        _helper = new JSONRPC2Helper(_secMan);
        if (ctx != null)
            _log = ctx.logManager().getLog(JSONRPC2Servlet.class);
        else
            _log = I2PAppContext.getGlobalContext().logManager().getLog(JSONRPC2Servlet.class);
        _conf = null;
        _isWebapp = false;
    }

    /** Register JSON-RPC 2.0 request handlers. */
    @Override
    public void init() throws ServletException {
        super.init();
        disp = new Dispatcher();
        disp.register(new EchoHandler(_helper));
        disp.register(new GetRateHandler(_helper));
        disp.register(new AuthenticateHandler(_helper, _secMan));
        disp.register(new NetworkSettingHandler(_context, _helper));
        disp.register(new RouterInfoHandler(_context, _helper));
        disp.register(new RouterManagerHandler(_context, _helper));
        disp.register(new I2PControlHandler(_helper, _secMan));
        disp.register(new AdvancedSettingsHandler(_context, _helper));
        if (_isWebapp) {
            PortMapper pm = _context.portMapper();
            int port = pm.getPort(PortMapper.SVC_CONSOLE);
            if (port > 0) {
                String host = pm.getHost(PortMapper.SVC_CONSOLE, "127.0.0.1");
                pm.register(SVC_HTTP_I2PCONTROL, host, port);
                _isHTTP = true;
            }
            port = pm.getPort(PortMapper.SVC_HTTPS_CONSOLE);
            if (port > 0) {
                String host = pm.getHost(PortMapper.SVC_HTTPS_CONSOLE, "127.0.0.1");
                pm.register(SVC_HTTPS_I2PCONTROL, host, port);
                _isHTTPS = true;
            }
        }
    }

    /** Clean up port mappings and stop timed events. */
    @Override
    public void destroy() {
        if (_isWebapp) {
            PortMapper pm = _context.portMapper();
            if (_isHTTP)
                pm.unregister(SVC_HTTP_I2PCONTROL);
            if (_isHTTPS)
                pm.unregister(SVC_HTTPS_I2PCONTROL);
            _secMan.stopTimedEvents();
            _conf.writeConfFile();
        }
        super.destroy();
    }

    /** Serve the I2PControl status page. */
    @Override
    protected void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException {
        setHeaders(httpServletResponse);
        PrintWriter out = httpServletResponse.getWriter();
        out.println("<!DOCTYPE html>\n");
        out.println("<html>\n<head>\n");
        out.println("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">");
        out.println("<title>I2PControl - I2P+</title>");
        out.println("<link rel=icon href=\"/themes/console/midnight/images/favicon.svg\">");
        out.println("<link href=\"/themes/i2pcontrol/i2pcontrol.css\" rel=stylesheet>");
        out.println("</head>\n<body>");
        out.println("<h1>I2PControl RPC Service&nbsp;&nbsp;<a href=\"/configwebapps\" title=\"Control and configure service\">" +
                    "<img src=\"/themes/console/images/configure.svg\" height=16 width=16></a></h1>");
        out.println("<p><b>Version:</b> " + I2PControlVersion.VERSION + "<br>");
        out.println("<b>Status:</b> Running</p>");
       if ("/password".equals(httpServletRequest.getServletPath())) {
            out.println("<hr>\n<form method=POST action=\"password\">\n");
            out.println("<input name=\"nonce\" type=hidden value=\"" + mintFormNonce() + "\">\n");
            out.println("<p><span class=prompt>Current API password:</span> <input name=\"password\" type=password></p>");
            if (_secMan.isDefaultPasswordValid()) {
                out.println("<p>The current API password is the default, \"" + _secMan.DEFAULT_AUTH_PASSWORD + "\". You should change it.</p>");
            }
            out.println("<p><span class=prompt>New API password:</span> <input name=\"password2\" type=password placeholder=\"new password\">&nbsp;" +
                        "<input name=\"password3\" type=password placeholder=\"confirm new password\">" + "</p>\n" +
                        "<p id=password><input name=\"save\" type=submit value=\"Change API Password\"></p>\n" +
                        "<hr><p class=infohelp>If you forget the API password, stop I2PControl, delete the config file and restart I2PControl.\n" +
                        "<br><b>Location:</b> <code>" + _conf.getConfFile() + "</code></p>\n</form>");
        } else {
            out.println("<hr><p id=password><a href=\"password\">Change API Password</a></p>");
        }
        out.println("</body>\n</html>");
        out.close();
    }

    /**
     * HTTP security headers.
     *
     * @param resp the response to add the security headers to
     */
    private static void setHeaders(HttpServletResponse resp) {
        resp.setContentType("text/html");
        resp.setHeader("X-Frame-Options", "SAMEORIGIN");
        resp.setHeader("Content-Security-Policy", "default-src 'self'; object-src 'none'; media-src 'none'; form-action 'self'");
        resp.setHeader("X-XSS-Protection", "1; mode=block");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        // The page names the config file that holds the API password and carries
        // the form token, so nothing of it may be kept by the browser or a proxy.
        resp.setHeader("Cache-Control", "private, no-store");
    }

    /**
     * Handle API password change form submission.
     *
     * @param req the form submission carrying the nonce, password, password2 and
     *            password3 fields
     * @param httpServletResponse the response the result page is written to
     * @throws IOException if the response writer cannot be obtained
     * @throws ServletException declared for the servlet API, never thrown here
     */
    private void doPasswordChange(HttpServletRequest req, HttpServletResponse httpServletResponse) throws ServletException, IOException {
        setHeaders(httpServletResponse);
        if (!nonceMatches(_formNonces, req.getParameter("nonce"), System.currentTimeMillis())) {
            _log.warnThrottled("i2pcontrol-nonce-fail", "API password change refused -> No usable form token");
            httpServletResponse.sendError(403, "Missing or stale form token");
            return;
        }
        PrintWriter out = httpServletResponse.getWriter();
        String pw = req.getParameter("password");
        String pw2 = req.getParameter("password2");
        String pw3 = req.getParameter("password3");
        out.println("<!DOCTYPE html>");
        out.println("<html>\n<head>");
        out.println("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">");
        out.println("<title>I2PControl - I2P+</title>");
        out.println("<link rel=icon href=\"/themes/console/midnight/images/favicon.svg\">");
        out.println("<link href=\"/themes/i2pcontrol/i2pcontrol.css\" rel=stylesheet>");
        out.println("</head>\n<body>");
        out.println("<h1>I2PControl RPC Service&nbsp;&nbsp;<a href=\"/configwebapps\" title=\"Control and configure service\">" +
                    "<img src=\"/themes/console/images/configure.svg\" height=16 width=16></a></h1>");
        String err = passwordFormError(pw, pw2, pw3);
        if (err != null) {
            out.println("<p>" + err + "</p>");
        } else if (_secMan.isValid(pw.trim())) {
            _secMan.setPasswd(pw2.trim());
            out.println("<p>API Password changed</p>");
        } else {
            out.println("<p>Incorrect old password, not changed</p>");
        }
        out.println("<hr>\n<p id=password><a href=\"password\">Change API Password</a></p>");
        out.println("</body>\n</html>");
    }

    /**
     * Check the three password fields of the change form before any of them is
     * compared against the stored password.
     * <p>
     * The current password is required. The form used to hide its field while
     * the default password was in place, and this handler substituted the
     * default for a missing password, so a cross-origin POST carrying only the
     * two new-password fields - a CORS simple request, needing no preflight -
     * replaced the API password without knowing it.
     *
     * @param pw the current password as submitted, null if the field was left out
     * @param pw2 the new password, null if the field was left out
     * @param pw3 the new password again, null if the field was left out
     * @return null if the change may go ahead, otherwise the message to show the
     *         user, already marked up for the page
     * @since 0.9.72+
     */
    static String passwordFormError(String pw, String pw2, String pw3) {
        if (pw == null || pw.trim().isEmpty()) {
            return "Enter the current API password!";
        }
        if (pw2 == null || pw3 == null) {
            return "Please fill in both fields&hellip;";
        }
        if (pw2.trim().isEmpty()) {
            return "Enter new password twice!";
        }
        if (!pw2.trim().equals(pw3.trim())) {
            return "New passwords don't match!";
        }
        return null;
    }

    /**
     * Hand out a single-use token for the password form, and drop the tokens no
     * browser ever submitted.
     *
     * @return the token to put in the form's hidden field
     */
    private String mintFormNonce() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : _formNonces.entrySet()) {
            if (entry.getValue() < now) {
                _formNonces.remove(entry.getKey(), entry.getValue());
            }
        }
        byte[] buf = new byte[NONCE_BYTES];
        RandomSource.getInstance().nextBytes(buf);
        String nonce = Base64.encode(buf);
        _formNonces.put(nonce, now + FORM_NONCE_TTL_MS);
        return nonce;
    }

    /**
     * Is this a token the given store handed out and not yet used? A match is
     * consumed here, so the same submission cannot be replayed.
     *
     * @param nonces the tokens handed out, token -&gt; the time each stops being usable
     * @param submitted the token from the form, null if the field was left out
     * @param now the current time in ms
     * @return true if the token is present, unused and unexpired
     * @since 0.9.72+
     */
    static boolean nonceMatches(Map<String, Long> nonces, String submitted, long now) {
        if (submitted == null || submitted.isEmpty()) {
            return false;
        }
        Long expiry = nonces.remove(submitted);
        return expiry != null && expiry >= now;
    }

    /** Handle JSON-RPC 2.0 requests and password changes. */
    @Override
    protected void doPost(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException {
   if ("/password".equals(httpServletRequest.getServletPath())) {
            doPasswordChange(httpServletRequest, httpServletResponse);
            return;
        }
        String req = getRequest(httpServletRequest.getInputStream());
        httpServletResponse.setContentType("application/json");
        PrintWriter out = httpServletResponse.getWriter();
        JSONRPC2Message msg = null;
        JSONRPC2Response jsonResp = null;
        try {
            msg = JSONRPC2Message.parse(req);

            if (msg instanceof JSONRPC2Request) {
                jsonResp = disp.process((JSONRPC2Request)msg, null);
                jsonResp.toJSONObject().put("API", I2PControlVersion.API_VERSION);
                if (_log.shouldDebug()) {
                    _log.debug("Request: " + msg);
                    _log.debug("Response: " + jsonResp);
                }
            }
            else if (msg instanceof JSONRPC2Notification) {
                disp.process((JSONRPC2Notification)msg, null);
                if (_log.shouldDebug())
                    _log.debug("Notification: " + msg);
            }

            out.println(jsonResp);
            out.close();
        } catch (JSONRPC2ParseException e) {
            _log.error("Unable to parse JSONRPC2Message: " + e.getMessage());
        }
    }

    /**
     * Reads the whole request body as text.
     *
     * @param sis the stream to read from
     * @return the request body
     * @throws IOException if the stream cannot be read
     */
    private String getRequest(ServletInputStream sis) throws IOException {
        Writer writer = new StringWriter();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(sis, StandardCharsets.UTF_8))) {
            char[] readBuffer = new char[BUFFER_LENGTH];
            int n;
            while ((n = reader.read(readBuffer)) != -1) {
                writer.write(readBuffer, 0, n);
            }
        }
        return writer.toString();
    }
}
