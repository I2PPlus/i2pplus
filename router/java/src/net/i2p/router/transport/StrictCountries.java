package net.i2p.router.transport;

import java.util.Arrays;

/**
 * Maintains list of countries with strict application restrictions.
 * Maintain a list of countries that may have tight restrictions on applications like ours.
 *
 * <p>The country set is stored as a sorted array of case-normalized 2-letter
 * integer keys so {@link #contains(String)} performs no per-call String
 * allocation (callers such as {@code Router.isHidden} check this on the hot
 * path with GeoIP codes that are already lowercase).
 *
 * @since 0.8.13
 */
public abstract class StrictCountries {

    private StrictCountries() { /* no-op */ }

    /** Sorted ascending for binary search; each key = ((c0 & 0xDF) << 8) | (c1 & 0xDF) */
    private static final int[] _countries;

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
        _countries = new int[c.length];
        for (int i = 0; i < c.length; i++) {
            // 0xDF maps any ASCII letter to its uppercase form (A-Z in bits 0-4)
            int key = ((c[i].charAt(0) & 0xDF) << 8) | (c[i].charAt(1) & 0xDF);
            _countries[i] = key;
        }
        Arrays.sort(_countries);
    }

    /**
     *  Whether the country is in the restricted set.
     *
     *  @param country two-letter code, case-independent; null or any other
     *                 length is not restricted
     *  @return true if the code is in the restricted set
     */
    public static boolean contains(String country) {
        if (country == null || country.length() != 2)
            return false;
        char c0 = country.charAt(0);
        char c1 = country.charAt(1);
        // 0xDF folds case by clearing bit 5 but preserves bit 7, so a char
        // >= 128 can never fold onto an ASCII key; the original Set lookup
        // also never matched them, so exclude them outright.
        if (c0 > 127 || c1 > 127)
            return false;
        int key = ((c0 & 0xDF) << 8) | (c1 & 0xDF);
        return Arrays.binarySearch(_countries, key) >= 0;
    }
}
