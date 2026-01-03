package net.i2p.addressbook;

import net.i2p.I2PAppContext;
import net.i2p.util.Translate;

public class Messages {
    static final String BUNDLE_NAME = "net.i2p.addressbook.messages";
    private final I2PAppContext _context;

    public Messages() {
        _context = I2PAppContext.getGlobalContext();
    }

    public String _t(String key) {
        return Translate.getString(key, _context, BUNDLE_NAME);
    }

    public static String getString(String s) {
        return Translate.getString(s, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    public static String getString(String s, Object o) {
        return Translate.getString(s, o, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    public static String getString(String s, Object o, Object o2) {
        return Translate.getString(s, o, o2, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }
}
