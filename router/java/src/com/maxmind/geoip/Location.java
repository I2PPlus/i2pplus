package com.maxmind.geoip;

/**
 * Represents geographical location data including coordinates and address information.
 */
public class Location {

    /**
     * Constructs an empty record; LookupService fills the public fields directly
     * from the database bytes, so the defaults are only a starting point.
     */
    public Location() {}

    /**
     * Two-letter ISO country code of the queried address.
     */
    public String countryCode;
    /**
     * Country name of the queried address.
     */
    public String countryName;
    /**
     * State, province or region name of the queried address.
     */
    public String region;
    /**
     * City name of the queried address.
     */
    public String city;
    /**
     * Postal code of the queried address.
     */
    public String postalCode;
    /**
     * Latitude of the queried address, in degrees.
     */
    public float latitude;
    /**
     * Longitude of the queried address, in degrees.
     */
    public float longitude;
    /**
     * Designated market area code of the queried address.
     */
    public int dma_code;
    /**
     * Telephone area code of the queried address.
     */
    public int area_code;
    /**
     * Metro area code of the queried address.
     */
    public int metro_code;

    /** Earth's diameter, in km, for the distance formula. */
    private final static double EARTH_DIAMETER = 2 * 6378.2;
    /** Pi, to the precision the formula needs. */
    private final static double PI = 3.14159265;
    /** Multiplies degrees to get radians. */
    private final static double RAD_CONVERT = PI / 180;

    /**
     * Great-circle distance from this location to another.
     *
     * @param loc the other endpoint, supplying the latitude and longitude the
     * @return approximate great-circle distance in km
     */
    public double distance(Location loc) {
        double delta_lat, delta_lon;
        double temp;

        float lat1 = latitude;
        float lon1 = longitude;
        float lat2 = loc.latitude;
        float lon2 = loc.longitude;

        // convert degrees to radians
        lat1 *= RAD_CONVERT;
        lat2 *= RAD_CONVERT;

        // find the deltas
        delta_lat = lat2 - lat1;
        delta_lon = (lon2 - lon1) * RAD_CONVERT;

        // Find the great circle distance
        temp = Math.pow(Math.sin(delta_lat / 2), 2) + Math.cos(lat1)
                * Math.cos(lat2) * Math.pow(Math.sin(delta_lon / 2), 2);
        return EARTH_DIAMETER
                * Math.atan2(Math.sqrt(temp), Math.sqrt(1 - temp));
    }
}
