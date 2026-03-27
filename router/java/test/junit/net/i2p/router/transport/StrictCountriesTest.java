package net.i2p.router.transport;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 *  Tests for StrictCountries.
 *  Static utility - no I2P context needed.
 */
public class StrictCountriesTest {

    @Test
    public void testKnownStrictCountries() {
        assertTrue(StrictCountries.contains("CN"));
        assertTrue(StrictCountries.contains("IR"));
        assertTrue(StrictCountries.contains("KP"));
        assertTrue(StrictCountries.contains("SA"));
        assertTrue(StrictCountries.contains("SY"));
        assertTrue(StrictCountries.contains("TR"));
        assertTrue(StrictCountries.contains("VE"));
        assertTrue(StrictCountries.contains("BY"));
    }

    @Test
    public void testCaseInsensitive() {
        assertTrue(StrictCountries.contains("cn"));
        assertTrue(StrictCountries.contains("ir"));
        assertTrue(StrictCountries.contains("Cn"));
    }

    @Test
    public void testFreeCountries() {
        assertFalse(StrictCountries.contains("US"));
        assertFalse(StrictCountries.contains("GB"));
        assertFalse(StrictCountries.contains("DE"));
        assertFalse(StrictCountries.contains("FR"));
        assertFalse(StrictCountries.contains("JP"));
        assertFalse(StrictCountries.contains("AU"));
        assertFalse(StrictCountries.contains("CA"));
        assertFalse(StrictCountries.contains("NL"));
    }

    @Test
    public void testContainsKnownCount() {
        // Verify some specific strict countries
        String[] strict = {"AE", "AF", "AZ", "BH", "BY", "CN", "CU", "EG", "ER", "IR", "KP", "KZ", "LA", "MM", "PK", "SA", "SY", "TD", "TM", "TR", "UZ", "VE", "VN", "YE"};
        for (String c : strict) {
            assertTrue(c + " should be strict", StrictCountries.contains(c));
        }
    }
}
