/*
 * This file is part of SusDNS project for I2P
 * Created on Sep 02, 2005
 * Copyright (C) 2005 <susi23@mail.i2p>
 * License: GPL2 or later
 */

package i2p.susi.dns;

/**
 * Bean providing SusDNS application version and URL information.
 */
public class VersionBean {
    private static String version = "0.7";
    private static String url = "http://susi.i2p/";

    public VersionBean() {
        super();
    }

    /** @return the SusDNS version string */
    public String getVersion() {return version;}

    /** @return the SusDNS project URL */
    public String getUrl() {return url;}
}
