package net.i2p.router.web;

import net.i2p.I2PAppContext;
import net.i2p.util.Translate;

/**
 * Translate strings for this package.
 */
public class Messages extends Translate {
    private static final String BUNDLE_NAME = "net.i2p.router.web.messages";

    /**
     * Not used: every method here is static, and this class exists only to
     * bind the package's own bundle name to {@link Translate}.
     */
    public Messages() {}

    /**
     * Resource bundle for country names, looked up separately from this
     * package's own strings so the country list can be translated on its own.
     *
     * @since 0.9.33
     */
    public static final String COUNTRY_BUNDLE_NAME = "net.i2p.router.countries.messages";

    /**
     * Look up a string in this package's bundle.
     *
     * @param key the translation key, holding the English text when untranslated
     * @param ctx supplies the routerconsole.lang property, else current locale
     * @return the translated string, or the key itself when no translation exists
     */
    public static String getString(String key, I2PAppContext ctx) {
        return Translate.getString(key, ctx, BUNDLE_NAME);
    }

    /**
     * translate a string with a parameter
     * This is a lot more expensive than getString(s, ctx), so use sparingly.
     *
     * @param s string to be translated containing {0}
     * The {0} will be replaced by the parameter.
     * Single quotes must be doubled, i.e. ' -&gt; '' in the string.
     * @param o parameter, not translated.
     * To translate parameter also, use _t("foo {0} bar", _t("baz"))
     * Do not double the single quotes in the parameter.
     * Use autoboxing to call with ints, longs, floats, etc.
     * @param ctx supplies the routerconsole.lang property, else current locale
     * @return the string
     */
    public static String getString(String s, Object o, I2PAppContext ctx) {
        return Translate.getString(s, o, ctx, BUNDLE_NAME);
    }

    /**
     * Translate a string carrying two parameters.
     *
     * @param s string to be translated containing {0} and {1}
     * @param o first parameter, not translated
     * @param o2 second parameter, not translated
     * @param ctx supplies the routerconsole.lang property, else current locale
     * @return the string
     * @since 0.7.14
     */
    public static String getString(String s, Object o, Object o2, I2PAppContext ctx) {
        return Translate.getString(s, o, o2, ctx, BUNDLE_NAME);
    }

    /**
     * Translate a string picking between the singular and the plural form,
     * as the GNU gettext plural-forms rules require.
     *
     * @param n the count, used to choose between s and p
     * @param s singular string, may contain {0} for the count
     * @param p plural string, may contain {0} for the count
     * @param ctx supplies the routerconsole.lang property, else current locale
     * @return the string
     * @since 0.7.14
     */
    public static String getString(int n, String s, String p, I2PAppContext ctx) {
        return Translate.getString(n, s, p, ctx, BUNDLE_NAME);
    }

    /**
     * Return the "display language", e.g. "English" for the language specified
     * by langCode, using the current language.
     * Uses translation if available, then JVM Locale.getDisplayLanguage() if available, else default param.
     *
     * @param langCode two-letter lower-case
     * @param dflt e.g. "English"
     * @param ctx supplies the language to display the name in
     * @return the display language
     * @since 0.9.5
     */
    public static String getDisplayLanguage(String langCode, String dflt, I2PAppContext ctx) {
        return Translate.getDisplayLanguage(langCode, dflt, ctx, BUNDLE_NAME);
    }
}
