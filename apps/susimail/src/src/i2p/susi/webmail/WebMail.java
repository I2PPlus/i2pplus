/*
 * Created on 04.11.2004
 * 
 *  This file is part of susimail project, see http://susi.i2p/
 *  
 *  Copyright (C) 2004-2005  <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 * $Revision: 1.2 $
 */
package i2p.susi.webmail;

import i2p.susi.util.Buffer;
import i2p.susi.util.Config;
import i2p.susi.util.DecodingOutputStream;
import i2p.susi.util.EscapeHTMLOutputStream;
import i2p.susi.util.EscapeHTMLWriter;
import i2p.susi.util.FileBuffer;
import i2p.susi.util.FilenameUtil;
import i2p.susi.util.Folder;
import i2p.susi.util.Folder.SortOrder;
import i2p.susi.util.OutputStreamBuffer;
import i2p.susi.util.RegexOutputStream;
import i2p.susi.util.StringBuilderWriter;
import i2p.susi.webmail.Messages;
import static i2p.susi.webmail.Sorters.*;
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingException;
import i2p.susi.webmail.encoding.EncodingFactory;
import i2p.susi.webmail.pop3.POP3MailBox;
import i2p.susi.webmail.smtp.SMTPClient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.servlet.RequestWrapper;
import net.i2p.servlet.util.ServletUtil;
import net.i2p.servlet.util.WriterOutputStream;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.RFC822Date;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.Translate;

/**
 * @author susi23
 */
public class WebMail extends HttpServlet
{
    private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(WebMail.class);

    private static final long serialVersionUID = 1L;
    private static final String LOGIN_NONCE = Long.toString(I2PAppContext.getGlobalContext().random().nextLong());

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_POP3PORT = 7660;
    private static final int DEFAULT_SMTPPORT = 7659;

    private enum State { AUTH, LOADING, LIST, SHOW, NEW, CONFIG }

    /** @since 0.9.62 */
    private enum HtmlMode { NONE, LINK, ALLOW, PREFER }

    // TODO generate from servlet name to allow for renaming or multiple instances
    private static final String myself = "/susimail/";

    /** @since 0.9.62+ */
    private static final String cspNonce = Integer.toHexString(net.i2p.util.RandomSource.getInstance().nextInt());

    /*
     * form keys on login page
     */
    private static final String LOGIN = "login";
    private static final String OFFLINE = "offline";
    private static final String USER = "user";
    private static final String PASS = "pass";
    private static final String HOST = "host";
    private static final String POP3 = "pop3";
    private static final String SMTP = "smtp";

    /*
     * GET params
     */
    private static final String CUR_PAGE  = "page";

    /*
     * hidden params
     */
    private static final String SUSI_NONCE = "susiNonce";
    private static final String B64UIDL = "msg";
    private static final String NEW_UIDL = "newmsg";
    private static final String PREV_B64UIDL = "prevmsg";
    private static final String NEXT_B64UIDL = "nextmsg";
    private static final String PREV_PAGE_NUM = "prevpagenum";
    private static final String NEXT_PAGE_NUM = "nextpagenum";
    private static final String CURRENT_SORT = "currentsort";
    private static final String CURRENT_FOLDER = "folder";
    private static final String NEW_FOLDER  = "newfolder";
    private static final String DRAFT_EXISTS = "draftexists";
    private static final String DEBUG_STATE = "currentstate";

    /*
     * button names
     */
    private static final String LOGOUT = "logout";
    private static final String RELOAD = "reload";
    private static final String SAVE = "save";
    private static final String SAVE_AS = "saveas";
    private static final String REFRESH = "refresh";
    // also a GET param
    private static final String CONFIGURE = "configure";
    private static final String NEW = "new";
    private static final String REPLY = "reply";
    private static final String REPLYALL = "replyall";
    private static final String FORWARD = "forward";
    private static final String DELETE = "delete";
    private static final String REALLYDELETE = "really_delete";
    private static final String MOVE_TO = "moveto";
    private static final String SWITCH_TO = "switchto";
    // also a GET param
    private static final String SHOW = "show";
    private static final String DOWNLOAD = "download";
    private static final String RAW_ATTACHMENT = "att";
    private static final String CID_ATTACHMENT = "cid";
    private static final String DRAFT_ATTACHMENT = "datt";
    private static final String HTML = "html";

    private static final String MARKALL = "markall";
    private static final String CLEAR = "clearselection";
    private static final String INVERT = "invertselection";

    private static final String PREVPAGE = "prevpage";
    private static final String NEXTPAGE = "nextpage";
    private static final String FIRSTPAGE = "firstpage";
    private static final String LASTPAGE = "lastpage";
    private static final String PAGESIZE = "pagesize";
    private static final String SETPAGESIZE = "setpagesize";

    private static final String SEND = "send";
    private static final String SAVE_AS_DRAFT = "saveasdraft";
    private static final String CANCEL = "cancel";
    private static final String DELETE_ATTACHMENT = "delete_attachment";

    private static final String NEW_FROM = "new_from";
    private static final String NEW_SUBJECT = "new_subject";
    private static final String NEW_TO = "new_to";
    private static final String NEW_CC = "new_cc";
    private static final String NEW_BCC = "new_bcc";
    private static final String NEW_TEXT = "new_text";
    private static final String NEW_FILENAME = "new_filename";
    private static final String NEW_UPLOAD = "new_upload";

    private static final String LIST = "list";
    private static final String PREV = "prev";
    private static final String NEXT = "next";

    // SORT is a GET or POST param, SORT_XX are the values, possibly prefixed by '-'
    private static final String SORT = "sort";
    static final String SORT_ID = "id";
    static final String SORT_SENDER = "sender";
    static final String SORT_SUBJECT = "subject";
    static final String SORT_DATE = "date";
    static final String SORT_SIZE = "size";
    static final String SORT_DEFAULT = SORT_DATE;
    static final SortOrder SORT_ORDER_DEFAULT = SortOrder.UP;
    // for XSS
    private static final List<String> VALID_SORTS = Arrays.asList(new String[] {
                             SORT_ID, SORT_SENDER, SORT_SUBJECT, SORT_DATE, SORT_SIZE,
                             '-' + SORT_ID, '-' + SORT_SENDER, '-' + SORT_SUBJECT, '-' + SORT_DATE, '-' + SORT_SIZE });

    static final String DIR_FOLDER = "cur"; // MailDir-like
    public static final String DIR_DRAFTS = _x("Drafts"); // MailDir-like
    public static final String DIR_SENT = _x("Sent"); // MailDir-like
    private static final String DIR_TRASH = _x("Trash"); // MailDir-like
    private static final String DIR_SPAM = _x("Bulk Mail"); // MailDir-like
    // internal/on-disk names
    private static final String[] DIRS = { DIR_FOLDER, DIR_DRAFTS, DIR_SENT, DIR_TRASH, DIR_SPAM };
    // untranslated, translate on use
    private static final String[] DISPLAY_DIRS = { _x("Inbox"), DIR_DRAFTS, DIR_SENT, DIR_TRASH, DIR_SPAM };

    private static final String CONFIG_TEXT = "config_text";

    private static final boolean SHOW_HTML = true;
    private static final boolean TEXT_ONLY = false;

    /*
     * name of configuration properties
     */
    private static final String CONFIG_HOST = "host";

    private static final String CONFIG_PORTS_FIXED = "ports.fixed";
    private static final String CONFIG_PORTS_POP3 = "ports.pop3";
    private static final String CONFIG_PORTS_SMTP = "ports.smtp";

    private static final String CONFIG_SENDER_FIXED = "sender.fixed";
    private static final String CONFIG_SENDER_DOMAIN = "sender.domain";
    private static final String CONFIG_SENDER_NAME = "sender.name";

    private static final String CONFIG_COMPOSER_COLS = "composer.cols";
    private static final String CONFIG_COMPOSER_ROWS = "composer.rows";

    private static final String CONFIG_HTML_ALLOWED = "view.html.allowed";
    private static final String CONFIG_HTML_PREFERRED = "view.html.preferred";

    private static final String CONFIG_COPY_TO_SENT = "composer.copy.to.sent";
    static final String CONFIG_LEAVE_ON_SERVER = "pop3.leave.on.server";
    public static final String CONFIG_BACKGROUND_CHECK = "pop3.check.enable";
    public static final String CONFIG_CHECK_MINUTES = "pop3.check.interval.minutes";
    public static final String CONFIG_IDLE_SECONDS = "pop3.idle.timeout.seconds";
    private static final String CONFIG_DEBUG = "debug";
    private static final String CONFIG_SHOW_HTML_WARNING = "view.html.warning";

    //public static final String SHOW_FULL_HEADERS = "display.full.headers";

    private static final String RC_PROP_THEME = "routerconsole.theme";
    private static final String RC_PROP_UNIVERSAL_THEMING = "routerconsole.universal.theme";
    private static final String RC_PROP_FORCE_MOBILE_CONSOLE = "routerconsole.forceMobileConsole";
    private static final String RC_PROP_ENABLE_SORA_FONT = "routerconsole.displayFontSora";
    private static final String CONFIG_THEME = "theme";
    private static final String DEFAULT_THEME = "dark";
    private static final String spacer = ""; /* this is best done with css */
    private static final String thSpacer = "<th>&nbsp;</th>\n";
    private static final String CONSOLE_BUNDLE_NAME = "net.i2p.router.web.messages";
    static {Config.setPrefix("susimail");}

    /**
     * data structure to hold any persistent data (to store them in session dictionary)
     * @author susi
     */
    private static class SessionObject implements HttpSessionBindingListener, NewMailListener {
        boolean pageChanged, markAll, clear, invert;
        int smtpPort;
        POP3MailBox mailbox;
        final Map<String, MailCache> caches;
        boolean isFetching;
        /** Set by threaded connector. Error or null */
        String connectError;
        /** Set by threaded connector. -1 if nothing to report, 0 or more after fetch complete */
        int newMails = -1;
        String user, pass, host, error = "", info = "";
        // Just convenience to pass from PSCB to P-R-G
        String draftUIDL;
        // TODO Map of UIDL to List
        public ArrayList<Attachment> attachments;
        // This is only for multi-delete. Single-message delete is handled with P-R-G
        public boolean reallyDelete;
        String themePath, imgPath;
        boolean isMobile;
        private final List<String> nonces;
        private static final int MAX_NONCES = 15;
        public final Log log;

        SessionObject(Log log) {
            nonces = new ArrayList<String>(MAX_NONCES + 1);
            caches = new HashMap<String, MailCache>(8);
            this.log = log;
            String dbg = Config.getProperty(CONFIG_DEBUG);
            if (dbg != null) {
                boolean release = !Boolean.parseBoolean(dbg);
                log.setMinimumPriority(release ? Log.ERROR : Log.DEBUG);
            }
        }

        /** @since 0.9.13 */
        public void valueBound(HttpSessionBindingEvent event) {}

        /**
         * Close the POP3 socket if still open
         * @since 0.9.13
         */
        public void valueUnbound(HttpSessionBindingEvent event) {
            if (log.shouldDebug()) log.debug("Session unbound: " + event.getSession().getId());
            POP3MailBox mbox = mailbox;
            if (mbox != null) {
                mbox.destroy();
                mailbox = null;
            }
        }

        /**
         *  Relay from the checker to the webmail session object,
         *  which relays to MailCache, which will fetch the mail from us
         *  in a big circle
         *
         *  @since 0.9.13
         */
        public void foundNewMail(boolean yes) {
            if (!yes)
                return;
            MailCache mc = caches.get(DIR_FOLDER);
            if (mc != null) {
                String[] uidls = mc.getUIDLs();
                mc.getFolder().addElements(Arrays.asList(uidls));
            }
        }

        /** @since 0.9.27 */
        public void addNonce(String nonce) {
            synchronized(nonces) {
                nonces.add(0, nonce);
                if (nonces.size() > MAX_NONCES) {
                    nonces.remove(MAX_NONCES);
                }
            }
        }

        /** @since 0.9.27 */
        public boolean isValidNonce(String nonce) {
            if (mailbox == null && LOGIN_NONCE.equals(nonce))
                return true;
            synchronized(nonces) {
                return nonces.contains(nonce);
            }
        }

        /**
         * Remove references but does not delete files
         * @since 0.9.33
         */
        public void clearAttachments() {
            if (attachments != null) {
                attachments.clear();
            }
        }

        /**
         * Remove references AND delete files
         * @since 0.9.35
         */
        public void deleteAttachments() {
            if (attachments != null) {
                for (Attachment a : attachments) {
                    a.deleteData();
                }
                attachments.clear();
            }
        }
    }

    /**
     * returns html string of a form button with name and label
     *
     * @param name
     * @param label
     * @return html string
     */
    private static String button(String name, String label) {
        StringBuilder buf = new StringBuilder(128);
        buf.append("<input type=submit name=\"")
           .append(name).append("\" value=\"").append(label).append('"');
        buf.append(" class=\"").append(name);
        if (name.equals(SEND) || name.equals(CANCEL) || name.equals(DELETE_ATTACHMENT) ||
            name.equals(NEW_UPLOAD) || name.equals(SAVE_AS_DRAFT) ||  // compose page
            name.equals(SETPAGESIZE) || name.equals(SAVE))  // config page
            buf.append(" beforePopup\"");
        // scroll to top of non-embedded page if delete clicked to ensure confirmation dialog is visible
        //else if (name.equals(DELETE))
        //buf.append(" onclick=\"smoothScroll(document.getElementById(\'mailbox\'))\"");
        else if (name.equals(REFRESH))
            buf.append("\" id=serverRefresh");
        else
            buf.append('"');
        // These are icons only now, via the CSS, so add a tooltip
        if (name.equals(FIRSTPAGE) || name.equals(PREVPAGE) || name.equals(NEXTPAGE) || name.equals(LASTPAGE) ||
            name.equals(PREV) || name.equals(LIST) || name.equals(NEXT))
            buf.append(" title=\"").append(label).append('"');
        buf.append('>');
        return buf.toString();
    }

    /**
     * returns html string of a disabled form button with name and label
     * 
     * @param name
     * @param label
     * @return html string
     */
    private static String button2(String name, String label) {
        return "<input type=submit class=\"" + name + "\" name=\"" + name + "\" value=\"" + label + "\" disabled>";
    }

    /**
     * returns a html string of the label and two imaged links using the parameter name
     * (used for sorting buttons in folder view)
     * 
     * @param name
     * @param label
     * @return the string
     */
    private static String sortHeader(String name, String label, String imgPath,
                                     String currentName, SortOrder currentOrder, int page, String folder) {
        StringBuilder buf = new StringBuilder(128);
        buf.append(label).append("&nbsp;&nbsp;");
        // UP is reverse sort (descending). DOWN is normal sort (ascending).
        if (name.equals(currentName) && currentOrder == SortOrder.UP) {
            buf.append("<img class=\"sort selected\" src=\"").append(imgPath).append("../../images/up.svg\" border=0 alt=\"^\">\n");
        } else {
            buf.append("<a class=sort href=\"").append(myself).append("?page=").append(page).append("&amp;sort=-")
                           .append(name).append("&amp;folder=").append(folder).append("\">");
            buf.append("<img class=sort src=\"").append(imgPath).append("../../images/up.svg\" border=0 alt=\"^\">");
            buf.append("</a>\n");
        }
        if (name.equals(currentName) && currentOrder == SortOrder.DOWN) {
            buf.append("<img class=\"sort selected\" src=\"").append(imgPath).append("../../images/down.svg\" border=0 alt=\"v\">");
        } else {
            buf.append("<a class=sort href=\"").append(myself).append("?page=").append(page).append("&amp;sort=")
                           .append(name).append("&amp;folder=").append(folder).append("\">");
            buf.append("<img class=sort src=\"").append(imgPath).append("../../images/down.svg\" border=0 alt=\"v\">");
            buf.append("</a>");
        }
        return buf.toString();
    }

    /**
     * check, if a given button "was pressed" in the received http request
     * 
     * @param request
     * @param key
     * @return true if pressed
     */
    private static boolean buttonPressed(RequestWrapper request, String key) {
        String value = request.getParameter(key);
        return value != null && (value.length() > 0 || key.equals(CONFIGURE) || key.equals(NEW_UIDL));
    }
    /**
     * recursively render all mail body parts
     *
     * 1. if type is multipart/alternative, look for preferred section and ignore others
     * 2. if type is multipart/*, recursively call all these parts
     * 3. if type is text/plain (or mail is not mime), print out
     * 4. in all other cases print out message, that part is not displayed
     *
     * @param out
     * @param mailPart
     * @param level is increased by recursively calling sub parts
     * @param html use html styling
     * @param allowHtml allow display of text/html parts
     */
    private static void showPart(PrintWriter out, MailPart mailPart, int level, boolean html, HtmlMode allowHtml) {
        String br = html ? "<br>\r\n" : "\r\n";
        if (html) {
/*
            out.println("<!-- ");
            out.println("Debug: Showing Mail Part at level " + level + " with ID " + mailPart.getID());
            out.println("Debug: Mail Part headers follow");
            out.println("-->");
*/
            out.println("<tr class=debugHeader style=display:none><td><pre class=left>");
            for (int i = 0; i < mailPart.headerLines.length; i++) {
                // fix Content-Type: multipart/alternative; boundary="----------8CDE39ECAF2633"
                out.println(mailPart.headerLines[i].replace("--", "&#45;&#45;").replace("<", "&lt;").replace(">", "&gt;"));
            }
            out.println("</pre></td></tr>");
        }

        if (mailPart.multipart) {
            if (mailPart.type.equals("multipart/alternative") || mailPart.type.equals("multipart/related")) {
                MailPart chosen = null;
                String preferred = html && allowHtml == HtmlMode.PREFER ? "text/html" : "text/plain";
                for (MailPart subPart : mailPart.parts) {
                    // check for multipart/related type=text/html RFC 2387
                    if (preferred.equals(subPart.type) || preferred.equals(subPart.multipart_type)) {
                        chosen = subPart;
                        break;
                    }
                }
                if (chosen == null) {
                    String backup = html && allowHtml != HtmlMode.NONE ? "text/html" : "text/plain";
                    if (!backup.equals(preferred)) {
                        for (MailPart subPart : mailPart.parts) {
                            if (backup.equals(subPart.type) || backup.equals(subPart.multipart_type)) {
                                chosen = subPart;
                                break;
                            }
                        }
                    }
                }
                if (chosen != null) {
                    showPart(out, chosen, level + 1, html, allowHtml);
                    if (html) {
                        for (MailPart subPart : mailPart.parts) {
                            if (chosen.equals(subPart))
                                continue;
                            if ("text/html".equals(subPart.type) || "text/html".equals(subPart.multipart_type)) {
/*
                                out.println("<!-- ");
                                out.println("Debug: Not showing alternative Mail Part at level " + (level + 1) + " with ID " + subPart.getID());
                                out.println("Debug: HTML Mode: " + allowHtml);
                                out.println("Debug: Mail Part headers follow");
                                for (int i = 0; i < subPart.headerLines.length; i++) {
                                    out.println(subPart.headerLines[i].replace("--", "&#45;&#45;"));
                            }
                            out.println("-->");
*/
                            if (allowHtml != HtmlMode.NONE) {
                                out.println("<tr id=toggleHtmlView class=mailbody><td colspan=2>");
                                out.println("<p class=info><a id=toggleHtmlLink href=\"" + myself + '?' + SHOW + '=' + Base64.encode(subPart.uidl) +
                                            "&amp;" + HTML + "=1\">" + _t("View message as HTML") + "</a></p>");
                                out.println("</td></tr>");
                            }
                        } else if ("text/plain".equals(subPart.type) ||
                                   "text/plain".equals(subPart.multipart_type)) {
/*
                            out.println("<!-- ");
                            out.println("Debug: Not showing alternative Mail Part at level " + (level + 1) + " with ID " + subPart.getID());
                            out.println("Debug: HTML Mode: " + allowHtml);
                            out.println("Debug: Mail Part headers follow");
                            for (int i = 0; i < subPart.headerLines.length; i++) {
                                out.println(subPart.headerLines[i].replace("--", "&#45;&#45;"));
                            }
                            out.println("-->");
*/
                            if (allowHtml != HtmlMode.NONE) {
                                out.println("<tr id=toggleHtmlView class=mailbody><td colspan=2>");
                                out.println("<p class=info><a id=toggleHtmlLink class=viewAsPlainText href=\"" + myself + '?' +
                                            SHOW + '=' + Base64.encode(subPart.uidl) +
                                            "&amp;" + HTML + "=0\">" +
                                            _t("View message as plain text") + "</a></p>");
                                out.println("</td></tr>");
                            }
                        } else {
                            // show as attachment
                            // if image is loaded as a CID in the iframe, we will still show it as an attachment also
                            showPart(out, subPart, level + 1, html, allowHtml);
                        }
                    }
                }
                return;
            }
        }
        for (MailPart part : mailPart.parts) {
            showPart(out, part, level + 1, html, allowHtml);
        }
    }
    else if (mailPart.message) {
        for (MailPart part : mailPart.parts) {
            showPart(out, part, level + 1, html, allowHtml);
        }
    }
    else {
        boolean showBody = false;
        boolean prepareAttachment = false;
        String reason = "";

        String ident = quoteHTML(
                (mailPart.description != null ? mailPart.description + " " : "") +
                (mailPart.filename != null ? mailPart.filename + " " : "") +
                (mailPart.name != null ? mailPart.name + " " : "") +
                (mailPart.type != null ? '(' + mailPart.type + ')' : _t("unknown")));

            if (level == 0 && mailPart.version == null) {
                /*
                 * not a MIME mail, so simply print it literally
                 */
                showBody = true;
            }
            if (!showBody && mailPart.type != null) {
                if (mailPart.type.equals("text/plain") ||
                    (html && allowHtml != HtmlMode.NONE && mailPart.type.equals("text/html"))) {
                    showBody = true;
                }
                else
                    prepareAttachment = true;
            }
            if (reason != null && reason.length() > 0) {
                if (html)
                    out.println("<p class=info>");
                out.println(reason);
                if (html)
                    out.println("</p>");
                reason = "";
            }
            boolean showHTMLWarning = Boolean.parseBoolean(Config.getProperty(CONFIG_SHOW_HTML_WARNING, "true"));
            if (html && allowHtml != HtmlMode.NONE && showBody && "text/html".equals(mailPart.type) && showHTMLWarning) {
                out.print("<tr id=privacywarn><td colspan=2><p class=info>" +
                            "<i>" + _t("To protect your privacy, SusiMail is blocking Javascript and any remote content contained in this HTML message.") + "</i>");
                out.print("<noscript><br><i>" + _t("Enable Javascript for enhanced presentation and additional features.") + "</i></noscript></p></td></tr>\n");
            }
            if (html)
                out.print("<tr class=\"mailbody htmlView\"><td colspan=2>");
            if (html && allowHtml != HtmlMode.NONE && showBody && "text/html".equals(mailPart.type)) {
                out.print("<iframe src=\"" + myself + '?' + RAW_ATTACHMENT + '=' + mailPart.getID() + "&amp;" +
                            B64UIDL + '=' + Base64.encode(mailPart.uidl) + "\" name=\"mailhtmlframe" + mailPart.getID() +
                            "\" id=iframeSusiHtmlView width=100% height=100% scrolling=auto frameborder=0 border=0 allowtransparency=true></iframe>\n");
                out.print("</td></tr>\n<tr class=mailbody><td colspan=2>");
                // TODO scrolling=no if js is on
            } else if (showBody) {
                if (html)
                    out.println("<p class=mailbody><br>");
                String charset = mailPart.charset;
                if (charset == null) {
                    charset = "ISO-8859-1";
                    // don't show this in text mode which is used to include the mail in the reply or forward
                    // Too common, don't show this at all.
                    // if (html)
                    // reason = _t("Warning: no charset found, fallback to US-ASCII.") + br;
                }
                try {
                    Writer escaper;
                    if (html)
                        escaper = new EscapeHTMLWriter(out);
                    else
                        escaper = out;
                    Buffer ob = new OutputStreamBuffer(new DecodingOutputStream(escaper, charset));
                    mailPart.decode(0, ob);
                    // todo Finally
                    ob.writeComplete(true);
                }
                catch(UnsupportedEncodingException uee) {
                    showBody = false;
                    reason = _t("Charset \\''{0}\\'' not supported.", quoteHTML(mailPart.charset)) + br;
                }
                catch (IOException e1) {
                    showBody = false;
                    reason += _t("Part ({0}) not shown, because of {1}", ident, e1.toString()) + br +
                                 _t("Reloading the page may fix the error.");
                }
                if (html)
                    out.println("<br></p>");
            }
            if (reason != null && reason.length() > 0) {
                if (html)
                    out.println("<p class=info>");
                out.println(reason);
                if (html)
                    out.println("</p>");
            }
            if (prepareAttachment) {
                if (html) {
                    out.println("<hr>\n<div class=attached>");
                    String type = mailPart.type;
                    if (type != null && type.startsWith("image/")) {
                        // we at least show images safely...
                        String name = mailPart.filename;
                        if (name == null) {
                            name = mailPart.name;
                            if (name == null)
                                name = mailPart.description;
                        }
                        name = quoteHTML(name);
                        out.println("<img src=\"" + myself + '?' + RAW_ATTACHMENT + '=' + mailPart.getID() + "&amp;" +
                                    B64UIDL + '=' + Base64.encode(mailPart.uidl) + "\" alt=\"" + name + "\">");
                        out.println("<span id=imageInfo><b>" + _t("File") + ": " + "</b><a target=_blank href=\"" +
                                    myself + '?' + RAW_ATTACHMENT + '=' + mailPart.getID() + "&amp;" + B64UIDL +
                                    '=' + Base64.encode(mailPart.uidl) + "\">" + name + "</a>");
                    } else if (type != null && (
                        // type list from snark
                        type.startsWith("audio/") || type.equals("application/ogg") || type.startsWith("video/") ||
                        (type.startsWith("text/") && !type.equals("text/html")) ||
                        type.equals("application/zip") || type.equals("application/x-gtar") || type.equals("application/x-zip-compressed") ||
                        type.equals("application/compress") || type.equals("application/gzip") || type.equals("application/x-7z-compressed") ||
                        type.equals("application/x-rar-compressed") || type.equals("application/x-tar") || type.equals("application/x-bzip2") ||
                        type.equals("application/pdf") || type.equals("application/x-bittorrent") ||
                        type.equals("application/pgp-encrypted") || type.equals("application/pgp-signature") ||
                        (type.equals("application/octet-stream") &&
                        ((mailPart.filename != null && mailPart.filename.endsWith(".asc")) ||
                        (mailPart.name != null && mailPart.name.endsWith(".asc")))))) {
                        out.println("<a href=\"" + myself + '?' + RAW_ATTACHMENT + '=' + mailPart.getID() + "&amp;" +
                                    B64UIDL + '=' + Base64.encode(mailPart.uidl) + "\">" + _t("Download attachment {0}", ident) + "</a>");
                    } else {
                        out.println("<a target=_blank href=\"" + myself + '?' + DOWNLOAD + '=' + mailPart.getID() + "&amp;" +
                                    B64UIDL + '=' + Base64.encode(mailPart.uidl) + "\">" + _t("Download attachment {0}", ident) + "</a>" +
                                    " (" + _t("File is packed into a zipfile for security reasons.") + ')');
                    }
                    out.println("</div>");
                }
                else {
                    out.println(_t("Attachment ({0}).", ident));
                }
            }
            if (html)
                out.print("</td></tr>\n");
        }
/*
        if (html) {
            out.println("<!-- ");
            out.println("Debug: End of Mail Part at level " + level + " with ID " + mailPart.getID());
            out.println("-->");
        }
*/
    }
    /**
     * prepare line for presentation between html tags
     *
     * Escapes html tags
     *
     * @param line null OK
     * @return escaped string or "" for null input
     */
    static String quoteHTML(String line) {
        if (line != null)
            line = DataHelper.escapeHTML(line);
        else
            line = "";
        return line;
    }

    //// Start state change and button processing here ////

    /**
     * 
     * @param sessionObject
     * @param request
     * @return new state, or null if unknown
     */
    private static State processLogin(SessionObject sessionObject, RequestWrapper request, State state) {
        if (state == State.AUTH) {
            String user = request.getParameter(USER);
            String pass = request.getParameter(PASS);
            String host = request.getParameter(HOST);
            String pop3Port = request.getParameter(POP3);
            String smtpPort = request.getParameter(SMTP);
            boolean fixedPorts = Boolean.parseBoolean(Config.getProperty(CONFIG_PORTS_FIXED, "true"));
            if (fixedPorts) {
                host = Config.getProperty(CONFIG_HOST, DEFAULT_HOST);
                pop3Port = Config.getProperty(CONFIG_PORTS_POP3, Integer.toString(DEFAULT_POP3PORT));
                smtpPort = Config.getProperty(CONFIG_PORTS_SMTP, Integer.toString(DEFAULT_SMTPPORT));
            }
            boolean doContinue = true;

            /*
             * security :(
             */
            boolean offline = buttonPressed(request, OFFLINE);
            if (buttonPressed(request, LOGIN) || offline) {

                if (user == null || user.length() == 0) {
                    sessionObject.error += _t("Need username for authentication.") + '\n';
                    doContinue = false;
                } else {
                    user = user.trim();
                    if (user.endsWith("@mail.i2p")) {
                        sessionObject.error += _t("Do not include @mail.i2p in the username") + '\n';
                        doContinue = false;
                    }
                }
                if (pass == null || pass.length() == 0) {
                    sessionObject.error += _t("Need password for authentication.") + '\n';
                    doContinue = false;
                } else {
                    pass = pass.trim();
                }
                if (host == null || host.length() == 0) {
                    sessionObject.error += _t("Need hostname for connect.") + '\n';
                    doContinue = false;
                } else {
                    host = host.trim();
                }
                int pop3PortNo = 0;
                if (pop3Port == null || pop3Port.length() == 0) {
                    sessionObject.error += _t("Need port number for pop3 connect.") + '\n';
                    doContinue = false;
                }
                else {
                    pop3Port = pop3Port.trim();
                    try {
                        pop3PortNo = Integer.parseInt(pop3Port);
                        if (pop3PortNo < 0 || pop3PortNo > 65535) {
                            sessionObject.error += _t("POP3 port number is not in range 0..65535.") + '\n';
                            doContinue = false;
                        }
                    }
                    catch(NumberFormatException nfe)
                    {
                        sessionObject.error += _t("POP3 port number is invalid.") + '\n';
                        doContinue = false;
                    }
                }
                int smtpPortNo = 0;
                if (smtpPort == null || smtpPort.length() == 0) {
                    sessionObject.error += _t("Need port number for smtp connect.") + '\n';
                    doContinue = false;
                }
                else {
                    smtpPort = smtpPort.trim();
                    try {
                        smtpPortNo = Integer.parseInt(smtpPort);
                        if (smtpPortNo < 0 || smtpPortNo > 65535) {
                            sessionObject.error += _t("SMTP port number is not in range 0..65535.") + '\n';
                            doContinue = false;
                        }
                    }
                    catch(NumberFormatException nfe)
                    {
                        sessionObject.error += _t("SMTP port number is invalid.") + '\n';
                        doContinue = false;
                    }
                }
                if (doContinue) {
                    sessionObject.smtpPort = smtpPortNo;
                    state = threadedStartup(sessionObject, offline, state,
                                            host, pop3PortNo, user, pass);
                }
            }
        }
        return state;
    }

    /**
     * Starts one thread to load the emails from disk,
     * and in parallel starts a second thread to connect to the POP3 server
     * (unless user clicked the 'read mail offline' at login).
     * Either could finish first, but unless the local disk cache is really big,
     * the loading will probably finish first.
     *
     * Once the POP3 connects, it waits for the disk loader to finish, and then
     * does the fetching of new emails.
     *
     * The user may view the local folder once the first (loader) thread is done.
     *
     * @since 0.9.34
     */
    private static State threadedStartup(SessionObject sessionObject, boolean offline, State state,
                                         String host, int pop3PortNo, String user, String pass) {
        POP3MailBox mailbox = new POP3MailBox(host, pop3PortNo, user, pass);
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        Log log = sessionObject.log;
        MailCache mc;
        try {
            mc = new MailCache(ctx, mailbox, DIR_FOLDER,
                               host, pop3PortNo, user, pass);
            sessionObject.caches.put(DIR_FOLDER, mc);
            MailCache mc2 = new MailCache(ctx, null, DIR_DRAFTS,
                                          host, pop3PortNo, user, pass);
            sessionObject.caches.put(DIR_DRAFTS, mc2);
            mc2 = new MailCache(ctx, null, DIR_SENT,
                                host, pop3PortNo, user, pass);
            sessionObject.caches.put(DIR_SENT, mc2);
            mc2 = new MailCache(ctx, null, DIR_TRASH,
                                host, pop3PortNo, user, pass);
            sessionObject.caches.put(DIR_TRASH, mc2);
            mc2 = new MailCache(ctx, null, DIR_SPAM,
                                host, pop3PortNo, user, pass);
            sessionObject.caches.put(DIR_SPAM, mc2);
        } catch (IOException ioe) {
            log.error("Error creating disk cache", ioe);
            sessionObject.error += ioe.toString() + '\n';
            return State.AUTH;
        }

        sessionObject.mailbox = mailbox;
        sessionObject.user = user;
        sessionObject.pass = pass;
        sessionObject.host = host;
        sessionObject.reallyDelete = false;

        // Thread the loading and the server connection.
        // Either could finish first.
        // We only load the inbox here. Others are loaded on-demand in processRequest()

        // With a mix of email (10KB median, 100KB average size),
        // about 20 emails per second per thread loaded.
        // thread 1: mc.loadFromDisk()
        boolean ok = mc.loadFromDisk(new LoadWaiter(sessionObject, mc));

        // thread 2: mailbox.connectToServer()
        if (offline) {
            if (log.shouldDebug()) log.debug("OFFLINE MODE");
        } else {
            sessionObject.isFetching = true;
            if (!mailbox.connectToServer(new ConnectWaiter(sessionObject))) {
                sessionObject.error += _t("Cannot connect") + '\n';
                sessionObject.isFetching = false;
            }
        }

        // wait a little while so we avoid the loading page if we can
        if (ok && mc.isLoading()) {
            try {
                sessionObject.wait(5000);
            } catch (InterruptedException ie) {
                if (log.shouldDebug()) log.debug("Interrupted waiting for load", ie);
            }
        }
        state = mc.isLoading() ? State.LOADING : State.LIST;
        return state;
    }

    /**
     *  Callback from MailCache.loadFromDisk()
     *  @since 0.9.34
     */
    private static class LoadWaiter implements NewMailListener {
        private final SessionObject _so;
        private final MailCache _mc;

        public LoadWaiter(SessionObject so, MailCache mc) {
            _so = so;
            _mc = mc;
        }

        public void foundNewMail(boolean yes) {
            synchronized(_so) {
                // get through cache so we have the disk-only ones too
                Folder<String> f = _mc.getFolder();
                String[] uidls = _mc.getUIDLs();
                int added = f.addElements(Arrays.asList(uidls));
                if (added > 0)
                    _so.pageChanged = true;
                _so.notifyAll();
            }
        }
    }

    /**
     *  Callback from POP3MailBox.connectToServer()
     *  @since 0.9.34
     */
    private static class ConnectWaiter implements NewMailListener, Runnable {
        private final SessionObject _so;
        private final POP3MailBox _mb;

        public ConnectWaiter(SessionObject so) {
            _so = so;
            _mb = _so.mailbox;
        }

        /** run this way if already connected */
        public void run() {
            foundNewMail(true);
        }

        /** @param connected are we? */
        public void foundNewMail(boolean connected) {
            MailCache mc = null;
            boolean found = false;
            Log log = _so.log;
            if (connected) {
                // we do this whether new mail was found or not,
                // because we may already have UIDLs in the MailCache to fetch
                synchronized(_so) {
                    mc = _so.caches.get(DIR_FOLDER);
                    if (mc == null) {
                        _so.error += "Internal error, no folder\n";
                        return;
                    }
                    while (mc.isLoading()) {
                        try {
                            _so.wait(5000);
                        } catch (InterruptedException ie) {
                               if (log.shouldDebug()) log.debug("Interrupted waiting for load", ie);
                            return;
                        }
                    }
                }
                if (log.shouldDebug()) log.debug("Done waiting for folder load");
                // fetch the mail outside the lock
                // TODO, would be better to add each email as we get it
                found = mc.getMail(MailCache.FetchMode.HEADER);
            }
            if (log.shouldDebug()) log.debug("CW.FNM connected? " + connected + " found? " + found);
            synchronized(_so) {
                if (!connected) {
                    String error = _mb.lastError();
                    if (error.length() > 0)
                        _so.connectError = error;
                    else
                        _so.connectError = _t("Error connecting to server");
                } else if (!found) {
                    if (log.shouldInfo()) log.info("No new emails");
                    _so.newMails = 0;
                    _so.connectError = null;
                } else if (mc != null) {
                    String[] uidls = mc.getUIDLs();
                    int added = mc.getFolder().addElements(Arrays.asList(uidls));
                    if (added > 0)
                        _so.pageChanged = true;
                    _so.newMails = added;
                    _so.connectError = null;
                    if (log.shouldInfo()) log.info("Added " + added + " new emails");
                } else {
                    if (log.shouldDebug()) log.debug("MailCache vanished?");
                }
                _mb.setNewMailListener(_so);
                _so.isFetching = false;
                _so.notifyAll();
            }
        }
    }


    /**
     * 
     * @param sessionObject
     * @param request
     * @return new state, or null if unknown
     */
    private static State processLogout(SessionObject sessionObject, RequestWrapper request, boolean isPOST, State state) {
        Log log = sessionObject.log;
        if (buttonPressed(request, LOGOUT) && isPOST) {
            if (log.shouldDebug()) log.debug("LOGOUT, REMOVING SESSION");
            HttpSession session = request.getSession();
            session.removeAttribute("sessionObject");
            session.invalidate();
            POP3MailBox mailbox = sessionObject.mailbox;
            if (mailbox != null) {
                mailbox.destroy();
                sessionObject.mailbox = null;
                sessionObject.caches.clear();
            }
            //sessionObject.info += _t("User logged out.") + '\n';
            state = State.AUTH;
        } else if (state == State.AUTH  &&
            !buttonPressed(request, CANCEL) && !buttonPressed(request, SAVE) &&
            !buttonPressed(request, LOGIN) &&
            (isPOST ||
            request.getParameter(CURRENT_FOLDER) != null ||
            request.getParameter(B64UIDL) != null ||
            request.getParameter(NEW_UIDL) != null ||
            request.getParameter(CUR_PAGE) != null)) {
            // AUTH will be passed in if mailbox is null
            // Check previous state
            if (log.shouldDebug()) log.debug("Lost connection, previous state was " + request.getParameter(DEBUG_STATE));
            //sessionObject.error += // _t("Internal error, lost connection.") + '\n' +
            //_t("User logged out.") + '\n';
        }
        return state;
    }

    /**
     * Process all buttons, which possibly change internal state.
     * Also processes ?show=x for a GET
     * 
     * @param sessionObject
     * @param request
     * @param isPOST disallow button pushes if false
     * @return new state, or null if unknown
     */
    private static State processStateChangeButtons(SessionObject sessionObject, RequestWrapper request,
                                                   boolean isPOST, State state) {
        /*
         * LOGIN/LOGOUT
         */
        if (isPOST)
            state = processLogin(sessionObject, request, state);

        state = processLogout(sessionObject, request, isPOST, state);
        if (state == State.AUTH)
            return state;
        // if loading, we can't get to states LIST/SHOW or it will block
        for (MailCache mc : sessionObject.caches.values()) {
            if (mc.isLoading())
                   return State.LOADING;
        }
        Log log = sessionObject.log;

        /*
         *  compose dialog
         *  NEW_SUBJECT may be empty but will be non-null
         */
        if (isPOST && request.getParameter(NEW_SUBJECT) != null) {
            // We have to make sure to get the state right even if
            // the user hit the back button previously
            if (buttonPressed(request, SEND) || buttonPressed(request, SAVE_AS_DRAFT)) {
                // always save as draft before sending
                String uidl = Base64.decodeToString(request.getParameter(NEW_UIDL));
                if (uidl == null)
                    uidl = I2PAppContext.getGlobalContext().random().nextLong() + "drft";
                StringBuilder draft = composeDraft(sessionObject, request);
                boolean ok = saveDraft(sessionObject, uidl, draft);
                if (ok) {
                    sessionObject.clearAttachments();
                }
                if (ok && buttonPressed(request, SAVE_AS_DRAFT)) {
                    sessionObject.info += _t("Draft saved.") + '\n';
                } else if (ok && buttonPressed(request, SEND)) {
                    MailCache toMC = sessionObject.caches.get(DIR_DRAFTS);
                    Draft mail = toMC != null ? (Draft) toMC.getMail(uidl, MailCache.FetchMode.CACHE_ONLY) : null;
                    if (mail != null) {
                        if (log.shouldDebug()) log.debug("Send mail: " + uidl);
                        ok = sendMail(sessionObject, mail);
                    } else {
                        // couldn't read it back in?
                        ok = false;
                        sessionObject.error += _t("Unable to save mail.") + '\n';
                        if (log.shouldDebug()) log.debug("Draft readback fail: " + uidl);
                    }
                }
                if (ok) {
                    // If we have a reference UIDL, go back to that
                    if (request.getParameter(B64UIDL) != null && buttonPressed(request, SEND))
                        state = State.SHOW;
                    else
                        state = State.LIST;
                } else {
                    state = State.NEW;
                }
                if (log.shouldDebug()) log.debug("State after save as draft: " + state);
            } else if (buttonPressed(request, CANCEL)) {
                // If we have a reference UIDL, go back to that
                if (request.getParameter(B64UIDL) != null)
                    state = State.SHOW;
                else
                    state = State.LIST;
                if (buttonPressed(request, DRAFT_EXISTS))
                    sessionObject.clearAttachments();
                else
                    sessionObject.deleteAttachments();
            }
        }
        /*
         * message dialog or config
         * Do not go through here if we were originally in NEW (handled above)
         */
        else if (isPOST) {
            if (buttonPressed(request, LIST) ||
                buttonPressed(request, PREVPAGE) ||
                buttonPressed(request, NEXTPAGE) ||
                buttonPressed(request, FIRSTPAGE) ||
                buttonPressed(request, LASTPAGE) ||
                buttonPressed(request, SETPAGESIZE) ||
                buttonPressed(request, MARKALL) ||
                buttonPressed(request, CLEAR) ||
                buttonPressed(request, INVERT) ||
                buttonPressed(request, SORT) ||
                buttonPressed(request, REFRESH)) {
                state = State.LIST;
            } else if (buttonPressed(request, PREV) ||
                       buttonPressed(request, NEXT) ||
                       buttonPressed(request, SAVE_AS)) {
                state = State.SHOW;
            } else if (buttonPressed(request, DELETE) ||
                       buttonPressed(request, REALLYDELETE) ||
                       buttonPressed(request, MOVE_TO)) {
                if (request.getParameter(B64UIDL) != null)
                    state = State.SHOW;
                else
                    state = State.LIST;
            } else if (buttonPressed(request, CANCEL)) {
                if (request.getParameter(B64UIDL) != null)
                    state = State.SHOW;
            } else if (buttonPressed(request, SWITCH_TO)) {
                    state = State.LIST;
                    sessionObject.reallyDelete = false;
            }
        } else if (buttonPressed(request, DOWNLOAD) ||
                   buttonPressed(request, RAW_ATTACHMENT) ||
                   buttonPressed(request, CID_ATTACHMENT)) {
            // GET params
            state = State.SHOW;
        } else if (buttonPressed(request, DRAFT_ATTACHMENT)) {
            // GET params
            state = State.NEW;
        }

        /*
         * buttons on both folder and message dialog
         */
            if (isPOST && buttonPressed(request, NEW)) {
                state = State.NEW;
            }

            boolean reply = false;
            boolean replyAll = false;
            boolean forward = false;

            if (buttonPressed(request, REPLY)) {
                reply = true;
            } else if (buttonPressed(request, REPLYALL)) {
                replyAll = true;
            } else if (buttonPressed(request, FORWARD)) {
                forward = true;
            }
            if (reply || replyAll || forward) {
                state = State.NEW;
                /*
                 * try to find message
                 */
                String uidl = null;
                if (state == State.LIST) {
                    // these buttons are now hidden on the folder page,
                    // but the idea is to use the first checked message
                    List<String> items = getCheckedItems(request);
                    if (!items.isEmpty()) {
                        String b64UIDL = items.get(0);
                        // This is the I2P Base64, not the encoder
                        uidl = Base64.decodeToString(b64UIDL);
                    }
                } else {
                    uidl = Base64.decodeToString(request.getParameter(B64UIDL));
                }

                if (uidl != null) {
                    MailCache mc = getCurrentMailCache(sessionObject, request);
                    Mail mail = (mc != null) ? mc.getMail(uidl, MailCache.FetchMode.ALL) : null;
                    /*
                     * extract original sender from Reply-To: or From:
                     */
                    MailPart part = mail != null ? mail.getPart() : null;
                    if (part != null) {
                        StringBuilderWriter text = new StringBuilderWriter();
                        String from = null, to = null, cc = null, bcc = null, subject = null;
                        List<Attachment> attachments = null;
                        if (reply || replyAll) {
                            if (mail.reply != null && Mail.validateAddress(mail.reply))
                                to = mail.reply;
                            else if (mail.sender != null && Mail.validateAddress(mail.sender))
                                to = mail.sender;
                            else
                                to = "";
                            subject = mail.subject;
                            if (!(subject.startsWith("Re:") ||
                                  subject.startsWith("re:") ||
                                  subject.startsWith("RE:") ||
                                  subject.startsWith(_t("Re:")))) {
                                subject = _t("Re:") + ' ' + subject;
                            }
                            PrintWriter pw = new PrintWriter(text);
                            pw.println(_t("On {0} {1} wrote:", mail.formattedDate + " UTC", to));
                            StringBuilderWriter text2 = new StringBuilderWriter();
                            PrintWriter pw2 = new PrintWriter(text2);
                            showPart(pw2, part, 0, TEXT_ONLY, HtmlMode.NONE);
                            pw2.flush();
                            String[] lines = DataHelper.split(text2.toString(), "\r\n");
                            for (int i = 0; i < lines.length; i++)
                                pw.println("> " + lines[i]);
                            pw.flush();
                        }
                        if (replyAll) {
                            /*
                             * extract additional recipients
                             */
                            StringBuilder buf = new StringBuilder();
                            String pad = "";
                            // TODO original recipients should be in the To: line, not the CC: line
                            if (mail.to != null) {
                                for (int i = 0; i < mail.to.length; i++) {
                                    buf.append(pad);
                                    buf.append(mail.to[i]);
                                    pad = ", ";
                                }
                            }
                            if (mail.cc != null) {
                                for (int i = 0; i < mail.cc.length; i++) {
                                    buf.append(pad);
                                    buf.append(mail.cc[i]);
                                    pad = ", ";
                                }
                            }
                            if (buf.length() > 0)
                                cc = buf.toString();
                        }
                        I2PAppContext ctx = I2PAppContext.getGlobalContext();
                        if (forward) {
                            List<MailPart> parts = part.parts;
                            if (!parts.isEmpty()) {
                                // Copy each valid attachment from the mail to a file
                                // in Drafts/attachments and add to list
                                // This is similar to the add attachment code in processComposeButtons()
                                attachments = new ArrayList<Attachment>(parts.size());
                                MailCache drafts = sessionObject.caches.get(DIR_DRAFTS);
                                for (MailPart mp : parts) {
                                    if (mp.name == null || mp.type == null || mp.encoding == null) {
                                        if (log.shouldDebug())
                                            log.debug("Skipping forwarded attachment: " + mp);
                                        continue;
                                    }
                                    String temp = "susimail-attachment-" + ctx.random().nextLong();
                                    File f;
                                    if (drafts != null) {
                                        f = new File(drafts.getAttachmentDir(), temp);
                                    } else {
                                        f = new File(ctx.getTempDir(), temp);
                                    }
                                    Buffer out = new FileBuffer(f);
                                    boolean ok = false;
                                    try {
                                        mp.decode(0, out);
                                        ok = true;
                                        attachments.add(new Attachment(mp.name, mp.type, mp.encoding, f));
                                    } catch (IOException e) {
                                        sessionObject.error += _t("Error reading uploaded file: {0}", e.getMessage()) + '\n';
                                    } finally {
                                        out.writeComplete(ok);
                                    }
                                }
                            }
                            subject = mail.subject;
                            if (!(subject.startsWith("Fwd:") ||
                                  subject.startsWith("fwd:") ||
                                  subject.startsWith("FWD:") ||
                                  subject.startsWith("Fw:") ||
                                  subject.startsWith("fw:") ||
                                  subject.startsWith("FW:") ||
                                  subject.startsWith(_t("Fwd:")))) {
                                subject = _t("Fwd:") + ' ' + subject;
                            }
                            String sender = null;
                            if (mail.reply != null && Mail.validateAddress(mail.reply))
                                sender = Mail.getAddress(mail.reply);
                            else if (mail.sender != null && Mail.validateAddress(mail.sender))
                                sender = Mail.getAddress(mail.sender);

                            PrintWriter pw = new PrintWriter(text);
                            pw.println();
                            pw.println();
                            pw.println();
                            pw.println("---- " + _t("begin forwarded mail") + " ----");
                            pw.println("From: " + sender);
                            if (mail.to != null && mail.to.length > 0)
                                Mail.appendRecipients(pw, mail.to, "To: ");
                            if (mail.cc != null && mail.cc.length > 0)
                                Mail.appendRecipients(pw, mail.cc, "Cc: ");
                            if (mail.dateString != null)
                                pw.print("Date: " + mail.dateString);
                            pw.println();
                            showPart(pw, part, 0, TEXT_ONLY, HtmlMode.NONE);
                            pw.println("----  " + _t("end forwarded mail") + "  ----");
                            pw.flush();
                        }
                        // Store as draft here, put draft UIDL in sessionObject,
                        // then P-R-G in processRequest()
                        StringBuilder draft = composeDraft(sessionObject, from, to, cc, bcc,
                                                           subject, text.toString(), attachments);
                        String draftuidl = ctx.random().nextLong() + "drft";
                        boolean ok = saveDraft(sessionObject, draftuidl, draft);
                        if (ok) {
                            sessionObject.draftUIDL = draftuidl;
                        } else {
                            sessionObject.error += _t("Unable to save mail.") + '\n';
                            log.error("Unable to save as draft: " + draftuidl);
                        }
                        state = State.NEW;
                    }
                    else {
                        sessionObject.error += _t("Could not fetch mail body.") + '\n';
                    } // part != null
                } // uidl != null
            }  // reply/fwd

        // Set state if unknown
        if (state == null) {
            if (request.getParameter(CONFIG_TEXT) != null || buttonPressed(request, CONFIGURE))
                state = State.CONFIG;
            else if (request.getParameter(SHOW) != null)
                state = State.SHOW;
            else if (request.getParameter(NEW_UIDL) != null)
                state = State.NEW;
            else
                state = State.LIST;
        }

        /*
         * folder view
         * SHOW is the one parameter that's a link, not a button, so we allow it for GET
         */
        if (state == State.LIST || state == State.SHOW) {
            /*
             * check if user wants to view a message
             */
            String show = request.getParameter(SHOW);
            if (show != null && show.length() > 0) {
                // This is the I2P Base64, not the encoder
                String uidl = Base64.decodeToString(show);
                if (uidl != null) {
                    state = State.SHOW;
                } else {
                    sessionObject.error += _t("Message id not valid.") + '\n';
                }
            }
        }
        return state;
    }

    /**
     * Returns e.g. 3,5 for ?check3=1&check5=1 (or POST equivalent)
     * @param request
     * @return non-null List of Base64 UIDLs, or attachment numbers as Strings
     */
    private static List<String> getCheckedItems(RequestWrapper request) {
        List<String> rv = new ArrayList<String>(8);
        for (Enumeration<String> e = request.getParameterNames(); e.hasMoreElements();) {
            String parameter = e.nextElement();
            if (parameter.startsWith("check") && request.getParameter(parameter).equals("1")) {
                String item = parameter.substring(5);
                rv.add(item);
            }
        }
        return rv;
    }

    /**
     * @param sessionObject
     * @param request
     * @return new state
     */
    private static State processGenericButtons(SessionObject sessionObject, RequestWrapper request, State state) {
        // these two buttons are only on the folder view now
        /**** All RELOAD buttons are commented out
        if (buttonPressed(request, RELOAD)) {
            Config.reloadConfiguration();
            int oldPageSize = sessionObject.folder.getPageSize();
            int pageSize = Config.getProperty(Folder.PAGESIZE, Folder.DEFAULT_PAGESIZE);
            if (pageSize != oldPageSize)
                sessionObject.folder.setPageSize(pageSize);
            sessionObject.info = _t("Configuration reloaded");
        }
        ****/
        if (buttonPressed(request, REFRESH)) {
            POP3MailBox mailbox = sessionObject.mailbox;
            if (mailbox == null) {
                sessionObject.error += _t("Internal error, lost connection.") + '\n';
                return State.AUTH;
            }
            if (sessionObject.isFetching) {
                // shouldn't happen, button disabled
                return state;
            }
            Log log = sessionObject.log;
            sessionObject.isFetching = true;
            ConnectWaiter cw = new ConnectWaiter(sessionObject);
            if (mailbox.connectToServer(cw)) {
                // mailbox will callback to cw
            } else {
                sessionObject.error += _t("Cannot connect") + '\n';
                sessionObject.isFetching = false;
            }
            // wait if it's going to be quick
            try {
                sessionObject.wait(3000);
            } catch (InterruptedException ie) {
                if (log.shouldDebug()) log.debug("Interrupted waiting for connect", ie);
            }
        }
        return state;
    }

    /**
     * process buttons of compose message dialog
     * This must be called BEFORE processStateChangeButtons so we can add the attachment before SEND
     *
     * @param sessionObject
     * @param request
     * @return new state, or null if unknown
     */
    private static State processComposeButtons(SessionObject sessionObject, RequestWrapper request) {
        State state = null;
        String filename = request.getFilename(NEW_FILENAME);
        // We handle an attachment whether sending or uploading
        if (filename != null && filename.length() > 0 &&
            (buttonPressed(request, NEW_UPLOAD) || buttonPressed(request, SEND) || buttonPressed(request, SAVE_AS_DRAFT))) {
            int i = filename.lastIndexOf('/');
            if (i != - 1)
                filename = filename.substring(i + 1);
            i = filename.lastIndexOf('\\');
            if (i != -1)
                filename = filename.substring(i + 1);
            if (filename.length() > 0) {
                InputStream in = null;
                OutputStream out = null;
                I2PAppContext ctx = I2PAppContext.getGlobalContext();
                String temp = "susimail-attachment-" + ctx.random().nextLong();
                File f;
                MailCache drafts = sessionObject.caches.get(DIR_DRAFTS);
                if (drafts != null) {
                    // preferably save across restarts
                    f = new File(drafts.getAttachmentDir(), temp);
                } else {
                    f = new File(ctx.getTempDir(), temp);
                }
                try {
                        in = request.getInputStream(NEW_FILENAME);
                    if (in == null)
                        throw new IOException("no stream");
                    out = new SecureFileOutputStream(f);
                    DataHelper.copy(in, out);
                    String contentType = request.getContentType(NEW_FILENAME);
                    String encodeTo;
                    String ctlc = contentType.toLowerCase(Locale.US);
                    if (ctlc.startsWith("text/")) {
                        encodeTo = "quoted-printable";
                        // Is this a better guess than the platform encoding?
                        // Either is a better guess than letting the receiver
                        // interpret it as ISO-8859-1
                        if (!ctlc.contains("charset="))
                            contentType += "; charset=utf-8";
                    } else {
                        encodeTo = "base64";
                    }
                    Encoding encoding = EncodingFactory.getEncoding(encodeTo);
                    if (encoding != null) {
                        if (sessionObject.attachments == null)
                            sessionObject.attachments = new ArrayList<Attachment>();
                        sessionObject.attachments.add(
                            new Attachment(filename, contentType, encodeTo, f)
                      );
                        // Save the draft
                        String uidl = Base64.decodeToString(request.getParameter(NEW_UIDL));
                        if (uidl != null) {
                            StringBuilder draft = composeDraft(sessionObject, request);
                            saveDraft(sessionObject, uidl, draft);
                        }
                    } else {
                        sessionObject.error += _t("No Encoding found for {0}", encodeTo) + '\n';
                    }
                } catch (IOException e) {
                    sessionObject.error += _t("Error reading uploaded file: {0}", e.getMessage()) + '\n';
                    f.delete();
                } finally {
                    if (in != null) try { in.close(); } catch (IOException ioe) {}
                    if (out != null) try { out.close(); } catch (IOException ioe) {}
                }
            }
            state = State.NEW;
        }
        else if (sessionObject.attachments != null && buttonPressed(request, DELETE_ATTACHMENT)) {
            boolean deleted = false;
            for (String item : getCheckedItems(request)) {
                try {
                    int n = Integer.parseInt(item);
                    for (int i = 0; i < sessionObject.attachments.size(); i++) {
                        Attachment attachment = sessionObject.attachments.get(i);
                        if (attachment.hashCode() == n) {
                            sessionObject.attachments.remove(i);
                            attachment.deleteData();
                            deleted = true;
                            break;
                        }
                    }
                } catch (NumberFormatException nfe) {}
            }
            // Save the draft or else the attachment comes back
            if (deleted) {
                String uidl = Base64.decodeToString(request.getParameter(NEW_UIDL));
                if (uidl != null) {
                    StringBuilder draft = composeDraft(sessionObject, request);
                    saveDraft(sessionObject, uidl, draft);
                }
            }
            state = State.NEW;
        }
        return state;
    }

    /**
     * process buttons of message view
     * @param sessionObject
     * @param request
     * @return the next UIDL to see (if PREV/NEXT pushed), or null (if REALLYDELETE pushed), or showUIDL,
     *         or "delete" (if DELETE pushed). If null, next state should be LIST.
     */
    private static String processMessageButtons(SessionObject sessionObject, String showUIDL, RequestWrapper request) {
        if (buttonPressed(request, PREV)) {
            String b64UIDL = request.getParameter(PREV_B64UIDL);
            String uidl = Base64.decodeToString(b64UIDL);
            return uidl;
        }
        if (buttonPressed(request, NEXT)) {
            String b64UIDL = request.getParameter(NEXT_B64UIDL);
            String uidl = Base64.decodeToString(b64UIDL);
            return uidl;
        }

        if (buttonPressed(request, DELETE)) {
            MailCache mc = getCurrentMailCache(sessionObject, request);
            if (mc != null && mc.getFolderName().equals(DIR_TRASH)) {
                // Delete from Trash does not require confirmation
                mc.delete(showUIDL);
                mc.getFolder().removeElement(showUIDL);
                return null;
            }
            // processRequest() will P-R-G to &delete=1
            // We do not keep this indication in the session object.
            return DELETE;
        }
        if (buttonPressed(request, REALLYDELETE)) {
            MailCache mc = getCurrentMailCache(sessionObject, request);
            if (mc != null) {
                mc.delete(showUIDL);
                mc.getFolder().removeElement(showUIDL);
            }
            return null;
        }
        if (buttonPressed(request, MOVE_TO)) {
            String uidl = Base64.decodeToString(request.getParameter(B64UIDL));
            String from = request.getParameter(CURRENT_FOLDER);
            String to = request.getParameter(NEW_FOLDER);
            if (uidl != null && from != null && to != null) {
                MailCache fromMC = sessionObject.caches.get(from);
                MailCache toMC = sessionObject.caches.get(to);
                if (fromMC != null && toMC != null) {
                    waitForLoad(sessionObject, fromMC);
                    waitForLoad(sessionObject, toMC);
                    if (fromMC.moveTo(uidl, toMC)) {
                        // success
                        return null;
                    }
                }
            }
            sessionObject.error += "Failed move to " + to + '\n';
        }
        return showUIDL;
    }

    /**
     * process download link in message view
     * @param sessionObject
     * @param request
     * @return If true, we sent an attachment or 404, do not send any other response
     */
    private static boolean processDownloadLink(SessionObject sessionObject, String showUIDL,
                                               RequestWrapper request, HttpServletResponse response) {
        String str = request.getParameter(DOWNLOAD);
        boolean isRaw = false;
        boolean isCID = false;
        if (str == null) {
            str = request.getParameter(RAW_ATTACHMENT);
            if (str == null) {
                str = request.getParameter(CID_ATTACHMENT);
                isCID = true;
            }
            isRaw = true;
        }
        if (str != null) {
            try {
                MailCache mc = getCurrentMailCache(sessionObject, request);
                Mail mail = (mc != null) ? mc.getMail(showUIDL, MailCache.FetchMode.ALL) : null;
                MailPart part = null;
                if (mail != null) {
                    if (isCID) {
                        // strip @ part, see RFC 2045
                        // https://stackoverflow.com/questions/39577386/the-precise-format-of-content-id-header
                        int idx = str.indexOf('@');
                        if (idx > 0)
                            str = str.substring(0, idx);
                        part = getMailPartFromID(mail.getPart(), str);
                    } else {
                        int id = Integer.parseInt(str);
                        part = getMailPartFromID(mail.getPart(), id);
                    }
                }
                if (part != null) {
                    if (sendAttachment(sessionObject, part, response, isRaw))
                        return true;
                }
            } catch(NumberFormatException nfe) {}
            // error if we get here
            try {
                response.sendError(404, _t("Attachment not found."));
            } catch (IOException ioe) {}
            return true;
        }
        return false;
    }


/**
     * Process thumbnail link in compose view
     * Draft attachments are stored in the SessionObject and identified by hashcode only.
     *
     * @since 0.9.62
     */
    private static void processDraftAttachmentLink(SessionObject sessionObject,
                                                   RequestWrapper request, HttpServletResponse response) {
        String str = request.getParameter(DRAFT_ATTACHMENT);
        if (str != null) {
            InputStream in = null;
            OutputStream out = null;
            try {
                if (sessionObject.attachments != null) {
                    int hc = Integer.parseInt(str);
                    for (Attachment att : sessionObject.attachments) {
                        if (hc == att.hashCode()) {
                            String ct = att.getContentType();
                            if (ct != null)
                                response.setContentType(ct);
                            response.setContentLength((int) att.getSize());
                            response.setHeader("Cache-Control", "private, max-age=3600");
                            in = att.getData();
                            out = response.getOutputStream();
                            DataHelper.copy(in, out);
                            return;
                        }
                    }
                }
            } catch (NumberFormatException nfe) {
            } catch (IOException ioe) {
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
                if (out != null) try { out.close(); } catch (IOException ioe) {}
            }
        }
        // error if we get here
        try {
            response.sendError(404, _t("Attachment not found."));
        } catch (IOException ioe) {}
    }

    /**
     * Process save-as link in message view
     *
     * @param sessionObject
     * @param request
     * @return If true, we sent the file or 404, do not send any other response
     * @since 0.9.18
     */
    private static boolean processSaveAsLink(SessionObject sessionObject, String showUIDL,
                                             RequestWrapper request, HttpServletResponse response) {
        String str = request.getParameter(SAVE_AS);
        if (str == null)
            return false;
        MailCache mc = getCurrentMailCache(sessionObject, request);
        Mail mail = (mc != null) ? mc.getMail(showUIDL, MailCache.FetchMode.ALL) : null;
        if (mail != null) {
            if (sendMailSaveAs(sessionObject, mail, response))
                return true;
        }
        // error if we get here
        sessionObject.error += _t("Message not found.");
        try {
            response.sendError(404, _t("Message not found."));
        } catch (IOException ioe) {}
        return true;
    }

    /**
     * Recursive.
     * @param id as retrieved from getID()
     * @return the part or null
     */
    private static MailPart getMailPartFromID(MailPart part, int id) {
        if (part == null)
            return null;

        if (part.getID() == id)
            return part;

        if (part.multipart || part.message) {
            for (MailPart p : part.parts) {
                MailPart subPart = getMailPartFromID(p, id);
                if (subPart != null)
                    return subPart;
            }
        }
        return null;
    }

    /**
     * Recursive.
     * @param id a content-id, without the surrounding &lt;&gt; or trailing @ part
     * @return the part or null
     * @since 0.9.62
     */
    private static MailPart getMailPartFromID(MailPart part, String id) {
        if (part == null)
            return null;
        if (id.equals(part.cid))
            return part;
        if (part.cid != null) {
            // strip @ and try again,
            int idx = part.cid.indexOf('@');
            if (idx > 0) {
                if (id.equals(part.cid.substring(0, idx)))
                    return part;
            }
        }
        if (part.multipart || part.message) {
            for (MailPart p : part.parts) {
                MailPart subPart = getMailPartFromID(p, id);
                if (subPart != null)
                    return subPart;
            }
        }
        return null;
    }

    /**
     * process buttons of folder view
     * @param sessionObject
     * @param page the current page
     * @param request
     * @param return the new page
     */
    private static int processFolderButtons(SessionObject sessionObject, int page, RequestWrapper request) {
        /*
         * process paging buttons
         */

        /* not on the folder view any more, handled in processConfigButtons()
        if (buttonPressed(request, SETPAGESIZE)) {
            try {
                int pageSize = Math.max(5, Integer.parseInt(request.getParameter(PAGESIZE)));
                int oldPageSize = sessionObject.folder.getPageSize();
                if (pageSize != oldPageSize)
                    sessionObject.folder.setPageSize(pageSize);
            }
            catch(NumberFormatException nfe) {
                sessionObject.error += _t("Invalid pagesize number, resetting to default value.") + '\n';
            }
        }
        */

        if (buttonPressed(request, PREVPAGE)) {
            String sp = request.getParameter(PREV_PAGE_NUM);
            if (sp != null) {
                try {
                    page = Integer.parseInt(sp);
                    sessionObject.pageChanged = true;
                } catch (NumberFormatException nfe) {}
            }
        }
        else if (buttonPressed(request, NEXTPAGE)) {
            String sp = request.getParameter(NEXT_PAGE_NUM);
            if (sp != null) {
                try {
                    page = Integer.parseInt(sp);
                    sessionObject.pageChanged = true;
                } catch (NumberFormatException nfe) {}
            }
        }
        else if (buttonPressed(request, FIRSTPAGE)) {
            sessionObject.pageChanged = true;
            page = 1;
        }
        else if (buttonPressed(request, LASTPAGE)) {
            sessionObject.pageChanged = true;
            Folder<String> folder = getCurrentFolder(sessionObject, request);
            page = (folder != null) ? folder.getPages() : 1;
        } else if (buttonPressed(request, DELETE) ||
                   buttonPressed(request, REALLYDELETE)) {
            List<String> b64uidls = getCheckedItems(request);
            int m = b64uidls.size();
            if (m > 0) {
                MailCache mc = getCurrentMailCache(sessionObject, request);
                if (mc == null) {
                    sessionObject.error += "Internal error, no folder\n";
                    sessionObject.reallyDelete = false;
                } else if (mc.getFolderName().equals(DIR_TRASH) ||
                           buttonPressed(request, REALLYDELETE)) {
                    // Delete from Trash does not require confirmation
                    List<String> toDelete = new ArrayList<String>(m);
                    for (String b64UIDL : b64uidls) {
                        // This is the I2P Base64, not the encoder
                        String uidl = Base64.decodeToString(b64UIDL);
                        if (uidl != null)
                            toDelete.add(uidl);
                    }
                    int numberDeleted = toDelete.size();
                    if (numberDeleted > 0) {
                        mc.delete(toDelete);
                        mc.getFolder().removeElements(toDelete);
                        sessionObject.pageChanged = true;
                        sessionObject.info += ngettext("1 message deleted.", "{0} messages deleted.", numberDeleted);
                    } else {
                        sessionObject.error += _t("No messages marked for deletion.") + '\n';
                    }
                    sessionObject.reallyDelete = false;
                } else {
                    // show 'really delete' message
                    sessionObject.reallyDelete = true;
                }
            } else {
                sessionObject.reallyDelete = false;
                sessionObject.error += _t("No messages marked for deletion.") + '\n';
            }
        } else if (buttonPressed(request, CLEAR)) {
            sessionObject.reallyDelete = false;
        }

        sessionObject.markAll = buttonPressed(request, MARKALL);
        sessionObject.clear = buttonPressed(request, CLEAR);
        sessionObject.invert = buttonPressed(request, INVERT);
        return page;
    }

    /*
     * process sorting buttons
     */
    private static void processSortingButtons(SessionObject sessionObject, RequestWrapper request) {
        // GET param
        String str = request.getParameter(SORT);
        // POST param
        if (str == null)
            str = request.getParameter(CURRENT_SORT);
        if (str != null && VALID_SORTS.contains(str)) {
            SortOrder order;
            // UP is reverse sort. DOWN is normal sort.
            if (str.startsWith("-")) {
                order = SortOrder.UP;
                str = str.substring(1);
            } else {
                order = SortOrder.DOWN;
            }
            // Store in session. processRequest() will re-sort if necessary.
            Folder<String> folder = getCurrentFolder(sessionObject, request);
            if (folder != null)
                folder.setSortBy(str, order);
        }
    }

    /*
     * process config buttons, both entering and exiting
     * @param isPOST disallow button pushes if false
     * @return new state, or null if unknown
     */
    private static State processConfigButtons(SessionObject sessionObject, RequestWrapper request, boolean isPOST, State state) {
        if (buttonPressed(request, CONFIGURE)) {
            return state.CONFIG;
        }
        // If no config text, we can't be on the config page,
        // and we don't want to process the CANCEL button which
        // is also on the compose page.
        if (!isPOST || request.getParameter(CONFIG_TEXT) == null)
            return state;
        if (buttonPressed(request, SAVE)) {
            try {
                String raw = request.getParameter(CONFIG_TEXT);
                Properties props = new Properties();
                DataHelper.loadProps(props, new ByteArrayInputStream(DataHelper.getUTF8(raw)));
                // for safety, disallow changing host via UI
                String oldHost = Config.getProperty(CONFIG_HOST, DEFAULT_HOST);
                String newHost = props.getProperty(CONFIG_HOST);
                if (newHost == null) {
                    props.setProperty(CONFIG_HOST, oldHost);
                } else if (!newHost.equals(oldHost) && !newHost.equals("localhost")) {
                    props.setProperty(CONFIG_HOST, oldHost);
                    File cfg = new File(I2PAppContext.getGlobalContext().getConfigDir(), "susimail.config");
                    sessionObject.error += _t("Host unchanged. Edit configation file {0} to change host.", cfg.getAbsolutePath()) + '\n';
                }
                String ps = props.getProperty(Folder.PAGESIZE);
                Folder<String> folder = getCurrentFolder(sessionObject, request);
                if (folder != null && ps != null) {
                    try {
                        int pageSize = Math.max(5, Integer.parseInt(request.getParameter(PAGESIZE)));
                        int oldPageSize = folder.getPageSize();
                        if (pageSize != oldPageSize)
                            folder.setPageSize(pageSize);
                    } catch(NumberFormatException nfe) {}
                }
                Config.saveConfiguration(props);
                String dbg = props.getProperty(CONFIG_DEBUG);
                if (dbg != null) {
                    boolean release = !Boolean.parseBoolean(dbg);
                    Log log = sessionObject.log;
                    log.setMinimumPriority(release ? Log.ERROR : Log.DEBUG);
                }
                state = folder != null ? State.LIST : State.AUTH;
                sessionObject.info = _t("Configuration saved");
            } catch (IOException ioe) {
                sessionObject.error = ioe.toString();
            }
        } else if (buttonPressed(request, SETPAGESIZE)) {
            Folder<String> folder = getCurrentFolder(sessionObject, request);
            try {
                int pageSize = Math.max(5, Integer.parseInt(request.getParameter(PAGESIZE)));
                if (folder != null) {
                    int oldPageSize = folder.getPageSize();
                    if (pageSize != oldPageSize)
                        folder.setPageSize(pageSize);
                    state = State.LIST;
                } else {
                    state = State.AUTH;
                }
                Properties props = Config.getProperties();
                props.setProperty(Folder.PAGESIZE, String.valueOf(pageSize));
                Config.saveConfiguration(props);
            } catch (IOException ioe) {
                sessionObject.error = ioe.toString();
            } catch(NumberFormatException nfe) {
                sessionObject.error += _t("Invalid pagesize number, resetting to default value.") + '\n';
            }
        } else if (buttonPressed(request, CANCEL)) {
            Folder<String> folder = getCurrentFolder(sessionObject, request);
            state = (folder != null) ? State.LIST : State.AUTH;
        }
        return state;
    }

    //// End state change and button processing ////

    /**
     * @param httpSession
     * @return non-null
     */
    private synchronized SessionObject getSessionObject(HttpSession httpSession) {
        SessionObject sessionObject = null;
        try {
            sessionObject = (SessionObject)httpSession.getAttribute("sessionObject");
        } catch (IllegalStateException ise) {}
        if (sessionObject == null) {
            sessionObject = new SessionObject(_log);
            try {
                httpSession.setAttribute("sessionObject", sessionObject);
            } catch (IllegalStateException ise) {}
            if (_log.shouldDebug()) _log.debug("NEW session " + httpSession.getId());
        } else {
            if (_log.shouldDebug()) {
                try {
                    _log.debug("Existing session " + httpSession.getId() +
                               "\n* Created: " + new Date(httpSession.getCreationTime()));
                } catch (IllegalStateException ise) {}
            }
        }
        return sessionObject;
    }

    /**
     * Either mobile or text browser
     * Copied from net.i2p.router.web.CSSHelper
     * @param ua null ok
     * @since 0.9.7
     */
    private static boolean isMobile(String ua) {
        if (ua == null)
            return false;
        return ServletUtil.isSmallBrowser(ua);
    }

    /**
     *  @return folder or null
     *  @since 0.9.35
     */
    private static MailCache getCurrentMailCache(SessionObject session, RequestWrapper request) {
        String folderName = (buttonPressed(request, SWITCH_TO) || buttonPressed(request, MOVE_TO)) ?
                            request.getParameter(NEW_FOLDER) : null;
        if (folderName == null) {
            if (buttonPressed(request, SAVE_AS_DRAFT) || buttonPressed(request, NEW_UIDL) ||
                (buttonPressed(request, CANCEL) && request.getParameter(NEW_SUBJECT) != null)) {
                folderName = DIR_DRAFTS;
            } else {
                folderName = request.getParameter(CURRENT_FOLDER);
                if (folderName == null)
                    folderName = DIR_FOLDER;
            }
        }
        MailCache rv = session.caches.get(folderName);
        if (rv == null) {
            // only show error if logged in
            if (DIR_FOLDER.equals(folderName)) {
                if (session.user != null)
                    session.error += "Cannot load Inbox\n";
            } else {
                if (session.user != null)
                    session.error += "Folder not found: " + folderName + '\n';
                rv = session.caches.get(DIR_FOLDER);
                if (rv == null && session.user != null)
                    session.error += "Cannot load Inbox\n";
            }
        }
        return rv;
    }

    /**
     *  @return folder or null
     *  @since 0.9.35
     */
    private static Folder<String> getCurrentFolder(SessionObject session, RequestWrapper request) {
        MailCache mc = getCurrentMailCache(session, request);
        return (mc != null) ? mc.getFolder() : null;
    }

    /**
     *  Blocking wait
     *  @since 0.9.35
     */
    private static void waitForLoad(SessionObject sessionObject, MailCache mc) {
        if (!mc.isLoaded()) {
            boolean ok = true;
            if (!mc.isLoading())
                ok = mc.loadFromDisk(new LoadWaiter(sessionObject, mc));
            if (ok) {
                while (mc.isLoading()) {
                    try {
                        sessionObject.wait(5000);
                    } catch (InterruptedException ie) {
                        Log log = sessionObject.log;
                        if (log.shouldDebug()) log.debug("Interrupted waiting for load", ie);
                    }
                }
            }
        }
    }

    //// Start request handling here ////

    /**
     * The entry point for all web page loads
     * 
     * @param httpRequest
     * @param response
     * @param isPOST disallow button pushes if false
     * @throws IOException
     * @throws ServletException
     */
    private void processRequest(HttpServletRequest httpRequest, HttpServletResponse response, boolean isPOST)
    throws IOException, ServletException {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        // Fetch routerconsole theme (or use our default if it doesn't exist)
        String theme = ctx.getProperty(RC_PROP_THEME, DEFAULT_THEME);
        boolean universalTheming = ctx.getBooleanProperty(RC_PROP_UNIVERSAL_THEMING);
        if (universalTheming) {
            // Ensure that theme exists
            String[] themes = getThemes(ctx);
            boolean themeExists = false;
            for (int i = 0; i < themes.length; i++) {
                if (themes[i].equals(theme)) {
                    themeExists = true;
                    break;
                }
            }
            if (!themeExists) {
                theme = DEFAULT_THEME;
            }
        } else {
            // Apply any override
            theme = Config.getProperty(CONFIG_THEME, theme);
        }

        boolean forceMobileConsole = ctx.getBooleanProperty(RC_PROP_FORCE_MOBILE_CONSOLE);
        boolean enableSoraFont = ctx.getBooleanProperty(RC_PROP_ENABLE_SORA_FONT);
        boolean isMobile = (forceMobileConsole || isMobile(httpRequest.getHeader("User-Agent")));

        httpRequest.setCharacterEncoding("UTF-8");
        //response.setHeader("X-Frame-Options", "SAMEORIGIN");
        // very strict CSP for HTML emails in iframes
        if (httpRequest.getParameter(RAW_ATTACHMENT) != null ||
            httpRequest.getParameter(CID_ATTACHMENT) != null ||
            httpRequest.getParameter(DRAFT_ATTACHMENT) != null) {
            // we don't even let the iframe get to the console, it must be within /susimail/
            String me = "127.0.0.1:7657";
            String requrl = httpRequest.getRequestURL().toString();
            try {
                URI uri = new URI(requrl);
                me = uri.getHost() + ':' + uri.getPort();
            } catch(URISyntaxException use) {}
            // img-src allows for cid: urls that were fixed up by RegexOutputStream
            // character encoding will be set in sendAttachment()
            response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'self' 'unsafe-inline'; " +
                               "script-src " + me + "/js/iframeResizer/iframeResizer.contentWindow.js " +
                               me + "/susimail/js/htmlView.js " + me + "/susimail/js/sanitizeHTML.js 'nonce-" + cspNonce + "'; " +
                               "form-action 'none'; frame-ancestors " + me + myself + "; object-src 'none'; media-src 'none'; img-src " +
                               me + myself + " data:; font-src 'self'; frame-src 'none'; worker-src 'none'");
        } else {
            response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'nonce-" + cspNonce + "'; " +
                               "form-action 'self'; frame-ancestors 'self'; object-src 'none'; media-src 'none'; img-src 'self' data:;");
            response.setCharacterEncoding("UTF-8");
        }
        //response.setHeader("X-Frame-Options", "SAMEORIGIN");
        //response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; media-src 'none'");
        response.setHeader("X-XSS-Protection", "1; mode=block");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "accelerometer=(), ambient-light-sensor=(), autoplay=(), battery=(), camera=(), " +
                           "display-capture(), fullscreen=(self), geolocation=(), gyroscope=(), interest-cohort=(), magnetometer=(), " +
                           "microphone=(), midi=(), payment=(), usb=(), vibrate=(), vr=()");
        response.setHeader("Accept-Ranges", "none");
        RequestWrapper request = new RequestWrapper(httpRequest);
        SessionObject sessionObject = null;
        String subtitle = "";
        HttpSession httpSession = request.getSession(true);
        sessionObject = getSessionObject(httpSession);

        synchronized(sessionObject) {
            sessionObject.pageChanged = false;
            sessionObject.themePath = "/themes/susimail/" + theme + '/';
            sessionObject.imgPath = sessionObject.themePath + "images/";
            sessionObject.isMobile = isMobile;

            if (isPOST) {
                // TODO not perfect, but only clear on POST so they survive a P-R-G
                sessionObject.error = "";
                sessionObject.info = "";
                try {
                    String nonce = request.getParameter(SUSI_NONCE);
                    if (nonce == null || !sessionObject.isValidNonce(nonce)) {
                        // These two strings are already in the router console FormHandler,
                        // so translate with that bundle.
                        sessionObject.error += consoleGetString(
                            "Invalid form submission, probably because you used the 'back' or 'reload' button on your browser. Please resubmit.",
                            ctx)
                            + '\n' +
                            consoleGetString("If the problem persists, verify that you have cookies enabled in your browser.",
                            ctx)
                            + '\n';
                        isPOST = false;
                    }
                } catch (IllegalStateException ise) {
                    // too big, can't get any parameters
                    sessionObject.error += ise.getMessage() + '\n';
                    isPOST = false;
                }
            }

            //// Start state determination and button processing

            State state = null;
            if (sessionObject.mailbox == null) {
                state = State.AUTH;
            } else if (isPOST) {
                // This must be called to add the attachment before
                // processStateChangeButtons() sends the message
                state = processComposeButtons(sessionObject, request);
            }
            state = processStateChangeButtons(sessionObject, request, isPOST, state);
            state = processConfigButtons(sessionObject, request, isPOST, state);
            if (_log.shouldDebug()) _log.debug("Prelim. state is " + state);
            if (state == State.CONFIG) {
                if (isPOST) {
                    // P-R-G
                    String q = '?' + CONFIGURE;
                    sendRedirect(httpRequest, response, q);
                    return;
                }
            }
            if (state == State.LOADING) {
                if (isPOST) {
                    String q = null;
                    if (buttonPressed(request, SAVE_AS_DRAFT)) {
                        q = '?' + CURRENT_FOLDER + '=' + DIR_DRAFTS;
                    } else {
                        String str = request.getParameter(NEW_FOLDER);
                        if (str == null) {
                            str = request.getParameter(CURRENT_FOLDER);
                            if (str != null && !str.equals(DIR_FOLDER) &&
                                (buttonPressed(request, SWITCH_TO) || buttonPressed(request, MOVE_TO)))
                                q = '?' + CURRENT_FOLDER + '=' + str;
                        }
                    }
                    sendRedirect(httpRequest, response, q);
                    return;
                }
            }

            // Set in web.xml
            //if (oldState == State.AUTH && newState != State.AUTH) {
            //int oldIdle = httpSession.getMaxInactiveInterval();
            //httpSession.setMaxInactiveInterval(60*60*24);  // seconds
            //int newIdle = httpSession.getMaxInactiveInterval();
            //if (_log.shouldDebug()) _log.debug("Changed idle from " + oldIdle + " to " + newIdle);
            //}

            if (state != State.AUTH) {
                if (isPOST)
                       state = processGenericButtons(sessionObject, request, state);
            }

            if (state == State.LIST) {
                if (isPOST) {
                    int page = 1;
                    String sp = request.getParameter(CUR_PAGE);
                    if (sp != null) {
                        try {
                            page = Integer.parseInt(sp);
                        } catch (NumberFormatException nfe) {}
                    }
                    int newPage = processFolderButtons(sessionObject, page, request);
                    // LIST is from SHOW page, SEND and CANCEL are from NEW page
                    // OFFLINE and LOGIN from login page
                    // REFRESH on list page
                    if (newPage != page || buttonPressed(request, LIST) ||
                        buttonPressed(request, SEND) || buttonPressed(request, CANCEL) ||
                        buttonPressed(request, REFRESH) ||
                        buttonPressed(request, SWITCH_TO) || buttonPressed(request, MOVE_TO) ||
                        buttonPressed(request, LOGIN) || buttonPressed(request, OFFLINE)) {
                        // P-R-G
                        String q = '?' + CUR_PAGE + '=' + newPage;
                        // CURRENT_SORT is only in page POSTs
                        String str = request.getParameter(CURRENT_SORT);
                        if (str != null && !str.equals(SORT_DEFAULT) && VALID_SORTS.contains(str))
                            q += '&' + SORT + '=' + str;
                        str = null;
                        if (buttonPressed(request, SWITCH_TO) || buttonPressed(request, MOVE_TO))
                            str = request.getParameter(NEW_FOLDER);
                        if (str == null)
                            str = request.getParameter(CURRENT_FOLDER);
                        // always go to inbox after SEND
                        if (str != null && !str.equals(DIR_FOLDER) && !buttonPressed(request, SEND))
                            q += '&' + CURRENT_FOLDER + '=' + str;
                        sendRedirect(httpRequest, response, q);
                        return;
                    }
                }
            }

            if (state == State.NEW) {
                if (isPOST) {
                    String q = '?' + NEW_UIDL;
                    String newUIDL;
                    if (buttonPressed(request, REPLY) ||
                        buttonPressed(request, REPLYALL) ||
                        buttonPressed(request, FORWARD)) {
                        // stuffed in by PCSB
                        newUIDL = sessionObject.draftUIDL;
                        if (newUIDL != null) {
                            newUIDL = Base64.encode(newUIDL);
                            sessionObject.draftUIDL = null;
                        }
                    } else {
                        newUIDL = request.getParameter(NEW_UIDL);
                    }
                    if (newUIDL != null)
                        q += '=' + newUIDL;
                    sendRedirect(httpRequest, response, q);
                    return;
                }
                if (request.getParameter(DRAFT_ATTACHMENT) != null) {
                    processDraftAttachmentLink(sessionObject, request, response);
                    return;
                }
            }

            // ?show= links - this forces State.SHOW
            String b64UIDL = request.getParameter(SHOW);
            // attachment links, images, next/prev/delete on show form
            if (b64UIDL == null)
                b64UIDL = request.getParameter(B64UIDL);
            String showUIDL = Base64.decodeToString(b64UIDL);
            if (state == State.SHOW) {
                if (isPOST) {
                    String newShowUIDL = processMessageButtons(sessionObject, showUIDL, request);
                    if (newShowUIDL == null) {
                        state = State.LIST;
                        showUIDL = null;
                    } else if (!newShowUIDL.equals(showUIDL) ||
                           buttonPressed(request, SEND) || buttonPressed(request, CANCEL)) {
                        // SEND and CANCEL are from NEW page
                        // P-R-G
                        String q;
                        if (newShowUIDL.equals(DELETE))
                            q = '?' + DELETE + "=1&" + SHOW + '=' + Base64.encode(showUIDL);
                        else
                            q = '?' + SHOW + '=' + Base64.encode(newShowUIDL);
                        String str = request.getParameter(CURRENT_FOLDER);
                        if (str != null && !str.equals(DIR_FOLDER))
                            q += '&' + CURRENT_FOLDER + '=' + str;
                        sendRedirect(httpRequest, response, q);
                        return;
                    }
                }
                // ?download=nnn&amp;b64uidl link (same for ?att) should be valid in any state
                if (showUIDL != null && processDownloadLink(sessionObject, showUIDL, request, response)) {
                    // download or raw view sent, or 404
                    return;
                }
                if (isPOST && showUIDL != null && processSaveAsLink(sessionObject, showUIDL, request, response)) {
                    // download sent, or 404
                    return;
                }
                // If the last message has just been deleted then
                // state = State.LIST and
                // sessionObject.showUIDL = null
                if (showUIDL != null) {
                    MailCache mc = getCurrentMailCache(sessionObject, request);
                    Mail mail = (mc != null) ? mc.getMail(showUIDL, MailCache.FetchMode.ALL) : null;
                    if (mail != null && mail.error.length() > 0) {
                        sessionObject.error += mail.error;
                        mail.error = "";
                    }
                } else {
                    // can't SHOW without a UIDL
                    state = State.LIST;
                }
            }

            /*
             * update folder content
             * We need a valid and sorted folder for SHOW also, for the previous/next buttons
             */
            MailCache mc = getCurrentMailCache(sessionObject, request);
            // folder could be null after an error, we can't proceed if it is
            if (mc == null && (state == State.LIST || state == State.SHOW || state == State.NEW)) {
                sessionObject.error += "Internal error, no folder\n";
                state = State.AUTH;
            } else if (mc != null) {
                if (!mc.isLoaded() && !mc.isLoading()) {
                    boolean ok = mc.loadFromDisk(new LoadWaiter(sessionObject, mc));
                    // wait a little while so we avoid the loading page if we can
                    if (ok) {
                        try {
                            sessionObject.wait(5000);
                        } catch (InterruptedException ie) {
                            if (_log.shouldDebug()) _log.debug("Interrupted waiting for load", ie);
                        }
                    }
                    if ((state == State.LIST || state == State.SHOW) && mc.isLoading())
                        state = State.LOADING;
                }
            }
            Folder<String> folder = mc != null ? mc.getFolder() : null;

            //// End state determination, state will not change after here
            if (_log.shouldDebug()) _log.debug("Final state is " + state);

            if (state == State.LIST || state == State.SHOW) {
                // mc non-null
                // sort buttons are GETs
                String oldSort = folder.getCurrentSortBy();
                SortOrder oldOrder = folder.getCurrentSortingDirection();
                processSortingButtons(sessionObject, request);
                if (state == State.LIST) {
                    for (Iterator<String> it = folder.currentPageIterator(); it != null && it.hasNext();) {
                        String uidl = it.next();
                        Mail mail = mc.getMail(uidl, MailCache.FetchMode.HEADER_CACHE_ONLY);
                        if (mail != null && mail.error.length() > 0) {
                            sessionObject.error += mail.error;
                            mail.error = "";
                        }
                    }
                }

                // get through cache so we have the disk-only ones too
                String[] uidls = mc.getUIDLs();
                if (folder.addElements(Arrays.asList(uidls)) > 0) {
                    // we added elements, so it got sorted
                } else {
                    // check for changed sort
                    String curSort = folder.getCurrentSortBy();
                    SortOrder curOrder = folder.getCurrentSortingDirection();
                    if (oldOrder != curOrder || !oldSort.equals(curSort))
                        folder.sort();
                }
            }

            response.setHeader("Cache-Control","private, no-cache, max-age=3600");

            //// Begin output

                PrintWriter out = response.getWriter();

                /*
                 * build subtitle
                 */
                if (state == State.AUTH) {
                    subtitle = _t("Login");
                } else if (state == State.LOADING) {
                    subtitle = _t("Loading messages, please wait...");
                } else if (state == State.LIST) {
                    // mailbox.getNumMails() forces a connection, don't use it
                    // Not only does it slow things down, but a failure causes all our messages to "vanish"
                    //subtitle = ngettext("1 Message", "{0} Messages", sessionObject.mailbox.getNumMails());
                    int sz = folder.getSize();
                    subtitle = mc.getTranslatedName() + " - ";
                    if (sz > 0)
                        subtitle += ngettext("1 message", "{0} messages", folder.getSize());
                    else
                        subtitle += _t("No messages");
                } else if (state == State.SHOW) {
                    // mc non-null
                    Mail mail = showUIDL != null ? mc.getMail(showUIDL, MailCache.FetchMode.HEADER) : null;
                    if (mail != null && mail.hasHeader()) {
                        if (mail.shortSubject != null)
                            subtitle = mail.shortSubject; // already HTML encoded
                        else
                            subtitle = _t("Show Message");
                    } else {
                        subtitle = _t("Message not found.");
                    }
                } else if (state == State.NEW) {
                    subtitle = _t("New Message");
                } else if (state == State.CONFIG) {
                    subtitle = _t("Configuration");
                }

                response.setContentType("text/html");

                /*
                 * write header
                 */
                out.print("<!DOCTYPE HTML>\n<html>\n" +
                          "<head>\n" +
                          "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n" +
                          "<title>" + _t("I2PMail") + " - " + subtitle + "</title>\n" +
                          "<link rel=preload as=style href=\"" + sessionObject.themePath + "../images/images.css?" + CoreVersion.VERSION + "\">\n" +
                          "<link rel=preload as=style href=\"" + sessionObject.themePath + "images/images.css?" + CoreVersion.VERSION + "\">\n" +
                          "<link rel=stylesheet href=\"" + sessionObject.themePath + "susimail.css?" + CoreVersion.VERSION + "\">\n" +
                          "<link rel=stylesheet href=\"" + sessionObject.themePath + "override.css?" + CoreVersion.VERSION + "\">\n" + 
                          "<link rel=\"shortcut icon\" type=\"image/x-icon\" href=\"" + sessionObject.themePath + "images/favicon.svg\">\n");
                if (sessionObject.isMobile) {
                    out.print("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=2.0, user-scalable=yes\" />\n" +
                              "<link rel=stylesheet href=\"" + sessionObject.themePath + "mobile.css?" + CoreVersion.VERSION + "\" />\n");
                }
                if (enableSoraFont) {
                    out.print("<link rel=stylesheet href=\"" + sessionObject.themePath + "../../fonts/Sora.css?" + CoreVersion.VERSION + "\">\n");
                }
                if (state == State.LIST)
                    out.print("<link rel=stylesheet href=\"/susimail/css/print.css?" + CoreVersion.VERSION + "\" media=\"print\" />\n");
                if (state == State.NEW || state == State.CONFIG) {
                    // TODO cancel if to and body are empty
                    out.print("<script src=\"/susimail/js/compose.js?" + CoreVersion.VERSION + "\"></script>\n");
                } else if (state == State.LIST) {
                    out.print("<script src=\"/susimail/js/folder.js?" + CoreVersion.VERSION + "\"></script>\n");
                    out.print("<script src=\"/js/scrollTo.js?" + CoreVersion.VERSION + "\"></script>\n");
                } else if (state == State.LOADING) {
                    // TODO JS?
                    out.print("<noscript><meta http-equiv=\"refresh\" content=\"5;url=" + myself + "\"></noscript>\n");
                    // TODO we don't need the form below
                } else if (state == State.SHOW) {
                    //out.print("<link rel=stylesheet type=text/css href=\"" + sessionObject.themePath + "markdown.css?" + CoreVersion.VERSION + "\">\n");
                    out.print("<script src=\"/susimail/js/markdown.js?" + CoreVersion.VERSION + "\"></script>\n");
                    out.print("<script src=\"/susimail/js/Markdown.Converter.js?" + CoreVersion.VERSION + "\"></script>\n");
                    out.print("<script src=\"/js/iframeResizer/iframeResizer.js?" + CoreVersion.VERSION + "\"></script>\n");
                    out.print("<script nonce='" + cspNonce + "'>\n" +
                              "  document.addEventListener('DOMContentLoaded', function(event) {\n" +
                              "    const htmlView = iFrameResize({interval: 0, heightCalculationMethod: 'taggedElement', warningTimeout: 0}, '#iframeSusiHtmlView');\n" +
                              "  });\n" +
                              "</script>\n");
                }

                // setup noscript style so we can hide js buttons when js is disabled
                out.print("<noscript><style>.script{display:none!important}</style></noscript>\n");
                out.print("<script src=/js/iframeResizer/iframeResizer.contentWindow.js></script>\n");
//                out.print("<script src=\"/susimail/js/notifications.js?" + CoreVersion.VERSION + "\"></script>\n");
                out.print("<style>body{display:none;pointer-events:none}</style>\n");
                out.print("</head>\n");
                if (state == State.LIST)
                    out.print("<body id=main>\n");
                else
                    out.print("<body>\n");
                String nonce = state == State.AUTH ? LOGIN_NONCE : Long.toString(ctx.random().nextLong());
                sessionObject.addNonce(nonce);
                out.print(
                    // TODO we don't need the form below
                    "<div class=page>\n<span class=header></span>\n" +
                    "<form method=POST enctype=\"multipart/form-data\" action=\"" + myself + "\" accept-charset=utf-8>\n" +
                    "<input type=hidden name=\"" + SUSI_NONCE + "\" value=\"" + nonce + "\">\n" +
                    // we use this to know if the user thought he was logged in at the time
                    "<input type=hidden name=\"" + DEBUG_STATE + "\" value=\"" + state + "\">\n");
                if (state == State.NEW) {
                    String newUIDL = request.getParameter(NEW_UIDL);
                    if (newUIDL == null || newUIDL.length() <= 0)
                        newUIDL = Base64.encode(ctx.random().nextLong() + "drft");
                    out.print("<input type=hidden name=\"" + NEW_UIDL + "\" value=\"" + newUIDL + "\">\n");
                }
                if (state == State.SHOW || state == State.NEW) {
                    // Store the reference UIDL on the compose form also
                    if (showUIDL != null) {
                        // reencode, as showUIDL may have changed, and also for XSS
                        b64UIDL = Base64.encode(showUIDL);
                        out.print("<input type=hidden name=\"" + B64UIDL + "\" value=\"" + b64UIDL + "\">\n");
                    } else if (state == State.NEW) {
                        // for NEW, try to get back to the current page if we weren't replying
                        int page = 1;
                        String sp = request.getParameter(CUR_PAGE);
                        if (sp != null) {
                            try {
                                page = Integer.parseInt(sp);
                            } catch (NumberFormatException nfe) {}
                        }
                        out.print("<input type=hidden name=\"" + CUR_PAGE + "\" value=\"" + page + "\">\n");
                    }
                }
                if (state == State.SHOW || state == State.NEW || state == State.LIST) {
                    // Save sort order in case it changes later
                    String curSort = folder.getCurrentSortBy();
                    SortOrder curOrder = folder.getCurrentSortingDirection();
                    // UP is reverse (descending) sort. DOWN is normal (ascending) sort.
                    String fullSort = curOrder == SortOrder.UP ? '-' + curSort : curSort;
                    out.print("<input type=hidden name=\"" + CURRENT_SORT + "\" value=\"" + fullSort + "\">\n");
                    out.print("<input type=hidden name=\"" + CURRENT_FOLDER + "\" value=\"" + mc.getFolderName() + "\">\n");
                }
                boolean showRefresh = false;
                if (mc != null && mc.isLoading()) {
                    showRefresh = true;
                } else if (sessionObject.isFetching) {
                    showRefresh = true;
                } else if (state != State.LOADING && state != State.AUTH && state != State.CONFIG) {
                    String error = sessionObject.connectError;
                    if (error != null && error.length() > 0) {
                        sessionObject.error += error + '\n';
                        sessionObject.connectError = null;
                    }
                    int added = sessionObject.newMails;
                    if (added > 0) {
                        sessionObject.info += ngettext("{0} new message", "{0} new messages", added) + '\n';
                        sessionObject.newMails = -1;
                    } else if (added == 0) {
                        sessionObject.info += _t("No new messages") + '\n';
                        sessionObject.newMails = -1;
                    }
                }
                if (showRefresh || sessionObject.error.length() > 0 || sessionObject.info.length() > 0) {
                    out.println("<div id=notify class=\"notifications ");
                    if (sessionObject.newMails > 0)
                        out.print("newmail ");
                    else if (sessionObject.error.length() > 0)
                        out.println("msgerror\"><p class=error>" + quoteHTML(sessionObject.error).replace("\n", "<br>") + "</p>");
                    if (sessionObject.info.length() > 0 || showRefresh) {
                        out.println("msginfo\"><p class=info><b>");
                        if (mc != null && mc.isLoading())
                            out.println(_t("Loading messages, please wait...").replace("...", "&hellip;") + "<br>");
                        if (sessionObject.isFetching)
                            out.println(_t("Checking for new messages on server") + "&hellip;<br>");
                        if (showRefresh)
                            out.println("<noscript>" + _t("Refresh the page for updates") + "<br></noscript>");
                        if (sessionObject.info.length() > 0)
                            out.println(quoteHTML(sessionObject.info).replace("\n", "<br>"));
                        out.println("</b></p>");
                    }
                    out.println("</div>");
                }
                /*
                 * now write body
                 */
                if (state == State.AUTH)
                    showLogin(out);

                else if (state == State.LOADING)
                    showLoading(out, sessionObject, request);

                else if (state == State.LIST)
                    showFolder(out, sessionObject, mc, request);

                else if (state == State.SHOW) {
                    // Determine what HtmlMode we're going to show the mail in
                    boolean disable = isMobile || !CSPDetector.supportsCSP(httpRequest.getHeader("User-Agent"));
                    boolean link = !disable;
                    String hp = httpRequest.getParameter(HTML);
                    boolean allow = link && ("1".equals(hp)) || (!"0".equals(hp) && Boolean.parseBoolean(Config.getProperty(CONFIG_HTML_ALLOWED, "true")));
                    boolean prefer = allow && ("1".equals(hp)) || (!"0".equals(hp) && Boolean.parseBoolean(Config.getProperty(CONFIG_HTML_PREFERRED, "true")));
                    HtmlMode allowHTML = prefer ? HtmlMode.PREFER : allow ? HtmlMode.ALLOW : link ? HtmlMode.LINK : HtmlMode.NONE;
                    showMessage(out, sessionObject, mc, showUIDL, buttonPressed(request, DELETE), allowHTML);
                    out.print("<script src=\"/susimail/js/toggleHeaders.js?" + CoreVersion.VERSION + "\"></script>\n");
                    out.print("<script src=/susimail/js/htmlView.js></script>\n");
                    //out.print("<script src=\"/themes/toggleHeaders.js?" + CoreVersion.VERSION + "\"></script>\n"); // debug
                    out.print("<script src=\"/susimail/js/markdown.js?" + CoreVersion.VERSION + "\"></script>\n");
                    out.print("<script src=\"/susimail/js/Markdown.Converter.js?" + CoreVersion.VERSION + "\"></script>\n");
                }

                else if (state == State.NEW)
                    showCompose(out, sessionObject, request);

                else if (state == State.CONFIG)
                    showConfig(out, folder);

                if (state == State.AUTH) {
                    out.print("\n<div class=footer>\n<script src=\"/js/togglePassword.js?" + CoreVersion.VERSION + "\"></script>" +
                              "<p class=footer>" +
                              _t("{0} is an I2P-exclusive service provided by {1}.", "<b>I2PMail</b>", "<a href=\"http://hq.postman.i2p/\" target=_blank>Postman</a>") + ' ' +
                              _t("{0} webmail client &copy Susi 2004-2005.", "<b>SusiMail</b>").replace("&copy", "&copy;") +
                              "</p>\n</div>\n");
                }
                out.print("</form>\n</div>\n<span data-iframe-height></span>\n");
                if (sessionObject.isFetching) {
                    out.print("<script id=autorefresh type=module src=\"/susimail/js/refreshInbox.js?" + CoreVersion.VERSION + "\"></script>\n");
                }
                out.print("<style>body{display:block;pointer-events:auto}</style>\n");
                out.print("</body>\n</html>");
                out.flush();
            }  // synch sessionObject
    }

    /**
     *  Redirect a POST to a GET (P-R-G), replacing the query string
     *  @param q starting with '?' or null
     *  @since 0.9.33 adapted from I2PSnarkServlet
     */
    private void sendRedirect(HttpServletRequest req, HttpServletResponse resp, String q) throws IOException {
        String url = req.getRequestURL().toString();
        StringBuilder buf = new StringBuilder(128);
        int qq = url.indexOf('?');
        if (qq >= 0)
            url = url.substring(0, qq);
        buf.append(url);
        if (q != null && q.length() > 0)
            buf.append(q.replace("&amp;", "&"));  // no you don't html escape the redirect header
        resp.setHeader("Location", buf.toString());
        resp.setStatus(303);
        resp.getOutputStream().close();
        if (_log.shouldDebug()) _log.debug("P-R-G to " + q);
    }

    /**
     * Translate with the console bundle.
     * @since 0.9.27
     */
    private static String consoleGetString(String s, I2PAppContext ctx) {
        return Translate.getString(s, ctx, CONSOLE_BUNDLE_NAME);
    }

    /**
     * @param sessionObject
     * @param response
     * @param isRaw if true, don't zip it
     * @return success
     */
    private static boolean sendAttachment(SessionObject sessionObject, MailPart part, HttpServletResponse response, boolean isRaw) {
        boolean shown = false;
        if (part != null) {
            Log log = sessionObject.log;
            String name = part.filename;
            if (name == null) {
                name = part.name;
                if (name == null) {
                    name = part.description;
                    if (name == null)
                        name = "part" + part.getID();
                }
            }
            String name2 = FilenameUtil.sanitizeFilename(name);
            String name3 = FilenameUtil.encodeFilenameRFC5987(name);
            response.setHeader("Cache-Control", "no-cache, private, max-age=604800");
            if (isRaw) {
                OutputStream out = null;
                try {
                    response.addHeader("Content-Disposition", "inline; filename=\"" + name2 + "\"; " + "filename*=" + name3);
                    if (part.type != null) {response.setContentType(part.type);}
                    if (part.charset != null) {response.setCharacterEncoding(part.charset);}
                    out = response.getOutputStream();
                    if ("text/html".equals(part.type)) {
                        // inject the js into the iframe
                        String contentWindowJs =
                            "\n<span id=endOfPage data-iframe-height></span>\n" +
                            "<script src=/susimail/js/sanitizeHTML.js></script>\n" + 
                            "<script src=/js/iframeResizer/iframeResizer.contentWindow.js></script>\n";
                        out = new RegexOutputStream(out, "</body>", contentWindowJs + "</body>", contentWindowJs);
                        // convert cid: urls to /susimail/?cid= urls
                        out = new RegexOutputStream(out, " src=\"cid:", " src=\"/susimail/?msg=" + Base64.encode(part.uidl) + "&amp;cid=", null);
                        // don't set content length, as it may change due to replacements
                    } else {
                        if (part.decodedLength >= 0) {response.setContentLength(part.decodedLength);}
                    }
                    if (log.shouldDebug()) {log.debug("Sending raw attachment " + name + " length " + part.decodedLength);}
                    part.decode(0, new OutputStreamBuffer(out));
                    shown = true;
                } catch (IOException e) {
                    log.error("Error sending raw attachment " + name + " length " + part.decodedLength, e);
                } finally {
                    if (out != null)
                        try { out.close(); } catch (IOException ioe) {}
                }
            } else {
                ZipOutputStream zip = null;
                try {
                    zip = new ZipOutputStream(response.getOutputStream());
                    response.setContentType("application/zip; name=\"" + name2 + ".zip\"");
                    response.addHeader("Content-Disposition", "attachment; filename=\"" + name2 + ".zip\"; " +
                                       "filename*=" + name3 + ".zip");
                    ZipEntry entry = new ZipEntry(name);
                    zip.putNextEntry(entry);
                    // was 2
                    part.decode(0, new OutputStreamBuffer(zip));
                    zip.closeEntry();
                    zip.finish();
                    shown = true;
                } catch (IOException e) {
                    log.error("Error sending zip attachment " + name + " length " + part.decodedLength, e);
                } finally {
                    if (zip != null)
                        try { zip.close(); } catch (IOException ioe) {}
                }
            }
        }
        return shown;
    }

    /**
     * Send the mail to be saved by the browser
     *
     * @param sessionObject
     * @param response
     * @return success
     * @since 0.9.18
     */
    private static boolean sendMailSaveAs(SessionObject sessionObject, Mail mail,
                         HttpServletResponse response) {
        Buffer content = mail.getBody();
        if (content == null)
            return false;
        String name;
        if (mail.subject.length() > 0)
            name = mail.subject.trim() + ".eml";
        else
            name = "message.eml";
        String name2 = FilenameUtil.sanitizeFilename(name);
        String name3 = FilenameUtil.encodeFilenameRFC5987(name);
        InputStream in = null;
        try {
            response.setContentType("message/rfc822");
            long sz = mail.getSize();
            if (sz > 0 && sz <= Integer.MAX_VALUE)
                response.setContentLength((int) sz);
            response.setHeader("Cache-Control", "no-cache, private, max-age=604800");
            response.addHeader("Content-Disposition", "attachment; filename=\"" + name2 + "\"; " +
                               "filename*=" + name3);
            in = content.getInputStream();
            DataHelper.copy(in, response.getOutputStream());
            return true;
        } catch (IOException e) {
            Log log = sessionObject.log;
            if (log.shouldDebug()) log.debug("Save-As", e);
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }

    /**
     * Take the data from the request, and put it in a StringBuilder
     * suitable for writing out as a Draft.
     *
     * We do no validation of recipients, total length, sender, etc. here.
     *
     * @param sessionObject only for error messages and attachments. All other data is in the request.
     * @param request
     * @return null on error
     * @since 0.9.35
     */
    private static StringBuilder composeDraft(SessionObject sessionObject, RequestWrapper request) {
        String from = request.getParameter(NEW_FROM);
        String to = request.getParameter(NEW_TO);
        String cc = request.getParameter(NEW_CC);
        String bcc = request.getParameter(NEW_BCC);
        String subject = request.getParameter(NEW_SUBJECT);
        String text = request.getParameter(NEW_TEXT, "");
        List<Attachment> attachments = sessionObject.attachments;
        return composeDraft(sessionObject, from, to, cc, bcc, subject, text, sessionObject.attachments);
    }

    /**
     * Take the data from the parameters, and put it in a StringBuilder
     * suitable for writing out as a Draft.
     *
     * We do no validation of recipients, total length, sender, etc. here.
     * All params except session may be null.
     *
     * @param sessionObject only for error messages. All data is in the parameters.
     * @return null on error
     * @since 0.9.35
     */
    private static StringBuilder composeDraft(SessionObject sessionObject,
                                              String from, String to, String cc, String bcc,
                                              String subject, String text, List<Attachment> attachments) {
        boolean ok = true;
        if (subject == null || subject.trim().length() <= 0)
            subject = _t("no subject");
        else
            subject = subject.trim();

        boolean fixed = Boolean.parseBoolean(Config.getProperty(CONFIG_SENDER_FIXED, "true"));
        if (fixed) {
            String domain = Config.getProperty(CONFIG_SENDER_DOMAIN, "mail.i2p");
            from = "<" + sessionObject.user + "@" + domain + ">";
        }
        ArrayList<String> toList = new ArrayList<String>();
        ArrayList<String> ccList = new ArrayList<String>();
        ArrayList<String> bccList = new ArrayList<String>();

        // no validation
        Mail.getRecipientsFromList(toList, to, ok);
        Mail.getRecipientsFromList(ccList, cc, ok);
        Mail.getRecipientsFromList(bccList, bcc, ok);

        Encoding qp = EncodingFactory.getEncoding("quoted-printable");
        Encoding hl = EncodingFactory.getEncoding("HEADERLINE");

        StringBuilder body = null;
        if (ok) {
            boolean multipart = attachments != null && !attachments.isEmpty();
            if (multipart) {
                // use Draft just to write out attachment headers
                Draft draft = new Draft("");
                for (Attachment a : attachments) {
                    draft.addAttachment(a);
                }
                body = draft.encodeAttachments();
            } else {
                body = new StringBuilder(1024);
            }
            I2PAppContext ctx = I2PAppContext.getGlobalContext();
            body.append("Date: " + RFC822Date.to822Date(ctx.clock().now()) + "\r\n");
            // todo include real names, and headerline encode them
            if (from != null)
                body.append("From: " + from + "\r\n");
            Mail.appendRecipients(body, toList, "To: ");
            Mail.appendRecipients(body, ccList, "Cc: ");
            // only for draft
            Mail.appendRecipients(body, bccList, Draft.HDR_BCC);
            Log log = sessionObject.log;
            try {
                body.append(hl.encode("Subject: " + subject));
            } catch (EncodingException e) {
                ok = false;
                sessionObject.error += e.getMessage() + '\n';
                if (log.shouldDebug()) log.debug("Draft subj", e);
            }
            body.append("MIME-Version: 1.0\r\nContent-type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n");
            try {
                body.append(qp.encode(text));
                int len = body.length();
                if (body.charAt(len - 2) != '\r' && body.charAt(len - 1) != '\n')
                    body.append("\r\n");
            } catch (EncodingException e) {
                ok = false;
                sessionObject.error += e.getMessage() + '\n';
                if (log.shouldDebug()) log.debug("Draft body", e);
            }
        }
        return ok ? body : null;
    }

    /**
     * Write out the draft.
     *
     * @param sessionObject only for error messages.
     * @return success
     * @since 0.9.35
     */
    private static boolean saveDraft(SessionObject sessionObject, String uidl, StringBuilder draft) {
        Log log = sessionObject.log;
        if (log.shouldDebug()) log.debug("Save as draft: " + uidl);
        MailCache toMC = sessionObject.caches.get(DIR_DRAFTS);
        Writer wout = null;
        boolean ok = false;
        Buffer buffer = null;
        try {
            if (toMC == null)
                throw new IOException("No Drafts folder?");
            waitForLoad(sessionObject, toMC);
            if (draft == null)
                throw new IOException("Draft compose error");  // composeDraft added error messages
            buffer = toMC.getFullWriteBuffer(uidl);
            wout = new BufferedWriter(new OutputStreamWriter(buffer.getOutputStream(), "ISO-8859-1"));
            SMTPClient.writeMail(wout, draft, null, null);
            if (log.shouldDebug()) log.debug("Saved as draft: " + uidl);
            ok = true;
        } catch (IOException ioe) {
            sessionObject.error += _t("Unable to save mail.") + ' ' + ioe.getMessage() + '\n';
            if (log.shouldDebug()) log.debug("Unable to save as draft: " + uidl, ioe);
        } finally {
            if (wout != null) try { wout.close(); } catch (IOException ioe) {}
            if (buffer != null)
                toMC.writeComplete(uidl, buffer, ok);
        }
        return ok;
    }

    /**
     * Take the data from the request, and put it in a StringBuilder
     * suitable for writing out as a Draft.
     *
     * @param sessionObject only for error messages. All data is in the draft.
     * @return success
     */
    private static boolean sendMail(SessionObject sessionObject, Draft draft) {
        boolean ok = true;

        String from = draft.sender;
        String[] to = draft.to;
        String[] cc = draft.cc;
        String[] bcc = draft.getBcc();
        String subject = draft.subject;
        List<Attachment> attachments = draft.getAttachments();

        ArrayList<String> toList = new ArrayList<String>();
        ArrayList<String> ccList = new ArrayList<String>();
        ArrayList<String> bccList = new ArrayList<String>();
        ArrayList<String> recipients = new ArrayList<String>();

        String sender = null;

        if (from == null || !Mail.validateAddress(from)) {
            ok = false;
            sessionObject.error += _t("Found no valid sender address.") + '\n';
        }
        else {
            sender = Mail.getAddress(from);
            if (sender == null || sender.length() == 0) {
                ok = false;
                sessionObject.error += _t("Found no valid address in \\''{0}\\''.", quoteHTML(from)) + '\n';
            }
        }

        ok = Mail.getRecipientsFromList(toList, to, ok);
        ok = Mail.getRecipientsFromList(ccList, cc, ok);
        ok = Mail.getRecipientsFromList(bccList, bcc, ok);

        recipients.addAll(toList);
        recipients.addAll(ccList);
        recipients.addAll(bccList);

        if (toList.isEmpty()) {
            ok = false;
            sessionObject.error += _t("No recipients found.") + '\n';
        }
        Encoding hl = EncodingFactory.getEncoding("HEADERLINE");

        // Not perfectly accurate but close
        long total = draft.getSize();
        boolean multipart = attachments != null && !attachments.isEmpty();
        if (multipart) {
            for(Attachment a : attachments) {
                total += a.getSize();
            }
        }
        if (total > SMTPClient.BINARY_MAX_SIZE) {
            ok = false;
            sessionObject.error += _t("Email is too large, max is {0}",
                                      DataHelper.formatSize2(SMTPClient.BINARY_MAX_SIZE, false) + 'B') + '\n';
        }

        if (ok) {
            StringBuilder body = new StringBuilder(1024);
            I2PAppContext ctx = I2PAppContext.getGlobalContext();
            body.append("Date: " + RFC822Date.to822Date(ctx.clock().now()) + "\r\n");
            // todo include real names, and headerline encode them
            body.append("From: " + from + "\r\n");
            Mail.appendRecipients(body, toList, "To: ");
            Mail.appendRecipients(body, ccList, "Cc: ");
            try {
                body.append(hl.encode("Subject: " + subject.trim()));
            } catch (EncodingException e) {
                ok = false;
                sessionObject.error += e.getMessage();
            }

            String boundary = "_=" + ctx.random().nextLong();
            if (multipart) {
                body.append("MIME-Version: 1.0\r\n" +
                                 "Content-type: multipart/mixed; boundary=\"" + boundary + "\"\r\n\r\n");
            }
            else {
                body.append("MIME-Version: 1.0\r\nContent-type: text/plain; charset=utf-8\r\n" +
                                 "Content-Transfer-Encoding: quoted-printable\r\n\r\n");
            }
            // TODO pass the text separately to SMTP and let it pick the encoding
            if (multipart)
                body.append("--" + boundary + "\r\nContent-type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n");
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
                draft.getPart().outputRaw(baos);
                body.append(baos.toString("ISO-8859-1"));
            } catch (IOException ioe) {
                ok = false;
                sessionObject.error += ioe.getMessage() + '\n';
            }

            if (ok) {
                Runnable es = new EmailSender(sessionObject, draft, sender,
                                              recipients.toArray(new String[recipients.size()]),
                                              body, attachments, boundary);
                Thread t = new I2PAppThread(es, "Email sender");
                sessionObject.info += _t("Sending mail.") + '\n';
                t.start();
            }
        }
        if (!ok)
            sessionObject.error = _t("Error sending mail") + '\n' + sessionObject.error;
        return ok;
    }

    /**
     * Threaded sending
     * @since 0.9.35
     */
    private static class EmailSender implements Runnable {
        private final SessionObject sessionObject;
        private final Draft draft;
        private final String host, user, pass, sender, boundary;
        private final int port;
        private final String[] recipients;
        private final StringBuilder body;
        private final List<Attachment> attachments;

        public EmailSender(SessionObject so, Draft d, String s, String[] recip,
                           StringBuilder bod, List<Attachment> att, String b) {
            sessionObject = so; draft = d;
            host = so.host; port = so.smtpPort; user = so.user; pass = so.pass;
            sender = s; boundary = b;
            recipients = recip; body = bod; attachments = att;
        }

        public void run() {
            Log log = sessionObject.log;
            if (log.shouldDebug()) log.debug("Attempting to send email...");
            SMTPClient relay = new SMTPClient();
            boolean ok = relay.sendMail(host, port, user, pass, sender, recipients, body,
                                        attachments, boundary);
            if (log.shouldDebug()) log.debug("Email send complete, success? " + ok);
            synchronized(sessionObject) {
                if (ok) {
                    sessionObject.info += _t("Mail sent.") + '\n';
                    // now delete from drafts
                    draft.clearAttachments();
                    MailCache mc = sessionObject.caches.get(DIR_DRAFTS);
                    if (mc != null) {
                        waitForLoad(sessionObject, mc);
                        mc.delete(draft.uidl);
                        mc.getFolder().removeElement(draft.uidl);
                        if (log.shouldDebug()) log.debug("Sent email deleted from drafts");
                    }
                    // now store to sent
                    // if configured (default true)
                    if (Boolean.parseBoolean(Config.getProperty(CONFIG_COPY_TO_SENT, "true"))) {
                        mc = sessionObject.caches.get(DIR_SENT);
                        if (mc != null) {
                            waitForLoad(sessionObject, mc);
                            I2PAppContext ctx = I2PAppContext.getGlobalContext();
                            String uidl = ctx.random().nextLong() + "sent";
                            Writer wout = null;
                            boolean copyOK = false;
                            Buffer buffer = null;
                            try {
                                buffer = mc.getFullWriteBuffer(uidl);
                                wout = new BufferedWriter(new OutputStreamWriter(buffer.getOutputStream(), "ISO-8859-1"));
                                SMTPClient.writeMail(wout, body,
                                                     attachments, boundary);
                                if (log.shouldDebug()) log.debug("Sent email saved to Sent");
                                copyOK = true;
                            } catch (IOException ioe) {
                                sessionObject.error += _t("Unable to save mail.") + ' ' + ioe.getMessage() + '\n';
                                if (log.shouldDebug()) log.debug("Sent email saved error", ioe);
                            } finally {
                                if (wout != null) try { wout.close(); } catch (IOException ioe) {}
                                if (buffer != null)
                                    mc.writeComplete(uidl, buffer, copyOK);
                            }
                        }
                    }
                } else {
                    sessionObject.error += relay.error;
                    if (log.shouldWarn()) log.warn("Error sending mail: " + relay.error);
                }
                sessionObject.info = sessionObject.info.replace(_t("Sending mail.") + '\n', "");
            }
        }
    }

    /**
     * 
     */
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
    throws IOException, ServletException {
        processRequest(request, response, false);
    }

    /**
     * 
     */
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
    throws IOException, ServletException {
        processRequest(request, response, true);
    }

    /**
     * @param arr may be null
     * @since 0.9.35
     */
    private static String arrayToCSV(String[] arr) {
        StringBuilder buf = new StringBuilder(64);
        if (arr != null) {
            for (int i = 0; i < arr.length; i++) {
                buf.append(arr[i]);
                if (i < arr.length - 1)
                    buf.append(", ");
            }
        }
        return buf.toString();
    }

    /**
     * 
     * @param out
     * @param sessionObject
     * @param request
     */
    private static void showCompose(PrintWriter out, SessionObject sessionObject, RequestWrapper request) {
        out.println("<div class=topbuttons>");
        out.println(button(SEND, _t("Send")) +
                button(SAVE_AS_DRAFT, _t("Save as Draft")) +
                button(CANCEL, _t("Cancel")));
        out.println("</div>");

        Draft draft = null;
        String from = "";
        String to = "";
        String cc = "";
        String bc = "";
        String bcc = "";
        String subject = "";
        String text = "";
        String b64UIDL = request.getParameter(NEW_UIDL);
        if (b64UIDL == null || b64UIDL.length() <= 0) {
            // header set in processRequest()
            I2PAppContext ctx = I2PAppContext.getGlobalContext();
            b64UIDL = Base64.encode(ctx.random().nextLong() + "drft");
        }

            MailCache drafts = sessionObject.caches.get(DIR_DRAFTS);
            if (drafts == null) {
                sessionObject.error += "No Drafts folder?\n";
                return;
            }
            String newUIDL = Base64.decodeToString(b64UIDL);
            Log log = sessionObject.log;
            if (log.shouldDebug()) log.debug("Show draft: " + newUIDL);
            if (newUIDL != null)
                draft = (Draft) drafts.getMail(newUIDL, MailCache.FetchMode.CACHE_ONLY);
            if (draft != null) {
                // populate from saved draft
                from = draft.sender;
                subject = draft.subject;
                to = arrayToCSV(draft.to);
                cc = arrayToCSV(draft.cc);
                bcc = arrayToCSV(draft.getBcc());
                StringBuilderWriter body = new StringBuilderWriter(1024);
                try {
                    Buffer ob = new OutputStreamBuffer(new DecodingOutputStream(body, "UTF-8"));
                    draft.getPart().decode(0, ob);
                } catch (IOException ioe) {
                    sessionObject.error += "Draft decode error: " + ioe.getMessage() + '\n';
                }
                text = body.toString();
                List<Attachment> a = draft.getAttachments();
                if (!a.isEmpty()) {
                    if (sessionObject.attachments == null)
                        sessionObject.attachments = new ArrayList<Attachment>(a.size());
                    else
                        sessionObject.attachments.clear();
                    sessionObject.attachments.addAll(a);
                } else if (sessionObject.attachments != null) {
                    sessionObject.attachments.clear();
                }
                // needed when processing the CANCEL button
                out.println("<input type=hidden name=\"" + DRAFT_EXISTS + "\" value=1>");
            }

        boolean fixed = Boolean.parseBoolean(Config.getProperty(CONFIG_SENDER_FIXED, "true"));

        if (from.length() <= 0 || !fixed) {
            String user = sessionObject.user;
            String name = Config.getProperty(CONFIG_SENDER_NAME);
            if (name != null) {
                name = name.trim();
                if (name.contains(" "))
                    from = '"' + name + "\" ";
                else
                    from = name + ' ';
            } else {
                from = "";
            }
            if (user.contains("@")) {
                from += '<' + user + '>';
            } else {
                String domain = Config.getProperty(CONFIG_SENDER_DOMAIN, "mail.i2p");
                if (from.length() == 0)
                    from = user + ' ';
                from += '<' + user + '@' + domain + '>';
            }
        }

        out.print("<div id=composemail>" +
                   "<table id=newmail width=100%>\n" +
                   "<tr><td colspan=2><hr></td></tr>\n" +
                   "<tr><td class=right>" + _t("From") + "</td>" +
                   "<td><input type=text size=80 name=\"" +
                   NEW_FROM + "\" value=\"" + quoteHTML(from) + "\" " + (fixed ? "disabled" : "") +"></td></tr>\n" +
                   "<tr><td class=right>" + _t("To") + "</td><td><input type=text size=80 name=\"" +
                   NEW_TO + "\" value=\"" + quoteHTML(to) + "\"></td></tr>\n" +
                   "<tr><td class=right>" + _t("Cc") + "</td><td><input type=text size=80 name=\"" +
                   NEW_CC + "\" value=\"" + quoteHTML(cc) + "\"></td></tr>\n" +
                   "<tr><td class=right>" + _t("Bcc") + "</td><td><input type=text size=80 name=\"" +
                   NEW_BCC + "\" value=\"" + quoteHTML(bcc) + "\"></td></tr>\n" +
                   "<tr><td class=right>" + _t("Subject") + "</td>" +
                   "<td><input type=text size=80 name=\"" +
                   NEW_SUBJECT + "\" value=\"" + quoteHTML(subject) + "\"></td></tr>\n" +
                   "<tr><td></td><td><textarea cols=\"" + Config.getProperty(CONFIG_COMPOSER_COLS, 80) + "\" rows=\"" +
                   Config.getProperty(CONFIG_COMPOSER_ROWS, 10)+ "\" name=\"" + NEW_TEXT + "\">" + text + "</textarea></td></tr>" +
                   "<tr class=\"bottombuttons spacer\"><td colspan=7><hr></td></tr>\n" +
                   "<tr class=bottombuttons id=addattachment><td class=right>" + _t("Add Attachment") + "</td>" +
                   "<td class=left><input type=file size=50% name=\"" + NEW_FILENAME + "\" value=\"\">&nbsp;");
        // TODO: reset button label to "add attachment" when no attachments are visible (currently counts attachments added per session)
        out.print(button(NEW_UPLOAD, _t("Add attachment")));
        out.print("</td></tr>\n");

        if (sessionObject.attachments != null && !sessionObject.attachments.isEmpty()) {
            boolean wroteHeader = false;
            for(Attachment attachment : sessionObject.attachments) {
                String attachSize = DataHelper.formatSize2(attachment.getSize());
                attachSize = attachSize.replace("i", "");
                if (!wroteHeader) {
                    out.print("<tr><td class=right>" + _t("Attachments") + "</td>");
                    wroteHeader = true;
                } else {
                    out.print("<tr><td>&nbsp;</td>");
                }
                out.print("<td id=attachedfile class=left><label><input type=checkbox class=optbox name=\"check" +
                          attachment.hashCode() + "\" value=1>&nbsp;" + quoteHTML(attachment.getFileName()));
                out.print(" <span class=attachSize>(" + attachSize + ")</span></label>");
                String type = attachment.getContentType();
                String iconDir = "/themes/susimail/images/";
                if (type != null) {
                    out.print("<span class=thumbnail><img alt=\"\" src=\"");
                    if (type.startsWith("image/")) {
                        out.print(myself + '?' + DRAFT_ATTACHMENT + '=' + attachment.hashCode());
                    } else if (type.startsWith("audio/")) {
                        out.print(iconDir + "audio.svg");
                    } else if (type.startsWith("text/")) {
                        out.print(iconDir + "text.svg");
                    } else if (type.startsWith("video/")) {
                        out.print(iconDir + "video.svg");
                    } else if (type.contains("pgp")) {
                        out.print(iconDir + "sig.svg");
                    } else if (type.equals("application/zip") || type.equals("application/x-gtar") ||
                               type.equals("application/x-zip-compressed") || type.equals("application/compress") ||
                               type.equals("application/gzip") || type.equals("application/x-7z-compressed") ||
                               type.equals("application/x-rar-compressed") || type.equals("application/x-tar") ||
                               type.equals("application/x-bzip2")) {
                        out.print(iconDir + "compress.svg");
                    } else if (type.equals("application/pdf")) {
                        out.print(iconDir + "pdf.svg");
                    } else {
                        out.print(iconDir + "generic.svg");
                    }
                    out.print("\" hidden></span>");
                }
                out.print("</td></tr>\n");
            }
            // TODO disable in JS if none selected
            out.println("<tr class=bottombuttons><td>&nbsp;</td><td id=deleteattached class=left>" +
                        button(DELETE_ATTACHMENT, _t("Delete selected attachments")) + "</td></tr>");
        }
        out.println("</table>\n</div>");
    }

    /**
     * 
     * @param out
     */
    private static void showLogin(PrintWriter out) {
        boolean fixed = Boolean.parseBoolean(Config.getProperty(CONFIG_PORTS_FIXED, "true"));
        String host = Config.getProperty(CONFIG_HOST, DEFAULT_HOST);
        String pop3 = Config.getProperty(CONFIG_PORTS_POP3, Integer.toString(DEFAULT_POP3PORT));
        String smtp = Config.getProperty(CONFIG_PORTS_SMTP, Integer.toString(DEFAULT_SMTPPORT));

        out.print("<div id=dologin>\n" +
                  "<h1>" + _t("I2PMail Login") + "</h1>\n" +
                  "<table width=100%>\n" +
                  // current postman hq length limits 16/12, new postman version 32/32
                  "<tr>" +
                  "<td width=30% class=right>" + _t("User") + "</td>" +
                  "<td width=40% class=left><input type=text required placeholder=\"" + _t("Username") +
                  "\" size=32 autocomplete=\"username\" name=\"" + USER + "\" value=\"" + "\"> @mail.i2p</td>" +
                  "</tr>\n" +
                  "<tr>" +
                  "<td width=30% class=right>" + _t("Password") + "</td>" +
                  "<td width=40% class=left><input type=password id=password required placeholder=\"" + _t("Password") +
                  "\" size=32 autocomplete=\"current-password\" name=\"pass\" value=\"" + "\">\n<button type=\"button\" class=script id=toggle" +
                  " title=\"" + _t("Toggle password visibility") + "\">Show password</button></td>" +
                  "</tr>\n");
        // which is better?
        if (!fixed) {
        //if (true) {
            out.print("<tr>" +
                      "<td width=30%>" + _t("Host") + "</td>" +
                      "<td width=40%><input type=text size=32 name=\"" + HOST +"\" value=\"" +
                      quoteHTML(host) + "\"" + (fixed ? " disabled" : "") + "></td>" +
                      "</tr>\n" +
                      "<tr>" +
                      "<td width=30%>" + _t("POP3 Port") + "</td>" +
                      "<td width=40%><input type=text style=text-align:right size=5 name=\"" + POP3 +"\" value=\"" +
                      quoteHTML(pop3) + "\"" + (fixed ? " disabled" : "") + "></td>" +
                      "</tr>\n" +
                      "<tr>" +
                      "<td width=30%>" + _t("SMTP Port") + "</td>" +
                      "<td width=40%><input type=text style=text-align:right size=5 name=\"" + SMTP +"\" value=\"" +
                      quoteHTML(smtp) + "\"" + (fixed ? " disabled" : "") + "></td>" +
                      "</tr>\n");
        }
        out.print("<tr><td colspan=2><hr></td></tr>\n" +
                  "<tr>" +
                  "<td colspan=2>" +
                  button(LOGIN, _t("Login")) + spacer + button(OFFLINE, _t("Read Mail Offline")) + spacer +
                  "<a href=\"/susimail/?configure\" id=settings class=fakebutton>" + _t("Settings") + "</a>" +
                  "</td>" +
                  "</tr>\n" +
                  "<tr>" +
                  "<td colspan=2><hr>" +
                  "<a href=\"http://hq.postman.i2p/?page_id=14\" target=_blank>" + _t("Learn about I2PMail") + "</a> | " +
                  "<a href=\"http://hq.postman.i2p/?page_id=16\" target=_blank>" + _t("Create Account") + "</a></td>" +
                  "</tr>\n" +
                  "</table>\n</div>\n");
    }

    /**
     * @since 0.9.34
     */
    private static void showLoading(PrintWriter out, SessionObject sessionObject, RequestWrapper request) {
        out.println("<p id=loading><span id=loadbar><b>");
        out.println(_t("Loading messages, please wait..."));
        out.println("</b></span>");
    }

    /**
     * 
     * @param out
     * @param sessionObject
     * @param request
     */
    private static void showFolder(PrintWriter out, SessionObject sessionObject, MailCache mc, RequestWrapper request) {
        out.println("<div class=topbuttons>\n<span id=mailboxcontrols>");
        out.println(button(NEW, _t("New")) + spacer);
            // In theory, these are valid and will apply to the first checked message,
            // but that's not obvious and did it work?
            //button(REPLY, _t("Reply")) +
            //button(REPLYALL, _t("Reply All")) +
            //button(FORWARD, _t("Forward")) + spacer +
            //button(DELETE, _t("Delete")) + spacer +
        String folderName = mc.getFolderName();
        String floc;
        floc = "";

        if (!sessionObject.isFetching) {
            if (folderName.equals(DIR_FOLDER)) {
                out.println(button(REFRESH, _t("Check Mail")) + spacer);
                floc = "";
            } else if (folderName.equals(DIR_DRAFTS)) {
                floc = "";
            } else {
                floc = '&' + CURRENT_FOLDER + '=' + folderName;
            }
        } else {
            out.println("<a id=pageRefresh class=fakebutton href=\"\">" + _t("Refresh Page") + "</a>");
        }

        boolean isSpamFolder = folderName.equals(DIR_SPAM);
        boolean showToColumn = folderName.equals(DIR_DRAFTS) || folderName.equals(DIR_SENT);
        out.println("</span>");

        String domain = Config.getProperty(CONFIG_SENDER_DOMAIN, "mail.i2p");
        String name = folderName.equals(DIR_FOLDER) ? "Inbox" : folderName;
        Folder<String> folder = mc.getFolder();
        // TODO: add total filesize of mailbox
        out.println("<span id=info><span id=infoUser>" + sessionObject.user + "@" + domain + "</span> <span id=folderName>" + name + "</span>: "+
                    "<span id=msgcount>" + ngettext("1 Message", "{0} Messages", folder.getSize()) + "</span>" + button(LOGOUT, _t("Logout")) + "</span>");

        int page = 1;
        if (folder.getPages() > 1) {
            String sp = request.getParameter(CUR_PAGE);
            if (sp != null) {
                try {
                    page = Integer.parseInt(sp);
                } catch (NumberFormatException nfe) {}
            }
            folder.setCurrentPage(page);
        }
        showPageButtons(out, folderName, page, folder.getPages(), true);

        String curSort = folder.getCurrentSortBy();
        SortOrder curOrder = folder.getCurrentSortingDirection();
        out.print("</div>\n<table id=mailbox width=100%>\n" +
                  "<tr class=spacer><td colspan=7><hr></td></tr>\n<tr>\n" +
                  "<th class=\"mailListDate left\">" + sortHeader(SORT_DATE, _t("Date"), sessionObject.imgPath, curSort, curOrder, page, folderName) + "</th>\n" +
                  "<th class=\"mailListSender left\">" + sortHeader(SORT_SENDER, showToColumn ? _t("To") : _t("From"), sessionObject.imgPath, curSort, curOrder, page, folderName) + "</th>\n" +
                  "<th class=\"mailListAttachment center\"></th>\n" +
                  "<th class=\"mailListSubject left\">" + sortHeader(SORT_SUBJECT, _t("Subject"), sessionObject.imgPath, curSort, curOrder, page, folderName) + "</th>\n" +
                  "<th class=\"mailListFlagged center\"></th>\n" +
                  "<th class=\"mailListSize right\">" + sortHeader(SORT_SIZE, _t("Size"), sessionObject.imgPath, curSort, curOrder, page, folderName) + "</th>\n" +
                  "<th class=\"mailListDelete center\" title=\"" + _t("Mark for deletion") + "\"></th>\n" +
                  "</tr>\n");
        int bg = 0;
        int i = 0;
        for (Iterator<String> it = folder.currentPageIterator(); it != null && it.hasNext();) {
            String uidl = it.next();
            Mail mail = mc.getMail(uidl, MailCache.FetchMode.HEADER_CACHE_ONLY);
            if (mail == null || !mail.hasHeader()) {
                continue;
            }
            String type;
            if (mail.isSpam())
                type = "linkspam";
            else if (mail.isNew())
                type = "linknew";
            else if (isSpamFolder)
                type = "linkspam";
            else
                type = "linkold";
            // this is I2P Base64, not the encoder
            String b64UIDL = Base64.encode(uidl);
            String loc = myself + '?' + (folderName.equals(DIR_DRAFTS) ? NEW_UIDL : SHOW) + '=' + b64UIDL + floc;
            String link = "<a href=\"" + loc + "\" class=" + type + ">";
            String jslink = "tdclick\" onclickloc=\"" + loc + "\"";

            boolean idChecked = false;
            String checkId = sessionObject.pageChanged ? null : request.getParameter("check" + b64UIDL);

            if (checkId != null && checkId.equals("1"))
                idChecked = true;

            if (sessionObject.markAll)
                idChecked = true;
            if (sessionObject.invert)
                idChecked = !idChecked;
            if (sessionObject.clear)
                idChecked = false;

            String subj = mail.shortSubject;
            if (subj.length() <= 0) {
                subj = "<i>" + _t("no subject") + "</i>";
            }
            StringBuilder tbuf = new StringBuilder(2048);
            tbuf.append("<tr class=\"list" + bg + "\">\n")
                .append("<td class=\"mailListDate " + jslink + ">")
                .append("<span class=listDate title=\"").append(mail.dateOnly).append("\"><span>")
                // let's format time and date so it aligns and wraps nicely (for mobile)
                .append(mail.localFormattedDate
                        .replace("/", "</span>&#8239;/&#8239;<span>")
                        .replace(":", "&#8239;:&#8239;")
                        .replaceFirst(" ", "</span></span>&nbsp;<span class=listTime>")
                        .replace(" AM", "&#8239;<span class=listClock>AM</span>")
                        .replace(" PM", "&#8239;<span class=listClock>PM</span>")
                        .replace(",", "") + "</span></td>");
            if (showToColumn) {
                if (mail.to != null) {
                    StringBuilder buf = new StringBuilder(mail.to.length * 16);
                    for (int j = 0; j < mail.to.length; j++) {
                        buf.append(mail.to[j]);
                        if (j < mail.to.length - 1)
                            buf.append(", ");
                        if (buf.length() > 45)
                            break;
                    }
                    // remove angle brackets and trim to (consistent) name only so only name shown (with full address on tooltip)
                    String to = buf.toString();
                    to = to.replace("&lt;", "").replace("&gt;", "").replace("@.*", "");
                    boolean trim = to.length() > 45;
                    if (trim) {
                        to = ServletUtil.truncate(to, 42).trim();
                    }
                    to = quoteHTML(to);
                    tbuf.append("<td class=\"mailListSender ").append(jslink).append(" title=\"")
                        .append(buildRecipientLine(mail.to).replace("\"", "") + "\">")
                        .append(link).append(to);
                    if (trim) {
                        tbuf.append("&hellip;");  // must be after html encode
                    }
                    tbuf.append("</a></td>\n");
                } else {
                    tbuf.append("<td class=\"mailListSender ").append(jslink).append("></td>\n");
                }
            } else {
                // mail.shortSender and mail.shortSubject already html encoded
                tbuf.append("<td class=\"mailListSender ").append(jslink).append(" title=\"").append(mail.sender.replace("\"", "") + "\">")
                    // remove angle brackets and trim to (consistent) name only so only name shown (with full address on tooltip)
                    .append(link).append(mail.shortSender.replace("&lt;", "").replace("&gt;", "").replaceAll("@.*", "")).append("</a></td>\n");
                    // TODO: add name of attachment(s) to tooltip
            }
            boolean isHTML = mail.getAttachmentType().equals("html");
            tbuf.append("<td ").append(isHTML ? "title=\"" + _t("HTML attachment") + "\"" : "").append(" class=\"mailListAttachment ")
                .append(mail.hasAttachment() ? "isAttached " : "").append(isHTML ? "htmlMessage " : "").append(jslink).append("></td>\n")
                // TODO: show mail fragment on tooltip or hover span
                .append("<td class=\"mailListSubject ").append(jslink).append(">").append(link).append(subj).append("</a></td>\n")
                .append("<td class=\"mailListFlagged ");
            if (mail.isNew() && !mail.isSpam()) {
                tbuf.append("new ");
            } else if (mail.isSpam()) {
                tbuf.append("spam ");
            }
            tbuf.append(jslink).append(">");
            if (mail.isNew() && !mail.isSpam()) {
                tbuf.append("<img src=/susimail/icons/flag_green.png alt=\"\" title=\"" + _t("Message is new") + "\">\n");
            } else if (isSpamFolder || mail.isSpam()) {
                tbuf.append("<img src=/susimail/icons/flag_red.png alt=\"\" title=\"" + _t("Message is spam") + "\">\n");
            }
            tbuf.append("<td class=\"mailListSize ").append(jslink).append("><span class=listSize>");
            String mailSize = mail.getSize() > 0 ? DataHelper.formatSize2(mail.getSize()) + "B" : "???";
            if (mail.getSize() > 0) {
                // truncate the unit to B/K/M to optimize presentation/alignment
                mailSize = mailSize.replace("&#8239;KiB", " <span class=listSizeUnit>K")
                                   .replace("&#8239;MiB", " <span class=listSizeUnit>M")
                                   .replace("&#8239;GiB", " <span class=listSizeUnit>G")
                                   .replace("&#8239;B", " <span class=listSizeUnit>B");
            } else {
                mailSize = "<span class=unknown title=\"" + _t("Message body not downloaded") + "\">???";
            }
            tbuf.append(mailSize).append("</span></span></td>\n")
                .append("<td class=mailListDelete><input type=checkbox class=\"optbox delete1\" name=\"check").append(b64UIDL).append("\" value=1")
                .append(' ').append(idChecked ? "checked" : "").append("></td>\n</tr>\n");
            bg = 1 - bg;
            i++;
            out.print(tbuf.toString());
            out.flush();
        }
        if (i == 0) {
            out.print("<tr>\n" +
                      "<td colspan=7>\n<div id=emptymailbox><i>" + _t("No messages") + "</i>\n</div>\n</td>\n" +
                      "</tr>\n");
        }
        out.print("<tr class=\"bottombuttons spacer\">\n" +
                  "<td colspan=7></td>\n" +
                  "</tr>\n");
        if (folder.getPages() > 1 && i > 30) {
            // show the buttons again if page is big
            out.println("<tr id=pagenavbottom>\n<td colspan=7>");
            showPageButtons(out, folderName, page, folder.getPages(), false);
        }
        if (i > 0) {
            // TODO do this in js
            if (sessionObject.reallyDelete) {
                if (i > 25) {
                    out.print("<tr class=\"bottombuttons floating\" ");
                } else {
                    out.print("<tr class=bottombuttons ");
                }
                out.print("id=confirmdelete>\n<td colspan=7>");
                if (i > 25) {
                    out.print("<p class=\"error floating\" ");
                } else {
                    out.print("<p class=error ");
                }
                // TODO ngettext
                out.print("id=nukemail><span>" + _t("Really delete the marked messages?") + "</span><br>" +
                          button(REALLYDELETE, _t("Yes, really delete them!")) + "&nbsp;" + button(CLEAR, _t("Cancel")));
                out.print("</p></td>\n");
            } else {
                out.print("<tr class=bottombuttons>\n" +
                          "<td class=left colspan=3>" + (button(CONFIGURE, _t("Settings"))) + "</td>\n" +
                          "<td class=right colspan=4>" + (button(DELETE, _t("Delete Selected"))) +
                          "<span class=script>" + button(MARKALL, _t("Mark All")) + "&nbsp;" +
                          (button(CLEAR, _t("Clear All"))) + "</span></td>");
            }
        }
        out.print("</tr>\n</table>\n");
    }

    /**
     *  Folder selector, then, if pages greater than 1:
     *  first prev next last
     */
    private static void showPageButtons(PrintWriter out, String folderName, int page, int pages, boolean outputHidden) {
        String name = folderName.equals(DIR_FOLDER) ? "Inbox" : folderName;
        out.println("<div class=folders>");
        out.println(button(SWITCH_TO, _t("Change to Folder") + ':'));
        showFolderSelect(out, folderName, false);
        out.println("</div>");
        out.println("<div class=pagenavcontainer><table class=pagenav width=100%>\n" +
                    "<tr class=pagenavcontrols>\n<td>");
        if (pages > 1) {
            if (outputHidden)
                out.println("<input type=hidden name=\"" + CUR_PAGE + "\" value=\"" + page + "\">");
            String t1 = _t("First");
            String t2 = _t("Previous");
            if (page <= 1) {
                out.println(button2(FIRSTPAGE, t1) + "&nbsp;" + button2(PREVPAGE, t2));
            } else {
                if (outputHidden)
                    out.println("<input type=hidden name=\"" + PREV_PAGE_NUM + "\" value=\"" + (page - 1) + "\">");
                out.println(button(FIRSTPAGE, t1) + "&nbsp;" + button(PREVPAGE, t2));
            }
            out.println("</td><td>" + page + "&nbsp;/&nbsp;" + pages + "</td><td>");
            t1 = _t("Next");
            t2 = _t("Last");
            if (page >= pages) {
                out.println(button2(NEXTPAGE, t1) + "&nbsp;" + button2(LASTPAGE, t2));
            } else {
                if (outputHidden)
                    out.println("<input type=hidden name=\"" + NEXT_PAGE_NUM + "\" value=\"" + (page + 1) + "\">");
                out.println(button(NEXTPAGE, t1) + "&nbsp;" + button(LASTPAGE, t2));
            }
        }
        out.println("</td></tr></table></div>");
    }

    /**
     *  @param disableCurrent true for move to folder, false for select folder
     *  @since 0.9.35
     */
    private static void showFolderSelect(PrintWriter out, String currentName, boolean disableCurrent) {
        out.println("<select name=\"" + NEW_FOLDER +
                    "\" class=\"select" + (disableCurrent ? "1" : "2") +
                    "\">");
        for (int i = 0; i < DIRS.length; i++) {
            String dir = DIRS[i];
            if (currentName.equals(dir)) {
                // can't move or switch to self
                continue;
            }
            if (disableCurrent && DIR_DRAFTS.equals(dir)) {
                // can't move to drafts
                continue;
            }
            out.print("<option value=\"" + dir + "\" ");
            if (currentName.equals(dir)) {
                out.print("selected=selected ");
            }
            out.print('>' + _t(DISPLAY_DIRS[i]));
            out.println("</option>");
        }
        out.println("</select>");
    }

    /**
     *
     * @param out
     * @param sessionObject
     * @param reallyDelete was the delete button pushed, if so, show the really delete? message
     * @param allowHtml allow display of text/html parts
     */
    private static void showMessage(PrintWriter out, SessionObject sessionObject, MailCache mc,
                                    String showUIDL, boolean reallyDelete, HtmlMode allowHTML)
    {
        if (reallyDelete) {
            out.println("<p class=error id=nukemail><span>" + _t("Really delete this message?") + "</span><br>" +
                      button(REALLYDELETE, _t("Yes, really delete it!")) + "&nbsp;" +
                      button(CANCEL, _t("Cancel")) +
                      "</p>");
        }
        Mail mail = mc.getMail(showUIDL, MailCache.FetchMode.ALL);
        boolean debug = Boolean.parseBoolean(Config.getProperty(CONFIG_DEBUG));
        if (debug && mail != null && mail.hasBody() && mail.getSize() < 16384) {
            out.println("<!--");
            out.println("Debug: Mail header and body follow");
            Buffer body = mail.getBody();
            InputStream in = null;
            OutputStream sout = null;
            try {
                in = body.getInputStream();
                sout = new EscapeHTMLOutputStream(new WriterOutputStream(out));
                DataHelper.copy(in, sout);
            } catch (IOException ioe) {
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
                if (sout != null) try { sout.close(); } catch (IOException ioe) {}
                body.readComplete(true);
            }
            out.println("-->");
        }
        out.println("<div class=topbuttons id=readmail>");
        out.println(button(NEW, _t("New")) + spacer);
        boolean hasHeader = mail != null && mail.hasHeader();
        if (hasHeader) {
            out.println(button(REPLY, _t("Reply")) +
                button(REPLYALL, _t("Reply All")) +
                button(FORWARD, _t("Forward")) +
                button(SAVE_AS, _t("Save As")));
            if (sessionObject.reallyDelete)
                out.println(button2(DELETE, _t("Delete")));
            else
                out.println(button(DELETE, _t("Delete")));
        }
        out.println(button(LOGOUT, _t("Logout")));
        if (hasHeader && mail.hasBody() && !mc.getFolderName().equals(DIR_DRAFTS)) {
            // can't move unless has body
            // can't move from drafts
            //out.println(button(MOVE_TO, _t("Move to Folder") + ':'));
            //showFolderSelect(out, mc.getFolderName(), true);
        }
        // processRequest() will P-R-G the PREV and NEXT so we have a consistent URL
        out.println("<div id=messagenav>");
        Folder<String> folder = mc.getFolder();
        if (hasHeader) {
            String uidl = folder.getPreviousElement(showUIDL);
            String text = _t("Previous");
            if (uidl == null || folder.isFirstElement(showUIDL)) {
                out.println(button2(PREV, text));
            } else {
                String b64UIDL = Base64.encode(uidl);
                out.println("<input type=hidden name=\"" + PREV_B64UIDL + "\" value=\"" + b64UIDL + "\">");
                out.println(button(PREV, text));
            }
            out.print(spacer);
        }
        int page = folder.getPageOf(showUIDL);
        out.println("<input type=hidden name=\"" + CUR_PAGE + "\" value=\"" + page + "\">");
        out.println(button(LIST, _t("Back to Folder")) + spacer);
        if (hasHeader) {
            String uidl = folder.getNextElement(showUIDL);
            String text = _t("Next");
            if (uidl == null || folder.isLastElement(showUIDL)) {
                out.println(button2(NEXT, text));
            } else {
                String b64UIDL = Base64.encode(uidl);
                out.println("<input type=hidden name=\"" + NEXT_B64UIDL + "\" value=\"" + b64UIDL + "\">");
                out.println(button(NEXT, text));
            }
            out.print(spacer);
        }
        out.println("</div>\n</div>\n");
        //if (Config.hasConfigFile())
        //out.println(button(RELOAD, _t("Reload Config")) + spacer);
        out.println("<div id=viewmail>");
        // can't move unless has body
        // can't move from drafts
        if (mail.hasBody() && !mc.getFolderName().equals(DIR_DRAFTS)) {
            out.print("<div class=folders>");
            out.print(button(MOVE_TO, _t("Move to Folder")));
            showFolderSelect(out, mc.getFolderName(), true);
            out.print("</div>\n");
        }
            out.println("<table id=message_full>\n");
        if (hasHeader) {
            String subj = mail.subject;
            if (subj.length() > 0)
                subj = quoteHTML(subj);
            else
                subj = "<i>" + _t("no subject") + "</i>";
            out.print("<tr>\n" +
                      "<td colspan=2>\n<table id=mailhead>\n" +
                      "<tr><td colspan=2><hr></td></tr>\n");
            out.print("<tr><td class=right>" + _t("Date") + ":</td><td class=left title=\"" + mail.dateOnly + "\">" + mail.quotedDate + "</td></tr>\n" +
                      "<tr><td class=right>" + _t("From") + ":</td><td class=left>" + quoteHTML(mail.sender) + "</td></tr>\n");
            if (mail.to != null) {
                out.print("<tr><td class=right>" + _t("To") + ":</td><td class=left>" + buildRecipientLine(mail.to) + "</td></tr>\n");
            }
            if (mail.cc != null) {
                out.print("<tr><td class=right>" + _t("Cc") + ":</td><td class=left>" + buildRecipientLine(mail.cc) + "</td></tr>\n");
            }
            out.print("<tr><td class=right>" + _t("Subject") + ":</td>\n" +
                      "<td class=left>" + subj + "<span id=toggleViewMode class=script style=float:right hidden><a id=switchViewMode></a>" +
                      "<a id=newTabHtmlView hidden>" + _t("View message in new tab") + "</a></span> " +
                      "<span id=toggleHeaders class=script style=float:right>" +
                      "<a href=\"#\" class=\"script fakebutton\" id=expand>Show Headers</a> " +
                      "<a href=\"#\" class=\"script fakebutton\" id=collapse style=display:none>Hide Headers</a> " +
                      "</span></td></tr>\n<tr><td colspan=2 class=spacer><hr></td></tr>\n</table>\n</td>\n</tr>\n");
            if (mail.hasPart()) {
                mail.setNew(false);
                showPart(out, mail.getPart(), 0, SHOW_HTML, allowHTML);
            }
            else {
                out.print("<tr class=mailbody>\n<td class=center colspan=2>" +
                          "<p class=error>" + _t("Could not fetch mail body.") + "</p>" +
                          "</td>\n</tr>\n");
            }
        }
        else {
            out.print("<tr class=mailbody>\n<td class=center colspan=2>" +
                        "<p class=error>" + _t("Message not found.") + "</p>" +
                        "</td>\n</tr>\n");
        }
        out.print("</table>\n</div>\n");
    }

    /**
     *  TODO this is addresses only, we don't save the full line in Mail
     *
     *  @param to non-null
     *  @since 0.9.33
     */
    private static String buildRecipientLine(String[] to) {
        if (to != null) {
            StringBuilder buf = new StringBuilder(to.length * 16);
            for (int i = 0; i < to.length; i++) {
                buf.append(to[i]);
                if (i < to.length - 1)
                    buf.append(", ");
            }
            return quoteHTML(buf.toString());
        } else {
            return "";
        }
    }

    /**
     *  Simple configure page
     *
     *  @param folder may be null
     *  @since 0.9.13
     */
    private static void showConfig(PrintWriter out, Folder<String> folder) {
        int sz;
        if (folder != null)
            sz = folder.getPageSize();
        else
            sz = Config.getProperty(Folder.PAGESIZE, Folder.DEFAULT_PAGESIZE);
        out.print("<div class=topbuttons id=pagesize><b>");
        out.print(_t("Folder Page Size") + ":</b>&nbsp;<input type=text name=\"" + PAGESIZE +
                    "\" size=4 value=\"" +  sz + "\">&nbsp;" + button(SETPAGESIZE, _t("Set")) + "</div>\n");
        out.print("<h3 id=config>");
        out.print(_t("Advanced Configuration"));
        Properties config = Config.getProperties();
        out.print("</h3>\n<textarea cols=80 rows=\"" + Math.max(8, config.size() + 2) + "\" spellcheck=false name=\"" + CONFIG_TEXT + "\">\n");
        for (Map.Entry<Object, Object> e : config.entrySet()) {
            out.print(quoteHTML(e.getKey().toString()));
            out.print('=');
            out.println(quoteHTML(e.getValue().toString()));
        }
        out.print("\n</textarea>\n");
        out.print("<div id=prefsave>");
        out.print(button(CANCEL, _t("Cancel")));
        out.print(button(SAVE, _t("Save Configuration")));
        if (folder != null)
            out.print(spacer + button(LOGOUT, _t("Logout")));
        out.print("</div>\n");
    }

    /** tag for translation */
    private static String _x(String s) {
        return s;
    }

    /** translate */
    private static String _t(String s) {
        return Messages.getString(s);
    }

    /** translate */
    private static String _t(String s, Object o) {
        return Messages.getString(s, o);
    }

    /** translate */
    private static String _t(String s, Object o, Object o2) {
        return Messages.getString(s, o, o2);
    }

    /** translate */
    private static String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p);
    }

    /**
     * Get all themes
     * @return String[] -- Array of all the themes found.
     */
    private static String[] getThemes(I2PAppContext ctx) {
            String[] themes;
            File dir = new File(ctx.getBaseDir(), "docs/themes/susimail");
            FileFilter fileFilter = new FileFilter() { public boolean accept(File file) { return file.isDirectory(); } };
            File[] dirnames = dir.listFiles(fileFilter);
            if (dirnames != null) {
                List<String> th = new ArrayList<String>(dirnames.length);
                for (int i = 0; i < dirnames.length; i++) {
                    String name = dirnames[i].getName();
                    th.add(name);
                }
                themes = th.toArray(new String[th.size()]);
            } else {
                themes = new String[0];
            }
            return themes;
    }
}
