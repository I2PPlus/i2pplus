package net.i2p.router.web.helpers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SigType;
import net.i2p.router.web.ConfigServiceHandler;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.util.FileUtil;
import net.i2p.util.Translate;

import net.i2p.router.web.CSSHelper;
import java.util.regex.*;

public class LogsHelper extends HelperBase {

    private static final String _jstlVersion = jstlVersion();

    /** @since 0.8.12 */
    public String getJettyVersion() {
        return RouterConsoleRunner.jettyVersion();
    }

    /** @since 0.9.15 */
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
     * @return non-null, "n/a" on failure
     * @since 0.9.26
     */
    public String getJstlVersion() {
        return _jstlVersion;
    }

    /**
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
            //int sp = rv.indexOf(' ');
            //if (sp >= 0 && rv.length() > sp + 1)
            //    rv = rv.substring(sp + 1);
        } catch (Exception e) {}
        return rv;
    }

    /**
     *  Does not call logManager.flush(); call getCriticalLogs() first to flush
     */
    public String getLogs() {
        String str = formatMessages(_context.logManager().getBuffer().getMostRecentMessages());
        boolean embedApps = _context.getBooleanProperty(CSSHelper.PROP_EMBED_APPS);
        if (!embedApps) {
            return "<p>" + _t("File location") + ": <a href=\"/router.log\" target=\"_blank\">" + _context.logManager().currentFile() + "</a></p>" + str;
        } else {
            return "<p>" + _t("File location") + ": <a href=\"/router.log\" target=\"_blank\">" + _context.logManager().currentFile() + "</a>&nbsp; " +
                    "<a class=\"embedlink script\" href=\"/embed?url=/router.log&amp;name=Router+Log\">" + _t("Embedded Log") + "</a></p>" + str;
        }
    }

    /**
     *  Side effect - calls logManager.flush()
     */
    public String getCriticalLogs() {
        _context.logManager().flush();
        return formatMessages(_context.logManager().getBuffer().getMostRecentCriticalMessages());
    }

    public String getServiceLogs() {
        File f = ConfigServiceHandler.wrapperLogFile(_context);
        String str;
        boolean embedApps = _context.getBooleanProperty(CSSHelper.PROP_EMBED_APPS);
        if (_context.hasWrapper()) {
            // platform encoding
            str = readTextFile(f, 250);
        } else {
            // UTF-8
            str = FileUtil.readTextFile(f.getAbsolutePath(), 250, false);
        }
        if (str == null) {
            return "<p>" + _t("File not found") + ": <b><code>" + f.getAbsolutePath() + "</code></b></p>";
        } else {
            str = str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            if (!embedApps) {
                return "<p>" + _t("File location") + ": <a href=\"/wrapper.log\" target=\"_blank\">" + f.getAbsolutePath() + "</a></p></td></tr>\n<tr><td><pre id=\"servicelogs\">" + str + "</pre>";
            } else {
                return "<p>" + _t("File location") + ": <a href=\"/wrapper.log\" target=\"_blank\">" + f.getAbsolutePath() + "</a>" +
                        "&nbsp; <a class=\"embedlink script\" href=\"/embed?url=/wrapper.log&amp;name=Wrapper+Log\">" + _t("Embedded Log") + "</a>" +
                        "</p></td></tr>\n<tr><td><pre id=\"servicelogs\">" + str + "</pre>";
            }
        }
    }

    /**
     * @since 0.9.35
     */
    public String getBuiltBy() {
        File baseDir = _context.getBaseDir();
        File f = new File(new File(baseDir, "lib"), "i2p.jar");
        Attributes att = FileDumpHelper.attributes(f);
        if (att != null) {
            String s = FileDumpHelper.getAtt(att, "Built-By");
            if (s != null) {
                return s;
            }
        }
        return "Undefined";
    }

    private final static String NL = System.getProperty("line.separator");

    /** formats in forward order */
    private String formatMessages(List<String> msgs) {
        if (msgs.isEmpty())
            return "</td></tr><tr><td><p class=\"nologs\"><i>" + _t("No log messages") + "</i></p>";
        boolean colorize = _context.getBooleanPropertyDefaultTrue("routerconsole.logs.color");
        StringBuilder buf = new StringBuilder(16*1024);
        buf.append("</td></tr><tr><td><ul>");
        boolean displayed = false;
        // newest first
        for (int i = msgs.size() - 1; i >= 0; i--) {
        // oldest first
        // for (int i = 0; i < msgs.size(); i++) {
            String msg = msgs.get(i);
            // don't display the dup message if it is last
            //if (i == 0 && msg.contains("&darr;"))
            // don't display the dup message if it is first
            if (!displayed && msg.contains("&uarr;") || !displayed && msg.contains("&darr;"))
                continue;
            displayed = true;
            msg = msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            msg = msg.replace("&amp;darr;", "&darr;");  // hack - undo the damage (LogWriter) BUFFER_DISPLAYED_REVERSE = true;
            msg = msg.replace("&amp;uarr;", "&uarr;");  // hack - undo the damage (LogWriter)
            msg = msg.replace("&amp;#10140;", "&#10140;");
            msg = msg.replace("--&gt;", " &#10140; ");
            msg = msg.replace(" -&gt;", " &#10140;");
            msg = msg.replace("-&gt;", " &#10140; ");
            msg = msg.replace("  &#10140;  ", " &#10140; ");
            msg = msg.replace("…obQueue", "JobQueue");
            msg = msg.replace("[DBWriter   ]", "[NetDB Writer]");
            msg = msg.replace("[NTCP Pumper", "[ NTCP Pumper");
            msg = msg.replace("[…ueue Pumper", "[Queue Pumper");
            msg = msg.replace("[UDPSender", "[UDP Sender");
            msg = msg.replace("[BWRefiller", "[BW Refiller");
            msg = msg.replace("[Addressbook]", "[Addressbook ]");
            msg = msg.replace("[Thread-", "[ Thread-");
            msg = msg.replace("[Timestamper]", "[Timestamper ]");
            msg = msg.replace("[DHT Explore]", "[DHT Explore ]");
            //msg = msg.replace("false", "no");
            msg = msg.replace("false", "[&#10008;]"); // no (cross)
            //msg = msg.replace("true", "yes");
            msg = msg.replace("true", "[&#10004;]"); // yes (tick)
            msg = msg.replace("[[&#10004;]]", "[&#10004;]");
            msg = msg.replace("[[&#10008;]]", "[&#10008;]");
            msg = msg.replace("=[&#10004;]", "=true");
            msg = msg.replace("=[&#10008;]", "=false");
            msg = msg.replace("[IRC Client] Inbound message", "[IRC Client] &#11167;");
            msg = msg.replace("[IRC Client] Outbound message", "[IRC Client] &#11165;");
            msg = msg.replace(":  ", ": ");
            msg = msg.replace("\n* ", "\n&bullet; ");
            msg = msg.replace("\n\t* ", "\n\t&bullet; ");
            msg = msg.replace("\r\n\r\n", "");
            msg = msg.replace("<br>:", " ");
            // highlight log level indicators
            msg = msg.replace("| DEBUG", " <span class=\"log_debug\">DEBUG</span> ");
            msg = msg.replace("| INFO ", " <span class=\"log_info\">INFO</span> ");
            msg = msg.replace("| WARN ", " <span class=\"log_warn\">WARN</span> ");
            msg = msg.replace("| ERROR", " <span class=\"log_error\">ERROR</span> ");
            msg = msg.replace("| CRIT ", " <span class=\"log_crit\">CRIT</span> ");
            msg = msg.replace("| &darr;&darr;&darr; ", " <span class=\"log_omitted\">&darr;&darr;&darr;</span> "); // LogWriter BUFFER_DISPLAYED_REVERSE = true;
            msg = msg.replace("| &uarr;&uarr;&uarr; ", " <span class=\"log_omitted\">&uarr;&uarr;&uarr;</span> ");
            // remove  last \n that LogRecordFormatter added
            if (msg.endsWith(NL))
                msg = msg.substring(0, msg.length() - NL.length());
            // replace \n so that exception stack traces will format correctly and will paste nicely into pastebin
            msg = msg.replace("\n", "<br>&nbsp;&nbsp;&nbsp;&nbsp;\n");
            buf.append("<li>");
            if (colorize) {
                // TODO this would be a lot easier if LogConsoleBuffer stored LogRecords instead of formatted strings
                String color;
                // Homeland Security Advisory System
                // http://www.dhs.gov/xinfoshare/programs/Copy_of_press_release_0046.shtm
                // but pink instead of yellow for WARN
                if (msg.contains(_c("CRIT")))
                    color = "#cc0000";
                else if (msg.contains(_c("ERROR")))
                    color = "#ff3300";
                else if (msg.contains(_c("WARN")))
                   // color = "#ff00cc"; poor legibility on light backgrounds
                    color = "#bf00df";
                else if (msg.contains(_c("INFO")))
                    color = "#000099";
                else
                    color = "#006600";
                buf.append("<font color=\"").append(color).append("\">");
                buf.append(msg);
                buf.append("</font>");
            } else {
                buf.append(msg);
            }
            buf.append("</li>\n");
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
     *           so don't make it too big.
     * Warning - converts \r\n to \n
     *
     * @param maxNumLines max number of lines (greater than zero)
     * @return string or null; does not throw IOException.
     * @since 0.9.11 modded from FileUtil.readTextFile()
     */
    private static String readTextFile(File f, int maxNumLines) {
        if (!f.exists()) return null;
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            List<String> lines = new ArrayList<String>(maxNumLines);
            String line = null;
            while ( (line = in.readLine()) != null) {
                lines.add(line);
                if (lines.size() >= maxNumLines)
                    lines.remove(0);
            }
            StringBuilder buf = new StringBuilder(lines.size() * 80);
            for (int i = 0; i < lines.size(); i++) {
                buf.append(lines.get(i)).append('\n');
            }
            return buf.toString();
        } catch (IOException ioe) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }

    private static final String CORE_BUNDLE_NAME = "net.i2p.util.messages";

    /**
     *  translate a string from the core bundle
     *  @since 0.9.45
     */
    private String _c(String s) {
        return Translate.getString(s, _context, CORE_BUNDLE_NAME);
    }
}
