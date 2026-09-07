package net.i2p.router.transport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link StrictCountries} case-independent membership lookup.
 * Covers every known code, both cases, non-codes, and degenerate inputs.
 */
public class StrictCountriesTest {

    private static final String[] KNOWN = {
        "AE", "AF", "AZ", "BH", "BI", "BN", "BY", "CD", "CF", "CM", "CN",
        "CU", "EG", "EH", "ER", "ET", "GQ", "IQ", "IR", "KP", "KZ", "LA",
        "LY", "MM", "PK", "PS", "RW", "SA", "SD", "SO", "SS", "SY", "SZ",
        "TD", "TH", "TJ", "TM", "TR", "UZ", "VE", "VN", "YE"
    };

    @Test
    public void everyKnownCodeMatchesInAllCases() {
        for (String code : KNOWN) {
            assertTrue("uppercase " + code, StrictCountries.contains(code));
            assertTrue("lowercase " + code, StrictCountries.contains(code.toLowerCase()));
            assertTrue("mixed " + code, StrictCountries.contains(code.charAt(0) + code.substring(1, 2).toLowerCase()));
        }
    }

    @Test
    public void unknownCodesAreNotRestricted() {
        assertFalse(StrictCountries.contains("US"));
        assertFalse(StrictCountries.contains("DE"));
        assertFalse(StrictCountries.contains("GB"));
        assertFalse(StrictCountries.contains("XX"));
        assertFalse(StrictCountries.contains("RU"));
    }

    @Test
    public void nullIsNotRestricted() {
        assertFalse(StrictCountries.contains(null));
    }

    @Test
    public void wrongLengthIsNotRestricted() {
        assertFalse(StrictCountries.contains("C"));
        assertFalse(StrictCountries.contains("CHN"));
        assertFalse(StrictCountries.contains(""));
    }

    @Test
    public void nonAsciiIsNotRestricted() {
        assertFalse(StrictCountries.contains("\u01c9X"));
        assertFalse(StrictCountries.contains("\u00c7N"));
    }
}
