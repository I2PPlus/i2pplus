package org.klomp.snark.standalone;

import net.i2p.I2PAppContext;
import net.i2p.util.Translate;

/**
 * Configuration helper for I2PSnark standalone application user interface.
 *
 * <p>This class provides language selection functionality for the I2PSnark web interface when
 * running in standalone mode (with its own app context). It is a simplified version of the router
 * console's ConfigUIHelper, adapted specifically for I2PSnark's needs.
 *
 * <p>Key features:
 *
 * <ul>
 *   <li>Generates HTML select dropdown for language selection
 *   <li>Supports ISO 639-1 and ISO 639-2 language codes
 *   <li>Performs intelligent language matching with fallback logic
 *   <li>Uses I2PSnark-specific message bundles for translations
 * </ul>
 *
 * <p>Note: This is a standalone-only helper that does not extend HelperBase and uses static methods
 * since I2PSnark runs with its own application context separate from the main router console.
 *
 * @since 0.9.27
 */
public class ConfigUIHelper {

    private static final String CHECKED = " selected ";
    private static final String BUNDLE_NAME = "org.klomp.snark.web.messages";

    /**
     * Each language has the ISO code, the flag, the name, and the optional country name.
     * Alphabetical by the ISO code please. See http://en.wikipedia.org/wiki/ISO_639-1 . Any
     * language-specific flag added to the icon set must be added to the top-level build.xml for the
     * updater. As of 0.9.12, ISO 639-2 three-letter codes are supported also.
     *
     * <p>Country flag unused.
     */
    private static final String langs[][] = {
        {"ar", "lang_ar", "Arabic عربية", null},
        {"az", "az", "Azerbaijani", null},
        {"cs", "cz", "Čeština", null},
        {"zh", "cn", "Chinese 中文", null},
        {"da", "dk", "Dansk", null},
        {"de", "de", "Deutsch", null},
        {"en", "us", "English", null},
        {"es", "es", "Español", null},
        {"et", "ee", "Eesti", null},
        {"fi", "fi", "Finnish Suomi", null},
        {"fr", "fr", "Français", null},
        {"el", "gr", "Greek Ελληνικά", null},
        {"hi", "in", "Hindi", null},
        {"hu", "hu", "Hungarian Magyar", null},
        {"in", "id", "Indonesian", null},
        {"it", "it", "Italiano", null},
        {"ja", "jp", "Japanese 日本語", null},
        {"ko", "kr", "Korean 한국어", null},
        {"nl", "nl", "Nederlands", null},
        {"nb", "no", "Norsk (bokmål)", null},
        {"fa", "ir", "Persian فارسی", null},
        {"pl", "pl", "Polski", null},
        {"pt", "pt", "Português", null},
        {"ro", "ro", "Română", null},
        {"ru", "ru", "Russian Русский", null},
        {"sl", "sk", "Slovenčina", null},
        {"sv", "se", "Svenska", null},
        {"bo", "xt", "Tibetan", null}, // position by name, not iso code
        {"tr", "tr", "Türkçe", null},
        {"uk", "ua", "Ukrainian Українська", null},
        {"vi", "vn", "Vietnamese Tiếng Việt", null},
        {"xx", "a1", "Debug: Find untagged strings", null},
        // { "es_AR", "ar", "Español" ,"Argentina" },
        // { "gl", "lang_gl", "Galego", null },
        // { "mg", "mg", "Malagasy", null },
        // { "pt_BR", "br", "Português", "Brazil" },
        // { "zh_TW", "tw", "Chinese 中文", "Taiwan" },
    };

    /**
     * Generates HTML select dropdown for language selection in I2PSnark standalone mode.
     *
     * <p>Creates a &lt;select&gt; element containing all available languages with their display
     * names. The current language is pre-selected based on the I2P app context configuration.
     * Unlike the router console version, this generates a dropdown rather than radio buttons with
     * flags.
     *
     * <p>Performs intelligent language matching:
     *
     * <ol>
     *   <li>First attempts to match the full language+country code (e.g., "en_US")
     *   <li>Falls back to language-only code (e.g., "en")
     *   <li>Defaults to English if no match is found
     * </ol>
     *
     * <p>The "Debug: Find untagged strings" option is hidden unless advanced mode is enabled.
     *
     * @param ctx the I2P application context for retrieving current language settings
     * @return HTML string containing a complete language selection dropdown
     * @since 0.9.27
     */
    public static String getLangSettings(I2PAppContext ctx) {
        String clang = Translate.getLanguage(ctx);
        String current = clang;
        String country = Translate.getCountry(ctx);
        if (country != null && country.length() > 0) {
            current += '_' + country;
        }
        // find best match
        boolean found = false;
        for (int i = 0; i < langs.length; i++) {
            if (langs[i][0].equals(current)) {
                found = true;
                break;
            }
        }
        if (!found) {
            if (country != null && country.length() > 0) {
                current = clang;
                for (int i = 0; i < langs.length; i++) {
                    if (langs[i][0].equals(current)) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                current = "en";
            }
        }
        StringBuilder buf = new StringBuilder(512);
        buf.append("<select name=lang>\n");
        for (int i = 0; i < langs.length; i++) {
            String lang = langs[i][0];
            if (lang.equals("xx") && !isAdvanced()) {
                continue;
            }
            buf.append("<option ");
            if (lang.equals(current)) {
                buf.append(CHECKED);
            }
            buf.append("value=\"").append(lang).append("\">");
            int under = lang.indexOf('_');
            String slang = (under > 0) ? lang.substring(0, under) : lang;
            buf.append(langs[i][2]);
            String name = langs[i][3];
            if (name != null) {
                buf.append(" (").append(name).append(')');
            }
            buf.append("</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }

    /**
     * Determines if advanced mode features should be enabled.
     *
     * <p>Currently hardcoded to return false, meaning advanced features like the "Debug: Find
     * untagged strings" language option are hidden. This method exists for future extensibility if
     * advanced mode detection is needed for I2PSnark standalone.
     *
     * @return true if advanced features should be shown, false otherwise
     */
    private static boolean isAdvanced() {
        return false;
    }
}
