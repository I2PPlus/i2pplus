package com.maxmind.geoip;

/**
 * Represents a country.
 *
 * @author Matt Tucker
 */
public class Country {

    private String code;
    private String name;

    /**
     * Creates a new Country.
     *
     * @param code
     *            the country code.
     * @param name
     *            the country name.
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
