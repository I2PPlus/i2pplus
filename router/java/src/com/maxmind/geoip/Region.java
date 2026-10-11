package com.maxmind.geoip;

/**
 * Represents a I2P region.
 */

public class Region {
	/**
	 * Every field is left null; the geoip reader fills in whichever of the country
	 * and region codes the record carries.
	 */
	public Region() {}

	/**
	 * Two-letter ISO country code, empty when the address is out of range.
	 */
	public String countryCode;
	/**
	 * Country name looked up alongside the country code.
	 */
	public String countryName;
	/**
	 * Two-letter state or province code, empty outside the US and Canada.
	 */
    /** Region or state code. */
    public String region;
}
