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
    public final static String ID = "Git";
    public final static String VERSION = CoreVersion.VERSION;
    /** for example: "beta", "alpha", "rc" */
    public final static String STATUS = "";
    public final static long BUILD = 6;

    /** for example "-test" */
    public final static String EXTRA = "+";
    //public final static String FULL_VERSION = VERSION + "-" + BUILD + QUALIFIER + EXTRA;
    public final static String FULL_VERSION = VERSION + "-" + BUILD + EXTRA;

    public static void main(String args[]) {
        System.out.println("I2P+ Router version: " + FULL_VERSION);
        System.out.println("I2P+ Core version: " + CoreVersion.VERSION + EXTRA);
    }
}
