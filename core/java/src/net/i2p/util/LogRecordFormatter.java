package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 * Render a log record according to the log manager's settings
 *
 */
class LogRecordFormatter {
    final static String NL = System.getProperty("line.separator");
    // arbitrary max length for the classname property (this makes is it lines up nicely)
    private final static int MAX_WHERE_LENGTH = 16;
    private final static int MAX_THREAD_LENGTH = 11;
    private final static int MAX_PRIORITY_LENGTH = 5;

    public static String formatRecord(LogManager manager, LogRecord rec) {
        return formatRecord(manager, rec, true);
    }

    /**
     *  @param showDate if false, skip any date in the format (use when writing to wrapper log)
     *  @since 0.8.2
     */
    static String formatRecord(LogManager manager, LogRecord rec, boolean showDate) {
        int size = 128 + rec.getMessage().length();
        if (rec.getThrowable() != null)
            size += 512;
        StringBuilder buf = new StringBuilder(size);
        char format[] = manager.getFormat();
        for (int i = 0; i < format.length; ++i) {
            switch (format[i]) {
            case LogManager.DATE:
                if (showDate)
                    buf.append(getWhen(manager, rec));
                else if (i+1 < format.length && format[i+1] == ' ')
                    i++;  // skip following space
                break;
            case LogManager.CLASS:
                buf.append(getWhere(rec));
                break;
            case LogManager.THREAD:
                buf.append(getThread(rec));
                break;
            case LogManager.PRIORITY:
                buf.append("| " + getPriority(rec, manager.getContext()));
                break;
            case LogManager.MESSAGE:
                String msg = getWhat(rec);
                if (msg != null)
                    buf.append(msg);
                break;
            default:
                buf.append(format[i]);
                break;
            }
        }
        buf.append(NL);
        if (rec.getThrowable() != null) {
            StringWriter sw = new StringWriter(512);
            PrintWriter pw = new PrintWriter(sw);
            rec.getThrowable().printStackTrace(pw);
            pw.flush();
            buf.append(sw.toString());
        }
        return buf.toString();
    }

    private static String getThread(LogRecord logRecord) {
        return toString(logRecord.getThreadName(), MAX_THREAD_LENGTH);
    }

    public static String getWhen(LogManager manager, LogRecord logRecord) {
        SimpleDateFormat fmt = manager.getDateFormat();
        Date d = new Date(logRecord.getDate());
        synchronized(fmt) {
            return fmt.format(d);
        }
    }

    /** don't translate */
/****
    private static String getPriority(LogRecord rec) {
        return toString(Log.toLevelString(rec.getPriority()), MAX_PRIORITY_LENGTH);
    }
****/

    /** */
    private static final String BUNDLE_NAME = "net.i2p.util.messages";
    static {
        // just for tagging
        String[] levels = { _x("CRIT"), _x("ERROR"), _x("WARN"), _x("INFO"), _x("DEBUG") };
    }

    /** translate @since 0.7.14 */
    private static String getPriority(LogRecord rec, I2PAppContext ctx) {
        int len;
        if (Translate.getLanguage(ctx).equals("de"))
            len = 8;  // KRITISCH
        else
            len = MAX_PRIORITY_LENGTH;
        StringBuilder buf = new StringBuilder();
        while (buf.length() < len)
            buf.append(' ');
        return toString(Translate.getString(Log.toLevelString(rec.getPriority()), ctx, BUNDLE_NAME), len);
    }

    private static String getWhat(LogRecord rec) {
        return rec.getMessage();
    }

    private static String getWhere(LogRecord rec) {
        String src = (rec.getSource() != null ? rec.getSource().getName() : rec.getSourceName());
        if (src == null) src = "<none>";
        return toString(src, MAX_WHERE_LENGTH);
    }

    /** truncates or pads to the specified size */
    private static String toString(String str, int size) {
    String ellipsis = "...";
        StringBuilder buf = new StringBuilder();
        if (str == null) str = "";
        if (str.length() > size) {
            str = str.substring(str.length() - size);
            buf.append(ellipsis);
        }
        buf.append(str);
        while (buf.length() < size)
            buf.append(' ');
        return buf.toString();
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    private static String _x(String s) {
        return s;
    }
}
