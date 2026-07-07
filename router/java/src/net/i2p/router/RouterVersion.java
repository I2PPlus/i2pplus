package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.CoreVersion;

/**
 * Expose a version string
 *
 */
public class RouterVersion {
    /** deprecated */
    public static final String ID = "Git";
    public static final String VERSION = CoreVersion.VERSION;
    /** for example: "beta", "alpha", "rc" */
    public static final String STATUS = "";
    public static final long BUILD = 12;

    /** for example "-test" */
    public static final String EXTRA = "+";
    public static final String FULL_VERSION = VERSION + "-" + BUILD + EXTRA;

    public static void main(String[] args) {
        System.out.println("I2P+ Router version: " + FULL_VERSION); // NOSONAR CLI tool
        System.out.println("I2P+ Core version: " + CoreVersion.VERSION + EXTRA); // NOSONAR CLI tool
    }
}
