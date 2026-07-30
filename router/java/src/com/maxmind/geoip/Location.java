package com.maxmind.geoip;

/**
 * Represents geographical location data including coordinates and address information.
 */
public class Location {
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
    public String region;
    /**
     * city.
     */
    public String city;
    /**
     * postalCode.
     */
    public String postalCode;
    /**
     * latitude.
     */
    public float latitude;
    /**
     * longitude.
     */
    public float longitude;
    /**
     * dma_code.
     */
    public int dma_code;
    /**
     * area_code.
     */
    public int area_code;
    /**
     * metro_code.
     */
    public int metro_code;

    private final static double EARTH_DIAMETER = 2 * 6378.2;
    private final static double PI = 3.14159265;
    private final static double RAD_CONVERT = PI / 180;

    /** @return approximate great-circle distance in km */
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
