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

    /* aliases */
    if ("a0".equals(c) || "a1".equals(c) || "a2".equals(c)) {c = "xx";}
    else if ("bl".equals(c) || "gf".equals(c) || "gp".equals(c) || "mf".equals(c) ||  "mq".equals(c) ||
             "pm".equals(c) || "re".equals(c) || "wf".equals(c) || "yt".equals(c)) {c = "fr";}
    else if ("bq".equals(c)) {c = "nl";}
    else if ("bv".equals(c) || "sj".equals(c)) {c = "no";}
    else if ("hm".equals(c)) {c = "au";}
    else if ("um".equals(c)) {c = "us";}

    String flagSet = "flags_svg";
    String ext = ".svg";
    String s = request.getParameter("s");

    java.io.File ffile;
    long lastmod = 0;
    java.io.InputStream fin = flags_jsp.class.getResourceAsStream("/net/i2p/router/web/resources/icons/" + flagSet + '/' + c + ext);
    if (fin != null) {
        java.io.File war = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "webapps/routerconsole.war");
        ffile = null;
        lastmod = war.lastModified();

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
            lastmod = ffile.lastModified();
        }
    }

    if (lastmod > 0) {
        long iflast = request.getDateHeader("If-Modified-Since");
        // iflast is -1 if not present; round down file time
        if (iflast >= ((lastmod / 1000) * 1000)) {
            response.setStatus(304);
            if (fin != null) {fin.close();}
            return;
        }
        response.setDateHeader("Last-Modified", lastmod);
    }
    // cache for a month
    response.setHeader("Cache-Control", "max-age=2628000, immutable");
    response.setHeader("X-Content-Type-Options", "nosniff");
    if (ext.equals(".svg")) {
        response.setContentType("image/svg+xml; charset=utf-8");
    }
    response.setHeader("Accept-Ranges", "none");
    java.io.OutputStream cout = response.getOutputStream();

    try {
        // flags dir may be a symlink, which readFile will reject
        // We carefully vetted the "c" value above.
        if (fin == null) {fin = new java.io.FileInputStream(ffile);}
        net.i2p.data.DataHelper.copy(fin, cout);
    } catch (java.io.IOException ioe) {
        // prevent 'Committed' IllegalStateException from Jetty
        if (!response.isCommitted()) {response.sendError(403, ioe.toString());}
        else {
            // not an error, happens when the browser closes the stream
            net.i2p.I2PAppContext.getGlobalContext().logManager().getLog(getClass()).warn("Error serving flags/" + c + ext, ioe);
            throw ioe; // Jetty doesn't log this
        }
    } finally {
        if (fin != null) {
            try {fin.close();}
            catch (java.io.IOException ioe) {}
        }
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