package net.i2p.addressbook;

import net.i2p.I2PAppContext;
import net.i2p.util.Translate;

/**
 * Provides translatable strings for the addressbook application.
 */
public class Messages {
    static final String BUNDLE_NAME = "net.i2p.addressbook.messages";
    private final I2PAppContext _context;

    /**
     * Construct a Messages instance.
     */
    public Messages() {
        _context = I2PAppContext.getGlobalContext();
    }

    /**
     * Return the translated string for the given key.
     *
     * @param key the message key
     * @return the translated string
     */
    public String _t(String key) {
        return Translate.getString(key, _context, BUNDLE_NAME);
    }

    /**
     * Return the translated string for the given key.
     *
     * @param s the message key
     * @return the translated string
     */
    public static String getString(String s) {
        return Translate.getString(s, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    /**
     * Return the translated string for the given key, with one argument.
     *
     * @param s the message key
     * @param o the argument to substitute
     * @return the translated string
     */
    public static String getString(String s, Object o) {
        return Translate.getString(s, o, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    /**
     * Return the translated string for the given key, with two arguments.
     *
     * @param s the message key
     * @param o the first argument to substitute
     * @param o2 the second argument to substitute
     * @return the translated string
     */
    public static String getString(String s, Object o, Object o2) {
        return Translate.getString(s, o, o2, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }
}
