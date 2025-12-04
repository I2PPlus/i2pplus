package net.i2p.router.transport;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Maintains list of countries with strict application restrictions.
 *  Maintain a list of countries that may have tight restrictions on applications like ours.
 *  @since 0.8.13
 */
public abstract class StrictCountries {

    private static final Set<String> _countries;

    /**
     * List updated using the Freedom in the World Index 2020 - https://freedomhouse.org/
     * General guidance: Include countries with a Civil Liberties (CL) score of 16 or less (equivalent to a
     * CL rating of 6 or 7 in their raw data) or a Internet Freedom score of 39 or less (not free)
     */
    static {
        String[] c = {
            "AE", // United Arab Emirates
            "AF", // Afghanistan
            "AZ", // Azerbaijan
            "BH", // Bahrain
            "BI", // Burundi
            "BN", // Brunei
            "BY", // Belarus
            "CD", // Democratic Republic of the Congo
            "CF", // Central African Republic
            "CM", // Cameroon
            "CN", // China
            "CU", // Cuba
            "EG", // Egypt
            "EH", // Western Sahara
            "ER", // Eritrea
            "ET", // Ethiopia
            "GQ", // Equatorial Guinea
            "IQ", // Iraq
            "IR", // Iran
            "KP", // North Korea
            "KZ", // Kazakhstan
            "LA", // Laos
            "LY", // Libya
            "MM", // Myanmar
            "PK", // Pakistan
            "PS", // Palestinian Territories
            "RW", // Rwanda
            "SA", // Saudi Arabia
            "SD", // Sudan
            "SO", // Somalia
            "SS", // South Sudan
            "SY", // Syria
            "SZ", // Eswatini (Swaziland)
            "TD", // Chad
            "TH", // Thailand
            "TJ", // Tajikistan
            "TM", // Turkmenistan
            "TR", // Turkey
            "UZ", // Uzbekistan
            "VE", // Venezuela
            "VN", // Vietnam
            "YE"  // Yemen
        };
        _countries = new HashSet<String>(Arrays.asList(c));
    }

    /** @param country non-null, two letter code, case-independent */
    public static boolean contains(String country) {
        return _countries.contains(country.toUpperCase(Locale.US));
    }
}
