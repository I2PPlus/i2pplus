<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="8kb"%>
<%
  /*
   * USE CAUTION WHEN EDITING
   * Trailing whitespace OR NEWLINE on the last line will cause
   * IllegalStateExceptions !!!
   *
   * Do not tag this file for translation.
   */

    String c = request.getParameter("c");
    if (c != null && (c.length() == 2 || c.length() == 7) && c.matches("[a-z0-9_]+")) {
        if ("a0".equals(c) || "a1".equals(c) || "a2".equals(c)) c = "xx";
        else if ("bl".equals(c) || "gf".equals(c) || "gp".equals(c) || "mf".equals(c) || "mq".equals(c) || "pm".equals(c) || "re".equals(c) || "wf".equals(c) || "yt".equals(c)) c = "fr";
        else if ("bq".equals(c)) c = "nl";
        else if ("bv".equals(c) || "sj".equals(c)) c = "no";
        else if ("hm".equals(c)) c = "au";
        else if ("um".equals(c)) c = "us";

        String flagSet = "flags_svg";
        String ext = ".svg";
        java.io.File ffile;
        long lastmod = 0;
        java.io.InputStream fin = flags_jsp.class.getResourceAsStream("/net/i2p/router/web/resources/icons/" + flagSet + '/' + c + ext);
        if (fin != null) {
            java.io.File war = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "webapps/routerconsole.war");
            ffile = null;
            lastmod = war.lastModified();
        } else {
            String base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() + java.io.File.separator + "docs" + java.io.File.separator + "icons";
            String file = "flags" + java.io.File.separator + c + ".png";
            ffile = new java.io.File(base, file);
            long length = ffile.length();
            if (length <= 0) {
                response.sendError(403, "Flag not found");
                return;
            }
            response.setHeader("Content-Length", Long.toString(length));
            lastmod = ffile.lastModified();
        }
        if (lastmod > 0) {
            long iflast = request.getDateHeader("If-Modified-Since");
            if (iflast >= (lastmod / 1000) * 1000) {
                response.setStatus(304);
                if (fin != null) fin.close();
                return;
            }
            response.setDateHeader("Last-Modified", lastmod);
        }
        response.setHeader("Cache-Control", "max-age=2628000, immutable");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (ext.equals(".svg")) response.setContentType("image/svg+xml; charset=utf-8");
        response.setHeader("Accept-Ranges", "none");
        java.io.OutputStream cout = response.getOutputStream();
        try {
            if (fin == null) fin = new java.io.FileInputStream(ffile);
            net.i2p.data.DataHelper.copy(fin, cout);
        } catch (java.io.IOException ioe) {
            if (!response.isCommitted()) response.sendError(403, ioe.toString());
            else {
                net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(getClass()).warn("Error serving flags/" + c + ext, ioe);
                throw ioe;
            }
        } finally {
            if (fin != null) try { fin.close(); } catch (java.io.IOException ignored) {}
        }
    } else {
        response.sendError(403, "No flag specified or invalid country code");
    }
%>