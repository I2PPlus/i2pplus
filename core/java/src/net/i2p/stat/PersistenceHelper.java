package net.i2p.stat;

import java.util.Date;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 *  Output rate data.
 *  This is used via ProfilePersistenceHelper and the output
 *  must be compatible.
 */
class PersistenceHelper {
    private final static String NL = System.getProperty("line.separator");

    public final static void add(StringBuilder buf, boolean addComments, String prefix, String name, String description, double value) {
        if (addComments)
            buf.append("# ").append(description).append(NL);
        else
            buf.append(prefix).append(name).append('=').append(value).append(NL);
    }

    /** @since 0.8.5 */
    public final static void addDate(StringBuilder buf, boolean addComments, String prefix, String name, String description, long value) {
        if (addComments) {
            String when = value > 0 ? (new Date(value)).toString() : "Never";
            add(buf, true, prefix, name, description + ' ' + when, value);
       } else {
            add(buf, false, prefix, name, description, value);
       }
    }

    /** @since 0.8.5 */
    public final static void addTime(StringBuilder buf, boolean addComments, String prefix, String name, String description, long value) {
        if (addComments) {
            String when = DataHelper.formatDuration(value);
            add(buf, true, prefix, name, description + ' ' + when, value);
       } else {
            add(buf, false, prefix, name, description, value);
       }
   }

    /** @param value non-negative */
    public final static void add(StringBuilder buf, boolean addComments, String prefix, String name, String description, long value) {
        if (addComments)
            buf.append("# ").append(description).append(NL);
        else
            buf.append(prefix).append(name).append('=').append(value).append(NL);
    }

    /**
     *  @return non-negative, returns 0 on error
     */
    public final static long getLong(Properties props, String prefix, String name) {
        String val = props.getProperty(prefix + name);
        if (val != null) {
            try {
                long rv = Long.parseLong(val);
                return rv >= 0 ? rv : 0;
            } catch (NumberFormatException nfe) {
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(PersistenceHelper.class);
                log.warn("Error formatting " + val, nfe);
            }
        }
        return 0;
    }

    /**
     *  @return 0 on error
     */
    public final static double getDouble(Properties props, String prefix, String name) {
        String val = props.getProperty(prefix + name);
        if (val != null) {
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException nfe) {
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(PersistenceHelper.class);
                log.warn("Error formatting " + val, nfe);
            }
        }
        return 0;
    }

    /**
     *  @return non-negative, returns 0 on error
     *  @since 0.8.13
     */
    public final static int getInt(Properties props, String prefix, String name) {
        String val = props.getProperty(prefix + name);
        if (val != null) {
            try {
                int rv = Integer.parseInt(val);
                return rv >= 0 ? rv : 0;
            } catch (NumberFormatException nfe) {
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(PersistenceHelper.class);
                log.warn("Error formatting " + val, nfe);
            }
        }
        return 0;
    }

}
