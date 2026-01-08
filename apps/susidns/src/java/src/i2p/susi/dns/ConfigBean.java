/*
 * This file is part of SusDNS project for I2P
 * Created on Sep 02, 2005
 * $Revision: 1.3 $
 * Copyright (C) 2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */

package i2p.susi.dns;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.Properties;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.OrderedProperties;

/**
 * Bean for managing SusDNS configuration properties and file operations.
 */
public class ConfigBean extends BaseBean implements Serializable {

    private String config;
    private boolean saved;
    /**
     * Get the config file path.
     * @return the absolute path to the config file
     */
    public String getfileName() {return configFile().toString();}

    /**
     * Check if the config has been saved.
     * @return true if the config was saved
     */
    public boolean isSaved() {return saved;}

    /**
     * Get the current configuration content.
     * @return the configuration content
     */
    public String getConfig() {
        if (config != null) {return config;}
        reload();
        return config;
    }

    @Override
    protected void reload() {
        super.reload();
        StringBuilder buf = new StringBuilder(256);
        for (Map.Entry<Object, Object> e : properties.entrySet()) {
            buf.append((String) e.getKey()).append('=').append((String) e.getValue()).append('\n');
        }
        config = buf.toString();
        saved = true;
    }

    private void save() {
        try {
            // use loadProps to trim, use storeProps to sort and get line endings right
            Properties props = new OrderedProperties();
            DataHelper.loadProps(props, new ByteArrayInputStream(config.getBytes("UTF-8")));
            synchronized (BaseBean.class) {DataHelper.storeProps(props, configFile());}
            saved = true;
        } catch (IOException e) {e.printStackTrace();} // TODO Auto-generated catch block
    }

    /**
     * Set the configuration content.
     * @param config the new configuration content
     */
    public void setConfig(String config) {
        // will come from form with \r\n line endings
        this.config = config;
        this.saved = false;
    }

    /**
     * Get status messages for the UI.
     * @return HTML formatted status message
     */
    public String getMessages() {
        String message = "";
        if (action != null) {
        if ("POST".equals(method) && (I2PAppContext.getGlobalContext().getBooleanProperty(BaseBean.PROP_PW_ENABLE) ||
            (serial != null && serial.equals(lastSerial)))) {
                if (action.equals(_t("Save"))) {
                    save();
                    message = _t("Configuration saved.");
                } else if (action.equals(_t("Reload"))) {
                    reload();
                    message = _t("Configuration reloaded.");
                }
            }
            else {
                message = _t("Invalid form submission, probably because you used the \"back\" or \"reload\" button on your browser. Please resubmit.") +
                             ' ' + _t("If the problem persists, verify that you have cookies enabled in your browser.");
            }
        }
        if (message.length() > 0) {message = "<p class=\"messages\">" + message + "</p>";}
        return message;
    }

}
