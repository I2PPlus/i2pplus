package net.i2p;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 */

/**
 * Expose a version string.
 * <p>
 * See also: RouterVersion, VersionComparator, and the update subsystem.
 */
public class CoreVersion {

    /**
     * Private constructor to prevent instantiation.
     */
    private CoreVersion() {}

    /** Build identifier. */
    public static final String ID = "Git";

    /**
     * The version used when checking for router updates, and exchanged between
     * router and client over I2CP. This is the marketing and user-visible version.
     * <p>
     * If we ever need a point release for a specific architecture only, append ".1" to VERSION
     * and leave PUBLISHED_VERSION unchanged. Otherwise, the same as PUBLISHED_VERSION.
     * <p>
     * RouterVersion.FULL_VERSION is suggested for display to the user.
     */
    public static final String VERSION = "2.14.1";

    /**
     * The version published in the netdb via StatisticsManager. This is the API version.
     * <p>
     * It must not go to 1.x for several years, because through 0.9.49, the Sybil analyzer
     * blocked releases that didn't start with "0.9."
     * <p>
     * If we ever need a point release for a specific architecture only, append ".1" to VERSION
     * and leave PUBLISHED_VERSION unchanged. Otherwise, the same as VERSION.
     * <p>
     * RouterVersion.FULL_VERSION is suggested for display to the user.
     *
     * @since 0.9.46
     */
    public static final String PUBLISHED_VERSION = "0.9.71";

    /**
     * For Vuze.
     *
     * @return the full version string, as required by Vuze
     * @since 0.9.19
     */
    public static String getVersion() {
        return VERSION;
    }

    /**
     * Entry point for CLI version display.
     * @param args command line arguments, unused; the version is always
     *             printed for both the core and the published API
     */
    public static void main(String[] args) {
        System.out.println("I2P+ Core version: " + VERSION);
        System.out.println("I2P+ API version: " + PUBLISHED_VERSION);
    }
}
