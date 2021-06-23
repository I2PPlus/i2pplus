<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */

/**
 *  flags.jsp?c=de => icons/flags_svg/de.svg
 *  flags.jsp?c=de&s=48 => icons/flags48x48/de.png
 *  with headers set so the browser caches.
 *
 *  As of 0.9.51+:
 *  flags_svg and flags48x48 in apps/routerconsole/resources/icons
 *  will be copied to routerconsole.war
 *  All new and changed flags must go in the flags_svg/ dir,
 *  which will be checked first by flags.jsp.
 *  The flags/ dir is the original set from famfamfam,
 *  which may be symlinked in package installs.
 *
 */
String c = request.getParameter("c");
if (c != null && (c.length() == 2 || c.length() == 7) && c.replaceAll("[a-z0-9_]", "").length() == 0) {
    String flagSet = "flags_svg";
    String ext = ".svg";
    String s = request.getParameter("s");
    if (s != null && s.equals("48")) {
        flagSet = "flags48x48";
        ext = ".png";
    }
    java.io.File ffile;
    long lastmod;
    java.io.InputStream fin = flags_jsp.class.getResourceAsStream("/net/i2p/router/web/resources/icons/" + flagSet + '/' + c + ext);
    if (fin != null) {
        java.io.File war = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "webapps/routerconsole.war");
        ffile = null;
    } else {
        // fallback to flags dir, which will be symlinked to /usr/share/flags/countries/16x11 for package builds
        String base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() +
                      java.io.File.separatorChar + "docs" + java.io.File.separatorChar + "icons";
        String file = "flags" + java.io.File.separatorChar + c + ".png";
        ffile = new java.io.File(base, file);
        long length = ffile.length();
        if (ffile == null || length <= 0) {
            response.sendError(403, "Flag not found");
            return;
        } else {
            response.setHeader("Content-Length", Long.toString(length));
        }
    }
    // cache for a month
    response.setHeader("Cache-Control", "private, max-age=2628000");
    response.setHeader("X-Content-Type-Options", "nosniff");
    if (ext.equals(".svg"))
        response.setContentType("image/svg+xml; charset=utf-8");
    else
        response.setContentType("image/png");
    if (ext.equals(".svg"))
        response.setContentType("image/svg+xml; charset=utf-8");
    else
        response.setContentType("image/png");
    response.setHeader("Accept-Ranges", "none");
    java.io.OutputStream cout = response.getOutputStream();
    try {
        // flags dir may be a symlink, which readFile will reject
        // We carefully vetted the "c" value above.
        if (fin == null)
            fin = new java.io.FileInputStream(ffile);
        net.i2p.data.DataHelper.copy(fin, cout);
    } catch (java.io.IOException ioe) {
        // prevent 'Committed' IllegalStateException from Jetty
        if (!response.isCommitted()) {
            response.sendError(403, ioe.toString());
        }  else {
            // not an error, happens when the browser closes the stream
            net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(getClass()).warn("Error serving flags/" + c + ext, ioe);
            // Jetty doesn't log this
            throw ioe;
        }
    } finally {
        if (fin != null)
            try { fin.close(); } catch (java.io.IOException ioe) {}
    }
} else {
    /*
     *  Send a 403 instead of a 404, because the server sends error.jsp
     *  for 404 errors, complete with the summary bar, which would be
     *  a huge load for a page full of flags if the user didn't have the
     *  flags directory for some reason.
     */
    response.sendError(403, "No flag specified or invalid country code");
}
%>