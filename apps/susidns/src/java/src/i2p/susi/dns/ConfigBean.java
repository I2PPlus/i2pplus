// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.dns;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.Properties;
import net.i2p.data.DataHelper;
import net.i2p.util.OrderedProperties;

import java.nio.charset.StandardCharsets;
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

    /**
     * Reload the configuration from file.
     */
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

    /**
     * Save the configuration to file.
     */
    private void save() {
        try {
            // use loadProps to trim, use storeProps to sort and get line endings right
            Properties props = new OrderedProperties();
            DataHelper.loadProps(props, new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));
            synchronized (BaseBean.class) {DataHelper.storeProps(props, configFile());}
            saved = true;
        } catch (IOException e) {warn(e);}
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
        if (serial != null && serial.equals(lastSerial)) {
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
        if (!message.isEmpty()) {message = "<p class=\"messages\">" + message + "</p>";}
        return message;
    }

}
