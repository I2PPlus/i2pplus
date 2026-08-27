package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Render a log record according to the log manager's settings
 *
 */
class LogRecordFormatter {
    /** Line separator. */
    static final String NL = System.getProperty("line.separator");
    /** Max length for the source class name column. */
    private static final int MAX_WHERE_LENGTH = 16;
    /** Max length for the thread name column — 12 fits SAM-PoolWkr.9 with 1-digit suffix. */
    private static final int MAX_THREAD_LENGTH = 12;
    /** Max length for the priority label column. */
    private static final int MAX_PRIORITY_LENGTH = 5;
    private static final String SEPARATOR = "| ";
    private static final String ELLIPSIS = "...";

    /**
     * Format a log record per manager settings.
     *
     * @param manager the log manager with format configuration
     * @param rec the log record to format
     * @return the formatted log line
     */
    public static String formatRecord(LogManager manager, LogRecord rec) {
        return formatRecord(manager, rec, true);
    }

    /**
     *  @param showDate if false, skip any date in the format (use when writing to wrapper log)
     *  @since 0.8.2
     */
    static String formatRecord(LogManager manager, LogRecord rec, boolean showDate) {
        int size = 128 + rec.getMessage().length();
        if (rec.getThrowable() != null) size += 512;
        StringBuilder buf = new StringBuilder(size);
        char[] format = manager.getFormat();
        for (int i = 0; i < format.length; ++i) {
            switch (format[i]) {
                case LogManager.DATE: if (showDate) buf.append(getWhen(manager, rec));
                    else if (i + 1 < format.length && format[i + 1] == ' ') i++; // skip following space
                    break;
                case LogManager.CLASS: buf.append(getWhere(rec));
                    break;
                case LogManager.THREAD: buf.append(getThread(rec));
                    break;
                case LogManager.PRIORITY: buf.append(SEPARATOR).append(getPriority(rec, manager.getContext()));
                    break;
                case LogManager.MESSAGE: String msg = rec.getMessage();
                    if (msg != null) buf.append(msg);
                    break;
                default: buf.append(format[i]);
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

    /** Format thread name to exactly MAX_THREAD_LENGTH chars, padded or truncated. */
    private static String getThread(LogRecord logRecord) {
        return toString(logRecord.getThreadName(), MAX_THREAD_LENGTH);
    }

    /**
     * Format the record timestamp.
     *
     * @return the formatted timestamp string
     */
    public static String getWhen(LogManager manager, LogRecord logRecord) {
        SimpleDateFormat fmt = manager.getDateFormat();
        Date d = new Date(logRecord.getDate());
        synchronized (fmt) {
            return fmt.format(d);
        }
    }

    /* don't translate */
    private static final String BUNDLE_NAME = "net.i2p.util.messages";

    static {
        // just for tagging
        _x("CRIT"); _x("ERROR"); _x("WARN"); _x("INFO"); _x("DEBUG");
    }

    /**
     * Return the localized priority label.
     *
     * @since 0.7.14
     */
    private static String getPriority(LogRecord rec, I2PAppContext ctx) {
        int len;
        if (Translate.getLanguage(ctx).equals("de")) {
            len = 8; // KRITISCH
        } else {
            len = MAX_PRIORITY_LENGTH;
        }
        return toString(Translate.getString(Log.toLevelString(rec.getPriority()), ctx, BUNDLE_NAME), len);
    }

    /**
     * Format the source class name, padded or truncated to MAX_WHERE_LENGTH.
     */
    private static String getWhere(LogRecord rec) {
        String src = (rec.getSource() != null ? rec.getSource().getName() : rec.getSourceName());
        if (src == null) src = "<none>";
        return toString(src, MAX_WHERE_LENGTH);
    }

    /**
     * Truncate or pad to exactly {@code size} chars; if truncated,
     * keeps the suffix and prefixes with "..." so column width stays constant.
     */
    private static String toString(String str, int size) {
        StringBuilder buf = new StringBuilder(size);
        if (str == null) str = "";
        if (str.length() > size) {
            str = str.substring(str.length() - size + ELLIPSIS.length());
            buf.append(ELLIPSIS);
        }
        buf.append(str);
        while (buf.length() < size) buf.append(' ');
        return buf.toString();
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *
     *  @return s
     */
    private static String _x(String s) {
        return s;
    }
}
