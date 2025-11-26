<%@ page pageEncoding="UTF-8" buffer="32kb" trimDirectiveWhitespaces="true"%>
<%
  /*
   * USE CAUTION WHEN EDITING
   * Trailing whitespace OR NEWLINE on the last line will cause IllegalStateExceptions !!!
   *
   * Do not tag this file for translation.
   */

    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    java.io.File f = net.i2p.router.web.ConfigServiceHandler.wrapperLogFile(ctx);
    long length = f.length();
    if (length <= 0 || !f.isFile()) {
        response.sendError(404, "Not Found");
        return;
    }
    response.setContentType("text/plain");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Content-Length", Long.toString(length));
    response.setHeader("Accept-Ranges", "none");
    response.addHeader("Cache-Control", "no-store");

    java.io.InputStream in = null;
    try {
        in = new java.io.FileInputStream(f);
        java.io.OutputStream bout = response.getOutputStream();
        net.i2p.data.DataHelper.copy(in, bout);
    } catch (java.io.IOException ioe) {
        if (!response.isCommitted()) {
            response.sendError(403, ioe.toString());
        } else {
            throw ioe;
        }
    } finally {
        if (in != null) try { in.close(); } catch (java.io.IOException ignored) {}
    }
%>