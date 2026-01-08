/*
 * This file is part of SusDNS project for I2P
 * Created on Sep 02, 2005
 * $Revision: 1.3 $
 * Copyright (C) 2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */

package i2p.susi.dns;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.i2p.data.DataHelper;
import net.i2p.util.PortMapper;
import net.i2p.util.SecureFileOutputStream;

/**
 * Bean for managing address book subscription sources and updates.
 */
public class SubscriptionsBean extends BaseBean {
    private String fileName, content;
    private static final String SUBS_FILE = "subscriptions.txt";
    // If you change this, change in Addressbook Daemon also
    private static final String DEFAULT_SUB = "http://stats.i2p/cgi-bin/newhosts.txt" + "\n" +
                                              "http://skank.i2p/hosts.txt" + "\n" +
                                              "http://notbob.i2p/hosts.txt";

    /**
     * Get the subscriptions file path.
     * @return the absolute path to the subscriptions file
     */
    public String getFileName() {
        loadConfig();
        fileName = subsFile().toString();
        return fileName;
    }

    /**
     * @since 0.9.13
      */
    private File subsFile() {return new File(addressbookDir(), SUBS_FILE);}

    private void reloadSubs() {
        synchronized(SubscriptionsBean.class) {locked_reloadSubs();}
    }

    private void locked_reloadSubs() {
        File file = subsFile();
        if (file.isFile()) {
            StringBuilder buf = new StringBuilder();
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
                String line;
                while((line = br.readLine()) != null) {
                    buf.append(line);
                    buf.append("\n");
                }
                content = buf.toString();
            } catch (IOException e) {e.printStackTrace();} // TODO Auto-generated catch block
            finally {
                if (br != null) {
                    try {br.close();}
                    catch (IOException ioe) {}
                }
            }
        } else {content = DEFAULT_SUB;}
    }

    private void save() {
        synchronized(SubscriptionsBean.class) {locked_save();}
    }

    private void locked_save() {
        File file = subsFile();
        try {
            // trim and sort
            List<String> urls = new ArrayList<String>();
            InputStream in = new ByteArrayInputStream(content.getBytes("UTF-8"));
            String line;
            while ((line = DataHelper.readLine(in)) != null) {
                line = line.trim();
                if (line.length() > 0) {urls.add(line);}
            }
            Collections.sort(urls);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(new SecureFileOutputStream(file), "UTF-8"));
            for (String url : urls) {out.println(url);}
            out.close();
            if (out.checkError()) {throw new IOException("Failed write to " + file);}
        } catch (IOException e) {e.printStackTrace();} // TODO Auto-generated catch block
    }

    public String getMessages() {
        String message = "";
        if (action != null) {
            if ("POST".equals(method) && (_context.getBooleanProperty(PROP_PW_ENABLE) || (serial != null && serial.equals(lastSerial)))) {
                if (action.equals(_t("Save"))) {
                    save();
                    message = _t("Subscriptions saved.");
                }
                if (action.equals(_t("Update"))) {
                    if (content != null && content.length() > 2 && _context.portMapper().isRegistered(PortMapper.SVC_HTTP_PROXY)) {
                        _context.namingService().requestUpdate(null);
                        message = _t("Attempting to update addressbook from subscription sources...");
                    } else if (!_context.portMapper().isRegistered(PortMapper.SVC_HTTP_PROXY)) {
                        message = _t("Cannot update subscriptions: HTTP proxy is not running!");
                    }
                } else if (action.equals(_t("Reload"))) {
                    reloadSubs();
                    message = _t("Subscriptions reloaded.");
                }
            } else {
                message = _t("Invalid form submission, probably because you used the \"back\" or \"reload\" button on your browser. Please resubmit.") + ' ' +
                          _t("If the problem persists, verify that you have cookies enabled in your browser.");
            }
        }
        if (message.length() > 0) {message = "<p class=\"messages\">" + message + "</p>";}
        return message;
    }

    /**
     * Set the subscriptions content.
     * @param content the new subscriptions content
     */
    public void setContent(String content) {
        this.content = DataHelper.stripHTML(content); // will come from form with \r\n line endings
    }

    /**
     * Get the current subscriptions content.
     * @return the subscriptions content, or default subscriptions if file doesn't exist
     */
    public String getContent() {
        if (content != null) {return content;}
        reloadSubs();
        return content;
    }

}
