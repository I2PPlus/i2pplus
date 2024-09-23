/*
 * Created on Sep 02, 2005
 *
 *  This file is part of susidns project, see http://susi.i2p/
 *
 *  Copyright (C) 2005 <susi23@mail.i2p>
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
 * $Revision: 1.3 $
 */

package i2p.susi.dns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

}