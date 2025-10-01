<%@ page pageEncoding="UTF-8" buffer="32kb" trimDirectiveWhitespaces="true"%>
<%
  /*
   * USE CAUTION WHEN EDITING
   * Trailing whitespace OR NEWLINE on the last line will cause IllegalStateExceptions !!!
   *
   * Do not tag this file for translation.
   */

    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    net.i2p.util.LogManager logMgr = ctx.logManager();
    logMgr.flush();

    java.io.File logFile = new java.io.File(logMgr.currentFile());
    long length = logFile.length();

    if (length <= 0 || !logFile.isFile()) {
        response.sendError(404, "Not Found");
        return;
    }

    response.setContentType("text/plain");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Accept-Ranges", "none");
    response.setHeader("Content-Length", Long.toString(length));
    response.addHeader("Cache-Control", "no-store");

    java.io.InputStream in = null;
    try {
        in = new java.io.FileInputStream(logFile);
        java.io.OutputStream stream = response.getOutputStream();
        net.i2p.data.DataHelper.copy(in, stream);
    } catch (java.io.IOException ioe) {
        if (!response.isCommitted()) {response.sendError(403, ioe.toString());}
        else {throw ioe;}
    } finally {
        if (in != null) {
            try {in.close();}
            catch (java.io.IOException ignored) {}
        }
    }
%>