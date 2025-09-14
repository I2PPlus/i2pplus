<%@ page pageEncoding="UTF-8" buffer="32kb"%>
<%
  /*
   * USE CAUTION WHEN EDITING
   * Trailing whitespace OR NEWLINE on the last line will cause IllegalStateExceptions !!!
   *
   * Do not tag this file for translation.
   */

    response.setContentType("text/plain");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Accept-Ranges", "none");
    response.addHeader("Cache-Control", "private, no-cache, max-age=86400");

    java.io.File base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir();
    String name = "LICENSE.txt";
    java.io.File file = new java.io.File(base, name);

    if (!file.exists()) {
        if (!net.i2p.util.SystemVersion.isWindows() && !net.i2p.util.SystemVersion.isMac()) {
            java.io.File b = new java.io.File("/usr/share/doc/i2p-router");
            java.io.File f = new java.io.File(b, "copyright");
            if (f.exists()) {
                name = "copyright";
                base = b;
                file = f;
            } else {
                response.sendError(404, "Not Found");
                return;
            }
        } else {
            response.sendError(404, "Not Found");
            return;
        }
    }

    long length = file.length();
    if (length > 0)
        response.setHeader("Content-Length", Long.toString(length));

    try {
        net.i2p.util.FileUtil.readFile(name, base.getAbsolutePath(), response.getOutputStream());
    } catch (java.io.IOException ioe) {
        if (!response.isCommitted()) {
            response.sendError(403, ioe.toString());
        } else {
            net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(getClass()).warn("Error serving license.txt", ioe);
            throw ioe;
        }
    }
%>