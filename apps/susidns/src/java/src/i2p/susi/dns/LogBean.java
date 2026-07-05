/*
 * This file is part of SusDNS project for I2P
 * Created on Sep 02, 2005
 * $Revision: 1.2 $
 * Copyright (C) 2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */

package i2p.susi.dns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.i2p.data.DataHelper;

/**
 * Bean for managing and displaying subscription log entries and statistics.
 *
 * @since 0.9.35
 */
public class LogBean extends BaseBean
{
    private String logName, logged;
    private static final String LOG_FILE = "log.txt";
    private static final Pattern DASH_SPLIT = Pattern.compile("\\s*--\\s*");

    /**
     * Get the log file path.
     * @return the absolute path to the log file
     */
    public String getLogName() {
        loadConfig();
        logName = logFile().toString();
        return logName;
    }

    /**
     * @since 0.9.35
     */
    private File logFile() {return new File(addressbookDir(), LOG_FILE);}

    /**
     * Reload the log file and parse entries into HTML.
     */
    private void reloadLog() {
        synchronized(LogBean.class) {locked_reloadLog();}
    }

    /**
     * Reload the log file (synchronized wrapper).
     */
    private void locked_reloadLog() {
        File log = logFile();
        int maxLines = 600;
        if (log.isFile()) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(log), "UTF-8"));
                List<String> lines = new ArrayList<>(maxLines);
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                    if (lines.size() >= maxLines) {lines.remove(0);}
                }
                StringBuilder buf = new StringBuilder(maxLines * 80);

                for (int i = lines.size() - 1; i >= 0; i--) { // Reverse order, most recent first
                    if (!lines.get(i).contains("Bad hostname")) {
                        String[] parts = DASH_SPLIT.split(lines.get(i));
                        if (parts.length < 2) {continue;}
                        String date = formatDate(parts[0]);
                        String message = parts[1];
                        String[] messageParts = message.split(" ");
                        if (messageParts.length < 4) {continue;}
                        String domain = messageParts[2].replace("[","").replace("]","");
                        String subhost = messageParts[3].replace("http://", "");

                        buf.append("<li><span class=date>").append(date).append("</span> &nbsp;").append(_t("New domain"))
                           .append(" <a href=http://").append(domain).append("/ target=_blank>").append(domain)
                           .append("</a> <span class=subhost>").append(subhost).append("</span></li>\n");
                    }
                }
                logged = buf.toString();
            } catch (IOException e) {warn(e);}
            finally {
                if (br != null) {
                    try {br.close();}
                    catch (IOException ioe) { /* ignored */ }
                }
            }
        } else {logged = LOG_FILE;}
    }

    /**
     * Get status messages for the UI.
     *
     * @return HTML formatted status message
     */
    public String getMessages() {
        String message = "";
        if (action != null) {
            if (logged != null && logged.length() > 2) {
                reloadLog();
                message = _t("Subscription log reloaded.");
            }
        }
        if (message.length() > 0) {message = "<p class=\"messages\">" + message + "</p>";}
        return message;
    }

    /**
     * Set the log content.
     * @param logged the new log content
     */
    public void setLogged(String logged) {this.logged = DataHelper.stripHTML(logged);} // will come from form with \r\n line endings

    /**
     * Get the current log content.
     * @return the log content, or default if file doesn't exist
     */
    public String getLogged() {
        if (logged != null) {return logged;}
        reloadLog();
        return logged;
    }

    /**
     * Get the count of log entries from today.
     * @return the number of entries logged today
     */
    public int getTodayEntryCount() {
        reloadLog();
        return countTodayEntries();
    }

    /**
     * Count the number of log entries from today.
     *
     * @return the number of entries logged today
     */
    private int countTodayEntries() {
        File log = logFile();
        int maxLines = 600;
        int todayEntryCount = 0;

        if (log.isFile()) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(log), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.contains("Bad hostname")) {
                        String[] parts = DASH_SPLIT.split(line);
                        if (parts.length < 2) {continue;}
                        String date = parts[0].replace("GMT", "UTC");
                        if (isToday(date)) {
                            todayEntryCount++;
                        }
                    }
                }
            } catch (IOException e) {warn(e);}
            finally {
                if (br != null) {
                    try {br.close();}
                    catch (IOException ioe) { /* ignored */ }
                }
            }
        }
        return todayEntryCount;
    }

    /**
     * Format a date string to a human-readable format.
     * Handles both ISO format and old format.
     *
     * @param dateStr the date string to format
     * @return the formatted date string
     */
    private String formatDate(String dateStr) {
        try {
            DateTimeFormatter isoFormatter = DateTimeFormatter.ISO_INSTANT;
            Instant instant = Instant.from(isoFormatter.parse(dateStr));
            DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss 'UTC' yyyy", Locale.ENGLISH);
            return instant.atZone(ZoneOffset.UTC).format(outputFormatter);
        } catch (DateTimeParseException e) {
            try {
                DateTimeFormatter oldFormatter = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)
                                                                  .withZone(ZoneOffset.UTC);
                Instant instant = Instant.from(oldFormatter.parse(dateStr));
                DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss 'UTC' yyyy", Locale.ENGLISH);
                return instant.atZone(ZoneOffset.UTC).format(outputFormatter);
            } catch (DateTimeParseException e2) {
                return dateStr;
            }
        }
    }

    /**
     * Check if a date string represents today.
     *
     * @param dateStr the date string to check
     * @return true if the date is today
     */
    private boolean isToday(String dateStr) {
        try {
            DateTimeFormatter isoFormatter = DateTimeFormatter.ISO_INSTANT;
            Instant instant = Instant.from(isoFormatter.parse(dateStr));
            LocalDate logDate = instant.atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            return logDate.equals(today);
        } catch (DateTimeParseException e) {
            try {
                DateTimeFormatter oldFormatter = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)
                                                                .withZone(ZoneOffset.UTC);
                Instant instant = Instant.from(oldFormatter.parse(dateStr));
                LocalDate logDate = instant.atZone(ZoneId.systemDefault()).toLocalDate();
                LocalDate today = LocalDate.now(ZoneId.systemDefault());
                return logDate.equals(today);
            } catch (DateTimeParseException e2) {
                return false;
            }
        }
    }

}
