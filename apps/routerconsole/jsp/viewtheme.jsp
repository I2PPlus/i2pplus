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
String contentType;
switch (uri.substring(uri.lastIndexOf(".") + 1)) {
  case "css":
    contentType = "text/css";
    break;
  case "png":
    contentType = "image/png";
    break;
  case "gif":
    contentType = "image/gif";
    break;
  case "jpg":
    contentType = "image/jpeg";
  case "webp":
    contentType = "image/webp";
    break;
  case "ico":
    contentType = "image/x-icon";
    break;
  case "svg":
    contentType = "image/svg+xml; charset=utf-8";
    break;
  case "ttf":
    contentType = "font/ttf";
    break;
  case "woff":
    contentType = "font/woff";
    break;
  case "woff2":
    contentType = "font/woff2";
    break;
  case "html":
    // /javadoc/
    contentType = "text/html";
    break;
  case "js":
    // /javadoc/
    contentType = "application/x-javascript; charset=utf-8";
    break;
  default:
    response.sendError(HttpServletResponse.SC_NOT_FOUND);
    return;
}
response.setContentType(contentType);
response.setCharacterEncoding("UTF-8");
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
        if (themePath != null) {uri = uri.substring(PFX.length() + theme.length());} // /bar/baz
    }
}

String base;
if (themePath != null) {base = themePath;}
else {base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() + java.io.File.separatorChar + "docs";}

java.io.File file = new java.io.File(base, uri);
if (!file.exists()) {
    response.sendError(HttpServletResponse.SC_NOT_FOUND);
    return;
}

long lastmod = file.lastModified();
if (lastmod > 0) {
    long iflast = request.getDateHeader("If-Modified-Since");
    // iflast is -1 if not present; round down file time
    if (iflast >= ((lastmod / 1000) * 1000)) {
        response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
        return;
    }
    response.setDateHeader("Last-Modified", lastmod);
}

if (uri.contains("override.css")) {
    response.setHeader("Cache-Control", "no-cache, private, max-age=2628000");
} else if (uri.contains(".css") || uri.contains(".js") || uri.contains(".png") || uri.contains(".jpg") || uri.contains(".webp") ||
           uri.contains(".svg") || uri.contains(".ico") || uri.contains(".ttf") || uri.contains(".woff2")) {
    response.setHeader("Cache-Control", "private, max-age=2628000, immutable");
} else {response.setHeader("Cache-Control", "no-cache, private, max-age=2628000");}

long length = file.length();
if (length > 0) {response.setHeader("Content-Length", Long.toString(length));}

try {
    net.i2p.util.FileUtil.readFile(uri, base, response.getOutputStream());
    response.getOutputStream().close();
} catch (java.io.IOException ioe) {
    if (!response.isCommitted()) {response.sendError(403, ioe.toString());} // prevent 'Committed' IllegalStateException from Jetty
    else {
        // not an error, happens when the browser closes the stream
        net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(getClass()).warn("Error serving " + uri + " (" + ioe.getMessage() + ")");
        // Jetty doesn't log this
        throw ioe;
    }
}
%>