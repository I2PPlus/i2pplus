package net.i2p.desktopgui.i18n;

import net.i2p.I2PAppContext;
import net.i2p.util.Translate;

/**
 * Translation utility for desktop GUI internationalization.
 * Provides methods for translating strings in the desktop interface.
 */
public class DesktopguiTranslator {

    private static final String BUNDLE_NAME = "net.i2p.desktopgui.messages";

    /**
     * Translate a string using the desktopgui message bundle.
     *
     * @param ctx the I2P application context
     * @param s the string to translate
     * @return the translated string
     */
    public static String _t(I2PAppContext ctx, String s) {
        return Translate.getString(s, ctx, BUNDLE_NAME);
    }

    /**
     * Translate a string with one parameter using the desktopgui message bundle.
     *
     * @param ctx the I2P application context
     * @param s the string to translate
     * @param o the parameter to insert into the translated string
     * @return the translated string with the parameter inserted
     */
    public static String _t(I2PAppContext ctx, String s, Object o) {
        return Translate.getString(s, o, ctx, BUNDLE_NAME);
    }
}
