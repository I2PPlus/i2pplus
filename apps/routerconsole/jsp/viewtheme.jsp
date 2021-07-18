<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Note: this also handles /javadoc/ URLs
 *
 * Do not tag this file for translation.
 */
String uri = request.getRequestURI();
if (uri.endsWith(".css")) {
  response.setContentType("text/css");
  response.setCharacterEncoding("UTF-8");
} else if (uri.endsWith(".png")) {
  response.setContentType("image/png");
} else if (uri.endsWith(".gif")) {
  response.setContentType("image/gif");
} else if (uri.endsWith(".jpg")) {
  response.setContentType("image/jpeg");
} else if (uri.endsWith(".ico")) {
  response.setContentType("image/x-icon");
} else if (uri.endsWith(".svg")) {
  response.setContentType("image/svg+xml; charset=utf-8");
} else if (uri.endsWith(".ttf")) {
  response.setContentType("font/ttf");
} else if (uri.endsWith(".woff")) {
  response.setContentType("font/woff");
} else if (uri.endsWith(".woff2")) {
  response.setContentType("font/woff2");
} else if (uri.endsWith(".html")) {
  // /javadoc/
  response.setContentType("text/html");
  response.setCharacterEncoding("UTF-8");
} else if (uri.endsWith(".js")) {
  // /javadoc/
  response.setContentType("application/x-javascript; charset=utf-8");
  response.setCharacterEncoding("UTF-8");
}
response.setHeader("Accept-Ranges", "none");
response.setHeader("X-Content-Type-Options", "nosniff");
/*
 * User or plugin themes
 * If the request is for /themes/console/foo/bar/baz,
 * and the property routerconsole.theme.foo=/path/to/foo,
 * get the file from /path/to/foo/bar/baz
 */
String themePath = null;
final String PFX = "/themes/console/";
if (uri.startsWith(PFX) && uri.length() > PFX.length() + 1) {
    String theme = uri.substring(PFX.length());
    int slash = theme.indexOf('/');
    if (slash > 0) {
        theme = theme.substring(0, slash);
        themePath = net.i2p.I2PAppContext.getGlobalContext().getProperty("routerconsole.theme." + theme);
        if (themePath != null)
            uri = uri.substring(PFX.length() + theme.length()); // /bar/baz
    }
}
String base;
if (themePath != null)
    base = themePath;
else
    base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() +
              java.io.File.separatorChar + "docs";
java.io.File file = new java.io.File(base, uri);
long lastmod = file.lastModified();
if (lastmod > 0) {
    long iflast = request.getDateHeader("If-Modified-Since");
    // iflast is -1 if not present; round down file time
    if (iflast >= ((lastmod / 1000) * 1000)) {
        response.setStatus(304);
        return;
    }
    response.setDateHeader("Last-Modified", lastmod);
    if (uri.contains("override.css")) {
        response.setHeader("Cache-Control", "no-store");
    } else if (uri.contains(".css") || uri.contains(".js") || uri.contains(".png") || uri.contains(".jpg")
               || uri.contains(".svg") || uri.contains(".ico") || uri.contains(".ttf") || uri.contains(".woff2")) {
        response.setHeader("Cache-Control", "private, max-age=2628000, immutable");
    } else {
        response.setHeader("Cache-Control", "no-cache, private, max-age=2628000");
    }
}
long length = file.length();
if (length > 0)
    response.setHeader("Content-Length", Long.toString(length));
try {
    net.i2p.util.FileUtil.readFile(uri, base, response.getOutputStream());
} catch (java.io.IOException ioe) {
    // prevent 'Committed' IllegalStateException from Jetty
    if (!response.isCommitted()) {
        response.sendError(403, ioe.toString());
    }  else {
        // not an error, happens when the browser closes the stream
        net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(getClass()).warn("Error serving " + uri, ioe);
        // Jetty doesn't log this
        throw ioe;
    }
}
%>