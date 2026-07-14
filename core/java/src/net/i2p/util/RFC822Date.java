package net.i2p.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 *  Moved from NewsFetcher
 *
 *  @since 0.8.5; moved from net.i2p.router.util in 0.9.34
 */
public abstract class RFC822Date {

    private static final ThreadLocal<SimpleDateFormat> OUTPUT_FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.US));

    private static final ThreadLocal<SimpleDateFormat[]> RFC822_DATE_FORMATS = ThreadLocal.withInitial(() -> {
        SimpleDateFormat[] fmts = new SimpleDateFormat[] {
            new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.US),
            new SimpleDateFormat("d MMM yy HH:mm:ss z", Locale.US),
            new SimpleDateFormat("EEE, d MMM yy HH:mm z", Locale.US),
            new SimpleDateFormat("EEE, d MMM yyyy HH:mm z", Locale.US),
            new SimpleDateFormat("d MMM yy HH:mm z", Locale.US),
            new SimpleDateFormat("d MMM yy HH:mm:ss z", Locale.US),
            new SimpleDateFormat("d MMM yyyy HH:mm z", Locale.US)
        };
        TimeZone utc = TimeZone.getTimeZone("GMT");
        for (int i = 0; i < fmts.length; i++) {
            fmts[i].setTimeZone(utc);
        }
        return fmts;
    });

    /**
     * new Date(String foo) is deprecated, so let's do this the hard way
     *
     * @param s non-null
     * @return -1 on failure
     */
    public static long parse822Date(String s) {
        SimpleDateFormat[] fmts = RFC822_DATE_FORMATS.get();
        for (int i = 0; i < fmts.length; i++) {
            try {
                Date date = fmts[i].parse(s);
                if (date != null) return date.getTime();
            } catch (ParseException pe) { /* ignored */ }
        }
        return -1;
    }

    /**
     * Format is "d MMM yyyy HH:mm:ss z"
     *
     * @since 0.8.2
     */
    public static String to822Date(long t) {
        return OUTPUT_FORMAT.get().format(Date.from(Instant.ofEpochMilli(t)));
    }

    public static void main(String[] args) {
        if (args.length == 1) {
            try {
                System.out.println(to822Date(Long.parseLong(args[0]) * 1000));
            } catch (NumberFormatException nfe) {
                System.out.println("Invalid date");
                System.out.println(nfe.toString());
            }
        } else if (args.length == 0) {
            long t = System.currentTimeMillis();
            System.out.println("Current Time: " + (t / 1000));
            System.out.println(to822Date(t));
        } else {
            StringBuilder buf = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                buf.append(args[i]);
                if (i < args.length - 1) buf.append(' ');
            }
            long t = parse822Date(buf.toString());
            if (t >= 0) {
                System.out.println(Long.toString(t / 1000));
            } else {
                System.out.println("Invalid date");
                System.out.println("Usage: RFC822Date [numericDate|textualDate]");
            }
        }
    }
}
