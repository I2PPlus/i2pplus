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
	 * countryCode.
	 */
	public String countryCode;
	/**
	 * countryName.
	 */
	public String countryName;
	/**
	 * region.
	 */
    /** Region or state code. */
    public String region;
}
