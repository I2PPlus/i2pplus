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
import java.io.InputStreamReader;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import net.i2p.data.DataHelper;

public class LogBean extends BaseBean
{
    private String logName, logged;
    private static final String LOG_FILE = "log.txt";

    public String getLogName() {
        loadConfig();
        logName = logFile().toString();
        return logName;
    }

    /**
     * @since 0.9.35
     */
    private File logFile() {return new File(addressbookDir(), LOG_FILE);}

    private void reloadLog() {
        synchronized(LogBean.class) {locked_reloadLog();}
    }

    private void locked_reloadLog() {
        File log = logFile();
        int maxLines = 600;
        if (log.isFile()) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(log), "UTF-8"));
                List<String> lines = new ArrayList<String>(maxLines);
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                    if (lines.size() >= maxLines) {lines.remove(0);}
                }
                StringBuilder buf = new StringBuilder(maxLines * 80);

                for (int i = lines.size() - 1; i >= 0; i--) { // Reverse order, most recent first
                    if (!lines.get(i).contains("Bad hostname")) {
                        String[] parts = lines.get(i).split(" -- ");
                        String date = parts[0].replace("GMT", "UTC");
                        String message = parts[1];
                        String[] messageParts = message.split(" ");
                        String domain = messageParts[2].replace("[","").replace("]","");
                        String subhost = messageParts[3].replace("http://", "");

                        buf.append("<li><span class=date>").append(date).append("</span> &nbsp;").append(_t("New domain"))
                           .append(" <a href=http://").append(domain).append("/ target=_blank>").append(domain)
                           .append("</a> <span class=subhost>").append(subhost).append("</span></li>\n");
                    }
                }
                logged = buf.toString();
            } catch (IOException e) {e.printStackTrace();} // TODO Auto-generated catch block
            finally {
                if (br != null) {
                    try {br.close();}
                    catch (IOException ioe) {}
                }
            }
        } else {logged = LOG_FILE;}
    }

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

    public void setLogged(String logged) {this.logged = DataHelper.stripHTML(logged);} // will come from form with \r\n line endings

    public String getLogged() {
        if (logged != null) {return logged;}
        reloadLog();
        return logged;
    }

    public int getTodayEntryCount() {
        reloadLog();
        return countTodayEntries();
    }

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
                        String[] parts = line.split(" -- ");
                        String date = parts[0].replace("GMT", "UTC");
                        if (isToday(date)) {
                            todayEntryCount++;
                        }
                    }
                }
            } catch (IOException e) {e.printStackTrace();} // TODO Auto-generated catch block
            finally {
                if (br != null) {
                    try {br.close();}
                    catch (IOException ioe) {}
                }
            }
        }
        return todayEntryCount;
    }

    private boolean isToday(String dateStr) {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date date = sdf.parse(dateStr);
            LocalDate logDate = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            return logDate.equals(today);
        } catch (ParseException e) {
            e.printStackTrace();
            return false;
        }
    }
}