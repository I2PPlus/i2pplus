package net.i2p.router.web.helpers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.jar.Attributes;
import java.util.regex.*;

import javax.servlet.http.HttpSession;

import net.i2p.crypto.SigType;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.router.web.CSSHelper;
import net.i2p.router.web.ConfigServiceHandler;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.util.Translate;
import net.i2p.util.UIMessages;

/**
 * Helper for the logs page.
 * @since 0.9.33
 */
public class LogsHelper extends HelperBase {

    /**
     *  Constructor, used by the console when it builds this bean. The rewrite
     *  tables below are static, so a bare instance needs no initialization.
     */
    public LogsHelper() {}

    private static final Pattern LOG_LEVEL_PATTERN = Pattern.compile("\\|\\s*(DEBUG|INFO|WARN|ERROR|CRIT)\\s");
    private static final Pattern BRACKET_CLEANUP_PATTERN = Pattern.compile("\\[\\[(&#10004;|&#10008;)\\]\\]");
    private static final Pattern NEWLINE_STAR_PATTERN = Pattern.compile("\n(\\t)?\\* ");
    private static final Pattern NEWLINE_SPLIT = Pattern.compile("\n");
    private static final Pattern SPACE_SPLIT = Pattern.compile(" ");
    private static final Pattern LOG_HUTD = Pattern.compile("\\|.*\\[.*hutd.*\\].*?:");
    private static final Pattern LOG_DATE = Pattern.compile("\\|.*\\[.*date.*\\].*?:");
    private static final Pattern LOG_CONNECTION = Pattern.compile("\\|.*\\[.*-Connection].*?:");
    private static final Pattern LOG_RESTART = Pattern.compile("\\|.*\\[.*Restart].*?:");
    private static final Pattern LOG_READ = Pattern.compile("\\|.*\\[.*Read.*\\].*?:");
    private static final Pattern LOG_DIRMON = Pattern.compile("\\|.*\\[.*DirMon.*\\].*?:");
    private static final Pattern LOG_QUEUE = Pattern.compile("\\|.*\\[.*Queue.*\\].*?:");
    /** Anchor-stripping runs on every rendered line, so its patterns are hoisted. */
    private static final Pattern ANCHOR_ESCAPED = Pattern.compile("&lt;a[^&]*&gt;");
    private static final Pattern ANCHOR_CLOSE_ESCAPED = Pattern.compile("&lt;/a&gt;");
    private static final Pattern ANCHOR = Pattern.compile("<a[^>]*>");
    private static final Pattern ANCHOR_CLOSE = Pattern.compile("</a>");

/**
 * Ordered literal rewrites applied to an escaped log message, up to the
 * double-bracket cleanup. Order is load-bearing and not mergeable: this table
 * turns "&amp;hellip;" into "...", which MSG_REWRITES_C turns into
 * "&hellip;", and it ticks "false" before a later rewrite can restore
 * "[&#10008;] positives" to "false positives".
 *
 * @since 0.9.72+
 */
    private static final String[][] MSG_REWRITES_A = {
        {"&amp;darr;", "&darr;"},
        {"&amp;uarr;", "&uarr;"},
        {"&amp;#10140;", "&#10140;"},
        {"--&gt;", " &#10140; "},
        {" -&gt;", " &#10140;"},
        {"-&gt;", " &#10140; "},
        {"  &#10140;  ", " &#10140; "},
        {"&amp;hellip;", "..."}, // "..." is rewritten again below
        {"\u2026obQueue", "JobQueue"},
        {"[DBWriter   ]", "[NetDB Writer]"},
        {"[NTCP Pumper", "[ NTCP Pumper"},
        {"[\u2026ueue Pumper", "[Queue Pumper"},
        {"[UDPSender", "[UDP Sender"},
        {"[BWRefiller", "[BW Refiller"},
        {"[Addressbook]", "[Addressbook ]"},
        {"[Thread-", "[ Thread-"},
        {"[Timestamper]", "[Timestamper ]"},
        {"[DHT Explore]", "[DHT Explore ]"},
        {"[HostChecker]", "[HostChecker ]"},
        {"false", "[&#10008;]"}, // no (cross)
        {"[&#10008;] positives", "false positives"},
        {"true", "[&#10004;]"} // yes (tick)
    };

/**
 * The middle of the rewrite chain, run after the double-bracket cleanup and
 * before the bullet rewrite. The un-ticking has to see the "&#10004;" and
 * "&#10008;" that the first table produces.
 *
 * @since 0.9.72+
 */
    private static final String[][] MSG_REWRITES_B = {
        {"=[&#10004;]", "=true"},
        {"=[&#10008;]", "=false"},
        {"[IRC Client] Inbound message", "[IRC Client] &#11167;"},
        {"[IRC Client] Outbound message", "[IRC Client] &#11165;"},
        {"not publishing old one: RouterInfo:", "not publishing old one:"},
        {"Publishing our RouterInfo after delay: RouterInfo:", "Publishing our RouterInfo after delay:"},
        {":  ", ": "}
    };

/**
 * The tail of the rewrite chain, run after the bullet rewrite. "..." has to be
 * rewritten here, not in the first table, because it is what that table's
 * "&amp;hellip;" rewrite produces.
 *
 * @since 0.9.72+
 */
    private static final String[][] MSG_REWRITES_C = {
        {"\r\n\r\n", ""},
        {"<br>:", " "},
        {"<b>", ""},
        {"</b>", ""},
        {"&lt;b&gt;", ""},
        {"&lt;/b&gt;", ""},
        {"...", "&hellip;"},
        {"]]", "]"}
    };

    /**
     * Ordered literal rewrites applied to a raw wrapper log buffer. Order is
     * load-bearing: "INFO   | ERROR" is rewritten into "| ERROR  |" first, and
     * the later "| ERROR  |" → "| ERR  |" then has to see that result.
     *
     * @since 0.9.72+
     */
    private static final String[][] WRAPPER_LOG_REWRITES = {
        {"| |", "|"},
        {"| INFO   | INFO:", "| INFO   |"},
        {"| INFO   | CRIT ", "| CRIT   |"},
        {"| INFO   | ERROR", "| ERROR  |"},
        {"| INFO   | Error", "| ERROR  | Error"},
        {"| INFO   | java.lang", "| ERROR  | java.lang"},
        {"| INFO   | \tat", "| ERROR  | \tat"},
        {"| ERROR  | [Reseed     ] ....reseed.Reseeder:", "| WARN   |"},
        {" |[", " | ["},
        {"INFO   | WARN:", "WARN   |"},
        {"INFO   | WARNING:", "WARN   |"},
        {"   |", " |"},
        {"| ERROR  |", "| ERR  |"},
        {"| INFO | # V  [", "| INFO | # Source: ["},
        {"->", "\u279c"},
        {"| LOCAL LeaseSet for", "| WARN | LOCAL LeaseSet for"},
        {"| Initiating graceful restart", "| INFO | Initiating graceful restart"},
        {"| Graceful shutdown", "| INFO | Graceful shutdown"},
        {"| I2P+ update downloaded", "| INFO | I2P+ update downloaded"}
    };

    /**
     * Sets the router context whose log manager this helper reads from
     *
     * @param context the context injected by the servlet container before any log call
     */
    public void setContext(RouterContext context) {this._context = context;}
    private static final String _jstlVersion = jstlVersion();
    private static final int MAX_WRAPPER_LINES = 320;
    private static final String PROP_LAST_WRAPPER = "routerconsole.lastWrapperLogEntry";
    private final StringBuilder _msgBuf = new StringBuilder(12*1024);

    /**
     * Return the Jetty version the console is running under.
     *
     * @return the Jetty version string, or "n/a" if it cannot be determined
     * @since 0.8.12
     */
    public String getJettyVersion() {return RouterConsoleRunner.jettyVersion();}

    /**
     * Return a table row for each signature type this router cannot use.
     *
     * @return the table rows listing each unavailable signature type, empty if
     *         every type is available
     * @since 0.9.15
     */
    public String getUnavailableCrypto() {
        StringBuilder buf = new StringBuilder(128);
        for (SigType t : SigType.values()) {
            if (!t.isAvailable()) {
                buf.append("<tr><td><b>Crypto:</b></td><td>").append(t.toString()).append(" unavailable</td></tr>");
            }
        }
        return buf.toString();
    }

    /**
     * Return the JSTL taglib version string.
     *
     * @return non-null, "n/a" on failure
     * @since 0.9.26
     */
    public String getJstlVersion() {return _jstlVersion;}

    /**
     * Look up the JSTL version via reflection.
     *
     * @return non-null, "n/a" on failure
     * @since 0.9.26
     */
    private static String jstlVersion() {
        String rv = "n/a";
        try {
            Class<?> cls = Class.forName("org.apache.taglibs.standard.Version", true, ClassLoader.getSystemClassLoader());
            Method getVersion = cls.getMethod("getVersion");
            // returns "standard-taglib 1.2.0"
            Object version = getVersion.invoke(null, (Object[]) null);
            rv = (String) version;
        } catch (Exception e) { /* ignored */ }
        return rv;
    }

    /**
     * Return HTML rendering of the most recent log messages.
     * Does not call logManager.flush(); call getCriticalLogs() first to flush
     * @return the formatted log messages, wrapped in a link to the log file
     */
    public String getLogs() {
        String str = formatMessages(_context.logManager().getBuffer().getMostRecentMessages());
        boolean embedApps = _context.getBooleanProperty(CSSHelper.PROP_EMBED_APPS);
        return "<p>" + _t("File location") + ": <a href=/router.log target=_blank>" +
                DataHelper.escapeHTML(_context.logManager().currentFile()) + "</a></p>" +
                "<span id=refreshPeriod style=display:none title=\"" + _t("Refresh interval (seconds)") +
                "\"><input type=number id=logRefreshInterval></span>" + str;
    }

    /**
     * Return HTML rendering of the most recent critical log messages.
     * @return the critical logs
     */
    public String getCriticalLogs() {
        return formatMessages(_context.logManager().getBuffer().getMostRecentCriticalMessages());
    }

    /**
     * Returns the number of critical/error log messages currently displayed.
     *
     * @return the count of critical/error messages
     * @since 0.9.68+
     */
    public int getCriticalLogCount() {
        _context.logManager().flush();
        int count = getLastCriticalMessageNumber();
        return count >= 0 ? count + 1 : 0;
    }

    /**
     * Returns the ID of the most recent log message, for clearThrough().
     * Call before getLogs()
     *
     * @return -1 if none
     * @since 0.9.46
     */
    public int getLastMessageNumber() {
        UIMessages msgs = _context.logManager().getBuffer().getUIMessages();
        if (msgs.isEmpty()) {return -1;}
        return msgs.getLastMessageID();
    }

    /**
     * Call before getLogs(), getCriticalLogs(), or getLastMessageNumber()
     * Side effect - calls logManager.flush()
     *
     * @return -1 if none
     * @since 0.9.46
     */
    public int getLastCriticalMessageNumber() {
        _context.logManager().flush();
        UIMessages msgs = _context.logManager().getBuffer().getCriticalUIMessages();
        if (msgs.isEmpty()) {return -1;}
        return msgs.getLastMessageID();
    }

    /**
     * Clear log messages up through the specified message IDs.
     *
     * @param n -1 for none
     * @param crit -1 for none
     * @param wn last line number of the wrapper log already shown, or -1 for none
     * @param wts timestamp in ms of the wrapper log line already shown, or -1
     *        for none
     * @param wf filename of the wrapper log already shown, or null for none
     * @param session the console session holding the nonce, or null to fall back
     *        to the process-wide nonce
     * @param consoleNonce the submitted anti-CSRF token, which must equal the
     *        session's nonce, or the process-wide one if no session was given
     * @since 0.9.46
     */
    public void clearThrough(int n, int crit, long wn, long wts, String wf, HttpSession session, String consoleNonce) {
        if (session != null) {
            if (!CSSHelper.validateNonce(session, consoleNonce)) {return;}
        } else {
            // Fallback for backward compatibility
            if (!CSSHelper.getNonce().equals(consoleNonce)) {return;}
        }
        if (n >= 0) {_context.logManager().getBuffer().getUIMessages().clearThrough(n);}
        if (crit >= 0) {
            _context.logManager().getBuffer().getCriticalUIMessages().clearThrough(crit);
            _context.logManager().flush(); // Force a refresh of the message list
        }
        if (wn >= 0 && wts > 0 && wf != null) {
            // timestamp, last line number, filename
            String val = wts + "," + wn + "," + wf;
            if (!val.equals(_context.getProperty(PROP_LAST_WRAPPER))) {
                _context.router().saveConfig(PROP_LAST_WRAPPER, val);
            }
        }
    }

    /**
     * Return HTML rendering of the tail of the wrapper (service) log, with the
     * modification time, last line number and filename of the log file.
     * @param obuf out parameter receiving the HTML location and content block
     * @return Long timestamp, Long last line number, String filename (escaped)
     */

    public Object[] getServiceLogs(StringBuilder obuf) {
        File f = ConfigServiceHandler.wrapperLogFile(_context);
        String str;
        boolean embedApps = _context.getBooleanProperty(CSSHelper.PROP_EMBED_APPS);
        long flastMod = f.lastModified();
        long lastMod = 0;
        long toSkip = 0;
        // timestamp, last line number, filename
        String prop = _context.getProperty(PROP_LAST_WRAPPER);
        if (prop != null) {
            String[] vals = DataHelper.split(prop, ",", 3);
            if (vals.length == 3) {
                if (vals[2].equals(f.getName())) {
                    try { lastMod = Long.parseLong(vals[0]); } catch (NumberFormatException nfe) { /* ignored */ }
                    try { toSkip = Long.parseLong(vals[1]); } catch (NumberFormatException nfe) { /* ignored */ }
                } else {lastMod = 0;} // file rotated
            }
        }
        if (lastMod > 0 && flastMod <= lastMod) {
            str = "";
            toSkip = -1;
        } else {
            // Platform encoding or UTF8
            boolean utf8 = !_context.hasWrapper();
            StringBuilder buf = new StringBuilder(MAX_WRAPPER_LINES * 80);
            long ntoSkip = readTextFile(f, utf8, MAX_WRAPPER_LINES, toSkip, buf);
            if (ntoSkip < toSkip) {
                if (ntoSkip < 0) {str = null;} // error
                else {str = "";} // truncated?
                if (prop != null) {_context.router().saveConfig(PROP_LAST_WRAPPER, null);} // remove old setting
            } else {
                str = applyRewrites(buf.toString(), WRAPPER_LOG_REWRITES);
                str = LOG_HUTD.matcher(str).replaceAll("|");
                str = LOG_DATE.matcher(str).replaceAll("|");
                str = LOG_CONNECTION.matcher(str).replaceAll("|");
                str = LOG_RESTART.matcher(str).replaceAll("|");
                str = LOG_READ.matcher(str).replaceAll("|");
                str = LOG_DIRMON.matcher(str).replaceAll("|");
                str = LOG_QUEUE.matcher(str).replaceAll("|");
                // Remove lines containing unwanted strings
                StringBuilder filtered = new StringBuilder(str.length());
                int start = 0;
                int end;
                while ((end = str.indexOf('\n', start)) >= 0) {
                    String line = str.substring(start, end);
                    start = end + 1;
                    // Fast path: skip lines with common unwanted substrings
                    if (line.indexOf("Copyright") >= 0 ||
                        line.indexOf("tanukisoftware") >= 0 ||
                        line.indexOf("STATUS") >= 0 ||
                        line.indexOf("If you would like to submit a bug report") >= 0 ||
                        line.indexOf("Problematic frame:") >= 0 ||
                        line.indexOf("INFO | #   Unknown") >= 0 ||
                        line.indexOf("Unable to build") >= 0 ||
                        line.indexOf("HTTPServer") >= 0 ||
                        line.indexOf("Incrementing failed invocation") >= 0 ||
                        line.indexOf("than the successful invocation time") >= 0 ||
                        line.indexOf("Disconnect Message received") >= 0 ||
                        line.indexOf("Cannot send to TunnelGateway") >= 0 ||
                        line.indexOf("Unable to connect to I2P") >= 0 ||
                        line.indexOf("Unable to add torrent") >= 0 ||
                        line.indexOf("Error creating session") >= 0 ||
                        line.indexOf("ContextHandler") >= 0 ||
                        line.indexOf("AbstractConnector") >= 0 ||
                        line.indexOf("I2PSessionException") >= 0 ||
                        line.indexOf("LOCAL LeaseSet for") >= 0 ||
                        line.indexOf("info_hash") >= 0 ||
                        line.contains("at net.i2p") ||
                        line.contains("at java.base") ||
                        line.contains("at org.eclipse.jetty") ||
                        line.contains("at javax.servlet.http") ||
                        line.endsWith("java.lang.IllegalStateException") ||
                        line.contains("  \n")) {
                        continue;
                    }
                    // Skip empty lines and certain patterns
                    if (line.isEmpty() || line.endsWith("#") || line.endsWith("* ")) {
                        continue;
                    }
                    filtered.append(line).append("\n");
                }

                // Sort lines first by timestamp and then by content
                String[] lines = NEWLINE_SPLIT.split(filtered.toString());
                final int len = lines.length;
                Arrays.sort(lines, LogsHelper::compareLogLines);

                // Reuse filtered StringBuilder and append in reverse order
                filtered.setLength(0);
                for (int i = len - 1; i >= 0; i--) {filtered.append(lines[i]).append("\n");}
                str = filtered.toString();
            }
            toSkip = ntoSkip;
        }
        String loc = DataHelper.escapeHTML(f.getAbsolutePath());
        if (str == null) {
            obuf.append("<p>").append(_t("File not found")).append(": <b><code>").append(loc).append("</code></b></p>");
            toSkip = -1;
        } else {
            obuf.append("<p>").append(_t("File location")).append(": <a href=\"/wrapper.log\" target=_blank>")
                .append(loc).append("</a>").append("</p></td></tr>\n<tr><td>");
            if (!str.isEmpty()) {
                str = str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                obuf.append("<pre id=service_logs>").append(str).append("</pre>");
            } else {
                obuf.append("<p class=nologs><i>").append(_t("No log messages")).append("</i></p>");
            }
        }
        Object[] rv = new Object[3];
        rv[0] = Long.valueOf(flastMod);
        rv[1] = Long.valueOf(toSkip);
        rv[2] = DataHelper.escapeHTML(f.getName()).replace(" ", "%20");
        return rv;
    }

    /**
     * Return the Built-By attribute from the i2p.jar manifest.
     *
     * @return the built by
     * @since 0.9.35
     */
    public String getBuiltBy() {
        File libDir = _context.getLibDir();
        File f = new File(libDir, "i2p.jar");
        Attributes att = FileDumpHelper.attributes(f);
        if (att != null) {
            String s = FileDumpHelper.getAtt(att, "Built-By");
            if (s != null) {return s;}
        }
        return "Undefined";
    }

    /**
     * Return the build revision and date from the routerconsole.war manifest.
     *
     * @return the Base-Revision attribute, linked to the commit when built by z3d,
     *         or an empty string when the manifest carries no usable attributes
     * @since 0.9.50+
     */
    public String getRevision() {
        File baseDir = _context.getBaseDir();
        File f = new File(new File(baseDir, "webapps"), "routerconsole.war");
        Attributes att = FileDumpHelper.attributes(f);
        if (att != null) {
            String rev = FileDumpHelper.getAtt(att, "Base-Revision");
            String date = FileDumpHelper.getAtt(att, "Build-Date");
            String by = FileDumpHelper.getAtt(att, "Built-By");
            if (rev != null && by.contains("|z3d")) {
                return "<a id=revision target=_blank rel=\"noreferrer\" href=\"http://git.skank.i2p/i2pplus/I2P.Plus/src/commit/" +
                        rev + "\">" + rev + "</a> (Build date: " + date + ")";
            } else {return rev + " (Build date: " + date + ")";}
        }
        return "";
    }

    private final static String NL = System.getProperty("line.separator");

/**
 * Apply an ordered table of literal rewrites.
 *
 * <p>Equivalent to chaining {@link String#replace(CharSequence, CharSequence)}
 * down the table in order, so a rewrite routinely matches text an earlier one
 * produced. A rewrite whose needle is absent costs one indexOf and allocates
 * nothing, which is the common case.
 *
 * @param msg the text to rewrite, never null
 * @param rewrites {needle, replacement} pairs, applied in array order
 * @return the rewritten text
 * @since 0.9.72+
 */
    static String applyRewrites(String msg, String[][] rewrites) {
        for (String[] rewrite : rewrites) {
            if (msg.indexOf(rewrite[0]) >= 0) {msg = msg.replace(rewrite[0], rewrite[1]);}
        }
        return msg;
    }

    /** formats in forward order */
    private String formatMessages(List<String> msgs) {
        if (msgs.isEmpty()) {return "</td></tr><tr><td><p class=nologs><i>" + _t("No log messages") + "</i></p>";}
        boolean colorize = _context.getBooleanPropertyDefaultTrue("routerconsole.logs.color");
        StringBuilder buf = _msgBuf;
        buf.setLength(0);
        buf.append("</td></tr><tr><td><ul>");
        boolean displayed = false;
        // newest first
        for (int i = msgs.size() - 1; i >= 0; i--) {
            String msg = msgs.get(i);
            // don't display the dup message if it is first
            if (!displayed && msg.contains("&uarr;") || !displayed && msg.contains("&darr;")) {continue;}
            displayed = true;
            msg = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            // Preserve thread column padding for HTML (raw logs keep spaces):
            // convert trailing spaces inside [thread··] to &nbsp; so HTML does
            // not collapse them, e.g. "[SAM-PWkr.2  ]" -> "[SAM-PWkr.2&nbsp;&nbsp;]".
            {
                int lb = msg.indexOf(" [");
                int rb = lb >= 0 ? msg.indexOf("] ", lb + 2) : -1;
                if (lb >= 0 && rb > lb) {
                    String inside = msg.substring(lb + 2, rb);
                    int pad = 0;
                    for (int k = inside.length() - 1; k >= 0 && inside.charAt(k) == ' '; k--) pad++;
                    if (pad > 0) {
                        String core = inside.substring(0, inside.length() - pad);
                        StringBuilder nbsp = new StringBuilder(pad * 6);
                        for (int k = 0; k < pad; k++) nbsp.append("&nbsp;");
                        msg = msg.substring(0, lb + 2) + core + nbsp.toString() + msg.substring(rb);
                    }
                }
            }
            // Strip <a>...</a> to just inner text for console — e.g., "<a href=...>foo</a>" → "foo"
            msg = ANCHOR_ESCAPED.matcher(msg).replaceAll("");
            msg = ANCHOR_CLOSE_ESCAPED.matcher(msg).replaceAll("");
            msg = ANCHOR.matcher(msg).replaceAll("");
            msg = ANCHOR_CLOSE.matcher(msg).replaceAll("");
            msg = applyRewrites(msg, MSG_REWRITES_A);
            // Use regex to clean up double brackets, but only when one is present
            if (msg.indexOf("[[") >= 0) {
                Matcher bm = BRACKET_CLEANUP_PATTERN.matcher(msg);
                msg = bm.replaceAll("[$1]");
            }
            msg = applyRewrites(msg, MSG_REWRITES_B);
            // Use regex for bullet replacements, but only when there is a newline
            if (msg.indexOf('\n') >= 0) {
                Matcher nm = NEWLINE_STAR_PATTERN.matcher(msg);
                msg = nm.replaceAll("\n$1&bullet; ");
            }
            msg = applyRewrites(msg, MSG_REWRITES_C);
            // highlight log level indicators using regex
            Matcher m = LOG_LEVEL_PATTERN.matcher(msg);
            if (m.find()) {
                String level = m.group(1);
                msg = m.replaceFirst("| <span class=log_level>" + level + "</span> ");
            }
            if (msg.contains("| &darr;&darr;&darr; ")) {msg = msg.replace("| &darr;&darr;&darr; ", " <span class=log_omitted>&darr;&darr;&darr;</span> ");} // LogWriter BUFFER_DISPLAYED_REVERSE = true;
            if (msg.contains("| &uarr;&uarr;&uarr; ")) {msg = msg.replace("| &uarr;&uarr;&uarr; ", " <span class=log_omitted>&uarr;&uarr;&uarr;</span> ");}
            // remove  last \n that LogRecordFormatter added
            if (msg.endsWith(NL)) {msg = msg.substring(0, msg.length() - NL.length());}
            // replace \n so that exception stack traces will format correctly and will paste nicely into pastebin
            msg = msg.replace("\n", "<br>&nbsp;&nbsp;\n");
            if (msg.contains("Sending client")) {msg = msg.replace("<br>&nbsp;&nbsp;\n", "");} // SAM client
            if (msg.contains("org.eclipse.jetty") && msg.contains(_c("WARN"))) {continue;} // hide jetty warn level logging
            String level;
            String color;
            if (msg.contains(_c("CRIT"))) {level = "log_critical";}
            else if (msg.contains(_c("ERROR"))) {level = "log_error";}
            else if (msg.contains(_c("WARN"))) {level = "log_warn";}
            else if (msg.contains(_c("INFO"))) {level = "log_info";}
            else if (msg.contains("&darr;") || msg.contains("&uarr;"))  {level = "log_omitted";}
            else {level = "log_debug";}
            buf.append("<li class=\"").append(level);
            if (colorize) {buf.append(" colorize");}
            buf.append("\">").append(msg).append("</li>\n");
        }
        buf.append("</ul>\n");

        return buf.toString();
    }

    /**
     * Read in the last few lines of a (newline delimited) textfile, or null if
     * the file doesn't exist.
     *
     * Same as FileUtil.readTextFile but uses platform encoding,
     * not UTF-8, since the wrapper log cannot be configured:
     * http://stackoverflow.com/questions/14887690/how-do-i-get-the-tanuki-wrapper-log-files-to-be-utf-8-encoded
     *
     * Warning - this inefficiently allocates a StringBuilder of size maxNumLines*80,
     * so don't make it too big.
     * Warning - converts \r\n to \n
     *
     * @param utf8 true for utf-8, false for system locale
     * @param maxNumLines max number of lines (greater than zero)
     * @param skipLines number of lines to skip, or zero
     * @param buf out parameter receiving the trailing lines, each terminated by '\n'
     * @return -1 on failure, or number of lines in the file. Does not throw IOException.
     * @since 0.9.11 modded from FileUtil.readTextFile()
     */
    private static long readTextFile(File f, boolean utf8, int maxNumLines, long skipLines, StringBuilder buf) {
        if (!f.exists()) {return -1;}
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            long i = 0;
            while (i < skipLines) {
                // skip without readLine() to avoid object churn
                int c;
                do {
                    c = in.read();
                    if (c < 0) {return i;} // truncated
                } while (c != '\n');
                i++;
            }
            Queue<String> lines = new ArrayBlockingQueue<>(maxNumLines);
            synchronized(lines) {
                String line = null;
                while ((line = in.readLine()) != null) {
                    i++;
                    if (lines.size() >= maxNumLines) {lines.poll();}
                    lines.offer(line);
                }
                for (String ln : lines) {buf.append(ln).append('\n');}
            }
            return i;
        } catch (IOException ioe) {return -1;}
        finally {
            if (in != null) {
                try {in.close();}
                catch (IOException ioe) { /* ignored */ }
            }
        }
    }

    private static final String CORE_BUNDLE_NAME = "net.i2p.util.messages";

    /**
     * Translate a string from the core bundle.
     * @since 0.9.45
     */
    private String _c(String s) {
        return Translate.getString(s, _context, CORE_BUNDLE_NAME);
    }

    /**
     * Reports whether advanced mode is enabled for this console.
     * @return true if the advanced-mode property is set, false otherwise
     */
    public boolean isAdvanced() {
        return _context.getBooleanProperty(PROP_ADVANCED);
    }

    /**
     * Compare two log lines by timestamp (first space-delimited token) then content.
     * @since 0.9.70+
     */
    private static int compareLogLines(String a, String b) {
        String[] aParts = SPACE_SPLIT.split(a);
        String[] bParts = SPACE_SPLIT.split(b);
        int result = aParts[0].compareTo(bParts[0]); // Sort by timestamp
        if (result == 0) {result = a.compareTo(b);} // If timestamps are equal, sort by content
        return result;
    }
}
