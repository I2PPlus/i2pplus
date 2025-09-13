<%@ page import="javax.servlet.http.HttpServletResponse" %>
<%
  /*
   * USE CAUTION WHEN EDITING
   * Trailing whitespace OR NEWLINE on the last line will cause IllegalStateExceptions !!!
   *
   * Do not tag this file for translation.
   */

    // Determine Content-Type by file extension
    String uri = request.getRequestURI();
    String ext = uri.substring(uri.lastIndexOf('.') + 1);
    String contentType;
    switch (ext) {
        case "css": contentType = "text/css"; break;
        case "png": contentType = "image/png"; break;
        case "gif": contentType = "image/gif"; break;
        case "jpg": contentType = "image/jpeg"; break;
        case "webp": contentType = "image/webp"; break;
        case "ico": contentType = "image/x-icon"; break;
        case "svg": contentType = "image/svg+xml; charset=utf-8"; break;
        case "ttf": contentType = "font/ttf"; break;
        case "woff": contentType = "font/woff"; break;
        case "woff2": contentType = "font/woff2"; break;
        case "html": contentType = "text/html"; break;
        case "js": contentType = "application/x-javascript; charset=utf-8"; break;
        default:
            response.sendError(HttpServletResponse.SC_NOT_FOUND); return;
    }
    response.setContentType(contentType);
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Accept-Ranges", "none");
    response.setHeader("X-Content-Type-Options", "nosniff");

    // Resolve theme path if applicable (/themes/console/<theme>/...)
    String themePath = null, PFX = "/themes/console/";
    if (uri.startsWith(PFX) && uri.length() > PFX.length() + 1) {
        String theme = uri.substring(PFX.length());
        int slash = theme.indexOf('/');
        if (slash > 0) {
            theme = theme.substring(0, slash);
            themePath = net.i2p.I2PAppContext.getGlobalContext().getProperty("routerconsole.theme." + theme);
            if (themePath != null) uri = uri.substring(PFX.length() + theme.length());
        }
    }
    String base = (themePath != null) ? themePath
                  : net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath()
                    + java.io.File.separatorChar + "docs";

    java.io.File file = new java.io.File(base, uri);
    if (!file.exists()) { response.sendError(HttpServletResponse.SC_NOT_FOUND); return; }

    // Check and handle If-Modified-Since for caching
    long lastMod = file.lastModified();
    if (lastMod > 0) {
        long ifModSince = request.getDateHeader("If-Modified-Since");
        if (ifModSince >= (lastMod / 1000) * 1000) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }
        response.setDateHeader("Last-Modified", lastMod);
    }

    // Set cache-control based on file type
    if (uri.contains("override.css"))
        response.setHeader("Cache-Control", "no-cache, private, max-age=2628000");
    else if (uri.matches(".*(\\.css|\\.js|\\.png|\\.jpg|\\.webp|\\.svg|\\.ico|\\.ttf|\\.woff2)$"))
        response.setHeader("Cache-Control", "private, max-age=2628000, immutable");
    else
        response.setHeader("Cache-Control", "no-cache, private, max-age=2628000");

    // Content length header for client info
    long length = file.length();
    if (length > 0) response.setHeader("Content-Length", Long.toString(length));

    // Stream file contents to response with safe error handling
    try (java.io.OutputStream stream = response.getOutputStream()) {
        net.i2p.util.FileUtil.readFile(uri, base, stream);
    } catch (java.io.IOException ioe) {
        if (!response.isCommitted()) response.sendError(403, ioe.toString());
        else {
            net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(getClass()).warn("Error serving " + uri + " (" + ioe.getMessage() + ")");
            throw ioe;
        }
    }
%>