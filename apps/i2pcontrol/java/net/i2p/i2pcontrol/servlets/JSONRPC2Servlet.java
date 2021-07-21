package net.i2p.i2pcontrol.servlets;
/*
 *  Copyright 2011 hottuna (dev@robertfoss.se)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

import com.thetransactioncompany.jsonrpc2.*;
import com.thetransactioncompany.jsonrpc2.server.Dispatcher;

import net.i2p.I2PAppContext;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;

import net.i2p.i2pcontrol.I2PControlVersion;
import net.i2p.i2pcontrol.security.KeyStoreProvider;
import net.i2p.i2pcontrol.security.SecurityManager;
import net.i2p.i2pcontrol.servlets.jsonrpc2handlers.*;
import net.i2p.i2pcontrol.servlets.configuration.ConfigurationManager;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;


/**
 * Provide an JSON-RPC 2.0 API for remote controlling of I2P
 */
public class JSONRPC2Servlet extends HttpServlet {

    private static final long serialVersionUID = -45075606818515212L;
    private static final int BUFFER_LENGTH = 2048;
    private static final String SVC_HTTP_I2PCONTROL = "http_i2pcontrol";
    private static final String SVC_HTTPS_I2PCONTROL = "https_i2pcontrol";
    private Dispatcher disp;
    private Log _log;
    private final SecurityManager _secMan;
    private final ConfigurationManager _conf;
    private final JSONRPC2Helper _helper;
    private final RouterContext _context;
    private final boolean _isWebapp;
    private boolean _isHTTP, _isHTTPS;

    /**
     *  Webapp
     */
    public JSONRPC2Servlet() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        if (!ctx.isRouterContext())
            throw new IllegalStateException();
        _context = (RouterContext) ctx;
        File appDir = ctx.getAppDir();
        _conf = new ConfigurationManager(ctx, appDir, false);
        // we don't really need a keystore
        //File ksDir = new File(ctx.getConfigDir(), "keystore");
        //ksDir.mkDir();
        //KeyStoreProvider ksp = new KeyStoreProvider(ksDir.getAbsolutePath());
        //_secMan = new SecurityManager(ctx, ksp, _conf);
        _secMan = new SecurityManager(ctx, null, _conf);
        _helper = new JSONRPC2Helper(_secMan);
        _log = ctx.logManager().getLog(JSONRPC2Servlet.class);
        _conf.writeConfFile();
        _isWebapp = true;
    }

    /**
     *  Plugin
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
        disp.register(new I2PControlHandler(_context, _helper, _secMan));
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

    @Override
    protected void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException {
        setHeaders(httpServletResponse);
        PrintWriter out = httpServletResponse.getWriter();
        out.println("<!DOCTYPE html>\n");
        out.println("<html>\n<head>\n");
        out.println("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">");
        out.println("<title>I2PControl - I2P+</title>");
        out.println("<link rel=\"icon\" href=\"/themes/console/midnight/images/favicon.svg\">");
        out.println("<link href=\"/themes/i2pcontrol/i2pcontrol.css\" rel=\"stylesheet\" type=\"text/css\">");
        out.println("</head>\n<body>");
        out.println("<h1>I2PControl RPC Service&nbsp;&nbsp;<a href=\"/configwebapps\" title=\"Control and configure service\">" +
                    "<img src=\"/themes/console/images/info/configure.png\" height=\"16\" width=\"16\"></a></h1>");
        out.println("<p><b>Version:</b> " + I2PControlVersion.VERSION + "<br>");
        out.println("<b>Status:</b> Running</p>");
       if ("/password".equals(httpServletRequest.getServletPath())) {
            out.println("<hr>\n<form method=\"POST\" action=\"password\">\n");
            if (_secMan.isDefaultPasswordValid()) {
                out.println("<p>The current API password is the default, \"" + _secMan.DEFAULT_AUTH_PASSWORD + "\". You should change it.</p>");
            } else {
                out.println("<p><span class=\"prompt\">Current API password:</span> <input name=\"password\" type=\"password\"></p>");
            }
            out.println("<p><span class=\"prompt\">New API password:</span> <input name=\"password2\" type=\"password\" placeholder=\"new password\">&nbsp;" +
                        "<input name=\"password3\" type=\"password\" placeholder=\"confirm new password\">" + "</p>\n" +
                        "<p id=\"password\"><input name=\"save\" type=\"submit\" value=\"Change API Password\"></p>\n" +
                        "<hr><p class=\"infohelp\">If you forget the API password, stop I2PControl, delete the config file and restart I2PControl.\n" +
                        "<br><b>Location:</b> <code>" + _conf.getConfFile() + "</code></p>\n</form>");
        } else {
            out.println("<hr><p id=\"password\"><a href=\"password\">Change API Password</a></p>");
        }
        out.println("</body>\n</html>");
        out.close();
    }


    /**
     *  @since 0.9.48
     */
    private static void setHeaders(HttpServletResponse resp) {
        resp.setContentType("text/html");
        resp.setHeader("X-Frame-Options", "SAMEORIGIN");
        resp.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; form-action 'self'; frame-ancestors 'self'; object-src 'none'; media-src 'none'");
        resp.setHeader("X-XSS-Protection", "1; mode=block");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Pragma", "no-cache");
        resp.setHeader("Cache-Control","no-cache");
    }

    /** @since 0.12 */
    private void doPasswordChange(HttpServletRequest req, HttpServletResponse httpServletResponse) throws ServletException, IOException {
        setHeaders(httpServletResponse);
        PrintWriter out = httpServletResponse.getWriter();
        String pw = req.getParameter("password");
        if (pw == null)
            pw = _secMan.DEFAULT_AUTH_PASSWORD;
        else
            pw = pw.trim();
        String pw2 = req.getParameter("password2");
        String pw3 = req.getParameter("password3");
        out.println("<!DOCTYPE html>");
        out.println("<html>\n<head>");
        out.println("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">");
        out.println("<title>I2PControl - I2P+</title>");
        out.println("<link rel=\"icon\" href=\"/themes/console/midnight/images/favicon.svg\">");
        out.println("<link href=\"/themes/i2pcontrol/i2pcontrol.css\" rel=\"stylesheet\" type=\"text/css\">");
        out.println("</head>\n<body>");
        out.println("<h1>I2PControl RPC Service&nbsp;&nbsp;<a href=\"/configwebapps\" title=\"Control and configure service\">" +
                    "<img src=\"/themes/console/images/svg/configure.svg\" height=\"16\" width=\"16\"></a></h1>");
        if (pw2 == null || pw3 == null) {
            out.println("<p>Please fill in both fields&hellip;</p>");
        } else {
            pw2 = pw2.trim();
            pw3 = pw3.trim();
            if (!pw2.equals(pw3)) {
                out.println("<p>New passwords don't match!</p>");
            } else if (pw2.length() <= 0) {
                out.println("<p>Enter new password twice!</p>");
            } else if (_secMan.isValid(pw)) {
                _secMan.setPasswd(pw2);
                out.println("<p>API Password changed</p>");
            } else {
                out.println("<p>Incorrect old password, not changed</p>");
            }
        }
        out.println("<hr>\n<p id=\"password\"><a href=\"password\">Change API Password</a></p>");
        out.println("</body>\n</html>");
    }

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

    private String getRequest(ServletInputStream sis) throws IOException {
        Writer writer = new StringWriter();

        BufferedReader reader = new BufferedReader(new InputStreamReader(sis, "UTF-8"));
        char[] readBuffer = new char[BUFFER_LENGTH];
        int n;
        while ((n = reader.read(readBuffer)) != -1) {
            writer.write(readBuffer, 0, n);
        }
        return writer.toString();
    }
}
