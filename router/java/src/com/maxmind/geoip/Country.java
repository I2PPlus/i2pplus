package com.maxmind.geoip;

/**
 * Represents a country.
 *
 */
public class Country {

    /** Two-letter country code. */
    private String code;
    /** Country name in English. */
    private String name;

    /**
     * Creates a new Country.
     *
     * @param code
     *               the country code.
     * @param name
     *               the country name.
     */
    public Country(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * Returns the ISO two-letter country code of this country.
     *
     * @return the country code.
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the name of this country.
     *
     * @return the country name.
     */
    public String getName() {
        return name;
    }
}
