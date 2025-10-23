/*
 * This file is part of SusiMail project for I2P
 * Created on 05.11.2004
 * $Revision: 1.4 $
 * Copyright (C) 2004-2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */

package i2p.susi.util;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;

/**
 * Warning - static - not for use by multiple applications or prefixes
 *
 * @author susi
 */
public class Config {
    private static Properties properties, config;
    private static String configPrefix;

    public synchronized static String getProperty(String name) {
        if (configPrefix != null)
            name = configPrefix + name;
        String result = null;
        if (properties == null) {
            reloadConfiguration();
        }
        result = System.getProperty(name);
        if (result != null)
            return result;
        if (config != null) {
            result = config.getProperty(name);
            if (result != null)
                return result;
        }
        result = properties.getProperty(name);
        return result;
    }

    /**
     *  Don't bother showing a reload config button if this returns false.
     *  @since 0.9.13
     */
    public synchronized static boolean hasConfigFile() {
        File cfg = new File(I2PAppContext.getGlobalContext().getConfigDir(), "susimail.config");
        return cfg.exists();
    }

    public synchronized static void reloadConfiguration() {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        properties = new Properties();
        InputStream iv = null;
        try {
            iv = Config.class.getResourceAsStream("/susimail.properties");
            properties.load(iv);
        } catch (IOException e) {
            Log log = ctx.logManager().getLog(Config.class);
            log.error("Could not open WEB-INF/classes/susimail.properties (possibly in jar)", e);
        } finally {
            if (iv != null) try { iv.close(); } catch(IOException ioe) {}
        }
        try {
            File cfg = new File(I2PAppContext.getGlobalContext().getConfigDir(), "susimail.config");
            if (cfg.exists()) {
                config = new OrderedProperties();
                DataHelper.loadProps(config, cfg);
            }
        } catch (IOException e) {
            Log log = ctx.logManager().getLog(Config.class);
            log.error("Could not open susimail.config", e);
        }
    }

    /**
     * Returns the properties, sorted, WITHOUT the prefix
     *
     * @since 0.9.13
     */
    public synchronized static Properties getProperties() {
        Properties rv = new OrderedProperties();
        if (properties != null) {
            if (configPrefix == null) {
                rv.putAll(properties);
            } else {
                for (Map.Entry<Object, Object> e : properties.entrySet()) {
                    String k = (String) e.getKey();
                    if (k.startsWith(configPrefix))
                        rv.put(k.substring(configPrefix.length()), e.getValue());
                }
            }
        }
        if (config != null) {
            if (configPrefix == null) {
                rv.putAll(config);
            } else {
                for (Map.Entry<Object, Object> e : config.entrySet()) {
                    String k = (String) e.getKey();
                    if (k.startsWith(configPrefix))
                        rv.put(k.substring(configPrefix.length()), e.getValue());
                }
            }
        }
        return rv;
    }

    /**
     * Saves the properties. A property not in newProps will be removed but
     * will not override the default in the resource.
     *
     * @param newProps non-null WITHOUT the prefix
     * @since 0.9.13
     */
    public synchronized static void saveConfiguration(Properties newProps) throws IOException {
        Properties toSave = new OrderedProperties();
        for (Map.Entry<Object, Object> e : newProps.entrySet()) {
            Object k = e.getKey();
            if (configPrefix != null)
                k = configPrefix + k;
            toSave.put(k, e.getValue());
        }
        config = toSave;
        File cfg = new File(I2PAppContext.getGlobalContext().getConfigDir(), "susimail.config");
        DataHelper.storeProps(toSave, cfg);
    }

    /**
     * @param name
     * @param defaultValue
     */
    public synchronized static String getProperty(String name, String defaultValue) {
        String result = getProperty(name);
        return result != null ? result : defaultValue;
    }

    /**
     * @param name
     * @param defaultValue
     */
    public synchronized static int getProperty(String name, int defaultValue) {
        int result = defaultValue;
        String str = getProperty(name);
        if (str != null) {
            try {
                result = Integer.parseInt(str);
            }
            catch(NumberFormatException nfe) {
                result = defaultValue;
            }
        }
        return result;
    }
    /**
     * Static! Not for use by multiple applications!
     * @param prefix
     */
    public synchronized static void setPrefix(String prefix) {
        configPrefix = prefix.endsWith(".") ? prefix : prefix + ".";
    }
}
