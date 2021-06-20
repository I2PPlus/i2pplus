// Contains code from Jetty 9.2.21:

//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package net.i2p.servlet;

import java.io.IOException;
import java.text.Collator;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.servlet.ServletContext;
import javax.servlet.UnavailableException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;

import net.i2p.data.DataHelper;

import org.eclipse.jetty.servlet.ErrorPageErrorHandler;

import org.eclipse.jetty.security.LoginService;

import java.lang.Class;

/**
 *  Extends DefaultServlet to set locale for the displayed time of directory listings,
 *  to prevent leaking of the locale.
 *
 *  @since 0.9.31
 *
 */
public class I2PDefaultServlet extends DefaultServlet
{
    // shadows of private fields in super
    private ContextHandler _contextHandler;
    private boolean _dirAllowed = true;
    private Resource _resourceBase;
    private Resource _stylesheet;

//    private static final String FORMAT = "yyyy-MM-dd HH:mm";
    private static final String FORMAT = "dd MMM yyyy HH:mm";

    /**
     * Overridden to save local copies of dirAllowed, locale, resourceBase, and stylesheet.
     * Calls super.
     */
    @Override
    public void init()
    throws UnavailableException
    {
        super.init();
        _dirAllowed=getInitBoolean("dirAllowed",_dirAllowed);

        String rb=getInitParameter("resourceBase");
        if (rb != null)
        {
            try{_resourceBase=_contextHandler.newResource(rb);}
            catch (Exception e)
            {
                throw new UnavailableException(e.toString());
            }
        }

        String css=getInitParameter("stylesheet");
        try {
            if (css != null) {
                _stylesheet = Resource.newResource(css);
                if (!_stylesheet.exists()) {
                    _stylesheet = null;
                }
            }
            if (_stylesheet == null) {
                _stylesheet = Resource.newResource(this.getClass().getResource("/jetty-dir.css"));
            }
        }
        catch(Exception e) {}
    }

    /**
     * Overridden to save the result
     * Calls super.
     */
    @Override
    protected ContextHandler initContextHandler(ServletContext servletContext) {
        ContextHandler rv = super.initContextHandler(servletContext);
        _contextHandler = rv;
        ErrorPageErrorHandler errorHandler = new ErrorPageErrorHandler();
        errorHandler.addErrorPage(403, "/.resources/403.html");
        errorHandler.addErrorPage(404, "/.resources/404.html");
        rv.setErrorHandler(errorHandler);
        return rv;
    }

    /* copied from DefaultServlet unchanged */
    private boolean getInitBoolean(String name, boolean dft) {
        String value = getInitParameter(name);
        if (value == null || value.length() == 0)
            return dft;
        return (value.startsWith("t") ||
                value.startsWith("T") ||
                value.startsWith("y") ||
                value.startsWith("Y") ||
                value.startsWith("1"));
    }

    /**
     * Copied and modified from DefaultServlet.java.
     * Overridden to set the Locale for the dates.
     *
     * Get the resource list as a HTML directory listing.
     */
    @Override
    protected void sendDirectory(HttpServletRequest request,
            HttpServletResponse response,
            Resource resource,
            String pathInContext)
    throws IOException {
        if (!_dirAllowed) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        byte[] data = null;
        String base = URIUtil.addPaths(request.getRequestURI(),URIUtil.SLASH);

        //If the DefaultServlet has a resource base set, use it
        if (_resourceBase != null) {
            // handle ResourceCollection
            if (_resourceBase instanceof ResourceCollection)
                resource=_resourceBase.addPath(pathInContext);
        }
        //Otherwise, try using the resource base of its enclosing context handler
        else if (_contextHandler.getBaseResource() instanceof ResourceCollection)
            resource=_contextHandler.getBaseResource().addPath(pathInContext);

        String dir = getListHTML(resource, base, pathInContext.length()>1);
        if (dir == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
            "No directory");
            return;
        }

        data=dir.getBytes("UTF-8");
        response.setContentType("text/html; charset=UTF-8");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
    }

    /**
     * Copied and modified from Resource.java
     * Modified to set the Locale for the dates.
     *
     * Get the resource list as a HTML directory listing.
     * @param base The base URL
     * @param parent True if the parent directory should be included
     * @return String of HTML
     */
    private static String getListHTML(Resource res, String base, boolean parent)
        throws IOException {
        base = URIUtil.canonicalPath(base);
        if (base == null || !res.isDirectory())
            return null;

        String[] ls = res.list();
        if (ls == null)
            return null;
        DataHelper.sort(ls, new FileComparator(res));

        String decodedBase = URIUtil.decodePath(base);
        String title =       "Directory: " + deTag(decodedBase);
        String pwd =         "Directory: <a href=\"" + deTag(decodedBase) + "\">" + deTag(decodedBase) + "</a>";
        String inlineCSS =   "<style type=\"text/css\">\n" +
                             "#dirlist {margin: 2.5%; padding: 0; font-family: Open Sans, Ubuntu, SegoeUI, sans-serif; " +
                             "font-size: 11pt; color: #333; border: 1px solid #ddd; background: #999}\n" +
                             "table {border-collapse: collapse}\n" +
                             "b, th {font-weight: 700; font-size: 90%}\n" +
                             "tr:nth-child(odd) {background: #eee}\n" +
                             "tr:nth-child(even) {background: #ddd}\n" +
                             "tr:hover {background: #ffd}\n" +
                             "#dirlist th, #dirlist td {padding: 4px 8px}\n" +
                             "#dirlist th {border-bottom: 1px solid #ddd; background: #fff}\n" +
                             "#dirlist td:nth-child(n+2) {font-size: 90%}\n" +
                             "h1 {margin: -1px -1px 0; padding: 5px 8px; font-size: 14pt; background: #f2f2f2; " +
                             "border: 1px solid #ddd}\n" +
                             "#dirlist a {text-decoration: none; outline: none; color: #b33}\n" +
                             "#dirlist td a {width: 100%; display: inline-block; font-weight: 600}\n" +
                             "#dirlist a:visited {color: #700}\n" +
                             "#dirlist a:hover, #dirlist a:focus {color: #d30}\n" +
                             "</style>\n";
        String tableHeader = "<table border=0 width=100% cellspacing=10 class=\"listing\">\n" +
                             "<thead>\n<tr><th align=left class=\"name\"><b>Name</b></th>" +
                             "<th align=right nowrap class=\"lastmodified\"><b>Last modified (UTC)</b></th>" +
                             "<th align=right nowrap class=\"size\"><b>Size (bytes)</b></th></tr>\n</thead>\n<tbody>\n";
        StringBuilder buf=new StringBuilder(4096);
        buf.append("<!DOCTYPE HTML>\n<html>\n<head>\n<title>");
        buf.append(title);
        buf.append("</title>\n<meta charset=\"utf-8\">\n");
//        buf.append("<link href=\"jetty-dir.css\" rel=\"stylesheet\" type=\"text/css\"/>\n");
        buf.append(inlineCSS);
        buf.append("<link href=\"/.resources/jetty-dir.css\" rel=\"stylesheet\" type=\"text/css\"/>\n")
           .append("</head>\n<body id=\"dirlist\">\n<h1>").append(pwd).append("</h1>\n")
           .append(tableHeader);
        if (parent) {
            buf.append("<tr><td class=\"name\"><a href=\"");
            buf.append(URIUtil.addPaths(base,"../"));
            buf.append("\">Parent Directory</a></td><td class=\"lastmodified\"></td><td class=\"size\"></td></tr>\n");
        }
        
        String encodedBase = hrefEncodeURI(base);

        DateFormat dfmt = new SimpleDateFormat(FORMAT, Locale.UK);
        TimeZone utc = TimeZone.getTimeZone("GMT");
        dfmt.setTimeZone(utc);
        for (int i = 0; i < ls.length; i++) {
            Resource item;
            try {
                item = res.addPath(ls[i]);
            } catch (IOException ioe) {
                System.out.println("Skipping file in directory listing: " + ioe.getMessage());
                continue;
            } catch (RuntimeException re) {
                // Jetty bug, addPath() argument must be unencoded,
                // but does not escape [],so it throws an unchecked exception:
                //
                // java.nio.file.InvalidPathException:
                // Illegal character in path at index xx: file:/home/.../[test].txt: [test].txt
                //   at org.eclipse.jetty.util.resource.FileResource.addPath(FileResource.java:213)
                //   ...
                //
                //  Catch here and continue so we show the rest of the listing,
                // and don't output the full path in the error page
                // TODO actually handle it
                System.out.println("Skipping file in directory listing: " + re.getMessage());
                continue;
            }

            if (!(ls[i].startsWith(".") || ls[i].equals("jetty-dir.css") || ls[i].equals("favicon.ico") ||
                  ls[i].equals("favicon.png") || ls[i].endsWith("~") || ls[i].endsWith("_") || ls[i].endsWith(".bak") ||
                  ls[i].endsWith(".backup") || ls[i].endsWith(".orig") || ls[i].endsWith(".old") || ls[i].endsWith("_files/"))) {
                buf.append("<tr><td class=\"name\"><a href=\"");
                String path = URIUtil.addPaths(encodedBase,URIUtil.encodePath(ls[i]));

                buf.append(path);

                boolean isDir = item.isDirectory();
                if (isDir && !path.endsWith("/"))
                    buf.append(URIUtil.SLASH);

                buf.append("\">");
                buf.append(deTag(ls[i]));
                buf.append("</a></td><td width=1% nowrap class=\"lastmodified\">");
                buf.append(dfmt.format(new Date(item.lastModified())));
                buf.append("</td><td width=1% align=right class=\"size\">");
                if (!isDir)
                    buf.append(item.length());
                buf.append("</td></tr>\n");
            }
        }
        buf.append("</tbody>\n</table>\n");
        buf.append("</body>\n</html>\n");

        return buf.toString();
    }

    /**
     *  I2P
     *
     *  @since 0.9.51
     */
    private static class FileComparator implements Comparator<String> {
        private final Comparator<Object> _coll;
        private final Resource _base;

        public FileComparator(Resource base) {
            _base = base;
            _coll = Collator.getInstance(Locale.US);
        }

        public int compare(String a, String b) {
            try {
                Resource ra = _base.addPath(a);
                Resource rb = _base.addPath(b);
                boolean da = ra.isDirectory();
                boolean db = rb.isDirectory();
                if (da && !db) return -1;
                if (!da && db) return 1;
            } catch (Exception e) {
                // see above
            }
            return _coll.compare(a, b);
        }
    }
    
    /**
     * Copied unchanged from Resource.java
     *
     * Encode any characters that could break the URI string in an HREF.
     * Such as &lt;a href="/path/to;&lt;script&gt;Window.alert("XSS"+'%20'+"here");&lt;/script&gt;"&gt;Link&lt;/a&gt;
     *
     * The above example would parse incorrectly on various browsers as the "&lt;" or '"' characters
     * would end the href attribute value string prematurely.
     *
     * @param raw the raw text to encode.
     * @return the defanged text.
     */
    private static String hrefEncodeURI(String raw) {
        StringBuffer buf = null;

        loop:
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch(c) {
                case '\'':
                case '"':
                case '<':
                case '>':
                    buf=new StringBuffer(raw.length()<<1);
                    break loop;
            }
        }
        if (buf == null)
            return raw;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch(c) {
              case '"':
                  buf.append("%22");
                  continue;
              case '\'':
                  buf.append("%27");
                  continue;
              case '<':
                  buf.append("%3C");
                  continue;
              case '>':
                  buf.append("%3E");
                  continue;
              default:
                  buf.append(c);
                  continue;
            }
        }

        return buf.toString();
    }

    /**
     * Copied unchanged from Resource.java
     */
    private static String deTag(String raw) {
        return StringUtil.sanitizeXmlString(raw);
    }
}
