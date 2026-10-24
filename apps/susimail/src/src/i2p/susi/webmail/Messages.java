package i2p.susi.webmail;

import net.i2p.I2PAppContext;
import net.i2p.util.Translate;

/**
 * Provides internationalization and string translation for the webmail package.
 * @since 0.8.5
 */
public class Messages {
    /** The bundle holding the webmail strings. */
    private static final String BUNDLE_NAME = "i2p.susi.webmail.messages";
    /** Supplies the locale the translated strings are looked up in. */
    private final I2PAppContext _context;

    /** Default constructor, uses global context. */
    public Messages() {_context = I2PAppContext.getGlobalContext();}

    /**
     * Translate a key to the current locale.
     * @param key the message key
     * @return the translated string
     */
    public String _t(String key) {return Translate.getString(key, _context, BUNDLE_NAME);}

    /**
     * Get a translated string for the given key.
     * @param s the message key
     * @return the translated string
     */
    public static String getString(String s) {
        return Translate.getString(s, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    /**
     * Get a translated string with a parameter.
     * @param s the message key
     * @param o substituted for {0} in the message key
     * @return the translated string
     */
    public static String getString(String s, Object o) {
        return Translate.getString(s, o, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    /**
     * Get a translated string with two parameters.
     * @param s the message key
     * @param o substituted for {0} in the message key
     * @param o2 substituted for {1} in the message key
     * @return the translated string
     */
    public static String getString(String s, Object o, Object o2) {
        return Translate.getString(s, o, o2, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    /**
     * Get a translated pluralized string based on a count.
     * @param n how many, selecting the form; also substituted for {0}
     * @param s singular message key, used when n is 1
     * @param p plural message key, used when n is not 1
     * @return the translated string
     */
    public static String getString(int n, String s, String p) {
        return Translate.getString(n, s, p, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }
}
