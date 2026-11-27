package net.i2p.router.web.helpers;

import static org.junit.Assert.assertEquals;

import java.util.Locale;

import org.junit.Test;

/**
 * The country codes the ban configuration keeps, and the fold that decides
 * whether an entered code survives validation.
 *
 * <p>A code has to be two ASCII letters to be stored, so the entered text is
 * folded to lower case and matched against that pattern. Under a Turkish default
 * locale "IL" folds to a dotless ı, fails the pattern, and the operator's ban
 * for Israel is dropped on save with nothing said about it - the field comes
 * back shorter than it was written.
 *
 * @since 0.9.72+
 */
public class ConfigBanHandlerCountryCodesTest {

    /** Validation is a static decision over the text entered; no handler state. */
    public ConfigBanHandlerCountryCodesTest() {}

    /**
     * Two-letter codes are kept, lower-cased and joined; anything else is
     * dropped rather than written into the config as a code nothing matches.
     */
    @Test
    public void testOnlyTwoLetterCodesAreKept() {
        assertEquals("us", ConfigBanHandler.validateCountryCodes("US"));
        assertEquals("fr,de", ConfigBanHandler.validateCountryCodes(" FR , de "));
        assertEquals("", ConfigBanHandler.validateCountryCodes("abc"));
        assertEquals("", ConfigBanHandler.validateCountryCodes("1a"));
        assertEquals("", ConfigBanHandler.validateCountryCodes(null));
        assertEquals("", ConfigBanHandler.validateCountryCodes(""));
    }

    /**
     * Codes separated by spaces alone are codes as much as comma-separated ones,
     * because the field is documented both ways.
     */
    @Test
    public void testSpaceSeparatedCodesAreKept() {
        assertEquals("us,ca,mx", ConfigBanHandler.validateCountryCodes("US CA MX"));
    }

    /**
     * Under a Turkish default locale the fold is what decides this: "IL" has to
     * stay a code, or the ban for Israel disappears from the field on save.
     */
    @Test
    public void testCodesSurviveTheTurkishLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("il", ConfigBanHandler.validateCountryCodes("IL"));
            assertEquals("il,us", ConfigBanHandler.validateCountryCodes("IL, US"));
            assertEquals("fi", ConfigBanHandler.validateCountryCodes("FI"));
        } finally {
            Locale.setDefault(saved);
        }
    }
}
